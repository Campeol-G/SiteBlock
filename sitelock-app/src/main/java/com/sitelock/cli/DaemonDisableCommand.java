package com.sitelock.cli;

import com.sitelock.core.AutostartInstaller;
import com.sitelock.core.CommandRunner;
import com.sitelock.core.OsType;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * siteblock daemon disable — turns autostart off.
 *
 * <p>Symmetrical to {@code enable}: removes the artifact and disables the scheduler.
 */
@Command(name = "disable",
        description = "Removes the expiration daemon from boot.",
        mixinStandardHelpOptions = true)
public class DaemonDisableCommand implements Callable<Integer> {

    @Option(names = "--state-file", hidden = true) String stateFileOverride;

    // Injected for tests; null = real OS and root.
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
            System.out.println("SiteBlock autostart is not installed.");
            return 0;
        }

        AutostartInstaller.InstallResult result = installer.disable();
        if (!result.success()) {
            System.err.println("Error removing autostart: " + result.detail());
            return 1;
        }

        System.out.println("Autostart removed. The daemon will no longer start by itself on each boot.");
        System.out.println("A daemon that is already running keeps running until: "
                + CliSupport.elevatedCommand("daemon stop"));
        return 0;
    }
}
