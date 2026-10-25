package com.sitelock.cli;

import com.sitelock.core.ProfileNames;
import com.sitelock.core.Profile;
import com.sitelock.core.ProfileStore;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;

/** siteblock create profile <name> — creates an empty profile. */
@Command(name = "create", description = "Creates an empty profile.", mixinStandardHelpOptions = true)
public class CreateProfileCommand implements Callable<Integer> {

    @Parameters(index = "0", paramLabel = "<sub>", description = "Always 'profile'.")
    String sub;

    @Parameters(index = "1", paramLabel = "<name>", description = "Profile name.")
    String name;

    @picocli.CommandLine.Option(names = "--state-file", hidden = true) String stateFileOverride;

    public CreateProfileCommand() {}

    CreateProfileCommand(String sub, String name, String stateFileOverride) {
        this.sub = sub;
        this.name = name;
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
        if (!"profile".equals(sub)) {
            System.err.println("Usage: siteblock create profile <name>");
            return 2;
        }
        ProfileStore store = new ProfileStore(CliSupport.resolveProfilesFile(stateFileOverride));
        boolean[] alreadyExists = {false};
        try {
            store.inTransaction(tx -> {
                Map<String, Profile> profiles = tx.load();
                if (profiles.containsKey(valid)) {
                    alreadyExists[0] = true;
                } else {
                    profiles.put(valid, new Profile(Instant.now(CliSupport.clock()), Instant.now(CliSupport.clock()), new TreeMap<>()));
                    tx.save(profiles);
                }
                return null;
            });
        } catch (IOException e) {
            System.err.println("Error creating profile: " + e.getMessage());
            return 1;
        }
        if (alreadyExists[0]) {
            System.out.println("Profile '" + valid + "' already exists.");
            return 0;
        }
        System.out.println("Profile '" + valid + "' created (empty).");
        return 0;
    }
}
