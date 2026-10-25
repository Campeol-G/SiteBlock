package com.sitelock.cli;

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
 * siteblock profile &lt;name&gt; add &lt;domain...&gt; [--for &lt;duration&gt;].
 *
 * <p>Adds domains to the recipe of an existing profile — the counterpart of
 * {@code save profile}, which can only save what is already blocked. It does not ask for
 * sudo and does not write to the hosts file.
 */
@Command(name = "add",
        description = "Adds domains to an existing profile.",
        mixinStandardHelpOptions = true)
public class ProfileAddCommand implements Callable<Integer> {

    @ParentCommand
    ProfileCommand parent;

    @Parameters(index = "0..*", paramLabel = "<domain>",
            description = "One or more domains. E.g. tiktok.com vm.tiktok.com",
            arity = "1..*")
    List<String> domainsRaw = new ArrayList<>();

    @Option(names = "--for", paramLabel = "<duration>",
            description = "Deadline stored in the profile. E.g. 30m, 2h, 1d. Omitted = permanent.")
    String forDuration;

    @Option(names = "--state-file", hidden = true) String stateFileOverride;

    // Clock injected by the test; null = use the system clock.
    Clock clockOverride;

    // Store injected by the test; null = use the one from the state directory.
    ProfileStore storeOverride;

    public ProfileAddCommand() {
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

        final Duration duration;
        try {
            duration = forDuration == null ? null : DurationParser.parse(forDuration);
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }

        ProfileStore store = storeOverride != null ? storeOverride
                : new ProfileStore(CliSupport.resolveProfilesFile(stateFileOverride));

        // What is stored is the remaining deadline at the moment of the add, as in save:
        // a profile is a recipe, not history.
        Long millis = duration == null ? null : duration.toMillis();
        List<String> added = new ArrayList<>();
        List<String> replaced = new ArrayList<>();
        boolean[] missing = {false};
        // Read and record the recipe inside one transaction: two simultaneous
        // `profile add` calls cannot be allowed to lose each other's changes.
        try {
            store.inTransaction(tx -> {
                Map<String, Profile> profiles = tx.load();
                Profile profile = profiles.get(profileName);
                if (profile == null) {
                    missing[0] = true;
                    return null;
                }
                Map<String, ProfileEntry> entries = new TreeMap<>(profile.getEntries());
                // WHY: a domain already in the profile replaces its entry instead of
                // accumulating — what gets stored is what you asked for now. A recipe
                // with two durations for the same domain is nobody's recipe.
                for (String domain : domains) {
                    if (entries.containsKey(domain)) {
                        replaced.add(domain);
                    } else {
                        added.add(domain);
                    }
                    entries.put(domain, new ProfileEntry(millis));
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
            System.err.println("To create a new profile, block the domains and run: "
                    + "siteblock save profile " + profileName);
            return 1;
        }

        String deadline = duration == null ? "permanent"
                : DurationParser.formatRemaining(duration);
        System.out.println("Profile '" + profileName + "': " + added.size() + " domain(s) added, "
                + replaced.size() + " replaced.");
        for (String domain : added) {
            System.out.println("  + " + domain + " — " + deadline);
        }
        for (String domain : replaced) {
            System.out.println("  ~ " + domain + " — " + deadline + " (replaced the previous entry)");
        }
        System.out.println("The recipe changed; what is blocked right now did not. "
                + "Run 'siteblock profile apply " + profileName + "' to apply it.");
        return 0;
    }

    // The command's clock: the one injected by the test, or the CLI's.
    private Clock clock() {
        return clockOverride != null ? clockOverride : CliSupport.clock();
    }

    // WHY: without a name there is nothing to do, and the message spells out the full
    // form because the most likely mistake is typing "siteblock profile add" and expecting
    // "add" to be the profile name.
    private String profileName() {
        if (parent == null || parent.name == null || parent.name.isBlank()) {
            System.err.println("Error: provide the profile name. "
                    + "Usage: siteblock profile <name> add <domain>");
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
