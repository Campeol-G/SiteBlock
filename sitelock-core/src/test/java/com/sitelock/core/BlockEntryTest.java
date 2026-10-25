package com.sitelock.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The {@code createdAt} of a block.
 *
 * <p>It answers "when was this block created", and it is what
 * {@code ProfileMerge} preserves when a deadline is raised. A file written
 * by hand — or by an old version — may not have the field; in that case the
 * instant is <b>unknown</b>, and inventing "now" changes the semantics with no
 * visible error: the displayed date lies.
 */
class BlockEntryTest {

    private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");

    @Test
    void entryWithoutCreatedAtKeepsCreatedAtNull() {
        BlockEntry entry = new BlockEntry("tiktok.com", null, null);

        assertNull(entry.getCreatedAt(),
                "with no field in the file there is no instant to invent");
    }

    @Test
    void jsonWithoutCreatedAtDoesNotGenerateCreatedAt() throws Exception {
        BlockEntry entry = BlockStore.defaultMapper()
                .readValue("{\"domain\": \"tiktok.com\", \"expiresAt\": null}", BlockEntry.class);

        assertNull(entry.getCreatedAt(),
                "a blocks.json without the field cannot gain the read date");
    }

    @Test
    void roundTripPreservesNullCreatedAt(@TempDir Path tmp) throws Exception {
        Path stateFile = tmp.resolve("blocks.json");
        BlockStore store = new BlockStore(stateFile);
        Map<String, BlockEntry> blocks = new TreeMap<>();
        blocks.put("tiktok.com", new BlockEntry("tiktok.com", null, null));
        store.save(blocks);

        Map<String, BlockEntry> loaded = store.load();

        assertNull(loaded.get("tiktok.com").getCreatedAt(),
                "the state cannot invent the instant the file does not have");
    }

    @Test
    void raisedDeadlineDoesNotFabricateCreatedAt() {
        Map<String, BlockEntry> state = new TreeMap<>();
        state.put("tiktok.com", new BlockEntry("tiktok.com", null, NOW.plusSeconds(1800)));
        Map<String, ProfileEntry> entries = new TreeMap<>();
        entries.put("tiktok.com", new ProfileEntry(8 * 3600_000L));
        Profile profile = new Profile(NOW, NOW, entries);

        Map<String, ProfileMerge.Decision> out = ProfileMerge.merge(state, profile, NOW);

        assertEquals(ProfileMerge.Action.RAISED, out.get("tiktok.com").action());
        assertNull(out.get("tiktok.com").entry().getCreatedAt(),
                "raising the deadline must not invent the block's origin");
    }

    @Test
    void keptEntryRemainsTheSameInstance() {
        Map<String, BlockEntry> state = new TreeMap<>();
        state.put("tiktok.com", new BlockEntry("tiktok.com", null, NOW.plusSeconds(3600)));
        BlockEntry original = state.get("tiktok.com");
        Map<String, ProfileEntry> entries = new TreeMap<>();
        entries.put("tiktok.com", new ProfileEntry(30 * 60_000L));
        Profile profile = new Profile(NOW, NOW, entries);

        Map<String, ProfileMerge.Decision> out = ProfileMerge.merge(state, profile, NOW);

        assertEquals(ProfileMerge.Action.KEPT, out.get("tiktok.com").action());
        assertSame(original, out.get("tiktok.com").entry(),
                "a KEPT decision returns the same instance: the deadline cannot be rewritten");
    }
}
