package com.sitelock.dns;

import com.sitelock.core.BlockEntry;
import com.sitelock.core.BlockStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;

import static com.sitelock.dns.DnsResolver.Decision.BLOCK;
import static com.sitelock.dns.DnsResolver.Decision.BLOCK_NODATA;
import static com.sitelock.dns.DnsResolver.Decision.FORWARD;
import static org.junit.jupiter.api.Assertions.*;

// "Block vs forward" logic isolated from the network: only in-memory maps, no sockets.
class DnsResolverTest {

    private static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");

    private static Map<String, BlockEntry> state(BlockEntry... entries) {
        Map<String, BlockEntry> m = new TreeMap<>();
        for (BlockEntry e : entries) {
            m.put(e.getDomain(), e);
        }
        return m;
    }

    private static BlockEntry permanent(String domain) {
        return new BlockEntry(domain, NOW.minusSeconds(60), null);
    }

    private static BlockEntry temporary(String domain, Instant expiresAt) {
        return new BlockEntry(domain, NOW.minusSeconds(60), expiresAt);
    }

    @Test
    void exactDomainBlocksAAndAaaa() {
        Map<String, BlockEntry> blocks = state(permanent("tiktok.com"));
        assertEquals(BLOCK, DnsResolver.decide("tiktok.com", 1, blocks, NOW));
        assertEquals(BLOCK, DnsResolver.decide("tiktok.com", 28, blocks, NOW));
    }

    @Test
    void subdomainsAreBlocked() {
        Map<String, BlockEntry> blocks = state(permanent("tiktok.com"));
        assertEquals(BLOCK, DnsResolver.decide("www.tiktok.com", 1, blocks, NOW));
        assertEquals(BLOCK, DnsResolver.decide("m.tiktok.com", 28, blocks, NOW));
        assertEquals(BLOCK, DnsResolver.decide("a.b.tiktok.com", 1, blocks, NOW));
    }

    @Test
    void partialSuffixIsNotBlocked() {
        Map<String, BlockEntry> blocks = state(permanent("tiktok.com"));
        // "eviltiktok.com" ends with "tiktok.com" but NOT with ".tiktok.com".
        assertEquals(FORWARD, DnsResolver.decide("eviltiktok.com", 1, blocks, NOW));
        assertEquals(FORWARD, DnsResolver.decide("tiktok.com.evil.com", 1, blocks, NOW));
        assertEquals(FORWARD, DnsResolver.decide("google.com", 1, blocks, NOW));
    }

    @Test
    void blockedNameIsNeverForwardedForAnyType() {
        Map<String, BlockEntry> blocks = state(permanent("youtube.com"));
        int[] types = {1, 2, 5, 6, 12, 15, 16, 28, 33, 35, 41, 43, 44, 52, 64, 65, 255, 257};
        for (int type : types) {
            assertNotEquals(FORWARD, DnsResolver.decide("youtube.com", type, blocks, NOW),
                    "type " + type + " must not escape the block");
        }
    }

    @Test
    void otherTypesForBlockedNameAnswerNodata() {
        Map<String, BlockEntry> blocks = state(permanent("tiktok.com"));
        assertEquals(BLOCK_NODATA, DnsResolver.decide("tiktok.com", 15, blocks, NOW)); // MX
        assertEquals(BLOCK_NODATA, DnsResolver.decide("www.tiktok.com", 16, blocks, NOW)); // TXT
        assertEquals(BLOCK_NODATA, DnsResolver.decide("tiktok.com", 2, blocks, NOW)); // NS
        assertEquals(BLOCK_NODATA, DnsResolver.decide("tiktok.com", 5, blocks, NOW)); // CNAME
        assertEquals(BLOCK_NODATA, DnsResolver.decide("tiktok.com", 255, blocks, NOW)); // ANY
    }

    @Test
    void unblockedDomainIsStillForwardedForAnyType() {
        Map<String, BlockEntry> blocks = state(permanent("youtube.com"));
        assertEquals(FORWARD, DnsResolver.decide("google.com", 15, blocks, NOW));
        assertEquals(FORWARD, DnsResolver.decide("google.com", 16, blocks, NOW));
        assertEquals(FORWARD, DnsResolver.decide("notyoutube.com", 15, blocks, NOW));
    }

    @Test
    void isBlockedCoversBlockAndBlockNodata() {
        assertTrue(BLOCK.isBlocked());
        assertTrue(BLOCK_NODATA.isBlocked());
        assertFalse(FORWARD.isBlocked());
    }

    @Test
    void expiredDoesNotBlockActiveTemporaryDoes() {
        Map<String, BlockEntry> blocks = state(
                temporary("old.com", NOW.minusSeconds(1)),
                temporary("new.com", NOW.plusSeconds(3600)));
        assertEquals(FORWARD, DnsResolver.decide("old.com", 1, blocks, NOW));
        assertEquals(BLOCK, DnsResolver.decide("new.com", 1, blocks, NOW));
        assertEquals(BLOCK, DnsResolver.decide("www.new.com", 28, blocks, NOW));
    }

