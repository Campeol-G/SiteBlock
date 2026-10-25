package com.sitelock.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sitelock.core.Profile;
import com.sitelock.core.ProfileStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Profile save/list/show/delete commands against temporary files. */
class ProfileCommandsTest {

    @TempDir
    Path tmp;

    // Create once: rewriting would erase the blocks the test just made.
    private Path hosts() throws Exception {
        if (hosts == null) {
            hosts = tmp.resolve("hosts");
            Files.writeString(hosts, "127.0.0.1 localhost\n", StandardCharsets.UTF_8);
        }
        return hosts;
    }

    private Path hosts;

    private String state() {
        return tmp.resolve("state/blocks.json").toString();
    }

    private Path profilesFile() {
        return tmp.resolve("state/profiles.json");
    }

    private void block(String... domains) throws Exception {
        assertEquals(0, new BlockCommand(List.of(domains), "4h", hosts().toString(), state()).call());
    }

    @Test
    void saveStoresRemainingDurations() throws Exception {
        block("tiktok.com");
        assertEquals(0, new ProfileSaveCommand("work", false, hosts().toString(), state()).call());

        String json = Files.readString(profilesFile(), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"work\""), json);
        assertTrue(json.contains("tiktok.com"), json);
        assertTrue(json.contains("durationMillis"), json);

