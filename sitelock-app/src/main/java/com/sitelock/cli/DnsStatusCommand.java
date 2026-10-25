package com.sitelock.cli;

import com.sitelock.core.BlockEntry;
import com.sitelock.core.BlockStore;
import com.sitelock.core.DurationParser;
import com.sitelock.dns.DnsDaemonState;
import com.sitelock.dns.NetworkUtils;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * siteblock dns status — shows whether the server is running (pid, port, upstream,
 * time left on --for), the local IP to configure on devices, and how many
 * domains are blocked.
 */
@Command(name = "status",
        description = "Shows the state of the local DNS server.",
        mixinStandardHelpOptions = true)
public class DnsStatusCommand implements Callable<Integer> {

    @Option(names = "--state-file", hidden = true) String stateFileOverride;

    public DnsStatusCommand() {
    }

    DnsStatusCommand(String stateFileOverride) {
        this.stateFileOverride = stateFileOverride;
    }

    @Override
    public Integer call() {
        Path stateDir = DnsDaemonState.stateDirFor(stateFileOverride);
        Path stateFile = stateFileOverride != null && !stateFileOverride.isBlank()
                ? Paths.get(stateFileOverride)
                : stateDir.resolve("blocks.json");

        boolean running = false;
        try {
            Optional<DnsDaemonState> existing = DnsDaemonState.read(stateDir);
            if (existing.isPresent() && DnsDaemonState.isAlive(existing.get())) {
                running = true;
                DnsDaemonState s = existing.get();
                System.out.println("DNS server: RUNNING (pid " + s.getPid()
                        + ", port " + s.getPort() + ", upstream " + s.getUpstream() + ").");
                if (s.getStartedAt() != null) {
                    System.out.println("  Started at: " + s.getStartedAt());
                }
                if (s.getExpiresAt() != null) {
                    Instant now = Instant.now(Clock.systemUTC());
                    if (now.isBefore(s.getExpiresAt())) {
                        System.out.println("  Shuts down automatically in: "
                                + DurationParser.formatRemaining(
                                        Duration.between(now, s.getExpiresAt())));
                    } else {
                        System.out.println("  The --for deadline has passed (shutdown imminent).");
                    }
                } else {
                    System.out.println("  No expiration (runs until 'dns stop').");
                }
            } else {
                if (existing.isPresent()) {
                    DnsDaemonState.delete(stateDir);
                }
                System.out.println("DNS server: STOPPED.");
            }
        } catch (Exception e) {
            System.err.println("Error reading the daemon state: " + e.getMessage());
            return 1;
        }

        if (running) {
            System.out.println("Local IP to configure on devices: "
                    + String.join(", ", NetworkUtils.localIPv4Addresses()));
        }
        try {
            Map<String, BlockEntry> state = new BlockStore(stateFile).load();
            Instant now = Instant.now(Clock.systemUTC());
            long active = state.values().stream().filter(e -> !e.isExpired(now)).count();
            System.out.println("Active blocked domains: " + active + ".");
        } catch (Exception e) {
            System.out.println("Blocked domains: unreadable (" + e.getMessage() + ").");
        }
        return 0;
    }
}
