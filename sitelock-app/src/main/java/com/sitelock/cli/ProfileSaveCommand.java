package com.sitelock.cli;

import com.sitelock.core.BlockEntry;
import com.sitelock.core.BlockService;
import com.sitelock.core.CorruptedStateException;
import com.sitelock.core.DurationParser;
import com.sitelock.core.Profile;
import com.sitelock.core.ProfileEntry;
import com.sitelock.core.ProfileNames;
import com.sitelock.core.ProfileStore;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;

/**
 * Saves the active blocks as a named profile; the CLI form is
 * {@code siteblock save profile <name> [--force]}.
 *
 * <p>Saves what is blocked <b>now</b> as a reusable recipe: it writes into the file
 * the remaining deadline of each domain, never the original {@code expiresAt}. Without {@code --force},
 * it does not overwrite: losing a recipe by accident is the worst possible error here.
 */
@Command(name = "save",
        description = "Saves the active blocks as a named profile.",
        mixinStandardHelpOptions = true)
public class ProfileSaveCommand implements Callable<Integer> {

    @Parameters(index = "0", paramLabel = "<name>", description = "Profile name.")
    String name;

    @Option(names = "--force", description = "Overwrites an existing profile.")
    boolean force;

    @Option(names = "--hosts-file", hidden = true) String hostsFileOverride;
    @Option(names = "--state-file", hidden = true) String stateFileOverride;

    // Store injected by the test; null = use the one from the state directory.
    ProfileStore storeOverride;

    public ProfileSaveCommand() {
    }

    ProfileSaveCommand(String name, boolean force, String hostsFileOverride, String stateFileOverride) {
        this.name = name;
        this.force = force;
        this.hostsFileOverride = hostsFileOverride;
        this.stateFileOverride = stateFileOverride;
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

        ProfileStore store = storeOverride != null ? storeOverride
                : new ProfileStore(CliSupport.resolveProfilesFile(stateFileOverride));
        BlockService service = CliSupport.newService(hostsFileOverride, stateFileOverride);

        final Map<String, BlockEntry> state;
        try {
            state = service.sweepExpired().state();
        } catch (CorruptedStateException e) {
            System.err.println("Error: corrupted state (" + e.getMessage() + ").");
            System.err.println("Nothing was changed.");
            return 1;
        } catch (IOException e) {
            System.err.println("Error reading the state: " + e.getMessage());
            return 1;
        }

        Instant now = Instant.now(service.getClock());

        // The sweep does not guarantee "only what is active now": when the hosts file is not
        // writable it returns the state untouched (skippedDueToPermission) and expired entries
        // stay in the map. Filtering here avoids storing an expired domain as if it were a live recipe.
        Map<String, ProfileEntry> entries = new TreeMap<>();
        for (BlockEntry e : state.values()) {
            if (e == null || e.getDomain() == null || e.isExpired(now)) {
                continue;
            }
            // WHY: the remaining deadline, not the original expiresAt — a profile is a recipe,
            // and not history: storing the instant would turn a 6-month profile into "already expired".
            Long millis = e.getExpiresAt() == null ? null
                    : Duration.between(now, e.getExpiresAt()).toMillis();
            entries.put(e.getDomain(), new ProfileEntry(millis));
        }

        if (entries.isEmpty()) {
            System.err.println("Nothing is blocked now — an empty profile would do nothing.");
            return 1;
        }

        // Read and record the recipes inside one transaction: two simultaneous
        // `save profile` calls cannot lose each other's changes, and a profile written
        // by another terminal along the way has to stay there.
        boolean[] alreadyExists = {false};
        try {
            store.inTransaction(tx -> {
                Map<String, Profile> profiles = tx.load();
                if (profiles.containsKey(valid) && !force) {
                    alreadyExists[0] = true;
                    return null;
                }
                Instant createdAt = profiles.containsKey(valid)
                        ? profiles.get(valid).getCreatedAt() : now;
                profiles.put(valid, new Profile(createdAt, now, entries));
                tx.save(profiles);
                return null;
            });
        } catch (IOException e) {
            System.err.println("Error saving the profile: " + e.getMessage());
            return 1;
        }
        if (alreadyExists[0]) {
            System.err.println("Profile already exists '" + valid + "'. Use --force to overwrite.");
            return 1;
        }

        System.out.println("Profile '" + valid + "' saved with " + entries.size() + " domain(s).");
        return 0;
    }
}