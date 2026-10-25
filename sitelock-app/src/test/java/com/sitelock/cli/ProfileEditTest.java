package com.sitelock.cli;

import com.sitelock.SiteLockApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Editing the recipe of an existing profile:
 * {@code siteblock profile <name> add <domain>} and
 * {@code siteblock profile <name> remove <domain>}.
 *
 * <p>The tests run through the real CLI — {@code CommandLine.execute} — and not
 * by directly constructing the command. The chosen shape puts the profile name
 * <b>before</b> the subcommand, and that only works if picocli couples the
 * parent positional with the right subcommand. A test that called
 * {@code new ProfileAddCommand(...).call()} would pass with all the parsing
 * broken.
 */
class ProfileEditTest {

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
        List<String> all = new java.util.ArrayList<>(List.of(args));
        all.add("--state-file");
        all.add(state());
        return new picocli.CommandLine(new SiteLockApplication()).execute(all.toArray(new String[0]));
    }

    private void block(String... domains) throws Exception {
        assertEquals(0, new BlockCommand(List.of(domains), "4h", hosts().toString(), state()).call());
    }

    private void saveProfile(String name) throws Exception {
        assertEquals(0, new ProfileSaveCommand(name, false, hosts().toString(), state()).call());
    }

    private String profile() throws Exception {
        return Files.readString(profilesFile(), StandardCharsets.UTF_8);
    }

    // ---- add ----

    @Test
    void addPutsTheDomainInTheProfile() throws Exception {
        block("tiktok.com");
        saveProfile("work");

        assertEquals(0, run("profile", "work", "add", "youtube.com"));

        String json = profile();
        assertTrue(json.contains("youtube.com"), json);
        assertTrue(json.contains("tiktok.com"), "what was already there must remain: " + json);
    }

    @Test
    void addWithoutDurationIsPermanent() throws Exception {
        block("tiktok.com");
        saveProfile("work");

        assertEquals(0, run("profile", "work", "add", "youtube.com"));

        assertTrue(profile().contains("\"youtube.com\""), profile());
        assertEquals(-1L, durationOf(profile(), "youtube.com"),
                "without --for the domain enters as permanent: " + profile());
    }

    @Test
    void addWithDurationStoresIt() throws Exception {
        block("tiktok.com");
        saveProfile("work");

        assertEquals(0, run("profile", "work", "add", "youtube.com", "--for", "4h"));

        long ms = durationOf(profile(), "youtube.com");
        assertTrue(ms > 0, "it must store the duration: " + ms);
        assertTrue(ms <= 4 * 3600_000L, "it must not exceed 4h: " + ms);
    }

    @Test
    void addFailsWhenTheProfileDoesNotExist() {
        assertEquals(1, run("profile", "missing", "add", "youtube.com"));
        assertFalse(Files.exists(profilesFile()), "add must not create a profile along the way");
    }

    @Test
    void addRejectsInvalidDomain() throws Exception {
        block("tiktok.com");
        saveProfile("work");
        String before = profile();

        assertEquals(1, run("profile", "work", "add", "not a domain"));

        assertEquals(before, profile(), "an invalid domain must not change anything");
    }

    @Test
    void addReplacesTheEntryWhenTheDomainIsAlreadyThere() throws Exception {
        block("tiktok.com");
        saveProfile("work");
        // tiktok.com enters as permanent (the block was 4h, but save stores what
        // remains); adding it again without --for keeps it permanent.
        assertEquals(0, run("profile", "work", "add", "tiktok.com", "--for", "10m"));
        assertEquals(0, run("profile", "work", "add", "tiktok.com"));

        assertEquals(-1L, durationOf(profile(), "tiktok.com"),
                "the recipe must store the latest request, not accumulate: " + profile());
    }

    @Test
    void addNormalizesTheDomain() throws Exception {
        block("tiktok.com");
        saveProfile("work");

        assertEquals(0, run("profile", "work", "add", "www.youtube.com"));

        assertTrue(profile().contains("\"youtube.com\""), "it must normalize: " + profile());
        assertFalse(profile().contains("www.youtube.com"), profile());
    }

    // ---- remove ----

    @Test
    void removeTakesTheDomainOutOfTheProfile() throws Exception {
        block("tiktok.com", "youtube.com");
        saveProfile("work");

        assertEquals(0, run("profile", "work", "remove", "youtube.com"));

        String json = profile();
        assertFalse(json.contains("youtube.com"), json);
        assertTrue(json.contains("tiktok.com"), "the rest must remain: " + json);
    }

    @Test
    void removeWarnsWhenTheDomainIsStillBlocked() throws Exception {
        block("tiktok.com");
        saveProfile("work");

        String output = capture(() -> run("profile", "work", "remove", "tiktok.com"));

        assertTrue(output.contains("tiktok.com"), output);
        assertTrue(output.toLowerCase().contains("stays blocked"),
                "it must warn that the block remains: " + output);
    }

    @Test
    void removeFailsWhenTheDomainIsNotInTheProfile() throws Exception {
        block("tiktok.com");
        saveProfile("work");
        String before = profile();

        assertEquals(1, run("profile", "work", "remove", "youtube.com"));

        assertEquals(before, profile());
    }

    // ---- the shape of the command must not break the others ----

    @Test
    void theOtherProfileCommandsStillWork() throws Exception {
        block("tiktok.com");
        saveProfile("work");

        assertEquals(0, run("profile", "list"));
        assertEquals(0, run("profile", "show", "work"));
        assertEquals(0, run("profile", "apply", "work", "--hosts-file", hosts().toString()));
    }

    /**
     * The profile name became a positional of the {@code profile} command. The
     * concrete danger is picocli starting to swallow the name of subcommands that
     * already had it as their own positional — {@code save profile work} must keep
     * working, otherwise the whole command breaks.
     */
    @Test
    void saveAndDeleteStillTakeTheirOwnName() throws Exception {
        block("tiktok.com");

        assertEquals(0, run("save", "profile", "work"));
        assertTrue(profile().contains("tiktok.com"), profile());

        // delete asks for a typed confirmation by reading System.in.
        java.io.InputStream originalIn = System.in;
        System.setIn(new java.io.ByteArrayInputStream("YES\n".getBytes(StandardCharsets.UTF_8)));
        try {
            assertEquals(0, run("delete", "profile", "work"));
        } finally {
            System.setIn(originalIn);
        }
        // delete empties profiles.json; what must be gone is the profile.
        assertFalse(profile().contains("work"), "the profile must be gone: " + profile());
        assertEquals(1, run("profile", "show", "work"));
    }

    @Test
    void addWithoutANameSaysHowToCallIt() {
        String output = capture(() -> run("profile", "add", "youtube.com"));

        assertTrue(output.contains("siteblock profile <name> add <domain>"),
                "the message must show the full form: " + output);
    }

    @Test
    void addRejectsAnInvalidProfileName() {
        assertEquals(1, run("profile", "../evil", "add", "youtube.com"));
    }

    // ---- the clock is injectable ----

    /**
     * {@code add} and {@code remove} used {@code Instant.now()} directly, while
     * the rest of the project injects {@link java.time.Clock}. With the raw clock
     * there is no way to test with controlled time — and what gets stored depends
     * on the instant.
     */
    @Test
    void addWritesWithTheInjectedInstant() throws Exception {
        block("tiktok.com");
        saveProfile("work");
        Instant fixed = Instant.parse("2020-01-02T03:04:05Z");

        ProfileCommand parent = new ProfileCommand();
        parent.name = "work";
        ProfileAddCommand cmd = new ProfileAddCommand();
        cmd.parent = parent;
        cmd.domainsRaw = List.of("youtube.com");
        cmd.stateFileOverride = state();
        cmd.clockOverride = Clock.fixed(fixed, ZoneOffset.UTC);

        assertEquals(0, cmd.call());

        assertTrue(profile().contains("2020-01-02T03:04:05Z"),
                "updatedAt must come from the injected clock: " + profile());
    }

    @Test
    void removeWritesWithTheInjectedInstant() throws Exception {
        block("tiktok.com", "youtube.com");
        saveProfile("work");
        Instant fixed = Instant.parse("2020-01-02T03:04:05Z");

        ProfileCommand parent = new ProfileCommand();
        parent.name = "work";
        ProfileRemoveCommand cmd = new ProfileRemoveCommand();
        cmd.parent = parent;
        cmd.domainsRaw = List.of("youtube.com");
        cmd.stateFileOverride = state();
        cmd.clockOverride = Clock.fixed(fixed, ZoneOffset.UTC);

        assertEquals(0, cmd.call());

        assertTrue(profile().contains("2020-01-02T03:04:05Z"),
                "updatedAt must come from the injected clock: " + profile());
    }

    /**
     * The "stays blocked" warning also depends on the clock: with the block
     * already expired, there is nothing to warn about.
     */
    @Test
    void removeUsesTheInjectedClockToKnowWhatStaysBlocked() throws Exception {
        block("tiktok.com");
        saveProfile("work");

        ProfileCommand parent = new ProfileCommand();
        parent.name = "work";
        ProfileRemoveCommand cmd = new ProfileRemoveCommand();
        cmd.parent = parent;
        cmd.domainsRaw = List.of("tiktok.com");
        cmd.stateFileOverride = state();
        // The 4h block has already expired at this instant.
        cmd.clockOverride = Clock.fixed(Instant.now().plusSeconds(10 * 3600), ZoneOffset.UTC);

        String output = capture(() -> cmd.call());

        assertFalse(output.toLowerCase().contains("stays blocked"),
                "an expired block must not generate a warning: " + output);
    }

    // ---- helpers ----

    private static long durationOf(String json, String domain) {
        int i = json.indexOf("\"" + domain + "\"");
        assertTrue(i >= 0, "domain missing from the JSON: " + json);
        int j = json.indexOf("durationMillis", i);
        assertTrue(j > 0, "durationMillis missing: " + json);
        String rest = json.substring(j);
        int open = rest.indexOf(':');
        int close = rest.indexOf('\n', open);
        String number = rest.substring(open + 1, close).replace(",", "").replace(" ", "").trim();
        return "null".equals(number) ? -1L : Long.parseLong(number);
    }

    private static String capture(Runnable r) {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        try {
            r.run();
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }
}
