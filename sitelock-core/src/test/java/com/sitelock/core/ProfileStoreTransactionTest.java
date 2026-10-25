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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The transaction of the {@link ProfileStore}.
 *
 * <p>Two simultaneous {@code profile add} operations overwrote each other: each one read the recipe and
 * wrote the whole file — the last write erased the other's domain. The
 * recipe is typed work: losing it is worse than losing block state,
 * which can be rebuilt.
 *
 * <p>In the test, the other writer writes the file outside the lock, and the
 * transaction has to apply its own change <b>on top</b> of what is on disk.
 */
class ProfileStoreTransactionTest {

    @TempDir
    Path tmp;

    private static final Instant NOW = Instant.parse("2026-10-15T12:02:11Z");

    private Path file() {
        return tmp.resolve("profiles.json");
    }

    private static Profile profile(String... domains) {
        Map<String, ProfileEntry> entries = new TreeMap<>();
        for (String domain : domains) {
            entries.put(domain, new ProfileEntry(null));
        }
        return new Profile(NOW, NOW, entries);
    }

    // Writes the file outside the lock, like a writer that does not respect it.
    private void writeOutsideTheLock(Path profilesFile, Map<String, Profile> profiles)
            throws IOException {
        Files.writeString(profilesFile, ProfileStore.defaultMapper()
                .writerWithDefaultPrettyPrinter()
                .writeValueAsString(new ProfileDocument(ProfileDocument.CURRENT_VERSION, profiles)),
                StandardCharsets.UTF_8);
    }

    @Test
    void concurrentWriteIsNotLost() throws Exception {
        ProfileStore store = new ProfileStore(file());
        store.save(Map.of("x", profile("x.com")));

        store.inTransaction(tx -> {
            Map<String, Profile> profiles = tx.load();
            profiles.put("y", profile("y.com"));

            Map<String, Profile> concurrent = new TreeMap<>(store.load());
            concurrent.put("z", profile("z.com"));
            writeOutsideTheLock(file(), concurrent);

            tx.save(profiles);
            return null;
        });

        Map<String, Profile> after = store.load();
        assertTrue(after.containsKey("z"),
                "the concurrent write must not be lost: " + after.keySet());
        assertTrue(after.containsKey("y"),
                "the transaction's change has to be there: " + after.keySet());
    }
}
