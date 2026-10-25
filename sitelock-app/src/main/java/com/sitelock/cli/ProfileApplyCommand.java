package com.sitelock.cli;

import com.sitelock.blocker.SiteBlocker;
import com.sitelock.core.BlockEntry;
import com.sitelock.core.BlockService;
import com.sitelock.core.CorruptedStateException;
import com.sitelock.core.DurationParser;
import com.sitelock.core.Profile;
import com.sitelock.core.ProfileMerge;
import com.sitelock.core.ProfileNames;
import com.sitelock.core.ProfileStore;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;

/**
 * siteblock profile apply &lt;name&gt; — reloads the recipe without ever shortening
 * a block that is already stronger.
 *
 * <p>Writes the state before the hosts file: if the process dies halfway, the domain ends up
 * free — never stuck without a record.
 */
@Command(name = "apply",
        description = "Applies a profile, only ever extending each domain's deadline.",
        mixinStandardHelpOptions = true)
public class ProfileApplyCommand implements Callable<Integer> {

    @Parameters(index = "0", paramLabel = "<name>", description = "Profile name.")
    String name;

    @Option(names = "--hosts-file", hidden = true) String hostsFileOverride;
    @Option(names = "--state-file", hidden = true) String stateFileOverride;

    // Blocker injected by the test; null = use the system hosts file's.
    SiteBlocker blockerOverride;

    public ProfileApplyCommand() {
    }

    ProfileApplyCommand(String name, String hostsFileOverride, String stateFileOverride,
            SiteBlocker blockerOverride) {
        this.name = name;
        this.hostsFileOverride = hostsFileOverride;
        this.stateFileOverride = stateFileOverride;
        this.blockerOverride = blockerOverride;
    }

    @Override
    public Integer call() {
        final String valid;
        try {
            valid = ProfileNames.require(name);
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }

        BlockService service = blockerOverride != null
                ? CliSupport.newService(hostsFileOverride, stateFileOverride, blockerOverride)
                : CliSupport.newService(hostsFileOverride, stateFileOverride);

        if (!service.isHostsWritable()) {
            System.err.println("Error: " + CliSupport.elevationHint("profile apply " + valid));
            return 1;
        }

        final Profile profile;
        try {
            profile = new ProfileStore(CliSupport.resolveProfilesFile(stateFileOverride)).load().get(valid);
        } catch (IOException e) {
            System.err.println("Error reading profiles: " + e.getMessage());
            return 1;
        }
        if (profile == null) {
            System.err.println("Profile '" + valid + "' does not exist.");
            return 1;
        }

        try {
            BlockService.SweepResult sweep = service.sweepExpired();
            if (!sweep.removed().isEmpty()) {
                System.out.println("Cleanup: removed expired blocks: "
                        + String.join(", ", sweep.removed()));
            }
        } catch (CorruptedStateException e) {
            System.err.println("Error: corrupted state (" + e.getMessage() + ").");
            System.err.println("Nothing was changed.");
            return 1;
        } catch (IOException e) {
            System.err.println("Error clearing expired blocks: " + e.getMessage());
            return 1;
        }

        Instant now = Instant.now(service.getClock());
        Plan plan = new Plan();
        try {
            // NOTE: the order is State -> hosts, and it is not negotiable. The reverse
            // path has a worse failure mode: a hosts line without a state record is a domain
            // stuck forever, because sweepExpired() walks the JSON, not the hosts file.
            service.inTransaction(
                    (state, tx) -> {
                        Map<String, ProfileMerge.Decision> decisions =
                                ProfileMerge.merge(state, profile, now);

                        // WHY: a TreeMap, not a HashMap — the state is written as JSON,
                        // and the JSON must always come out in the same order. With a HashMap
                        // the same file came out in two shapes, and diffs were not real.
                        Map<String, BlockEntry> newState = new TreeMap<>(state);
                        for (Map.Entry<String, ProfileMerge.Decision> e : decisions.entrySet()) {
                            String domain = e.getKey();
                            ProfileMerge.Decision d = e.getValue();
                            if (d.action() != ProfileMerge.Action.KEPT) {
                                newState.put(domain, d.entry());
                                plan.changed++;
                            } else {
                                plan.kept++;
                            }
                            System.out.println("  " + domain + " — " + describe(d, now));
                        }
                        plan.decisions = decisions;
                        // The final state decides the daemon warning: what matters is
                        // whether any deadline is currently active, not whether this apply
                        // changed anything.
                        plan.finalState = newState;

                        if (plan.changed > 0) {
                            try {
                                tx.save(newState);
                            } catch (IOException e) {
                                plan.saveError = e;
                            }
                        }
                        return plan;
                    },
                    (p, state, tx) -> {
                        if (p.saveError != null) {
                            return;
                        }
                        // NOTE: all domains, including the KEPT ones — rewriting them repairs
                        // a state that says blocked while the hosts file lacks the line (a previous
                        // apply died between the save and blockAll); without that, every apply would see
                        // "nothing to do".
                        List<String> all = new ArrayList<>(p.decisions.keySet());
                        // WHY: one save and one blockAll instead of one per domain — 40 domains
                        // used to mean 40 hosts locks, and HostsFileManager's optimistic
                        // check could kill it at domain k with k-1 already applied.
                        service.getBlocker().blockAll(all);
                        p.hostsApplied = true;
                    });
        } catch (IOException e) {
            // The hosts file refused the write: the transaction already restored the previous state.
            for (Throwable suppressed : e.getSuppressed()) {
                System.err.println("CRITICAL ERROR: hosts write failed AND the state could not be reverted: "
                        + suppressed.getMessage());
            }
            System.err.println("Error blocking profile domains: " + e.getMessage());
            System.err.println("Nothing was changed.");
            return 1;
        }
        if (plan.saveError != null) {
            System.err.println("Error saving the state: " + plan.saveError.getMessage());
            return 1;
        }
        if (plan.hostsApplied) {
            CliSupport.flushDnsBestEffort();
        }
        System.out.println("Profile '" + valid + "': " + plan.decisions.size() + " domain(s), "
                + plan.changed + " changed, " + plan.kept + " kept.");
        // The warning depends on the FINAL STATE, not on the change: a KEPT entry leaves
        // the time-bound block exactly as it was, and whoever typed that deadline still
        // needs to know that there is no daemon to enforce it.
        boolean temporaryActive = plan.finalState.values().stream()
                .anyMatch(e -> e.isTemporaryAndActive(now));
        if (temporaryActive) {
            CliSupport.warnIfNoDaemon(stateFileOverride);
        }
        return 0;
    }

    // What the profile apply transaction carries between state and hosts: the merge and
    // its recording happen on different sides, and the command needs the counts and
    // the decisions to print the summary and decide whether to write to the hosts file.
    private static final class Plan {

        Map<String, ProfileMerge.Decision> decisions = new TreeMap<>();
        Map<String, BlockEntry> finalState = new TreeMap<>();
        int changed;
        int kept;
        boolean hostsApplied;
        IOException saveError;
    }

    private String describe(ProfileMerge.Decision d, Instant now) {
        BlockEntry entry = d.entry();
        String deadline = entry.getExpiresAt() == null ? "permanent"
                : DurationParser.formatRemaining(Duration.between(now, entry.getExpiresAt()));
        switch (d.action()) {
            case ADDED:
                return deadline + " (new)";
            case RAISED:
                return deadline + " (extended)";
            case KEPT:
            default:
                return "already blocked, " + deadline + " (kept)";
        }
    }
}
