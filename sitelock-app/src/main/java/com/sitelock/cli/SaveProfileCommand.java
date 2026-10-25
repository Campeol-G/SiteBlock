package com.sitelock.cli;

import com.sitelock.core.BlockService;
import com.sitelock.core.BlockEntry;
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
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;

/** siteblock save profile <name> [--force] — saves active blocks as a profile. */
@Command(name = "save", description = "Saves active blocks as a profile.", mixinStandardHelpOptions = true)
public class SaveProfileCommand implements Callable<Integer> {

    @Parameters(index = "0", paramLabel = "<sub>", description = "Always 'profile'.")
    String sub;

    @Parameters(index = "1", paramLabel = "<name>", description = "Profile name.")
    String name;

    @Option(names = "--force", description = "Overwrites an existing profile.")
    boolean force;

    @Option(names = "--hosts-file", hidden = true) String hostsFileOverride;
    @Option(names = "--state-file", hidden = true) String stateFileOverride;

    public SaveProfileCommand() {}

    SaveProfileCommand(String sub, String name, boolean force, String hostsFileOverride, String stateFileOverride) {
        this.sub = sub; this.name = name; this.force = force; this.hostsFileOverride = hostsFileOverride; this.stateFileOverride = stateFileOverride;
    }

    @Override
    public Integer call() {
        if (!"profile".equals(sub)) {
            System.err.println("Usage: siteblock save profile <name> [--force]");
            return 2;
        }
        final String valid;
        try {
            valid = ProfileNames.require(name);
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
        ProfileStore store = new ProfileStore(CliSupport.resolveProfilesFile(stateFileOverride));
        BlockService service = CliSupport.newService(hostsFileOverride, stateFileOverride);
        final Map<String, BlockEntry> state;
        try {
            state = service.sweepExpired().state();
        } catch (CorruptedStateException e) {
            System.err.println("Error: corrupted state (" + e.getMessage() + ").");
            return 1;
        } catch (IOException e) {
            System.err.println("Error reading state: " + e.getMessage());
            return 1;
        }
        Instant now = Instant.now(service.getClock());
        Map<String, ProfileEntry> entries = new TreeMap<>();
        for (BlockEntry e : state.values()) {
            if (e == null || e.getDomain() == null || e.isExpired(now)) continue;
            Long millis = e.getExpiresAt() == null ? null : java.time.Duration.between(now, e.getExpiresAt()).toMillis();
            entries.put(e.getDomain(), new ProfileEntry(millis));
        }
        if (entries.isEmpty()) {
            System.err.println("Nothing is blocked right now — an empty profile would do nothing.");
            return 1;
        }
        boolean[] alreadyExists = {false};
        try {
            store.inTransaction(tx -> {
                Map<String, Profile> profiles = tx.load();
                if (profiles.containsKey(valid) && !force) {
                    alreadyExists[0] = true;
                    return null;
                }
                Instant createdAt = profiles.containsKey(valid) ? profiles.get(valid).getCreatedAt() : now;
                profiles.put(valid, new Profile(createdAt, now, entries));
                tx.save(profiles);
                return null;
            });
        } catch (IOException e) {
            System.err.println("Error saving profile: " + e.getMessage());
            return 1;
        }
        if (alreadyExists[0]) {
            System.err.println("Profile '" + valid + "' already exists. Use --force to overwrite.");
            return 1;
        }
        System.out.println("Profile '" + valid + "' saved with " + entries.size() + " domain(s).");
        return 0;
    }
}
