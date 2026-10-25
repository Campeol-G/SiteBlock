package com.sitelock.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** CLI integration with temporary files. */
class CliCommandsTest {

    @TempDir
    Path tmp;

    private Path hosts() throws Exception {
        Path h = tmp.resolve("hosts");
        Files.writeString(h, "127.0.0.1 localhost\n", StandardCharsets.UTF_8);
        return h;
    }

    private String state() {
        return tmp.resolve("state/blocks.json").toString();
    }

    @Test
    void blockMultipleDomainsWritesEightLines() throws Exception {
        Path h = hosts();
        int exit = new BlockCommand(List.of("tiktok.com", "vm.tiktok.com"), "30m",
                h.toString(), state()).call();
        assertEquals(0, exit);

        String content = Files.readString(h, StandardCharsets.UTF_8);
        for (String d : List.of("tiktok.com", "www.tiktok.com",
                "vm.tiktok.com", "www.vm.tiktok.com")) {
            assertTrue(content.contains("127.0.0.1 " + d), "missing 127.0.0.1 " + d);
            assertTrue(content.contains("::1 " + d), "missing ::1 " + d);
        }
        assertEquals(0, new BlockCommand(List.of("tiktok.com"), null, h.toString(), state()).call());
        String again = Files.readString(h, StandardCharsets.UTF_8);
        assertEquals(1, countOccurrences(again, "127.0.0.1 tiktok.com"));
    }

    @Test
    void blockRejectsWholeBatchOnInvalidDomain() throws Exception {
        Path h = hosts();
        int exit = new BlockCommand(List.of("tiktok.com", "bleh"), null, h.toString(), state()).call();
        assertEquals(2, exit);
        assertFalse(Files.readString(h, StandardCharsets.UTF_8).contains("tiktok.com"));
    }

    @Test
    void blockRejectsDurationThatWouldOverflowInstant() throws Exception {
        Path h = hosts();
        int exit = new BlockCommand(List.of("tiktok.com"), "999999999999d",
                h.toString(), state()).call();
        assertEquals(2, exit);
        assertFalse(Files.readString(h, StandardCharsets.UTF_8).contains("tiktok.com"));
        assertFalse(Files.exists(Path.of(state())), "nothing may be stored with an invalid duration");
    }

    @Test
    void unblockPermanentNeedsNoForce() throws Exception {
        Path h = hosts();
        assertEquals(0, new BlockCommand(List.of("a.com", "b.com"), null, h.toString(), state()).call());
        assertEquals(0, new UnblockCommand(List.of("a.com", "b.com"), false, h.toString(), state()).call());
        String content = Files.readString(h, StandardCharsets.UTF_8);
        assertFalse(content.contains("a.com"));
        assertFalse(content.contains("b.com"));
    }

    @Test
    void unblockTemporaryWithoutForceRefusesEverything() throws Exception {
        Path h = hosts();
        assertEquals(0, new BlockCommand(List.of("a.com", "b.com"), "1h", h.toString(), state()).call());
        int exit = new UnblockCommand(List.of("a.com", "b.com"), false, h.toString(), state()).call();
        assertEquals(3, exit);
        String content = Files.readString(h, StandardCharsets.UTF_8);
        assertTrue(content.contains("127.0.0.1 a.com"));
        assertTrue(content.contains("127.0.0.1 b.com"));
    }

    // WARNING: a new Scanner per question read stdin into a 1 KB buffer and
    // consumed both typed lines at once — the person confirmed twice and the
    // command reported that it did not unblock.
    /**
     * Typed confirmations must not be lost between each other.
     */
    @Test
    void unblockForceAcceptsBothConfirmations() throws Exception {
        Path h = hosts();
        assertEquals(0, new BlockCommand(List.of("a.com", "b.com"), "1h",
                h.toString(), state()).call());

        java.io.InputStream originalIn = System.in;
        System.setIn(new java.io.ByteArrayInputStream("YES\nYES\n".getBytes(StandardCharsets.UTF_8)));
        int exit;
        try {
            exit = new UnblockCommand(List.of("a.com", "b.com"), true,
                    h.toString(), state()).call();
        } finally {
            System.setIn(originalIn);
        }

        assertEquals(0, exit, "both typed confirmations must count");
        String content = Files.readString(h, StandardCharsets.UTF_8);
        assertFalse(content.contains("127.0.0.1 a.com"), content);
        assertFalse(content.contains("127.0.0.1 b.com"), content);
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
