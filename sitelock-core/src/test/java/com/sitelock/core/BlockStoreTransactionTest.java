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

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The transaction of the {@link BlockStore}.
 *
 * <p>It exists to prevent an unprotected read-modify-write: the {@code load}
 * happens outside the lock, and a block made by another terminal in that interval
 * is erased from the JSON while it remains in {@code /etc/hosts}.
 *
 * <p>In the test, the other writer writes the file outside the lock, and the transaction
 * has to apply its own change <b>on top</b> of what is on disk.
 */
class BlockStoreTransactionTest {

    @TempDir
    Path tmp;

    private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");

    private static BlockEntry entry(String domain) {
        return new BlockEntry(domain, NOW, null);
    }

    // Writes the state outside the lock, like a writer that does not respect it.
    private void writeOutsideTheLock(Path stateFile, Map<String, BlockEntry> state)
            throws IOException {
        Files.writeString(stateFile, BlockStore.defaultMapper()
                .writerWithDefaultPrettyPrinter().writeValueAsString(state),
                StandardCharsets.UTF_8);
    }

    @Test
    void concurrentWriteIsNotLost() throws Exception {
        Path stateFile = tmp.resolve("blocks.json");
        BlockStore store = new BlockStore(stateFile);
        Map<String, BlockEntry> initial = new TreeMap<>();
        initial.put("a.com", entry("a.com"));
        store.save(initial);

        store.inTransaction(tx -> {
            Map<String, BlockEntry> state = tx.load();
            state.put("b.com", entry("b.com"));

            // Another path: while the transaction holds the lock, someone writes the
            // state outside of it. That is the "block from another terminal": read what
            // is on disk, add the domain and write.
            Map<String, BlockEntry> concurrent = new TreeMap<>(store.load());
            concurrent.put("c.com", entry("c.com"));
            writeOutsideTheLock(stateFile, concurrent);

            tx.save(state);
            return null;
        });

        Map<String, BlockEntry> after = store.load();
        assertTrue(after.containsKey("c.com"),
                "the concurrent write must not be lost: " + after.keySet());
        assertTrue(after.containsKey("b.com"),
                "the transaction's change has to be there: " + after.keySet());
        assertTrue(after.containsKey("a.com"),
                "what already existed must remain: " + after.keySet());
    }

    @Test
    void transactionWritesInSortedOrder() throws Exception {
        Path stateFile = tmp.resolve("blocks.json");
        BlockStore store = new BlockStore(stateFile);

        store.inTransaction(tx -> {
            Map<String, BlockEntry> state = tx.load();
            state.put("zeta.com", entry("zeta.com"));
            state.put("alpha.com", entry("alpha.com"));
            state.put("mike.com", entry("mike.com"));
            tx.save(state);
            return null;
        });

        String json = Files.readString(stateFile, StandardCharsets.UTF_8);
        int zeta = json.indexOf("zeta.com");
        int alpha = json.indexOf("alpha.com");
        int mike = json.indexOf("mike.com");
        assertTrue(alpha < mike && mike < zeta,
                "the JSON must be sorted: " + json);
    }
}
