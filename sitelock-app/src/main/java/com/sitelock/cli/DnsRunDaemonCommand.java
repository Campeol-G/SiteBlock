package com.sitelock.cli;

import com.sitelock.core.DurationParser;
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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Child process of {@code dns start}: runs the server in the foreground until
 * {@code dns stop} (SIGTERM), Ctrl+C, or the end of {@code --for}.
 * Internal command (hidden from help); do not call it directly.
 */
@Command(name = "__run-daemon", hidden = true,
        description = "Runs the DNS server in the foreground (internal use by 'dns start').")
public class DnsRunDaemonCommand implements Callable<Integer> {

    @Option(names = "--port", defaultValue = "53") int port;
    @Option(names = "--upstream", defaultValue = "1.1.1.1") String upstream;
    @Option(names = "--for") String forDuration;
    @Option(names = "--state-file", hidden = true) String stateFileOverride;

    @Option(names = "--allow-from", paramLabel = "<list>",
            description = "Origins allowed beyond the local ones (IP or CIDR, separated by commas).")
    String allowFrom;

    public DnsRunDaemonCommand() {
    }

    @Override
    public Integer call() {
        final java.net.InetAddress upstreamAddr;
        try {
            upstreamAddr = java.net.InetAddress.getByName(upstream);
        } catch (Exception e) {
            System.err.println("[sitelock-dns] invalid upstream '" + upstream + "': " + e.getMessage());
            return 2;
        }

        final Duration duration;
        try {
            duration = forDuration == null ? null : DurationParser.parse(forDuration);
        } catch (IllegalArgumentException e) {
            System.err.println("[sitelock-dns] " + e.getMessage());
            return 2;
        }

        Path stateDir = DnsDaemonState.stateDirFor(stateFileOverride);
        Path stateFile = stateFileOverride != null && !stateFileOverride.isBlank()
                ? Path.of(stateFileOverride)
                : stateDir.resolve("blocks.json");

        final java.util.List<String> allowedOrigins;
        try {
            allowedOrigins = com.sitelock.dns.DnsServer.parseAllowFrom(allowFrom);
        } catch (IllegalArgumentException e) {
            System.err.println("[sitelock-dns] " + e.getMessage());
            return 2;
        }

        com.sitelock.dns.DnsServer server = new com.sitelock.dns.DnsServer(
                port, upstreamAddr, new com.sitelock.core.BlockStore(stateFile), Clock.systemUTC(),
                allowedOrigins);
        server.installShutdownHook();

        java.util.concurrent.ScheduledExecutorService scheduler = null;
        if (duration != null) {
            scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "sitelock-dns-expiry");
                t.setDaemon(true);
                return t;
            });
            scheduler.schedule(() -> {
                System.out.println("[sitelock-dns] the --for time ("
                        + DurationParser.formatRemaining(duration) + ") ran out; shutting down.");
                server.stop();
            }, duration.toMillis(), TimeUnit.MILLISECONDS);
        }

        try {
            com.sitelock.dns.NetworkUtils.printStartupInfo(port, upstreamAddr.getHostAddress());
            server.runWithWatchdog();
        } catch (com.sitelock.dns.DnsServerException e) {
            // Failure during the initial bind (port in use / no permission): exit so that
            // `dns start` detects the dead child and shows the reason from the log.
            System.err.println("[sitelock-dns] " + e.getMessage());
            return 1;
        } finally {
            if (scheduler != null) {
                scheduler.shutdownNow();
            }
            try {
                DnsDaemonState.deleteIfOwned(stateDir, ProcessHandle.current().pid());
            } catch (IOException ignored) {
            }
        }
        return 0;
    }

    /** Alternative entry point (not used by the CLI; the daemon is born via SiteLockApplication). */
    public static int run(int port, String upstream, String forDuration, String stateFileOverride) {
        DnsRunDaemonCommand cmd = new DnsRunDaemonCommand();
        cmd.port = port;
        cmd.upstream = upstream;
        cmd.forDuration = forDuration;
        cmd.stateFileOverride = stateFileOverride;
        return cmd.call();
    }
}
