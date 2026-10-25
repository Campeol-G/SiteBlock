package com.sitelock.cli;

import com.sitelock.blocker.SiteBlocker;
import com.sitelock.core.BlockEntry;
import com.sitelock.core.BlockService;
import com.sitelock.core.CorruptedStateException;
import com.sitelock.core.DurationParser;
import com.sitelock.core.Profile;
import com.sitelock.core.ProfileNames;
import com.sitelock.core.ProfileStore;
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
import java.util.Objects;
import java.util.concurrent.Callable;

/**
 * siteblock profile unblock &lt;name&gt; [--force] — releases every domain of the
 * profile at once.
 *
 * <p>Two layers: {@code --force}, without which any active domain makes the command
 * refuse the whole batch and exit with 3, and the typed confirmation that comes after.
 * Cancelling undoes nothing — the command does not touch any domain.
 */
@Command(name = "unblock",
        description = "Releases every domain of a profile at once.",
        mixinStandardHelpOptions = true)
public class ProfileUnblockCommand implements Callable<Integer> {

    @Parameters(index = "0", paramLabel = "<name>", description = "Profile name.")
    String name;

    @Option(names = "--force", description = "Releases domains even if they still have time left.")
    boolean force;

    @Option(names = "--hosts-file", hidden = true) String hostsFileOverride;
    @Option(names = "--state-file", hidden = true) String stateFileOverride;

    // Blocker injected by the test; null = use the system hosts file's.
    SiteBlocker blockerOverride;

    // Answer injected by the test; null = read from stdin.
    String answer;

    public ProfileUnblockCommand() {
    }

    ProfileUnblockCommand(String name, boolean force, String hostsFileOverride, String stateFileOverride,
            String answer) {
        this(name, force, hostsFileOverride, stateFileOverride, answer, null);
    }

    ProfileUnblockCommand(String name, boolean force, String hostsFileOverride, String stateFileOverride,
            String answer, SiteBlocker blockerOverride) {
        this.name = name;
        this.force = force;
        this.hostsFileOverride = hostsFileOverride;
        this.stateFileOverride = stateFileOverride;
        this.answer = answer;
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

        // NOTE: without this gate, whoever forgets the su gets the worst possible outcome: the
        // state is saved first, the hosts write fails after, and the domain stays
        // stuck in the hosts file with no record. The recovery would be editing /etc/hosts by hand.
        if (!service.isHostsWritable()) {
            System.err.println("Error: " + CliSupport.elevationHint("profile unblock " + valid));
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

        final Map<String, BlockEntry> state;
        try {
            state = service.sweepExpired().state();
        } catch (CorruptedStateException e) {
            System.err.println("Error: corrupted state (" + e.getMessage() + ").");
            System.err.println("Nothing was changed.");
            return 1;
        } catch (IOException e) {
            System.err.println("Error clearing expired blocks: " + e.getMessage());
            return 1;
        }

        Instant now = Instant.now(service.getClock());
        List<String> targets = new ArrayList<>();
        List<String> descriptions = new ArrayList<>();
        Map<String, BlockEntry> confirmed = new LinkedHashMap<>();
        Duration total = Duration.ZERO;
        for (String domain : profile.getEntries().keySet()) {
            BlockEntry entry = state.get(domain);
            if (entry == null) {
                continue;
            }
            targets.add(domain);
            confirmed.put(domain, entry);
            if (entry.isTemporaryAndActive(now)) {
                Duration remaining = Duration.between(now, entry.getExpiresAt());
                total = total.plus(remaining);
                descriptions.add(domain + " (" + DurationParser.formatRemaining(remaining) + " left)");
            } else {
                descriptions.add(domain + " (permanent)");
            }
        }

        if (targets.isEmpty()) {
            System.out.println("No domain in profile '" + valid + "' is blocked. Nothing to do.");
            return 0;
        }

        // NOTE: the gate also covers permanent ones. If it covered only temporary ones, a
        // profile of unlimited locks would leave without a flag and without typing a key.
        if (!force) {
            System.err.println("Unblock REFUSED for: " + String.join(", ", descriptions) + ".");
            System.err.println("To release anyway, run again with --force "
                    + "(a typed confirmation will be requested). Nothing was changed.");
            return 3;
        }

        // WHY: the permanent ones enter the confirmation, even though individual "unblock"
        // releases them silently — permanent is the strongest commitment the person made, and
        // releasing it quietly would be the shortcut that "--force" exists to prevent.
        System.out.println("You are about to UNBLOCK " + targets.size() + " domain(s) from profile '"
                + valid + "':");
        for (String d : descriptions) {
            System.out.println("  " + d);
        }
        if (!total.isZero()) {
            System.out.println("Total block time being discarded: "
                    + DurationParser.formatRemaining(total) + ".");
        }
        System.out.print("To confirm, type YES and press Enter (anything else cancels): ");
        System.out.flush();

        String reply = answer != null ? answer : CliSupport.readLine();
        if (reply == null || !reply.trim().equalsIgnoreCase("YES")) {
            System.out.println("Unblock CANCELLED. Everything stays blocked.");
            return 3;
        }

        // WHY: the transaction is the only entry point that writes state — load and
        // save under the same lock, and the hosts written inside it, in the State → hosts order.
        // Outside of it, a block from another terminal disappears from the JSON and the domain gets stuck.
        final boolean[] reachedHosts = {false};
        final String[] conflict = {null};
        try {
            service.inTransaction(
                    (current, tx) -> {
                        // Confirmation and removal cannot have different states: if
                        // any target changed, the whole batch is cancelled.
                        for (String domain : targets) {
                            if (!Objects.equals(current.get(domain), confirmed.get(domain))) {
                                conflict[0] = domain;
                                return null;
                            }
                        }
                        for (String domain : targets) {
                            current.remove(domain);
                        }
                        tx.save(current);
                        return null;
                    },
                    (result, current, tx) -> {
                        if (conflict[0] != null) {
                            return;
                        }
                        reachedHosts[0] = true;
                        service.getBlocker().unblockAll(targets);

                    });
        } catch (IOException e) {
            if (!reachedHosts[0]) {
                System.err.println("Error saving the state: " + e.getMessage());
                return 1;
            }
            // The transaction already restored the state. If the restore
            // failed, the state lies about the hosts — and the domain stays stuck
            // with no record, which nobody in the project can clean up.
            if (e.getSuppressed().length > 0) {
                System.err.println("CRITICAL error: hosts write FAILED and the state could not be reverted: "
                        + e.getSuppressed()[0].getMessage());
                System.err.println("The domains " + String.join(", ", targets)
                        + " may have been left in the hosts file with no record. "
                        + "Run: " + CliSupport.elevatedCommand("unblock " + targets.get(0) + " --force"));
            }
            System.err.println("Error removing profile domains from hosts: " + e.getMessage());
            System.err.println("Nothing was changed.");
            return 1;
        }

        if (conflict[0] != null) {
            System.err.println("'" + conflict[0] + "' changed while you were confirming; "
                    + "the whole batch was cancelled. Try again.");
            return 1;
        }

        System.out.println("Unblocked " + targets.size() + " domain(s) from profile '" + valid + "'.");
        CliSupport.flushDnsBestEffort();
        return 0;
    }
}