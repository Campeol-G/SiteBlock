package com.sitelock.cli;

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
import java.util.Map;
import java.util.concurrent.Callable;

/** siteblock profile show <name> — profile details with durations. */
@Command(name = "show",
        description = "Shows the contents of a profile.",
        mixinStandardHelpOptions = true)
public class ProfileShowCommand implements Callable<Integer> {

    @Parameters(index = "0", paramLabel = "<name>", description = "Profile name.")
    String name;

    @Option(names = "--state-file", hidden = true) String stateFileOverride;

    public ProfileShowCommand() {
    }

    ProfileShowCommand(String name, String stateFileOverride) {
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

        System.out.println("Profile '" + valid + "' (" + profile.size() + " domain(s)):");
        for (Map.Entry<String, ProfileEntry> e : profile.getEntries().entrySet()) {
            Long millis = e.getValue() == null ? null : e.getValue().getDurationMillis();
            String duration = millis == null ? "permanent"
                    : DurationParser.formatRemaining(Duration.ofMillis(millis));
            System.out.println("  " + e.getKey() + " — " + duration);
        }
        return 0;
    }
}