        // What is stored is what remains, not the original 4h: a profile is a recipe.
        long ms = extractedDurationMillis(json, "tiktok.com");
        assertTrue(ms > 0, "the remaining deadline should be positive: " + ms);
        assertTrue(ms < 4 * 3600_000L, "it must not store the original 4h: " + ms);
    }

    @Test
    void saveFailsWithNoActiveBlocks() throws Exception {
        int exit = new ProfileSaveCommand("empty", false, hosts().toString(), state()).call();
        assertEquals(1, exit);
        assertFalse(Files.exists(profilesFile()), "an empty profile does nothing and hides the error");
    }

    @Test
    void saveRefusesToOverwriteWithoutForce() throws Exception {
        block("tiktok.com");
        assertEquals(0, new ProfileSaveCommand("work", false, hosts().toString(), state()).call());
        String first = Files.readString(profilesFile(), StandardCharsets.UTF_8);

        block("youtube.com");
        assertEquals(1, new ProfileSaveCommand("work", false, hosts().toString(), state()).call());
        assertEquals(first, Files.readString(profilesFile(), StandardCharsets.UTF_8),
                "without --force the existing profile must stay intact");

        assertEquals(0, new ProfileSaveCommand("work", true, hosts().toString(), state()).call());
        assertTrue(Files.readString(profilesFile(), StandardCharsets.UTF_8).contains("youtube.com"));
    }

    @Test
    void saveRejectsInvalidName() throws Exception {
        block("tiktok.com");
        assertEquals(1, new ProfileSaveCommand("../evil", false, hosts().toString(), state()).call());
    }

    @Test
    void addRejectsDurationThatWouldOverflowMillis() throws Exception {
        block("tiktok.com");
        assertEquals(0, new ProfileSaveCommand("work", false,
                hosts().toString(), state()).call());

        ProfileCommand parent = new ProfileCommand();
        parent.name = "work";
        ProfileAddCommand cmd = new ProfileAddCommand();
        cmd.parent = parent;
        cmd.domainsRaw = List.of("youtube.com");
        cmd.forDuration = "999999999999d";
        cmd.stateFileOverride = state();

        assertEquals(1, cmd.call());
        assertFalse(Files.readString(profilesFile(), StandardCharsets.UTF_8).contains("youtube.com"),
                "an invalid duration must not write an entry to the profile");
    }

    @Test
    void saveSkipsExpiredThatAreStillPending() throws Exception {
        // hosts file not writable: the sweep returns skippedDueToPermission and the
        // expired entry stays in the state. It must not enter the profile — otherwise
        // applying the recipe tomorrow re-blocks a domain that has already expired.
        Path sf = Path.of(state());
        Files.createDirectories(sf.getParent());
        Files.writeString(sf, """
                {
                  "expired.com" : {
                    "domain" : "expired.com",
                    "createdAt" : "2020-01-01T00:00:00Z",
                    "expiresAt" : "2020-01-01T01:00:00Z"
                  },
                  "live.com" : {
                    "domain" : "live.com",
                    "createdAt" : "2020-01-01T00:00:00Z",
                    "expiresAt" : null
                  }
                }
                """, StandardCharsets.UTF_8);

        Path unwritable = tmp.resolve("no-parent/hosts");
        assertEquals(0, new ProfileSaveCommand("clean", false, unwritable.toString(), state()).call());

        String profile = Files.readString(profilesFile(), StandardCharsets.UTF_8);
        assertFalse(profile.contains("expired.com"),
                "a pending expired entry must not become a profile entry: " + profile);
        assertTrue(profile.contains("live.com"), profile);
    }

    /**
     * Saving a profile must not erase a recipe another terminal wrote.
     *
     * <p>The command read the recipes and wrote the whole file: a recipe written
     * in between disappeared. The test is deterministic — the other writer saves
     * the file outside the lock, at the moment the command loads the recipes.
     */
    @Test
    void saveDoesNotLoseRecipeWrittenByAnotherTerminal() throws Exception {
        block("tiktok.com");

        ProfileStore spy = new ProfileStore(profilesFile()) {
            @Override
            public Map<String, Profile> load() throws IOException {
                Map<String, Profile> profiles = super.load();
                writeRecipeOutsideLock(profilesFile(), "other");
                return profiles;
            }
        };

        ProfileSaveCommand cmd = new ProfileSaveCommand("work", false,
                hosts().toString(), state());
        cmd.storeOverride = spy;
        assertEquals(0, cmd.call());

        Map<String, Profile> after = new ProfileStore(profilesFile()).load();
        assertTrue(after.containsKey("work"),
                "the saved profile must be there: " + after.keySet());
        assertTrue(after.containsKey("other"),
                "the recipe written by another terminal must not be lost: " + after.keySet());
    }

    // Writes profiles.json outside the lock, like a writer that does not respect it.
    private void writeRecipeOutsideLock(Path file, String name) throws IOException {
        String json = """
                {
                  "version" : 1,
                  "profiles" : {
                    "%s" : {
                      "createdAt" : "2026-10-15T12:02:11Z",
                      "updatedAt" : "2026-10-15T12:02:11Z",
                      "entries" : {
                        "other.com" : { "durationMillis" : null }
                      }
                    }
                  }
                }
                """.formatted(name);
        Files.writeString(file, json, StandardCharsets.UTF_8);
    }

    @Test
    void listShowsNameAndCount() throws Exception {
        block("tiktok.com", "youtube.com");
        assertEquals(0, new ProfileSaveCommand("work", false, hosts().toString(), state()).call());
        assertEquals(0, new ProfileSaveCommand("rest", false, hosts().toString(), state()).call());

        String out = capture(() -> new ProfileListCommand(state()).call());
        assertTrue(out.contains("work"), out);
        assertTrue(out.contains("rest"), out);
        assertTrue(out.contains("2"), "it must show the number of domains: " + out);
    }

    @Test
    void listWithNoProfilesSaysSo() {
        String out = capture(() -> new ProfileListCommand(state()).call());
        assertEquals(0, new ProfileListCommand(state()).call());
        assertTrue(out.toLowerCase().contains("no profiles"), out);
    }

    @Test
    void showListsDomainsWithDurations() throws Exception {
        assertEquals(0, new BlockCommand(List.of("tiktok.com"), null, hosts().toString(), state()).call());
        assertEquals(0, new ProfileSaveCommand("permanent", false, hosts().toString(), state()).call());

        String out = capture(() -> new ProfileShowCommand("permanent", state()).call());
        assertTrue(out.contains("tiktok.com"), out);
        assertTrue(out.contains("permanent"), out);
    }

    @Test
    void showUnknownProfileFails() throws Exception {
        assertEquals(1, new ProfileShowCommand("missing", state()).call());
    }

    @Test
    void deleteRemovesProfile() throws Exception {
        block("tiktok.com");
        assertEquals(0, new ProfileSaveCommand("temporary", false, hosts().toString(), state()).call());

        assertEquals(0, new ProfileDeleteCommand("temporary", "YES", state()).call());

        assertEquals(1, new ProfileShowCommand("temporary", state()).call());
    }

    @Test
    void deleteCancelledKeepsProfile() throws Exception {
        block("tiktok.com");
        assertEquals(0, new ProfileSaveCommand("temporary", false, hosts().toString(), state()).call());

        assertEquals(3, new ProfileDeleteCommand("temporary", "no", state()).call());

        assertEquals(0, new ProfileShowCommand("temporary", state()).call(),
                "cancelling must not delete the profile");
    }

    @Test
    void deleteUnknownProfileFails() throws Exception {
        assertEquals(1, new ProfileDeleteCommand("missing", "YES", state()).call());
    }

    private static long extractedDurationMillis(String json, String domain) {
        int i = json.indexOf("\"" + domain + "\"");
        assertTrue(i >= 0, "domain missing from the JSON: " + json);
        int j = json.indexOf("durationMillis", i);
        assertTrue(j > 0, "durationMillis missing: " + json);
        String rest = json.substring(j);
        int open = rest.indexOf(':');
        int close = rest.indexOf('\n', open);
        String number = rest.substring(open + 1, close).replace(",", "").replace(" ", "").trim();
        return Long.parseLong(number);
    }

    private static String capture(Runnable r) {
        java.io.PrintStream original = System.out;
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        System.setOut(new java.io.PrintStream(buffer, true, java.nio.charset.StandardCharsets.UTF_8));
        try {
            r.run();
        } finally {
            System.setOut(original);
        }
        return buffer.toString(java.nio.charset.StandardCharsets.UTF_8);
    }
}
