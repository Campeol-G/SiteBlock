package com.sitelock.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/** Serialização do blocks.json. */
class BlockStoreTest {

    @TempDir
    Path tmp;

    @Test
    void roundTripPermanentAndTemporary() throws Exception {
        Path stateFile = tmp.resolve("blocks.json");
        BlockStore store = new BlockStore(stateFile);

        assertTrue(store.load().isEmpty());

        Map<String, BlockEntry> blocks = new TreeMap<>();
        blocks.put("a.com", new BlockEntry("a.com",
                Instant.parse("2026-01-01T00:00:00Z"), null));
        blocks.put("b.com", new BlockEntry("b.com",
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-02T00:00:00Z")));
        store.save(blocks);

        Map<String, BlockEntry> loaded = store.load();
        assertEquals(2, loaded.size());
        assertNull(loaded.get("a.com").getExpiresAt());
        assertEquals(Instant.parse("2026-01-02T00:00:00Z"), loaded.get("b.com").getExpiresAt());
        assertEquals(blocks, loaded);
    }

    @Test
    void corruptedJsonThrowsWithoutDeletingFile() throws Exception {
        Path stateFile = tmp.resolve("blocks.json");
        Files.writeString(stateFile, "{ isto não é json !!!", StandardCharsets.UTF_8);
        BlockStore store = new BlockStore(stateFile);

        assertThrows(CorruptedStateException.class, store::load);
        assertTrue(Files.exists(stateFile));
    }

    @Test
    void emptyFileIsCorrupted() throws Exception {
        Path stateFile = tmp.resolve("blocks.json");
        Files.writeString(stateFile, "   \n", StandardCharsets.UTF_8);
        assertThrows(CorruptedStateException.class, () -> new BlockStore(stateFile).load());
    }

    @Test
    void entryWithoutDomainIsCorrupted() throws Exception {
        Path stateFile = tmp.resolve("blocks.json");
        Files.writeString(stateFile, "{\"a.com\": {\"createdAt\": \"2026-01-01T00:00:00Z\"}}",
                StandardCharsets.UTF_8);
        assertThrows(CorruptedStateException.class, () -> new BlockStore(stateFile).load());
    }

    @Test
    void saveIsAtomicAndCreatesParentDirs() throws Exception {
        Path stateFile = tmp.resolve("nested/dir/blocks.json");
        BlockStore store = new BlockStore(stateFile);
        Map<String, BlockEntry> blocks = new TreeMap<>();
        blocks.put("x.com", new BlockEntry("x.com", Instant.now(), null));
        store.save(blocks);

        assertTrue(Files.exists(stateFile));
        assertFalse(Files.exists(stateFile.resolveSibling("blocks.json.tmp")));
        assertEquals(1, store.load().size());
    }

    @Test
    void loadDoesNotRequireWriteAccessToLockFile() throws Exception {
        Path stateFile = tmp.resolve("blocks.json");
        BlockStore store = new BlockStore(stateFile);
        Map<String, BlockEntry> blocks = new TreeMap<>();
        blocks.put("x.com", new BlockEntry("x.com", Instant.now(), null));
        store.save(blocks);

        Path lock = stateFile.resolveSibling("blocks.json.lock");
        assertTrue(Files.exists(lock));
        try {
            Set<PosixFilePermission> readOnly = Set.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.GROUP_READ,
                    PosixFilePermission.OTHERS_READ);
            Files.setPosixFilePermissions(lock, readOnly);
        } catch (UnsupportedOperationException e) {
        }
        assertEquals(1, new BlockStore(stateFile).load().size());
    }
}
