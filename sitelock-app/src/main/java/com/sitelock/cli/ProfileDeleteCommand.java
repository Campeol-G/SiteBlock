package com.sitelock.cli;

import com.sitelock.core.Profile;
import com.sitelock.core.ProfileNames;
import com.sitelock.core.ProfileStore;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.util.concurrent.Callable;

/**
 * Deletes a saved profile; the CLI form is {@code siteblock delete profile <name>}.
 *
 * <p>The confirmation is typed, and not an {@code Y/N}: deleting a recipe is the kind of error that
 * nobody undoes. Typing something else cancels with exit code 3 — the same as a refused {@code unblock}
 * — so scripts can tell "I did not" from "error".
 */
@Command(name = "delete",
        description = "Deletes a saved profile.",
        mixinStandardHelpOptions = true)
public class ProfileDeleteCommand implements Callable<Integer> {

    @Parameters(index = "0", paramLabel = "<name>", description = "Profile name.")
    String name;

    @Option(names = "--state-file", hidden = true) String stateFileOverride;

    // Answer injected by the test; null = read from stdin.
    String answer;

    public ProfileDeleteCommand() {
    }

    ProfileDeleteCommand(String name, String answer, String stateFileOverride) {
        this.name = name;
        this.answer = answer;
        this.stateFileOverride = stateFileOverride;
    }

    @Override
    public Integer call() {
        final String valid;
        try {
            valid = ProfileNames.require(name);
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            return 2;
        }

        ProfileStore store = new ProfileStore(CliSupport.resolveProfilesFile(stateFileOverride));
        final Profile profile;
        try {
            profile = store.load().get(valid);
        } catch (IOException e) {
            System.err.println("Error reading profiles: " + e.getMessage());
            return 1;
        }
        if (profile == null) {
            System.err.println("Profile '" + valid + "' does not exist.");
            return 1;
        }

        System.out.println("Delete profile '" + valid + "' (" + profile.size() + " domain(s))?");
        System.out.print("To confirm, type YES and press Enter: ");
        System.out.flush();

        String reply = answer != null ? answer : CliSupport.readLine();
        if (reply == null || !reply.trim().equalsIgnoreCase("YES")) {
            System.out.println("Deletion CANCELLED. The profile still exists.");
            return 3;
        }

        try {
            store.delete(valid);
        } catch (IOException e) {
            System.err.println("Error deleting the profile: " + e.getMessage());
            return 1;
        }
        System.out.println("Profile '" + valid + "' deleted.");
        return 0;
    }
}