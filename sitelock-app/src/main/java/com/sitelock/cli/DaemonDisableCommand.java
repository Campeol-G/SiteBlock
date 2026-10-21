package com.sitelock.cli;

import com.sitelock.core.AutostartInstaller;
import com.sitelock.core.CommandRunner;
import com.sitelock.core.OsType;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * siteblock daemon disable — desliga o autostart.
 *
 * <p>Simétrico ao {@code enable}: remove o artefato e desliga o agendador.
 */
@Command(name = "disable",
        description = "Remove o daemon de expiracao do boot.",
        mixinStandardHelpOptions = true)
public class DaemonDisableCommand implements Callable<Integer> {

    @Option(names = "--state-file", hidden = true) String stateFileOverride;

    // Injetados para teste; null = SO e raiz reais.
    OsType osOverride;
    Path systemRootOverride;
    CommandRunner runnerOverride;

    public DaemonDisableCommand() {
    }

    DaemonDisableCommand(OsType os, Path systemRoot, CommandRunner runner) {
        this.osOverride = os;
        this.systemRootOverride = systemRoot;
        this.runnerOverride = runner;
    }

    @Override
    public Integer call() {
        OsType os = osOverride != null ? osOverride : OsType.detect();
        Path systemRoot = systemRootOverride != null ? systemRootOverride : Path.of("/");

        AutostartInstaller installer = new AutostartInstaller(os, systemRoot, runnerOverride);
        if (!AutostartInstaller.isEnabled(os, systemRoot)) {
            System.out.println("O autostart do SiteBlock não está instalado.");
            return 0;
        }

        AutostartInstaller.InstallResult result = installer.disable();
        if (!result.success()) {
            System.err.println("Erro ao remover o autostart: " + result.detail());
            return 1;
        }

        System.out.println("Autostart removido. O daemon para de subir sozinho a cada boot.");
        System.out.println("Um daemon que já estiver rodando continua até: "
                + CliSupport.elevatedCommand("daemon stop"));
        return 0;
    }
}