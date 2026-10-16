package com.sitelock.cli;

import com.sitelock.core.AutostartInstaller;
import com.sitelock.core.ExpirationDaemonState;
import com.sitelock.core.OsType;
import com.sitelock.core.SiteLockPaths;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * siteblock daemon status — responde sem elevação.
 *
 * <p>Roda sem {@code sudo} de propósito: perguntar "o prazo vai ser cumprido?"
 * não pode exigir privilégio para ser respondida. Por isso o "autostart
 * ligado?" é teste de existência do artefato, e não uma chamada ao agendador —
 * e por isso o daemon devolve a posse dos arquivos que escreve, para que
 * este comando consiga ler o estado.
 */
@Command(name = "status",
        description = "Mostra se o daemon de expiracao esta rodando.",
        mixinStandardHelpOptions = true)
public class DaemonStatusCommand implements Callable<Integer> {

    @Option(names = "--state-file", hidden = true) String stateFileOverride;

    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("dd/MM HH:mm")
            .withZone(ZoneId.systemDefault());

    public DaemonStatusCommand() {
    }

    DaemonStatusCommand(String stateFileOverride) {
        this.stateFileOverride = stateFileOverride;
    }

    @Override
    public Integer call() {
        Path stateDir = CliSupport.stateDirFor(stateFileOverride);

        Optional<ExpirationDaemonState> state;
        try {
            state = ExpirationDaemonState.read(stateDir);
        } catch (IOException e) {
            System.err.println("Erro ao ler o estado do daemon: " + e.getMessage());
            return 1;
        }

        if (state.isEmpty()) {
            System.out.println("Daemon de expiração: não está rodando.");
            System.out.println("Autostart: " + (AutostartInstaller.isEnabled(OsType.detect(), Path.of("/"))
                    ? "instalado" : "não instalado"));
            System.out.println("Estado lido de: " + stateDir);
            System.out.println("Enquanto não houver daemon, um bloqueio com prazo só é liberado "
                    + "quando você roda outro comando do siteblock.");
            System.out.println("Para subir sozinho a cada boot: sudo siteblock daemon enable");
            return 0;
        }

        ExpirationDaemonState s = state.get();
        boolean vivo = ExpirationDaemonState.isAlive(s);
        System.out.println("Daemon de expiração: " + (vivo ? "rodando" : "NÃO está rodando")
                + " (pid " + s.getPid() + ").");
        System.out.println("Subiu em: " + (s.getStartedAt() == null ? "desconhecido"
                : HORA.format(s.getStartedAt())));
        System.out.println("Último sweep que liberou algo: " + (s.getLastSweepAt() == null
                ? "nenhum desde que subiu" : HORA.format(s.getLastSweepAt())));
        System.out.println("Estado lido de: " + s.getStateDir());
        System.out.println("Autostart: " + (AutostartInstaller.isEnabled(OsType.detect(), Path.of("/"))
                ? "instalado" : "não instalado"));
        if (!vivo) {
            System.out.println("O PID registrado não está mais vivo. Suba com: "
                    + "sudo siteblock daemon start");
        }
        return 0;
    }
}