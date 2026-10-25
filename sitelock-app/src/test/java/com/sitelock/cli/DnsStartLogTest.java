package com.sitelock.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The log is appended between startups. The old listening mark must not make the
 * new {@code dns start} report success before the current child opens the port.
 */
class DnsStartLogTest {

    @TempDir
    Path tmp;

    @Test
    void markBeforeTheOffsetDoesNotCountAsCurrentListening() throws Exception {
        Path log = tmp.resolve("dns-server.log");
        Files.writeString(log, "[old] " + DnsStartCommand.LISTENING_MARKER + "\n",
                StandardCharsets.UTF_8);
        long offset = Files.size(log);

        Files.writeString(log, "[new] still binding\n", StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);
        assertFalse(DnsStartCommand.logShowsListening(log, offset),
                "the mark from the previous run must not count for this one");

        Files.writeString(log, "[new] " + DnsStartCommand.LISTENING_MARKER + "\n",
                StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        assertTrue(DnsStartCommand.logShowsListening(log, offset),
                "the mark written after the offset belongs to the current run");
    }

    @Test
    void largeLogIsArchivedBeforeTheNewStart() throws Exception {
        Path log = tmp.resolve("dns-server.log");
        Files.write(log, new byte[(int) DnsStartCommand.LOG_ROTATE_BYTES + 1]);

        assertEquals(0, DnsStartCommand.prepareLog(log));
        assertFalse(Files.exists(log), "the current log must get out of the way");
        assertTrue(Files.exists(tmp.resolve("dns-server.log.1")),
                "the old content must be preserved in the .1 file");
    }
}
