package com.sitelock.cli;

import com.sitelock.core.BlockEntry;
import com.sitelock.core.BlockStore;
import com.sitelock.core.DomainUtils;
import com.sitelock.core.DurationParser;
import com.sitelock.core.Profile;
import com.sitelock.core.ProfileEntry;
import com.sitelock.core.ProfileNames;
import com.sitelock.core.ProfileStore;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;

/**
 * siteblock profile &lt;name&gt; remove &lt;domain...&gt;.
 *
 * <p>Removes domains from the recipe of an existing profile; like {@code add}, it does not touch
 * the hosts file. It warns when a domain stays blocked, because removing it from the recipe is not
 * releasing it.
 */
@Command(name = "remove",
        description = "Removes domains from an existing profile.",
        mixinStandardHelpOptions = true)
public class ProfileRemoveCommand implements Callable<Integer> {

    @ParentCommand
    ProfileCommand parent;

    @Parameters(index = "0..*", paramLabel = "<domain>",
            description = "One or more domains. E.g. tiktok.com vm.tiktok.com",
            arity = "1..*")
    List<String> domainsRaw = new ArrayList<>();

    @Option(names = "--state-file", hidden = true) String stateFileOverride;

    // Clock injected by the test; null = use the system clock.
    Clock clockOverride;

    // Store injected by the test; null = use the one from the state directory.
    ProfileStore storeOverride;

    public ProfileRemoveCommand() {
    }

    @Override
    public Integer call() {
        final String profileName = profileName();
        if (profileName == null) {
            return 1;
        }

        final List<String> domains = new ArrayList<>();
        for (String raw : domainsRaw) {
            try {
                DomainUtils.NormalizationResult r = DomainUtils.normalize(raw);
                if (r.normalized()) {
                    System.out.println("Warning: normalized input from '" + r.original()
                            + "' to '" + r.domain() + "'.");
                }
                if (!domains.contains(r.domain())) {
                    domains.add(r.domain());
                }
            } catch (IllegalArgumentException e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }
        }

        ProfileStore store = storeOverride != null ? storeOverride
                : new ProfileStore(CliSupport.resolveProfilesFile(stateFileOverride));

        List<String> removed = new ArrayList<>();
        List<String> absent = new ArrayList<>();
        boolean[] missing = {false};
        boolean[] nothingToRemove = {false};
        // Read and record the recipe inside one transaction: two simultaneous
        // `profile remove` calls cannot be allowed to lose each other's changes.
        try {
            store.inTransaction(tx -> {
                Map<String, Profile> profiles = tx.load();
                Profile profile = profiles.get(profileName);
                if (profile == null) {
                    missing[0] = true;
                    return null;
                }
                Map<String, ProfileEntry> entries = new TreeMap<>(profile.getEntries());
                for (String domain : domains) {
                    if (entries.remove(domain) != null) {
                        removed.add(domain);
                    } else {
                        absent.add(domain);
                    }
                }
                if (removed.isEmpty()) {
                    nothingToRemove[0] = true;
                    return null;
                }
                profiles.put(profileName, new Profile(profile.getCreatedAt(), Instant.now(clock()), entries));
                tx.save(profiles);
                return null;
            });
        } catch (IOException e) {
            System.err.println("Error saving the profile: " + e.getMessage());
            return 1;
        }
        if (missing[0]) {
            System.err.println("Profile '" + profileName + "' does not exist.");
            return 1;
        }
        if (nothingToRemove[0]) {
            System.err.println("None of the domains are in profile '" + profileName + "'. Nothing was changed.");
            return 1;
        }

        System.out.println("Profile '" + profileName + "': " + removed.size() + " domain(s) removed.");
        for (String domain : removed) {
            System.out.println("  - " + domain);
        }
        for (String domain : absent) {
            System.out.println("  (not in the profile: " + domain + ")");
        }

        // WHY: the warning points to the individual "unblock" — "profile unblock" only
        // works on the domains still in the profile, and this one is no longer there. Without
        // the warning, whoever assumes removing means releasing keeps the site blocked.
        warnIfStillBlocked(removed);
        return 0;
    }

    // WHY: reading the state is separate from writing the profile — a failure here only
    // prevents the warning, not the removal, and undoing the new recipe because of an
    // unreadable state would trade a small problem for a bigger one.
    private void warnIfStillBlocked(List<String> removed) {
        final Map<String, BlockEntry> state;
        try {
            state = new BlockStore(CliSupport.resolveStateFile(stateFileOverride)).load();
        } catch (Exception e) {
            System.out.println("Warning: could not read the state to check what is still "
                    + "blocked (" + e.getMessage() + ").");
            return;
        }
        Instant now = Instant.now(clock());
        List<String> stillBlocked = new ArrayList<>();
        for (String domain : removed) {
            BlockEntry entry = state.get(domain);
            if (entry != null && !entry.isExpired(now)) {
                stillBlocked.add(domain);
                System.out.println("WARNING: " + domain + " stays blocked ("
                        + describeDeadline(entry, now) + ").");
            }
        }
        if (!stillBlocked.isEmpty()) {
            System.out.println("         Removing it from the recipe does not lift the block. To release it: "
                    + CliSupport.elevatedCommand("unblock " + String.join(" ", stillBlocked) + " --force"));
        }
    }

    private static String describeDeadline(BlockEntry entry, Instant now) {
        if (entry.getExpiresAt() == null) {
            return "permanent";
        }
        Duration remaining = Duration.between(now, entry.getExpiresAt());
        return DurationParser.formatRemaining(remaining) + " left";
    }

    // The command's clock: the one injected by the test, or the CLI's.
    private Clock clock() {
        return clockOverride != null ? clockOverride : CliSupport.clock();
    }

    private String profileName() {
        if (parent == null || parent.name == null || parent.name.isBlank()) {
            System.err.println("Error: provide the profile name. "
                    + "Usage: siteblock profile <name> remove <domain>");
            return null;
        }
        try {
            return ProfileNames.require(parent.name);
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            return null;
        }
    }
}
