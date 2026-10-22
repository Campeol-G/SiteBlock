package com.sitelock.cli;

import com.sitelock.core.BlockService;
import com.sitelock.core.CorruptedStateException;
import com.sitelock.core.ExpirationDaemonLock;
import com.sitelock.core.ExpirationDaemonState;
import com.sitelock.core.SiteLockPaths;
import com.sitelock.core.SweeperSchedule;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

// ATENÇÃO: o daemon só remove — BlockService.sweepExpired() é a única coisa que
// ele chama, mais o flush de cache. Se alguém colocar um block dentro daqui,
// um profile apply errado passa a parecer um bloqueio válido.

// ATENÇÃO: quem escreve o daemon.json é este processo, e não o pai do daemon
// start — sob autostart não existe pai. Se dependesse do pai, o status diria
// "parado" e o stop não teria o que matar.

/**
 * siteblock daemon __run-daemon — o laço de expiração. Comando interno
 * (oculto); não chame diretamente.
 */
@Command(name = "__run-daemon", hidden = true,
        description = "Executa o laço de expiração em foreground (uso interno do 'daemon start').")
public class DaemonRunCommand implements Callable<Integer> {

    @Option(names = "--interval", defaultValue = "60",
            description = "Teto do sono entre varreduras, em segundos.")
    int intervalSeconds;

    @Option(names = "--once", hidden = true,
            description = "Faz uma varredura e sai. Depuração e teste.")
    boolean once;

    @Option(names = "--state-file", hidden = true) String stateFileOverride;

    // Serializa a varredura do laço com a varredura final do shutdown hook.
    private final Object sweepMonitor = new Object();

    public DaemonRunCommand() {
    }

    @Override
    public Integer call() {
        Path stateDir = CliSupport.stateDirFor(stateFileOverride);
        Duration maxInterval = Duration.ofSeconds(Math.max(1, intervalSeconds));
        // POR QUE: o ocioso é IGUAL ao teto, e não 5 minutos — 5 min de sono
        // faz um bloqueio de 1 minuto ficar preso por até 5 minutos depois de
        // vencer, que é o defeito que o daemon existe para eliminar.
        Duration idleInterval = maxInterval;

        if (once) {
            // Modo depuração: sem registro, sem trava e sem shutdown hook. Um
            // --once disparado por engano não pode deixar estado para trás nem
            // pegar o lock de um daemon que esteja de fato rodando.
            return varrerUmaVez(CliSupport.newService(null, stateFileOverride));
        }

        final ExpirationDaemonLock lock;
        try {
            lock = ExpirationDaemonLock.acquire(stateDir);
        } catch (IOException e) {
            System.err.println("[sitelock] " + e.getMessage());
            return 1;
        }

        final AtomicBoolean parando = new AtomicBoolean(false);
        final CountDownLatch terminou = new CountDownLatch(1);
        // POR QUE: a varredura final precisa RODAR UMA VEZ e nenhum dos dois
        // lados pode encerrar antes dela. Com um CAS simples, se a main ganhasse
        // a corrida o hook voltava na hora e a JVM encerrava no meio da
        // varredura; a FutureTask faz todos esperarem o mesmo resultado.
        final FutureTask<Integer> varreduraFinal = new FutureTask<>(() -> {
            synchronized (sweepMonitor) {
                return varrerUmaVez(CliSupport.newService(null, stateFileOverride));
            }
        });
        final AtomicBoolean varreduraDisparada = new AtomicBoolean(false);
        final Runnable dispararVarredura = () -> {
            if (varreduraDisparada.compareAndSet(false, true)) {
                varreduraFinal.run();
            }
        };
        try {
            ExpirationDaemonState.write(stateDir, new ExpirationDaemonState(
                    ProcessHandle.current().pid(), Instant.now(Clock.systemUTC()),
                    stateDir.toString(), null));

            Thread hook = new Thread(() -> {
                parando.set(true);
                terminou.countDown();
                // A varredura final roda AQUI, não só na main: no SIGTERM a JVM
                // encerra quando os hooks terminam, e a main pode nem ser
                // escalonada antes disso.
                dispararVarredura.run();
                esperarVarreduraFinal(varreduraFinal);
            }, "sitelock-sweeper-shutdown");
            Runtime.getRuntime().addShutdownHook(hook);

            System.out.println("[sitelock] daemon de expiração no ar (pid "
                    + ProcessHandle.current().pid() + ", teto de " + intervalSeconds + "s).");
            System.out.println("[sitelock] estado: " + stateDir);
            System.out.flush();

            while (!parando.get()) {
                BlockService.SweepResult sweep = varrer();
                if (sweep == null) {
                    if (!dormir(terminou, (int) idleInterval.toSeconds())) {
                        break;
                    }
                    continue;
                }
                long proximo = sweep.skippedDueToPermission()
                        ? idleInterval.toSeconds()
                        : SweeperSchedule.nextSleep(sweep.state(), Instant.now(Clock.systemUTC()),
                                maxInterval, idleInterval).toSeconds();
                if (!dormir(terminou, proximo)) {
                    break;
                }
            }

            // Ninguém sai daqui sem a varredura final concluída — inclusive o
            // hook, que é quem a JVM espera no SIGTERM.
            dispararVarredura.run();
            return esperarVarreduraFinal(varreduraFinal);
        } catch (IOException e) {
            System.err.println("[sitelock] falha fatal no daemon: " + e.getMessage());
            return 1;
        } finally {
            try {
                ExpirationDaemonState.delete(stateDir);
            } catch (IOException ignored) {
            }
            try {
                lock.close();
            } catch (IOException ignored) {
            }
        }
    }

