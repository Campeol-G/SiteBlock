package com.sitelock.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Persistence of profiles.json. The contract that matters most is the one from the
 * {@code BlockStore}: unreadable JSON becomes a {@link CorruptedStateException} and
 * <b>deletes nothing</b>. A profile is typed work; losing one because of
 * a stray quote would be the worst possible failure here.
 */
class ProfileStoreTest {

    @TempDir
    Path tmp;

    private ProfileStore store() {
        return new ProfileStore(tmp.resolve("profiles.json"));
    }

    private static Profile profile(Instant at, Map<String, Long> entries) {
        Map<String, ProfileEntry> m = new TreeMap<>();
        entries.forEach((d, ms) -> m.put(d, new ProfileEntry(ms)));
        return new Profile(at, at, m);
    }

    // Map.of does not accept a null value, and "permanent" is exactly null.
    private static Map<String, Long> durations(Object... pairs) {
        Map<String, Long> m = new TreeMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            m.put((String) pairs[i], (Long) pairs[i + 1]);
        }
        return m;
    }

    @Test
    void roundTripsProfiles() throws Exception {
        Instant at = Instant.parse("2026-10-15T14:02:11Z");
        Map<String, Profile> profiles = new TreeMap<>();
        profiles.put("work", profile(at, durations("youtube.com", 28800000L, "tiktok.com", null)));
        profiles.put("weekend", profile(at, durations("instagram.com", 3600000L)));

        store().save(profiles);
        Map<String, Profile> read = store().load();

        assertEquals(2, read.size());
        Profile t = read.get("work");
        assertEquals(at, t.getCreatedAt());
        assertEquals(at, t.getUpdatedAt());
        assertEquals(Long.valueOf(28800000L), t.getEntries().get("youtube.com").getDurationMillis());
        assertEquals(Long.valueOf(3600000L),
                read.get("weekend").getEntries().get("instagram.com").getDurationMillis());
    }

    @Test
    void missingFileLoadsEmpty() throws Exception {
        assertTrue(store().load().isEmpty());
    }

    @Test
    void corruptedJsonThrowsAndKeepsFile() throws Exception {
        Path file = tmp.resolve("profiles.json");
        String garbage = "{ not json";
        Files.writeString(file, garbage, StandardCharsets.UTF_8);

        ProfileStore store = store();
        assertThrows(CorruptedStateException.class, store::load);
        assertEquals(garbage, Files.readString(file, StandardCharsets.UTF_8),
                "corruption must not erase the file — the profile is typed work");
    }

    @Test
    void deleteRemovesOnlyTheNamedProfile() throws Exception {
        Instant at = Instant.parse("2026-10-15T14:02:11Z");
        Map<String, Profile> profiles = new TreeMap<>();
        profiles.put("a", profile(at, durations("x.com", 1000L)));
        profiles.put("b", profile(at, durations("y.com", 1000L)));
        ProfileStore store = store();
        store.save(profiles);

        store.delete("a");

        Map<String, Profile> read = store.load();
        assertEquals(1, read.size());
        assertTrue(read.containsKey("b"));
    }

    // WHY: the standalone delete was a load followed by a save, and the recipe
    // another terminal saved along the way vanished with it. The test is
    // deterministic: the other writer writes the file outside the lock.
    @Test
    void deleteDoesNotEraseWhatAnotherWriterWrote() throws Exception {
        Path file = tmp.resolve("profiles.json");
        ProfileStore store = new ProfileStore(file);
        store.save(Map.of("x", profile(Instant.parse("2026-10-15T14:02:11Z"),
                durations("x.com", 1000L))));

        // "Another terminal": while the delete loads the file, another writer
        // saves the recipe "y" outside the lock.
        ProfileStore spy = new ProfileStore(file) {
            @Override
            public Map<String, Profile> load() throws IOException {
                Map<String, Profile> profiles = super.load();
                Map<String, Profile> concurrent = new TreeMap<>(profiles);
                concurrent.put("y", profile(Instant.parse("2026-10-15T14:02:11Z"),
                        durations("y.com", 1000L)));
                Files.writeString(file, ProfileStore.defaultMapper()
                        .writerWithDefaultPrettyPrinter()
                        .writeValueAsString(new ProfileDocument(
                                ProfileDocument.CURRENT_VERSION, concurrent)),
                        StandardCharsets.UTF_8);
                return profiles;
            }
        };

        spy.delete("x");

        Map<String, Profile> after = store.load();
        assertFalse(after.containsKey("x"), "the deleted profile has to disappear: " + after.keySet());
        assertTrue(after.containsKey("y"),
                "the concurrent write must not be lost: " + after.keySet());
    }

    @Test
    void invalidNamesAreRejected() {
        for (String name : new String[]{"", " work", "work ", "a b", "../escape",
                "profile\twith\ttab", "x".repeat(65), "profile/../etc"}) {
            assertThrows(IllegalArgumentException.class, () -> ProfileNames.require(name),
                    "should reject: '" + name + "'");
            assertTrue(!ProfileNames.isValid(name), "isValid should say no to '" + name + "'");
        }
    }

    @Test
    void validNamesAreAccepted() {
        assertTrue(ProfileNames.isValid("work"));
        assertTrue(ProfileNames.isValid("Profile-1"));
        assertTrue(ProfileNames.isValid("a"));
        assertTrue(ProfileNames.isValid("x".repeat(64)));
        assertEquals("work", ProfileNames.require("work"));
    }

    @Test
    void nullDurationMeansPermanent() throws Exception {
        Instant at = Instant.parse("2026-10-15T14:02:11Z");
        store().save(Map.of("p", profile(at, durations("tiktok.com", null))));

        Long duration = store().load().get("p").getEntries().get("tiktok.com").getDurationMillis();
        assertNull(duration, "permanent is null, not 0");
    }

    @Test
    void versionIsWrittenAtTheRoot() throws Exception {
        store().save(Map.of());
        String json = Files.readString(tmp.resolve("profiles.json"), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"version\""), "the format will evolve and needs a version field: " + json);
    }
}