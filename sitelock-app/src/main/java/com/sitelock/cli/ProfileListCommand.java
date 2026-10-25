package com.sitelock.cli;

import com.sitelock.core.Profile;
import com.sitelock.core.ProfileStore;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.Callable;

/** siteblock profile list — names and size of each saved profile. */
@Command(name = "list",
        description = "Lists the saved profiles.",
        mixinStandardHelpOptions = true)
public class ProfileListCommand implements Callable<Integer> {

    @Option(names = "--state-file", hidden = true) String stateFileOverride;

    public ProfileListCommand() {
    }

    ProfileListCommand(String stateFileOverride) {
        this.stateFileOverride = stateFileOverride;
    }

    @Override
    public Integer call() {
        final Map<String, Profile> profiles;
        try {
            profiles = new ProfileStore(CliSupport.resolveProfilesFile(stateFileOverride)).load();
        } catch (IOException e) {
            System.err.println("Error reading profiles: " + e.getMessage());
            return 1;
        }

        if (profiles.isEmpty()) {
            System.out.println("No profiles saved. Create one with: siteblock save profile <name>");
            return 0;
        }

        System.out.println("Saved profiles (" + profiles.size() + "):");
        for (Map.Entry<String, Profile> e : profiles.entrySet()) {
            System.out.println("  " + e.getKey() + " — " + e.getValue().size() + " domain(s)");
        }
        return 0;
    }
}
