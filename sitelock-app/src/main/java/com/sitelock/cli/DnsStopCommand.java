package com.sitelock.cli;

import com.sitelock.core.DurationParser;
import com.sitelock.core.PermissionAdvisor;
import com.sitelock.dns.DnsDaemonState;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Scanner;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * siteblock dns stop [--force].
 *
 * <p>Encerra o daemon (SIGTERM → shutdown hook fecha socket e pool de forma
 * controlada). Se o servidor foi iniciado com {@code --for} e ainda está no prazo,
 * a parada antecipada segue a mesma regra do {@code unblock}: sem {@code --force}
 * nada muda (saída 3); com {@code --force}, pede confirmação digitada (SIM).
 */
@Command(name = "stop",
        description = "Encerra o servidor DNS local.",
        mixinStandardHelpOptions = true)
public class DnsStopCommand implements Callable<Integer> {

    @Option(names = {"--force", "-f"},
            description = "Permite parada antecipada de servidor temporário (pede confirmação digitada).")
    boolean force;

    @Option(names = "--state-file", hidden = true) String stateFileOverride;

    public DnsStopCommand() {
    }

    DnsStopCommand(boolean force, String stateFileOverride) {
        this.force = force;
        this.stateFileOverride = stateFileOverride;
    }

    @Override
    public Integer call() {
        Path stateDir = DnsDaemonState.stateDirFor(stateFileOverride);
        final Optional<DnsDaemonState> existing;
        try {
            existing = DnsDaemonState.read(stateDir);
        } catch (IOException e) {
            System.err.println("Erro ao ler estado do daemon: " + e.getMessage());
            return 1;
        }
        if (existing.isEmpty()) {
            System.out.println("Servidor DNS não está em execução.");
            return 0;
        }
        DnsDaemonState state = existing.get();
        if (!DnsDaemonState.isAlive(state)) {
            try {
                DnsDaemonState.delete(stateDir);
            } catch (IOException ignored) {
            }
            System.out.println("Servidor DNS não está em execução (restava estado obsoleto do pid "
                    + state.getPid() + ", já limpo).");
            return 0;
        }

        Instant now = Instant.now(Clock.systemUTC());
        if (state.isTemporaryAndActive(now) && !force) {
            Duration remaining = Duration.between(now, state.getExpiresAt());
            System.err.println("Parada RECUSADA: o servidor DNS é temporário (restam "
                    + DurationParser.formatRemaining(remaining) + ").");
            System.err.println("Para encerrar antecipadamente, rode de novo com --force "
                    + "(será pedida confirmação digitada). Nada foi alterado.");
            return 3;
        }
        if (state.isTemporaryAndActive(now)) {
            Duration remaining = Duration.between(now, state.getExpiresAt());
            System.out.println("!!! AVISO FORTE !!!");
            System.out.println("Você está prestes a ENCERRAR ANTECIPADAMENTE o servidor DNS,");
            System.out.println("que ainda rodaria por " + DurationParser.formatRemaining(remaining) + ".");
            System.out.println("Dispositivos com DNS apontado para cá podem ficar sem proteção.");
            System.out.print("Para confirmar, digite SIM e pressione Enter (qualquer outra coisa cancela): ");
            System.out.flush();
            String answer = readLine();
            if (answer == null || !answer.trim().equalsIgnoreCase("SIM")) {
                System.out.println("Parada antecipada CANCELADA. Servidor continua rodando.");
                return 3;
            }
        }

        Optional<ProcessHandle> handle = ProcessHandle.of(state.getPid());
        if (handle.isEmpty() || !handle.get().isAlive()) {
            try {
                DnsDaemonState.delete(stateDir);
            } catch (IOException ignored) {
            }
            System.out.println("Servidor DNS já havia encerrado (pid " + state.getPid() + "). Estado limpo.");
            return 0;
        }
        try {
            handle.get().destroy(); // SIGTERM: o hook no daemon fecha tudo com controle.
            try {
                handle.get().onExit().get(5, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                handle.get().destroyForcibly();
            } catch (Exception e) {
                System.err.println("Erro ao aguardar o encerramento: " + e.getMessage());
                return 1;
            }
        } catch (SecurityException e) {
            System.err.println("Erro: sem permissão para encerrar o pid " + state.getPid()
                    + " (daemon iniciado elevado?). "
                    + new PermissionAdvisor().elevationHint(
                            com.sitelock.core.SiteLockPaths.getHostsPath(),
                            "dns stop" + (force ? " --force" : "")));
            return 1;
        }
        try {
            DnsDaemonState.delete(stateDir);
        } catch (IOException ignored) {
        }
        System.out.println("Servidor DNS encerrado (pid " + state.getPid() + ").");
        System.out.println("Lembrete: volte o DNS dos dispositivos para automático/DHCP, "
                + "senão eles continuam sem resolver nada.");
        return 0;
    }

    private static String readLine() {
        try {
            Scanner scanner = new Scanner(System.in);
            if (!scanner.hasNextLine()) {
                return null;
            }
            return scanner.nextLine();
        } catch (Exception e) {
            return null;
        }
    }
}
