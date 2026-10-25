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
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * siteblock dns stop [--force].
 *
 * <p>Stops the daemon (SIGTERM → the shutdown hook closes the socket and pool in a controlled way).
 * With {@code --for} still within its deadline, an early stop follows the {@code unblock} rule:
 * without {@code --force} nothing changes (exit 3); with {@code --force}, a typed confirmation.
 */
@Command(name = "stop",
        description = "Stops the local DNS server.",
        mixinStandardHelpOptions = true)
public class DnsStopCommand implements Callable<Integer> {

    @Option(names = {"--force", "-f"},
            description = "Allows an early stop of a temporary server (asks for a typed confirmation).")
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
            System.err.println("Error reading the daemon state: " + e.getMessage());
            return 1;
        }
        if (existing.isEmpty()) {
            System.out.println("The DNS server is not running.");
            return 0;
        }
        DnsDaemonState state = existing.get();
        if (!DnsDaemonState.isAlive(state)) {
            try {
                DnsDaemonState.delete(stateDir);
            } catch (IOException ignored) {
            }
            System.out.println("The DNS server was not running (stale state for pid "
                    + state.getPid() + " was still there, cleared).");
            return 0;
        }

        Instant now = Instant.now(Clock.systemUTC());
        if (state.isTemporaryAndActive(now) && !force) {
            Duration remaining = Duration.between(now, state.getExpiresAt());
            System.err.println("Stop REFUSED: the DNS server is temporary ("
                    + DurationParser.formatRemaining(remaining) + " left).");
            System.err.println("To stop it early, run again with --force "
                    + "(a typed confirmation will be requested). Nothing was changed.");
            return 3;
        }
        if (state.isTemporaryAndActive(now)) {
            Duration remaining = Duration.between(now, state.getExpiresAt());
            System.out.println("!!! STRONG WARNING !!!");
            System.out.println("You are about to STOP THE DNS SERVER EARLY,");
            System.out.println("which would otherwise keep running for " + DurationParser.formatRemaining(remaining) + ".");
            System.out.println("Devices with DNS pointed here may lose protection.");
            System.out.print("To confirm, type YES and press Enter (anything else cancels): ");
            System.out.flush();
            String answer = CliSupport.readLine();
            if (answer == null || !answer.trim().equalsIgnoreCase("YES")) {
                System.out.println("Early stop CANCELLED. The server keeps running.");
                return 3;
            }
        }

        Optional<ProcessHandle> handle = ProcessHandle.of(state.getPid());
        if (handle.isEmpty() || !handle.get().isAlive()) {
            try {
                DnsDaemonState.delete(stateDir);
            } catch (IOException ignored) {
            }
            System.out.println("The DNS server had already stopped (pid " + state.getPid() + "). State cleared.");
            return 0;
        }
        try {
            handle.get().destroy(); // SIGTERM: the daemon's hook closes everything in a controlled way.
            try {
                handle.get().onExit().get(5, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                handle.get().destroyForcibly();
            } catch (Exception e) {
                System.err.println("Error waiting for shutdown: " + e.getMessage());
                return 1;
            }
        } catch (SecurityException e) {
            System.err.println("Error: no permission to stop pid " + state.getPid()
                    + " (daemon started elevated?). "
                    + new PermissionAdvisor().elevationHint(
                            com.sitelock.core.SiteLockPaths.getHostsPath(),
                            "dns stop" + (force ? " --force" : "")));
            return 1;
        }
        try {
            DnsDaemonState.delete(stateDir);
        } catch (IOException ignored) {
        }
        System.out.println("DNS server stopped (pid " + state.getPid() + ").");
        System.out.println("Reminder: set the devices' DNS back to automatic/DHCP, "
                + "otherwise they keep resolving nothing.");
        return 0;
    }
}
