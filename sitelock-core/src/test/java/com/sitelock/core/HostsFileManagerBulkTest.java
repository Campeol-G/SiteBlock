package com.sitelock.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Batch operation on the hosts file: one lock, one read, one write.
 *
 * <p>The comparison against N individual calls is the property that matters — if
 * the batch diverged from the per-domain path, the rest of the feature (profile
 * apply of 40 domains, daemon sweep) would start producing a file that
 * nobody expected.
 */
class HostsFileManagerBulkTest {

    private static final String BASE_HOSTS = "127.0.0.1 localhost\n::1 localhost\n";

    @TempDir
    Path tmp;

    private Path hosts(String name) throws IOException {
        Path h = tmp.resolve(name);
        Files.writeString(h, BASE_HOSTS, StandardCharsets.UTF_8);
        return h;
    }

    private Path backup(String name) {
        return tmp.resolve(name + ".backup");
    }

    private Path lock(String name) {
        return tmp.resolve(name + ".lock");
    }

    private String read(Path h) throws IOException {
        return Files.readString(h, StandardCharsets.UTF_8);
    }

    @Test
    void bulkBlockMatchesIndividualCalls() throws Exception {
        Path batch = hosts("batch");
        HostsFileManager.blockInFile(batch, backup("batch"), lock("batch"),
                List.of("tiktok.com", "youtube.com"));

        Path individual = hosts("individual");
        HostsFileManager.blockInFile(individual, backup("individual"), lock("individual"), "tiktok.com");
        HostsFileManager.blockInFile(individual, backup("individual"), lock("individual"), "youtube.com");

        assertEquals(read(individual), read(batch));
        assertTrue(read(batch).contains("::1 localhost"), "the original content above the block must not be touched");
    }

    @Test
    void bulkUnblockRemovesEveryDomain() throws Exception {
        Path h = hosts("h");
        HostsFileManager.blockInFile(h, backup("h"), lock("h"), List.of("tiktok.com", "youtube.com"));
        HostsFileManager.unblockInFile(h, backup("h"), lock("h"), List.of("tiktok.com", "youtube.com"));

        String out = read(h);
        assertFalse(out.contains(HostsFileManager.START_MARKER), "an empty block must remove the markers");
        assertFalse(out.contains(HostsFileManager.END_MARKER));
        assertFalse(out.contains("tiktok.com"));
        assertFalse(out.contains("youtube.com"));
        assertTrue(out.contains("127.0.0.1 localhost"), "the original content must not be touched");
        assertTrue(out.contains("::1 localhost"));
    }

    @Test
    void bulkUnblockMatchesIndividualCalls() throws Exception {
        Path batch = hosts("batch");
        HostsFileManager.blockInFile(batch, backup("batch"), lock("batch"), List.of("tiktok.com", "youtube.com"));
        HostsFileManager.unblockInFile(batch, backup("batch"), lock("batch"), List.of("tiktok.com", "youtube.com"));

        Path individual = hosts("individual");
        HostsFileManager.blockInFile(individual, backup("individual"), lock("individual"), "tiktok.com");
        HostsFileManager.blockInFile(individual, backup("individual"), lock("individual"), "youtube.com");
        HostsFileManager.unblockInFile(individual, backup("individual"), lock("individual"), "tiktok.com");
        HostsFileManager.unblockInFile(individual, backup("individual"), lock("individual"), "youtube.com");

        assertEquals(read(individual), read(batch));
    }

    @Test
    void bulkIsIdempotent() throws Exception {
        Path h = hosts("h");
        HostsFileManager.blockInFile(h, backup("h"), lock("h"), List.of("tiktok.com", "youtube.com"));
        HostsFileManager.blockInFile(h, backup("h"), lock("h"), List.of("tiktok.com", "youtube.com"));

        assertEquals(1, countOccurrences(read(h), "127.0.0.1 tiktok.com"));
        assertEquals(1, countOccurrences(read(h), "::1 www.youtube.com"));
    }

    @Test
    void emptyListLeavesFileUntouched() throws Exception {
        Path h = hosts("h");
        String before = read(h);

        HostsFileManager.blockInFile(h, backup("h"), lock("h"), List.of());
        assertEquals(before, read(h), "an empty list must not create a block");

        HostsFileManager.blockInFile(h, backup("h"), lock("h"), List.of("tiktok.com"));
        String blocked = read(h);
        HostsFileManager.unblockInFile(h, backup("h"), lock("h"), List.of());

        assertEquals(blocked, read(h), "an empty list must not remove the block");
    }

    @Test
    void singleArgumentOverloadMatchesBatch() throws Exception {
        Path single = hosts("single");
        HostsFileManager.blockInFile(single, backup("single"), lock("single"), "tiktok.com");

        Path batch = hosts("batch");
        HostsFileManager.blockInFile(batch, backup("batch"), lock("batch"), List.of("tiktok.com"));

        assertEquals(read(batch), read(single));
    }

    private static int countOccurrences(String s, String needle) {
        int n = 0;
        int i = 0;
        while ((i = s.indexOf(needle, i)) >= 0) {
            n++;
            i += needle.length();
        }
        return n;
    }
}