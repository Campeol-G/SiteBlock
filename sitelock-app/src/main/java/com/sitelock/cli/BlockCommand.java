package com.sitelock.cli;

import com.sitelock.core.BlockEntry;
import com.sitelock.core.BlockService;
import com.sitelock.core.CorruptedStateException;
import com.sitelock.core.DomainUtils;
import com.sitelock.core.DurationParser;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * siteblock block <domain...> [--for <duration>].
 * Validates everything before applying; if any input is invalid, nothing changes.
 */
@Command(name = "block",
        description = "Blocks one or more domains.",
        mixinStandardHelpOptions = true)
public class BlockCommand implements Callable<Integer> {

    @Parameters(index = "0..*", paramLabel = "<domain>",
            description = "One or more domains. E.g. tiktok.com vm.tiktok.com",
            arity = "1..*")
    List<String> domainsRaw = new ArrayList<>();

    @Option(names = "--for", paramLabel = "<duration>",
            description = "Block duration. E.g. 30m, 2h, 1h30m, 1d. Omitted = permanent.")
    String forDuration;

    // Test overrides.
    @Option(names = "--hosts-file", hidden = true) String hostsFileOverride;
    @Option(names = "--state-file", hidden = true) String stateFileOverride;

    public BlockCommand() {
    }

    BlockCommand(List<String> domainsRaw, String forDuration, String hostsFileOverride,
                 String stateFileOverride) {
        this.domainsRaw = new ArrayList<>(domainsRaw);
        this.forDuration = forDuration;
        this.hostsFileOverride = hostsFileOverride;
        this.stateFileOverride = stateFileOverride;
    }

    @Override
    public Integer call() {
        final List<String> domains = new ArrayList<>();
        final Map<String, String> normalizedFrom = new LinkedHashMap<>();
        for (String raw : domainsRaw) {
            final String domain;
            try {
                DomainUtils.NormalizationResult r = DomainUtils.normalize(raw);
                domain = r.domain();
                if (r.normalized()) {
                    System.out.println("Warning: normalized input from '" + r.original()
                            + "' to '" + r.domain() + "'.");
                }
            } catch (IllegalArgumentException e) {
                System.err.println("Error: " + e.getMessage());
                return 2;
            }
            if (!domains.contains(domain)) {
                domains.add(domain);
                normalizedFrom.put(domain, raw);
            }
        }

        final Duration duration;
        try {
            duration = forDuration == null ? null : DurationParser.parse(forDuration);
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            return 2;
        }

        BlockService service = CliSupport.newService(hostsFileOverride, stateFileOverride);

        if (!service.isHostsWritable()) {
            System.err.println("Error: " + CliSupport.elevationHint("block " + String.join(" ", domains)
                    + (forDuration != null ? " --for " + forDuration : "")));
            return 1;
        }

        try {
            BlockService.SweepResult sweep = service.sweepExpired();
            if (!sweep.removed().isEmpty()) {
                System.out.println("Cleanup: expired blocks removed: "
                        + String.join(", ", sweep.removed()));
            }
        } catch (CorruptedStateException e) {
            System.err.println("Error: corrupted state (" + e.getMessage() + ").");
            System.err.println("Nothing was changed. To recreate from scratch, delete the file manually or run: siteblock reset --yes");
            System.err.println("(The hosts file (" + service.getHostsPath() + ") was NOT modified for safety.)");
            return 1;
        } catch (IOException e) {
            System.err.println("Error cleaning expired blocks: " + e.getMessage());
            return 1;
        }

        Instant now = Instant.now(service.getClock());
        final Instant expiresAt = duration == null ? null : now.plus(duration);
        Plan plan = new Plan();
        try {
            service.inTransaction(
                    (state, tx) -> {
                        for (String domain : domains) {
                            BlockEntry previous = state.get(domain);
                            plan.previous.put(domain, previous);
                            state.put(domain, new BlockEntry(domain, now, expiresAt));
                            try {
                                tx.save(state);
                            } catch (IOException e) {
                                System.err.println("Error saving state for '" + domain + "': "
                                        + e.getMessage());
                                if (previous == null) {
                                    state.remove(domain);
                                } else {
                                    state.put(domain, previous);
                                }
                                plan.failures++;
                                continue;
                            }
                            plan.recorded.add(domain);
                        }
                        return plan;
                    },
                    (p, state, tx) -> {
                        for (String domain : p.recorded) {
                            try {
                                service.getBlocker().block(domain);
                            } catch (IOException e) {
                                try {
                                    BlockEntry previous = p.previous.get(domain);
                                    if (previous == null) {
                                        state.remove(domain);
                                    } else {
                                        state.put(domain, previous);
                                    }
                                    tx.save(state);
                                } catch (IOException rollbackFailure) {
                                    System.err.println("CRITICAL ERROR: hosts failure AND state rollback failure for '"
                                            + domain + "': " + rollbackFailure.getMessage());
                                }
                                System.err.println("Error blocking '" + domain + "': " + e.getMessage());
                                p.failures++;
                                continue;
                            }
                            if (expiresAt == null) {
                                System.out.println("Blocked " + domain + ".");
                            } else {
                                System.out.println("Blocked " + domain + " for "
                                        + DurationParser.formatRemaining(duration) + ".");
                            }
                        }
                    });
        } catch (IOException e) {
            System.err.println("Error writing state: " + e.getMessage());
            return 1;
        }
        int failures = plan.failures;
        if (failures == 0) {
            // Best-effort: does not change exit code; only warns if flush fails.
            CliSupport.flushDnsBestEffort();
            if (forDuration != null) {
                CliSupport.warnIfNoDaemon(stateFileOverride);
            }
            return 0;
        }
        return 1;
    }

    // What the block transaction carries between state and hosts: both sides
    // need to know what was recorded and which was the previous entry for each domain,
    // to roll back only the domain that failed, without undoing the others.
    private static final class Plan {
        final List<String> recorded = new ArrayList<>();
        final Map<String, BlockEntry> previous = new LinkedHashMap<>();
        int failures;
    }
}
