package com.sitelock.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Testa add/remove do bloco em memória. */
class HostsFileManagerTest {

    private static final String BASE_HOSTS =
            "127.0.0.1 localhost\n::1 localhost\n";

    @Test
    void addCreatesBlockWhenMissing() {
        String out = HostsFileManager.addDomain(BASE_HOSTS, "tiktok.com");
        assertTrue(out.contains(HostsFileManager.START_MARKER));
        assertTrue(out.contains(HostsFileManager.END_MARKER));
        assertTrue(out.contains("127.0.0.1 tiktok.com"));
        assertTrue(out.contains("127.0.0.1 www.tiktok.com"));
        assertTrue(out.contains("::1 tiktok.com"));
        assertTrue(out.contains("::1 www.tiktok.com"));
        assertTrue(out.contains("127.0.0.1 localhost"));
    }

    @Test
    void addInsertsInsideExistingBlockWithoutDuplicatingMarkers() {
        String withOne = HostsFileManager.addDomain(BASE_HOSTS, "a.com");
        String withTwo = HostsFileManager.addDomain(withOne, "b.com");
        assertEquals(1, countOccurrences(withTwo, HostsFileManager.START_MARKER));
        assertEquals(1, countOccurrences(withTwo, HostsFileManager.END_MARKER));
        assertTrue(withTwo.contains("127.0.0.1 a.com"));
        assertTrue(withTwo.contains("127.0.0.1 b.com"));
        assertTrue(withTwo.contains("::1 a.com"));
        assertTrue(withTwo.contains("::1 b.com"));
    }

    @Test
    void addIsIdempotent() {
        String once = HostsFileManager.addDomain(BASE_HOSTS, "tiktok.com");
        String twice = HostsFileManager.addDomain(once, "tiktok.com");
        assertEquals(once, twice);
        assertEquals(1, countOccurrences(twice, "127.0.0.1 tiktok.com"));
        assertEquals(1, countOccurrences(twice, "::1 tiktok.com"));
    }

    @Test
    void addComplementsLegacyIpv4OnlyEntries() {
        String legacy = BASE_HOSTS
                + "# SITELOCK-START\n"
                + "127.0.0.1 old.com\n"
                + "127.0.0.1 www.old.com\n"
                + "# SITELOCK-END\n";
        String out = HostsFileManager.addDomain(legacy, "old.com");
        assertTrue(out.contains("::1 old.com"));
        assertTrue(out.contains("::1 www.old.com"));
        assertEquals(1, countOccurrences(out, "127.0.0.1 old.com"));
    }

    @Test
    void removeDeletesOnlyRequestedDomain() {
        String blocked = HostsFileManager.addDomain(BASE_HOSTS, "a.com");
        blocked = HostsFileManager.addDomain(blocked, "b.com");
        String removed = HostsFileManager.removeDomain(blocked, "a.com");
        assertFalse(removed.contains("127.0.0.1 a.com"));
        assertFalse(removed.contains("127.0.0.1 www.a.com"));
        assertFalse(removed.contains("::1 a.com"));
        assertFalse(removed.contains("::1 www.a.com"));
        assertTrue(removed.contains("127.0.0.1 b.com"));
        assertTrue(removed.contains(HostsFileManager.START_MARKER));
    }

    @Test
    void removeLastDomainAlsoRemovesMarkers() {
        String blocked = HostsFileManager.addDomain(BASE_HOSTS, "tiktok.com");
        String removed = HostsFileManager.removeDomain(blocked, "tiktok.com");
        assertFalse(removed.contains(HostsFileManager.START_MARKER));
        assertFalse(removed.contains(HostsFileManager.END_MARKER));
        assertFalse(removed.contains("tiktok.com"));
        assertTrue(removed.contains("127.0.0.1 localhost"));
    }

    @Test
    void removeUnknownDomainLeavesContentUntouched() {
        String blocked = HostsFileManager.addDomain(BASE_HOSTS, "a.com");
        String out = HostsFileManager.removeDomain(blocked, "other.com");
        assertEquals(blocked, out);
    }

    @Test
    void removeHandlesNullGracefully() {
        assertDoesNotThrow(() -> HostsFileManager.removeDomain(null, "a.com"));
        assertDoesNotThrow(() -> HostsFileManager.addDomain(null, "a.com"));
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
