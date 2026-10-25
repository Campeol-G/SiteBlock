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
 * siteblock unblock <domain...> [--force].
 * An active temporary block only goes away with --force plus typing YES. Without --force,
 * nothing changes.
 */
@Command(name = "unblock", description = "Removes the block from one or more domains.",
        mixinStandardHelpOptions = true)
public class UnblockCommand implements Callable<Integer> {

    @Parameters(index = "0..*", paramLabel = "<domain>",
            description = "One or more domains. E.g. tiktok.com vm.tiktok.com",
            arity = "1..*")
    List<String> domainsRaw = new ArrayList<>();

    @Option(names = {"--force", "-f"},
            description = "Allows an early unblock of a temporary block (asks for a typed confirmation per domain).")
    boolean force;

    @Option(names = "--hosts-file", hidden = true) String hostsFileOverride;
    @Option(names = "--state-file", hidden = true) String stateFileOverride;

    public UnblockCommand() {
    }

    UnblockCommand(List<String> domainsRaw, boolean force, String hostsFileOverride,
                   String stateFileOverride) {
        this.domainsRaw = new ArrayList<>(domainsRaw);
        this.force = force;
        this.hostsFileOverride = hostsFileOverride;
        this.stateFileOverride = stateFileOverride;
    }

    @Override
    public Integer call() {
        final List<String> domains = new ArrayList<>();
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
            }
        }

        BlockService service = CliSupport.newService(hostsFileOverride, stateFileOverride);

        if (!service.isHostsWritable()) {
            System.err.println("Error: " + CliSupport.elevationHint("unblock " + String.join(" ", domains)));
            return 1;
        }

        final Map<String, BlockEntry> state;
        try {
            BlockService.SweepResult sweep = service.sweepExpired();
            state = sweep.state();
            if (!sweep.removed().isEmpty()) {
                System.out.println("Cleanup: removed expired blocks: "
                        + String.join(", ", sweep.removed()));
            }
        } catch (CorruptedStateException e) {
            System.err.println("Error: corrupted state (" + e.getMessage() + ").");
            System.err.println("Nothing was changed. To recreate it from scratch, delete the file manually or run: siteblock reset --yes");
            return 1;
        } catch (IOException e) {
            System.err.println("Error clearing expired blocks: " + e.getMessage());
            return 1;
        }

        Instant now = Instant.now(service.getClock());

        if (!force) {
            List<String> protectedDomains = new ArrayList<>();
            for (String domain : domains) {
                BlockEntry entry = state.get(domain);
                if (entry != null && entry.isTemporaryAndActive(now)) {
                    protectedDomains.add(domain + " (" + DurationParser.formatRemaining(
                            Duration.between(now, entry.getExpiresAt())) + " left)");
                }
            }
            if (!protectedDomains.isEmpty()) {
                System.err.println("Unblock REFUSED for: " + String.join(", ", protectedDomains) + ".");
                System.err.println("To unblock early, run again with --force "
                        + "(a typed confirmation will be requested per domain). Nothing was changed.");
                return 3;
            }
        }

        // The confirmation is typed before the transaction: holding the state lock while
        // the person types YES would block the daemon and every other command until
        // they finish typing.
        final List<String> toRelease = new ArrayList<>();
        final Map<String, BlockEntry> confirmed = new LinkedHashMap<>();
        boolean cancelledAny = false;
        for (String domain : domains) {
            BlockEntry entry = state.get(domain);
            if (entry == null) {
                System.out.println("'" + domain + "' is not blocked. Nothing to do.");
                continue;
            }
            if (entry.isTemporaryAndActive(now)) {
                Duration remaining = Duration.between(now, entry.getExpiresAt());
                System.out.println("!!! STRONG WARNING !!!");
                System.out.println("You are about to UNBLOCK '" + domain + "' EARLY,");
                System.out.println("and it would still have " + DurationParser.formatRemaining(remaining)
                        + " of blocking left. This weakens your protection against procrastination.");
                System.out.print("To confirm, type YES and press Enter (anything else cancels): ");
                System.out.flush();
                String answer = CliSupport.readLine();
                if (answer == null || !answer.trim().equalsIgnoreCase("YES")) {
                    System.out.println("Early unblock CANCELLED for '" + domain + "'. It stays blocked.");
                    cancelledAny = true;
                    continue;
                }
            }
            toRelease.add(domain);
            confirmed.put(domain, entry);
        }

        Plan plan = new Plan();
        try {
            service.inTransaction(
                    (stateNow, tx) -> {
                        for (String domain : toRelease) {
                            BlockEntry entry = stateNow.get(domain);
                            if (entry == null) {
                                plan.absent.add(domain);
                                continue;
                            }
                            // The entry was read before the confirmation. If another process
                            // changed it in the meantime, removing it would undo a block
                            // the user did not confirm.
                            if (!entry.equals(confirmed.get(domain))) {
                                plan.conflicts.add(domain);
                                continue;
                            }
                            plan.previous.put(domain, entry);
                            stateNow.remove(domain);
                            try {
                                tx.save(stateNow);
                            } catch (IOException e) {
                                System.err.println("Error saving the state of '" + domain + "': " + e.getMessage());
                                stateNow.put(domain, entry);
                                plan.failures++;
                                continue;
                            }
                            plan.saved.add(domain);
                        }
                        return plan;
                    },
                    (p, stateNow, tx) -> {
                        for (String domain : p.saved) {
                            try {
                                service.getBlocker().unblock(domain);
                            } catch (IOException e) {
                                try {
                                    stateNow.put(domain, p.previous.get(domain));
                                    tx.save(stateNow);
                                } catch (IOException rollbackFailure) {
                                    System.err.println("CRITICAL ERROR: hosts write failed AND the state of '"
                                            + domain + "' could not be reverted: " + rollbackFailure.getMessage());
                                }
                                System.err.println("Error unblocking '" + domain + "': " + e.getMessage());
                                p.failures++;
                                continue;
                            }
                            System.out.println("Unblocked " + domain + ".");
                        }
                    });
        } catch (IOException e) {
            System.err.println("Error writing the state: " + e.getMessage());
            return 1;
        }
        for (String domain : plan.absent) {
            System.out.println("'" + domain + "' is not blocked. Nothing to do.");
        }
        for (String domain : plan.conflicts) {
            System.err.println("'" + domain + "' changed while you were confirming; "
                    + "nothing was done for it. Try again.");
        }
        int failures = plan.failures;
        if (failures > 0 || !plan.conflicts.isEmpty()) {
            return 1;
        }
        if (!cancelledAny) {
            // Best-effort: does not change the exit code; only warns if the flush fails.
            CliSupport.flushDnsBestEffort();
        }
        return cancelledAny ? 3 : 0;
    }

    // What the unblock transaction carries between state and hosts: the state and the
    // hosts file are two places, and the unblock has to happen in both. Keeping what was
    // there for each domain is what allows reverting only the ones that failed.
    private static final class Plan {

        final List<String> saved = new ArrayList<>();
        final List<String> absent = new ArrayList<>();
        final List<String> conflicts = new ArrayList<>();
        final Map<String, BlockEntry> previous = new LinkedHashMap<>();
        int failures;
    }

}