    // Uma falha aqui NÃO mata o daemon: registra e devolve null para dormir o
    // intervalo ocioso e tentar de novo.

    // ATENÇÃO: o CorruptedStateException é o caso provável (o usuário editou o
    // JSON na mão) e ele não pode virar um daemon que não renasce — pior que
    // não limpar, porque o usuário nem recebe aviso de que parou de ser avisado.
    private BlockService.SweepResult varrer() {
        try {
            synchronized (sweepMonitor) {
                BlockService.SweepResult sweep =
                        CliSupport.newService(null, stateFileOverride).sweepExpired();
                if (!sweep.removed().isEmpty()) {
                    for (String domain : sweep.removed()) {
                        System.out.println("[sitelock] expirou e foi liberado: " + domain);
                    }
                    try {
                        ExpirationDaemonState.updateLastSweep(stateDir(), Instant.now(Clock.systemUTC()));
                        CliSupport.flushDnsBestEffort();
                    } catch (IOException e) {
                        System.err.println("[sitelock] falha ao registrar o sweep: " + e.getMessage());
                    }
                    System.out.flush();
                }
                return sweep;
            }
        } catch (CorruptedStateException e) {
            System.err.println("[sitelock] estado corrompido (" + e.getMessage()
                    + "); tentando de novo na próxima volta.");
            System.err.flush();
            return null;
        } catch (IOException e) {
            System.err.println("[sitelock] falha ao varrer: " + e.getMessage() + "; continuando.");
            System.err.flush();
            return null;
        }
    }

    private Path stateDir() {
        return CliSupport.stateDirFor(stateFileOverride);
    }

    // Devolve false quando o laço deve encerrar.
    private boolean dormir(CountDownLatch terminou, long segundos) {
        try {
            return !terminou.await(Math.max(1, segundos), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // Espera a varredura final concluir, venha ela da main ou do hook. O hook
    // precisa esperar mesmo quando perde a corrida: se ele retornar antes, a
    // JVM encerra o processo no meio da varredura que a main executa. Sem
    // timeout de propósito — o preço de encerrar no meio é maior.
    private static int esperarVarreduraFinal(FutureTask<Integer> varreduraFinal) {
        try {
            return varreduraFinal.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 1;
        } catch (ExecutionException e) {
            System.err.println("[sitelock] varredura final falhou: " + e.getMessage());
            return 1;
        }
    }

    private int varrerUmaVez(BlockService service) {
        try {
            BlockService.SweepResult sweep = service.sweepExpired();
            for (String domain : sweep.removed()) {
                System.out.println("[sitelock] expirou e foi liberado: " + domain);
            }
            if (!sweep.removed().isEmpty()) {
                CliSupport.flushDnsBestEffort();
            }
            System.out.flush();
            return 0;
        } catch (CorruptedStateException e) {
            System.err.println("[sitelock] estado corrompido: " + e.getMessage());
            System.err.println("[sitelock] Nada foi alterado. Apague o arquivo "
                    + service.getStore().getStateFile() + " ou rode: siteblock reset --yes");
            return 1;
        } catch (IOException e) {
            System.err.println("[sitelock] falha ao varrer: " + e.getMessage());
            return 1;
        }
    }

    /** Ponto de entrada alternativo para teste direto. */
    public static int run(int intervalSeconds, boolean once, String stateFileOverride) {
        DaemonRunCommand cmd = new DaemonRunCommand();
        cmd.intervalSeconds = intervalSeconds;
        cmd.once = once;
        cmd.stateFileOverride = stateFileOverride;
        return cmd.call();
    }
}