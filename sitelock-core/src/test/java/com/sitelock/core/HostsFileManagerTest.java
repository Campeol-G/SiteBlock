package com.sitelock.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Tests add/remove of the block in memory. */
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

    @Test
    void removeKeepsUserLinesOutsideTheBlock() {
        String content = "127.0.0.1 tiktok.com\n"
                + "10.0.0.5 intranet.local\n"
                + "# SITELOCK-START\n"
                + "127.0.0.1 youtube.com\n"
                + "::1 youtube.com\n"
                + "# SITELOCK-END\n";

        String out = HostsFileManager.removeDomain(content, "tiktok.com");

        assertTrue(out.contains("127.0.0.1 tiktok.com"),
                "a user line outside the block must not be deleted: " + out);
        assertTrue(out.contains("10.0.0.5 intranet.local"), out);
        assertTrue(out.contains("127.0.0.1 youtube.com"), out);
    }

    @Test
    void removeDoesNotRewriteUserLinesOutsideTheBlock() {
        String content = "127.0.0.1 tiktok.com intranet.local\n"
                + "# SITELOCK-START\n"
                + "127.0.0.1 youtube.com\n"
                + "# SITELOCK-END\n";

        String out = HostsFileManager.removeDomain(content, "tiktok.com");

        assertTrue(out.contains("127.0.0.1 tiktok.com intranet.local"),
                "a user line outside the block must not be rewritten: " + out);
    }

    @Test
    void removeDeletesDomainFromSharedLineInsideBlock() {
        String content = "# SITELOCK-START\n"
                + "127.0.0.1 tiktok.com www.tiktok.com\n"
                + "# SITELOCK-END\n";

        String out = HostsFileManager.removeDomain(content, "tiktok.com");

        assertFalse(out.contains("tiktok.com"), out);
    }

    @Test
    void removeKeepsCommentsInsideBlock() {
        String content = "# SITELOCK-START\n"
                + "# manual block\n"
                + "127.0.0.1 youtube.com\n"
                + "127.0.0.1 tiktok.com\n"
                + "# SITELOCK-END\n";

        String out = HostsFileManager.removeDomain(content, "tiktok.com");

        assertTrue(out.contains("# manual block"), out);
        assertTrue(out.contains("127.0.0.1 youtube.com"), out);
        assertFalse(out.contains("127.0.0.1 tiktok.com"), out);
    }

    @Test
    void removeDropsCommentThatBelongedToTheRemovedEntry() {
        String content = "# SITELOCK-START\n"
                + "127.0.0.1 tiktok.com # note\n"
                + "# SITELOCK-END\n";

        String out = HostsFileManager.removeDomain(content, "tiktok.com");

        assertFalse(out.contains("127.0.0.1 # note"), out);
        assertFalse(out.contains("tiktok.com"), out);
        assertFalse(out.contains(HostsFileManager.START_MARKER),
                "a block with no entries has to disappear entirely: " + out);
        assertFalse(out.contains(HostsFileManager.END_MARKER), out);
    }

    @Test
    void removeKeepsCommentWhenAnotherHostRemains() {
        String content = "# SITELOCK-START\n"
                + "127.0.0.1 tiktok.com youtube.com # note\n"
                + "# SITELOCK-END\n";

        String out = HostsFileManager.removeDomain(content, "tiktok.com");

        assertTrue(out.contains("127.0.0.1 youtube.com # note"), out);
        assertFalse(out.contains("tiktok.com"), out);
    }

    @Test
    void removeTreatsIpPlusCommentAsAnEmptyBlock() {
        String content = "# SITELOCK-START\n"
                + "127.0.0.1 # stray comment\n"
                + "# SITELOCK-END\n";

        String out = HostsFileManager.removeDomain(content, "other.com");

        assertFalse(out.contains(HostsFileManager.START_MARKER),
                "IP + comment is not an entry: " + out);
        assertFalse(out.contains(HostsFileManager.END_MARKER), out);
    }

    @Test
    void addDoesNotDuplicateBlockWhenEndMarkerIsMissing() {
        String content = "# SITELOCK-START\n127.0.0.1 x.com\n";

        String out = HostsFileManager.addDomain(content, "y.com");

        assertEquals(1, countOccurrences(out, HostsFileManager.START_MARKER), out);
        assertEquals(1, countOccurrences(out, HostsFileManager.END_MARKER), out);
        assertTrue(out.contains("127.0.0.1 x.com"), out);
        assertTrue(out.contains("127.0.0.1 y.com"), out);
    }

    @Test
    void removeWorksWhenEndMarkerIsMissing() {
        String content = "# SITELOCK-START\n"
                + "127.0.0.1 x.com\n"
                + "127.0.0.1 y.com\n";

        String out = HostsFileManager.removeDomain(content, "y.com");

        assertFalse(out.contains("y.com"), out);
        assertTrue(out.contains("127.0.0.1 x.com"), out);
    }

    @Test
    void removeWorksWhenBlockWasDuplicated() {
        String content = "# SITELOCK-START\n"
                + "127.0.0.1 x.com\n"
                + "# SITELOCK-START\n"
                + "127.0.0.1 y.com\n"
                + "127.0.0.1 www.y.com\n"
                + "::1 y.com\n"
                + "::1 www.y.com\n"
                + "# SITELOCK-END\n";

        String out = HostsFileManager.removeDomain(content, "y.com");

        assertFalse(out.contains("y.com"), out);
        assertTrue(out.contains("127.0.0.1 x.com"), out);
        assertEquals(1, countOccurrences(out, HostsFileManager.START_MARKER), out);
        assertEquals(1, countOccurrences(out, HostsFileManager.END_MARKER), out);
    }

    @Test
    void addNeverGeneratesWwwWww() {
        String out = HostsFileManager.addDomain("", "www.tiktok.com");

        assertFalse(out.contains("www.www."), out);
        assertTrue(out.contains("127.0.0.1 www.tiktok.com"), out);
        assertTrue(out.contains("::1 www.tiktok.com"), out);
    }

    @Test
    void managedHostsNeverRepeatWwwPrefix() {
        assertEquals(List.of("tiktok.com", "www.tiktok.com"),
                HostsFileManager.managedHosts("www.tiktok.com"));
        assertEquals(List.of("tiktok.com", "www.tiktok.com"),
                HostsFileManager.managedHosts("tiktok.com"));
    }

    @Test
    void addWwwPrefixedTldCoversDomainAndWwwVariant() {
        String out = HostsFileManager.addDomain(BASE_HOSTS, "www.com");

        assertTrue(out.contains("127.0.0.1 com"), out);
        assertTrue(out.contains("127.0.0.1 www.com"), out);
        assertTrue(out.contains("::1 com"), out);
        assertTrue(out.contains("::1 www.com"), out);
        assertFalse(out.contains("www.www."), out);
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
