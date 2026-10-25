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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@link ProfileStore} delta.
 *
 * <p>The store writes a delta over what is on disk, not the whole file:
 * a concurrent write from someone who did not wait for the lock must not be erased. For
 * that the comparison has to be by value — by identity, a profile returned
 * unchanged looked modified, and the write erased another writer's version.
 */
class ProfileDeltaTest {

    @TempDir
    Path tmp;

    private static final Instant CREATED = Instant.parse("2026-10-15T12:00:00Z");
    private static final Instant NOW = Instant.parse("2026-10-15T18:30:00Z");

    private Path file() {
        return tmp.resolve("profiles.json");
    }

    private ProfileStore store() {
        return new ProfileStore(file());
    }

    private static Profile profile(Long duration) {
        Map<String, ProfileEntry> entries = new TreeMap<>();
        entries.put("x.com", new ProfileEntry(duration));
        return new Profile(CREATED, NOW, entries);
    }

    // Writes the file outside the lock, like a writer that does not respect it.
    private void writeOutsideTheLock(Map<String, Profile> profiles) throws IOException {
        Files.writeString(file(), ProfileStore.defaultMapper()
                .writerWithDefaultPrettyPrinter()
                .writeValueAsString(new ProfileDocument(ProfileDocument.CURRENT_VERSION, profiles)),
                StandardCharsets.UTF_8);
    }

    @Test
    void changedProfileMustBeSaved() throws Exception {
        ProfileStore store = store();
        store.save(Map.of("x", profile(1000L)));

        store.inTransaction(tx -> {
            Map<String, Profile> profiles = tx.load();
            Map<String, ProfileEntry> entries = new TreeMap<>(profiles.get("x").getEntries());
            entries.put("y.com", new ProfileEntry(2000L));
            profiles.put("x", new Profile(CREATED, NOW.plusSeconds(60), entries));
            tx.save(profiles);
            return null;
        });

        Map<String, ProfileEntry> entries = store.load().get("x").getEntries();
        assertTrue(entries.containsKey("y.com"),
                "the new entry has to be on disk: " + entries.keySet());
    }

    @Test
    void unchangedProfileMustNotEraseAnotherWritersVersion() throws Exception {
        ProfileStore store = store();
        store.save(Map.of("x", profile(1000L)));

        store.inTransaction(tx -> {
            Map<String, Profile> profiles = tx.load();
            // The transaction returns the profile without touching it — but as a new
            // object, the way a command that rebuilds the recipe would return it.
            profiles.put("x", new Profile(CREATED, NOW, new TreeMap<>(
                    profiles.get("x").getEntries())));
            // Meanwhile, another writer changes the same profile on disk.
            writeOutsideTheLock(Map.of("x", profile(9999L)));
            tx.save(profiles);
            return null;
        });

        assertEquals(Long.valueOf(9999L),
                store.load().get("x").getEntries().get("x.com").getDurationMillis(),
                "a profile that did not change must not overwrite another writer's version");
    }

    @Test
    void aProfileChangedInPlaceMustBeSaved() throws Exception {
        ProfileStore store = store();
        store.save(Map.of("x", profile(1000L)));

        store.inTransaction(tx -> {
            Map<String, Profile> profiles = tx.load();
            // The change happens on the loaded profile itself, not on a
            // new object: the delta has to see the change.
            profiles.get("x").getEntries().put("y.com", new ProfileEntry(2000L));
            tx.save(profiles);
            return null;
        });

        Map<String, ProfileEntry> entries = store.load().get("x").getEntries();
        assertTrue(entries.containsKey("y.com"),
                "an in-place change has to reach the disk: " + entries.keySet());
    }
}