    @Test
    void uppercaseAndTrailingDotAreNormalized() {
        Map<String, BlockEntry> blocks = state(permanent("tiktok.com"));
        assertEquals(BLOCK, DnsResolver.decide("WWW.TikTok.COM.", 1, blocks, NOW));
        assertEquals(BLOCK, DnsResolver.decide("TIKTOK.COM", 28, blocks, NOW));
    }

    @Test
    void emptyStateOrRootIsForwarded() {
        assertEquals(FORWARD, DnsResolver.decide("tiktok.com", 1, Map.of(), NOW));
        assertEquals(FORWARD, DnsResolver.decide("", 1, state(permanent("tiktok.com")), NOW));
    }

    @Test
    void cnameQueryForBlockedNameIsNotForwarded() {
        Map<String, BlockEntry> blocks = state(permanent("www.microsoft.com-c-3.edgekey.net"));
        assertEquals(BLOCK_NODATA,
                DnsResolver.decide("www.microsoft.com-c-3.edgekey.net", 5, blocks, NOW));
        assertEquals(BLOCK,
                DnsResolver.decide("www.microsoft.com-c-3.edgekey.net", 1, blocks, NOW));
    }

    @Test
    void freeNameWithBlockedAliasIsQueriedUpstream() {
        // WHY: the alias only appears in the upstream RESPONSE — the decision cannot
        // know that "www.microsoft.com" points at a blocked name. That is why the
        // query goes out; the server swaps in the sinkhole (see DnsAliasCnameTest).
        Map<String, BlockEntry> blocks = state(permanent("www.microsoft.com-c-3.edgekey.net"));
        assertEquals(FORWARD, DnsResolver.decide("www.microsoft.com", 1, blocks, NOW));
    }

    @Test
    void instanceDecideLogsNonAaaaTypeForBlockedName(@TempDir Path dir) throws Exception {
        Path stateFile = dir.resolve("blocks.json");
        Files.writeString(stateFile, "{\"youtube.com\":{\"domain\":\"youtube.com\","
                + "\"createdAt\":\"2026-01-01T11:00:00Z\",\"expiresAt\":null}}");
        DnsResolver resolver = new DnsResolver(new BlockStore(stateFile), Clock.systemUTC());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream original = System.out;
        DnsResolver.Decision decision;
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            decision = resolver.decide("youtube.com", 15);
        } finally {
            System.setOut(original);
        }

        assertEquals(BLOCK_NODATA, decision);
        String log = out.toString(StandardCharsets.UTF_8);
        assertTrue(log.contains("youtube.com"), "the log should mention the name: " + log);
        assertTrue(log.contains("15"), "the log should mention the type: " + log);
    }

    @Test
    void unicodeListMatchesPunycodeQuery() {
        Map<String, BlockEntry> blocks = state(permanent("пример.рф"));
        assertEquals(BLOCK, DnsResolver.decide("xn--e1afmkfd.xn--p1ai", 1, blocks, NOW));
        assertEquals(BLOCK, DnsResolver.decide("пример.рф", 1, blocks, NOW));
        assertEquals(BLOCK, DnsResolver.decide("www.пример.рф", 1, blocks, NOW));
    }

    @Test
    void punycodeListMatchesUnicodeQuery() {
        Map<String, BlockEntry> blocks = state(permanent("xn--e1afmkfd.xn--p1ai"));
        assertEquals(BLOCK, DnsResolver.decide("пример.рф", 1, blocks, NOW));
        assertEquals(BLOCK, DnsResolver.decide("xn--e1afmkfd.xn--p1ai", 28, blocks, NOW));
    }

    @Test
    void unblockedIdnNameStaysForwarded() {
        Map<String, BlockEntry> blocks = state(permanent("youtube.com"));
        assertEquals(FORWARD, DnsResolver.decide("пример.рф", 1, blocks, NOW));
    }

    @Test
    void instanceDecideDoesNotLogTypeAQuery(@TempDir Path dir) throws Exception {
        Path stateFile = dir.resolve("blocks.json");
        Files.writeString(stateFile, "{\"youtube.com\":{\"domain\":\"youtube.com\","
                + "\"createdAt\":\"2026-01-01T11:00:00Z\",\"expiresAt\":null}}");
        DnsResolver resolver = new DnsResolver(new BlockStore(stateFile), Clock.systemUTC());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream original = System.out;
        DnsResolver.Decision decision;
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            decision = resolver.decide("youtube.com", 1);
        } finally {
            System.setOut(original);
        }

        assertEquals(BLOCK, decision);
        assertFalse(out.toString(StandardCharsets.UTF_8).contains("youtube.com"),
                "an A/AAAA query should not produce the non-A/AAAA type log");
    }
}
