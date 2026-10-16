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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * siteblock daemon __run-daemon — o laço de expiração. Comando interno
 * (oculto); não chame diretamente.
 *
 * <p>Ele <b>só remove</b>. {@link BlockService#sweepExpired()} é a única coisa
 * que ele chama, mais o flush de cache. Se alguém colocar um {@code block}
 * dentro daqui, um {@code profile apply} errado passa a parecer um bloqueio
 * válido — e o daemon que deveria limpar vira outra coisa que trava.
 *
 * <p><b>Quem escreve o {@code daemon.json}:</b> este próprio processo, e não o
 * pai do {@code daemon start}. É o ponto mais fácil de errar da feature: sob
 * autostart não existe pai. O systemd, o launchd e o Task Scheduler executam
 * este comando direto; se o registro dependesse do pai, depois de um boot não
 * haveria PID, o {@code daemon status} diria "parado" com o processo vivo, e o
 * {@code daemon stop} não teria o que matar.
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

    public DaemonRunCommand() {
    }

    @Override
    public Integer call() {
        Path stateDir = CliSupport.stateDirFor(stateFileOverride);
        Duration maxInterval = Duration.ofSeconds(Math.max(1, intervalSeconds));
        Duration idleInterval = Duration.ofMinutes(5);

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
        try {
            ExpirationDaemonState.write(stateDir, new ExpirationDaemonState(
                    ProcessHandle.current().pid(), Instant.now(Clock.systemUTC()),
                    stateDir.toString(), null));

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                parando.set(true);
                terminou.countDown();
            }, "sitelock-sweeper-shutdown"));

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

            // Varredura final: um shutdown não pode deixar expirado para trás.
            int finalExit = varrerUmaVez(CliSupport.newService(null, stateFileOverride));
            return finalExit;
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

    /**
     * Uma volta do laço.
     *
     * <p>Uma falha aqui <b>não</b> mata o daemon: registra e devolve null para
     * dormir o intervalo ocioso e tentar de novo. O {@code CorruptedStateException}
     * é o caso provável (o usuário editou o JSON na mão) e ele não pode virar um
     * daemon que não renasce — que é pior que não limpar, porque o usuário nem
     * recebe aviso de que parou de ser avisado.
     *
     * @return o resultado da varredura, ou null se esta volta falhou
     */
    private BlockService.SweepResult varrer() {
        try {
            BlockService.SweepResult sweep = CliSupport.newService(null, stateFileOverride).sweepExpired();
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

    /** @return false quando o laço deve encerrar */
    private boolean dormir(CountDownLatch terminou, long segundos) {
        try {
            return !terminou.await(Math.max(1, segundos), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
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