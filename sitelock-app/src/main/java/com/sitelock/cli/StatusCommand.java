package com.sitelock.cli;

import com.sitelock.core.BlockEntry;
import com.sitelock.core.BlockService;
import com.sitelock.core.CorruptedStateException;
import com.sitelock.core.DomainUtils;
import com.sitelock.core.DurationParser;
import com.sitelock.core.HostsFileManager;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;

/**
 * siteblock status — lists blocks. Cleans expired ones only if it can write the hosts file.
 */
@Command(name = "status", description = "Lists blocked domains and the time remaining.",
        mixinStandardHelpOptions = true)
public class StatusCommand implements Callable<Integer> {

    @Option(names = "--hosts-file", hidden = true) String hostsFileOverride;
    @Option(names = "--state-file", hidden = true) String stateFileOverride;

    public StatusCommand() {
    }

    StatusCommand(String hostsFileOverride, String stateFileOverride) {
        this.hostsFileOverride = hostsFileOverride;
        this.stateFileOverride = stateFileOverride;
    }

    @Override
    public Integer call() {
        BlockService service = CliSupport.newService(hostsFileOverride, stateFileOverride);

        final Map<String, BlockEntry> state;
        try {
            if (service.isHostsWritable()) {
                BlockService.SweepResult sweep = service.sweepExpired();
                state = sweep.state();
                if (!sweep.removed().isEmpty()) {
                    System.out.println("Cleanup: expired blocks removed: "
                            + String.join(", ", sweep.removed()));
                }
            } else {
                state = service.getStore().load();
                long pending = state.values().stream()
                        .filter(e -> e.isExpired(Instant.now(service.getClock())))
                        .count();
                if (pending > 0) {
                    System.out.println("Warning: there are " + pending + " expired block(s) pending cleanup.");
                    System.out.println(CliSupport.elevationHint("status")
                            + " to finish the cleanup in the hosts file.");
                }
            }
        } catch (CorruptedStateException e) {
            System.err.println("Error: corrupted state (" + e.getMessage() + ").");
            System.err.println("Nothing was changed. To recreate from scratch, delete the file manually or run: siteblock reset --yes");
            return 1;
        } catch (IOException e) {
            System.err.println("Error reading state: " + e.getMessage());
            return 1;
        }

        Instant now = Instant.now(service.getClock());

        if (state.isEmpty()) {
            System.out.println("No domains blocked.");
            checkDivergence(service, state, now);
            return 0;
        }

        List<String> lines = new ArrayList<>();
        for (BlockEntry e : state.values()) {
            String remaining;
            if (e.getExpiresAt() == null) {
                remaining = "permanent";
            } else if (!now.isBefore(e.getExpiresAt())) {
                remaining = "expired (pending cleanup)";
            } else {
                remaining = DurationParser.formatRemaining(Duration.between(now, e.getExpiresAt()));
            }
            lines.add(e.getDomain() + " — " + remaining);
        }
        System.out.println("Blocked domains (" + lines.size() + "):");
        lines.forEach(l -> System.out.println("  " + l));

        checkDivergence(service, state, now);

        // Only speaks when there is an active temporary block: a permanent block has no deadline
        // to expire, so there is nothing to warn about.
        boolean hasActive = state.values().stream().anyMatch(e -> e.isTemporaryAndActive(now));
        if (hasActive && !CliSupport.isDaemonAlive(stateFileOverride)) {
            System.out.println("There is a block with a deadline and no daemon running: the deadline will only be "
                    + "honored when you run another siteblock command.");
            System.out.println(CliSupport.elevatedCommand("daemon enable")
                    + " to make it expire on its own.");
        }
        return 0;
    }

