package com.sitelock.cli;

import com.sitelock.SiteLockApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * siteblock profile &lt;name&gt; remove &lt;domain...&gt;.
 *
 * <p>Taking a domain out of the recipe is not releasing the block: the hosts file
 * still has the line, and the command must warn about that — it is what closes
 * the gap between what the person asked and what the machine is doing.
 *
 * <p>The tests run through the real CLI: the profile name is a positional of the
 * {@code profile} command, and a test that called the command directly would
 * pass with the parsing broken.
 */
class ProfileRemoveCommandTest {

    @TempDir
    Path tmp;

    private Path hosts;

    private Path hosts() throws Exception {
        if (hosts == null) {
            hosts = tmp.resolve("hosts");
            Files.writeString(hosts, "127.0.0.1 localhost\n", StandardCharsets.UTF_8);
        }
        return hosts;
    }

    private String state() {
        return tmp.resolve("state/blocks.json").toString();
    }

    private Path profilesFile() {
        return tmp.resolve("state/profiles.json");
    }

    // Runs the real CLI, with --state-file pointing at the temporary directory.
    private int run(String... args) {
        List<String> all = new ArrayList<>(List.of(args));
        all.add("--state-file");
        all.add(state());
        return new CommandLine(new SiteLockApplication()).execute(all.toArray(new String[0]));
    }

    // Blocks and saves a profile with the given domains.
    private void prepareProfile(String name, String... domains) throws Exception {
        List<String> args = new ArrayList<>();
        args.add("block");
        args.addAll(List.of(domains));
        args.add("--hosts-file");
        args.add(hosts().toString());
        assertEquals(0, run(args.toArray(new String[0])));
        assertEquals(0, run("save", "profile", name));
    }

    private String profile() throws Exception {
        return Files.readString(profilesFile(), StandardCharsets.UTF_8);
    }

    // Exit code + stdout/stderr of a command.
    private record Result(int exit, String text) {
    }

    private Result runCommand(Command cmd) throws Exception {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream fake = new PrintStream(buffer, true, StandardCharsets.UTF_8);
        System.setOut(fake);
        System.setErr(fake);
        int exit;
        try {
            exit = cmd.run();
        } catch (Exception e) {
            throw new AssertionError(e);
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
        return new Result(exit, buffer.toString(StandardCharsets.UTF_8));
    }

    @FunctionalInterface
    private interface Command {
        int run() throws Exception;
    }

    @Test
    void removeTakesTheDomainOutOfTheRecipe() throws Exception {
        prepareProfile("work", "tiktok.com", "youtube.com");

        assertEquals(0, run("profile", "work", "remove", "youtube.com"));

        String json = profile();
        assertFalse(json.contains("youtube.com"), "the domain must leave the recipe: " + json);
        assertTrue(json.contains("tiktok.com"), "the rest of the recipe must remain: " + json);
    }

    @Test
    void removeDoesNotTouchHosts() throws Exception {
        prepareProfile("work", "tiktok.com");
        String before = Files.readString(hosts(), StandardCharsets.UTF_8);

        assertEquals(0, run("profile", "work", "remove", "tiktok.com"));

        assertEquals(before, Files.readString(hosts(), StandardCharsets.UTF_8),
                "removing from the recipe does not release the block: the hosts file must not change");
    }

    @Test
    void removeWarnsThatTheDomainStaysBlocked() throws Exception {
        prepareProfile("work", "tiktok.com");

        Result r = runCommand(() -> run("profile", "work", "remove", "tiktok.com"));

        assertEquals(0, r.exit(), r.text());
        assertTrue(r.text().contains("tiktok.com"), r.text());
        assertTrue(r.text().toLowerCase().contains("stays blocked"),
                "it must warn that the block remains: " + r.text());
        assertTrue(r.text().toLowerCase().contains("unblock"),
                "it must name the command that still releases it: " + r.text());
    }

    @Test
    void removeFailsWhenTheDomainIsNotInTheRecipe() throws Exception {
        prepareProfile("work", "tiktok.com");
        String before = profile();

        Result r = runCommand(() -> run("profile", "work", "remove", "youtube.com"));

        assertEquals(1, r.exit(), r.text());
        assertTrue(r.text().toLowerCase().contains("nothing was changed"),
                "the refusal must say that nothing changed: " + r.text());
        assertEquals(before, profile(), "a domain that is not in the recipe must not change anything");
    }

    @Test
    void removeFailsWhenTheProfileDoesNotExist() throws Exception {
        Result r = runCommand(() -> run("profile", "missing", "remove", "tiktok.com"));

        assertEquals(1, r.exit(), r.text());
        assertTrue(r.text().toLowerCase().contains("does not exist"),
                "the refusal must say that the profile does not exist: " + r.text());
        assertFalse(Files.exists(profilesFile()),
                "it must not create a recipe while handling a profile that does not exist");
    }

    @Test
    void removeFailsWithAnInvalidProfileName() throws Exception {
        Result r = runCommand(() -> run("profile", "../evil", "remove", "tiktok.com"));

        assertEquals(1, r.exit(), r.text());
        assertTrue(r.text().toLowerCase().contains("invalid"),
                "the refusal must say that the name is invalid: " + r.text());
    }

    @Test
    void removeFailsWithAnInvalidDomain() throws Exception {
        prepareProfile("work", "tiktok.com");
        String before = profile();

        Result r = runCommand(() -> run("profile", "work", "remove", "not a domain"));

        assertEquals(1, r.exit(), r.text());
        assertTrue(r.text().toLowerCase().contains("error"),
                "the refusal must say that the domain is invalid: " + r.text());
        assertEquals(before, profile(), "an invalid domain must not change anything");
    }

    // WHY: the state read exists only for the "stays blocked" warning. Failing it
    // prevents the warning, not the recipe edit — undoing the removal because of a
    // broken file would trade a small problem for a bigger one.
    /**
     * An unreadable state must not prevent the removal.
     */
    @Test
    void removeSurvivesAnUnreadableState() throws Exception {
        prepareProfile("work", "tiktok.com");
        Files.writeString(Path.of(state()), "{ this is not json", StandardCharsets.UTF_8);

        Result r = runCommand(() -> run("profile", "work", "remove", "tiktok.com"));

        assertEquals(0, r.exit(), r.text());
        assertFalse(profile().contains("tiktok.com"),
                "the removal must happen even with a broken state: " + profile());
        assertTrue(r.text().toLowerCase().contains("could not read the state"),
                "the read failure must be reported: " + r.text());
    }
}
