package com.sitelock.cli;

import com.sitelock.core.AutostartInstaller;
import com.sitelock.core.ExpirationDaemonState;
import com.sitelock.core.OsType;
import com.sitelock.core.SiteLockPaths;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * siteblock daemon status — answers without elevation.
 *
 * <p>Runs without {@code sudo} on purpose: asking "will the deadline be enforced?" must
 * not require privileges to be answered.
 */
@Command(name = "status",
        description = "Shows whether the expiration daemon is running.",
        mixinStandardHelpOptions = true)
public class DaemonStatusCommand implements Callable<Integer> {

    @Option(names = "--state-file", hidden = true) String stateFileOverride;

    // System root; null = "/" in production. Injected in tests.
    Path systemRootOverride;

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("dd/MM HH:mm")
            .withZone(ZoneId.systemDefault());

    public DaemonStatusCommand() {
    }

    DaemonStatusCommand(String stateFileOverride) {
        this.stateFileOverride = stateFileOverride;
    }

    DaemonStatusCommand(String stateFileOverride, Path systemRootOverride) {
        this.stateFileOverride = stateFileOverride;
        this.systemRootOverride = systemRootOverride;
    }

    @Override
    public Integer call() {
        Path stateDir = CliSupport.stateDirFor(stateFileOverride);
        OsType os = OsType.detect();
        Path systemRoot = systemRootOverride != null ? systemRootOverride : Path.of("/");
        Path installedJar = AutostartInstaller.installJarPath(os, systemRoot);
        // NOTE: "autostart on?" is a test for the artifact's existence, not a call to
        // the scheduler: this command answers without elevation.
        boolean autostart = AutostartInstaller.isEnabled(os, systemRoot);
        boolean jarExists = Files.exists(installedJar);

        Optional<ExpirationDaemonState> state;
        try {
            // WHY: the daemon hands ownership of the files it writes back to the user, so
            // this state is readable without elevation.
            state = ExpirationDaemonState.read(stateDir);
        } catch (IOException e) {
            System.err.println("Error reading the daemon state: " + e.getMessage());
            return 1;
        }

        if (state.isEmpty()) {
            System.out.println("Expiration daemon: not running.");
            System.out.println("Autostart: " + (autostart ? "installed" : "not installed"));
            System.out.println("State read from: " + stateDir);
            if (autostart) {
                System.out.println("Daemon jar: " + installedJar
                        + (jarExists ? "" : "  <- DOES NOT EXIST"));
            }
            if (autostart && !jarExists) {
                // The likely cause: the unit points at a jar that is gone. Without this
                // line the symptom is "the daemon will not start" and the user has no
                // way to find out why.
                System.out.println("The daemon jar no longer exists. Reinstall with: "
                        + CliSupport.elevatedCommand("daemon enable"));
            }
            System.out.println("While there is no daemon, a block with a deadline is only released "
                    + "when you run another siteblock command.");
            System.out.println("To start it automatically on each boot: "
                    + CliSupport.elevatedCommand("daemon enable"));
            return 0;
        }

        ExpirationDaemonState s = state.get();
        boolean alive = ExpirationDaemonState.isAlive(s);
        System.out.println("Expiration daemon: " + (alive ? "running" : "NOT running")
                + " (pid " + s.getPid() + ").");
        System.out.println("Started at: " + (s.getStartedAt() == null ? "unknown"
                : TIME_FORMAT.format(s.getStartedAt())));
        System.out.println("Last sweep that released something: " + (s.getLastSweepAt() == null
                ? "none since it started" : TIME_FORMAT.format(s.getLastSweepAt())));
        System.out.println("State read from: " + s.getStateDir());
        System.out.println("Autostart: " + (autostart ? "installed" : "not installed"));
        if (autostart) {
            System.out.println("Daemon jar: " + installedJar
                    + (jarExists ? "" : "  <- DOES NOT EXIST"));
            if (!jarExists) {
                System.out.println("The daemon jar does not exist — that is why it does not start. "
                        + "Reinstall with: " + CliSupport.elevatedCommand("daemon enable"));
            }
        }
        if (!alive) {
            System.out.println("The recorded PID is no longer alive. Start it with: "
                    + CliSupport.elevatedCommand("daemon start"));
        }
        return 0;
    }
}