    // WHY: the two can disagree, and each outcome is bad in a different way.
    // State without a line in the hosts file: the site is free and status says it is blocked.
    // Line in the hosts file without a record: stuck forever, because the sweep walks the JSON.
    private void checkDivergence(BlockService service, Map<String, BlockEntry> state, Instant now) {
        final String content;
        try {
            content = service.readHostsContent();
        } catch (IOException e) {
            // Status runs without elevation: reading the hosts file can fail on
            // permission. It is not an error — it is a missing cross-check.
            System.out.println("Warning: could not read the hosts file (" + service.getHostsPath()
                    + ") to check for divergence: " + e.getMessage());
            return;
        }

        Set<String> ineffective = new TreeSet<>();
        Set<String> inState = new TreeSet<>();
        for (BlockEntry e : state.values()) {
            inState.add(baseDomain(e.getDomain()));
            if (e.isExpired(now)) {
                // An expired entry is not active: the pending cleanup is the sweep's job.
                continue;
            }
            if (!hasManagedLine(content, e.getDomain())) {
                ineffective.add(e.getDomain());
            }
        }

        Set<String> stuckWithoutRecord = new TreeSet<>();
        for (String domain : blockedDomains(content)) {
            if (!inState.contains(domain)) {
                stuckWithoutRecord.add(domain);
            }
        }

        if (ineffective.isEmpty() && stuckWithoutRecord.isEmpty()) {
            return;
        }
        System.out.println("Divergence between state and hosts ("
                + (ineffective.size() + stuckWithoutRecord.size()) + "):");
        for (String domain : ineffective) {
            System.out.println("  " + domain
                    + " — ineffective: the state says blocked, but the hosts file has no line"
                    + " (the site is free).");
        }
        for (String domain : stuckWithoutRecord) {
            System.out.println("  " + domain
                    + " — stuck without a record: the hosts file has the line, but the state has no"
                    + " record (the site stays blocked forever).");
            // unblock only knows how to remove what is in the state: without a record it
            // would say the domain is not blocked and never touch the hosts file.
            System.out.println("    Fix with: " + CliSupport.elevatedCommand("block " + domain)
                    + " and then " + CliSupport.elevatedCommand("unblock " + domain));
        }
    }

    // The domain without the "www." prefix — the block is the same with or without it.
    private static String baseDomain(String domain) {
        return HostsFileManager.managedHosts(domain).get(0);
    }

    private static boolean hasManagedLine(String content, String domain) {
        for (String managed : HostsFileManager.managedLines(domain)) {
            String[] parts = managed.strip().split("\\s+");
            if (lineExists(content, parts[0], parts[1])) {
                return true;
            }
        }
        return false;
    }

    private static boolean lineExists(String content, String ip, String host) {
        for (String line : content.split("\n", -1)) {
            String t = line.strip();
            if (t.isEmpty() || t.startsWith("#")) {
                continue;
            }
            String[] parts = t.split("\\s+");
            if (parts.length < 2 || !parts[0].equalsIgnoreCase(ip)) {
                continue;
            }
            for (int i = 1; i < parts.length; i++) {
                if (parts[i].equalsIgnoreCase(host)) {
                    return true;
                }
            }
        }
        return false;
    }

    // The domains SiteLock has in the hosts file's managed block.
    private static Set<String> blockedDomains(String content) {
        Set<String> domains = new TreeSet<>();
        for (String line : blockLines(content)) {
            String[] parts = line.split("\\s+");
            if (parts.length < 2 || !isBlockableDomain(parts[1])) {
                continue;
            }
            String domain = baseDomain(parts[1]);
            for (String managed : HostsFileManager.managedLines(domain)) {
                if (managed.equals(parts[0] + " " + parts[1])) {
                    domains.add(domain);
                    break;
                }
            }
        }
        return domains;
    }

    // The lines inside the block that SiteLock manages.
    private static List<String> blockLines(String content) {
        List<String> lines = new ArrayList<>();
        boolean inside = false;
        for (String line : content.split("\n", -1)) {
            String t = line.strip();
            if (t.contains(HostsFileManager.START_MARKER)) {
                inside = true;
                continue;
            }
            if (t.contains(HostsFileManager.END_MARKER)) {
                inside = false;
                continue;
            }
            if (inside) {
                lines.add(t);
            }
        }
        return lines;
    }

    // WHY: the "localhost" and friends of /etc/hosts are system content, and what
    // SiteLock can block is what normalize() accepts as a domain.
    private static boolean isBlockableDomain(String host) {
        try {
            DomainUtils.normalize(host);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}