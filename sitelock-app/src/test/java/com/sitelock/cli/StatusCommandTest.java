package com.sitelock.cli;

import com.sitelock.core.BlockEntry;
import com.sitelock.core.BlockStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * status checking the state against the hosts file.
 *
 * <p>There are two opposite outcomes when the two disagree. State without a line
 * in the hosts file: the site is free and status claims it is blocked. A line in
 * the hosts file without a record in the state: the site is stuck forever,
 * because the sweep walks the JSON and never the hosts file — and recovery would
 * mean editing /etc/hosts by hand, which is what this program exists to avoid.
 */
class StatusCommandTest {

    @TempDir
    Path tmp;

    private Path hosts;

    // The test hosts file; created only once, so it does not erase the block written.
    private Path hosts() throws Exception {
        if (hosts == null) {
            hosts = tmp.resolve("hosts");
            Files.writeString(hosts, "127.0.0.1 localhost\n", StandardCharsets.UTF_8);
        }
        return hosts;
    }

    private String state() {
        return tmp.resolve("state/blocks.json").toString();
    }

    // Writes a state with a single domain, without going through block.
    private void writeState(String domain, Instant expiresAt) throws Exception {
        Map<String, BlockEntry> state = new TreeMap<>();
        state.put(domain, new BlockEntry(domain, Instant.now(), expiresAt));
        Files.createDirectories(tmp.resolve("state"));
        new BlockStore(Path.of(state())).save(state);
    }

    private Result run(Callable c) throws Exception {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream fake = new PrintStream(buffer, true, StandardCharsets.UTF_8);
        System.setOut(fake);
        System.setErr(fake);
        AtomicInteger code = new AtomicInteger(-1);
        try {
            code.set(c.call());
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
        return new Result(code.get(), buffer.toString(StandardCharsets.UTF_8));
    }

    private record Result(int exit, String text) {
    }

    @FunctionalInterface
    private interface Callable {
        int call() throws Exception;
    }

    @Test
    void stateMatchesHostsReportsNothing() throws Exception {
        assertEquals(0, new BlockCommand(List.of("tiktok.com"), null,
                hosts().toString(), state()).call());

        Result r = run(() -> new StatusCommand(hosts().toString(), state()).call());

        assertEquals(0, r.exit());
        assertFalse(r.text().contains("Divergence"),
                "state and hosts in sync have no divergence: " + r.text());
    }

    @Test
    void stateWithoutHostsLineReportsIneffective() throws Exception {
        writeState("tiktok.com", null);

        Result r = run(() -> new StatusCommand(hosts().toString(), state()).call());

        assertEquals(0, r.exit());
        assertTrue(r.text().contains("ineffective"), r.text());
        assertTrue(r.text().contains("tiktok.com"), r.text());
        assertTrue(r.text().contains("Blocked domains (1):"),
                "the blocked list does not change: " + r.text());
    }

    @Test
    void hostsLineWithoutRecordReportsStuckWithoutRecord() throws Exception {
        assertEquals(0, new BlockCommand(List.of("tiktok.com"), null,
                hosts().toString(), state()).call());
        // The record is lost (state deleted, overwritten, or corrupted): the hosts
        // file keeps the line and the site stays stuck forever.
        Files.delete(Path.of(state()));

        Result r = run(() -> new StatusCommand(hosts().toString(), state()).call());

        assertEquals(0, r.exit());
        assertTrue(r.text().contains("stuck without a record"), r.text());
        assertTrue(r.text().contains("tiktok.com"), r.text());
        assertTrue(r.text().contains("siteblock block tiktok.com"),
                "the output must say how to fix it: " + r.text());
        assertTrue(r.text().contains("siteblock unblock tiktok.com"), r.text());
        assertFalse(r.text().contains("Blocked domains"),
                "status must not invent a block that is not in the state: " + r.text());
    }

    @Test
    void wwwStateDoesNotBecomeStuckWithoutRecord() throws Exception {
        // Blocking www and the domain is the same thing: the lines are the same.
        writeState("www.tiktok.com", null);
        Files.writeString(hosts(), """
                127.0.0.1 localhost
                # SITELOCK-START
                127.0.0.1 tiktok.com
                127.0.0.1 www.tiktok.com
                ::1 tiktok.com
                ::1 www.tiktok.com
                # SITELOCK-END
                """, StandardCharsets.UTF_8);

        Result r = run(() -> new StatusCommand(hosts().toString(), state()).call());

        assertFalse(r.text().contains("stuck without a record"),
                "www and the domain are the same block: " + r.text());
    }

    // WARNING: the test depends on POSIX permissions — on Windows
    // setPosixFilePermissions does not exist, the file stays readable, and the
    // assertion breaks. Without @EnabledOnOs, the Windows leg of CI turns RED.
    /**
     * Without read permission on the hosts file, status warns and does not become an error.
     */
    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void unreadableHostsDoesNotBecomeAnError() throws Exception {
        writeState("tiktok.com", null);
        Path unreadableHosts = tmp.resolve("unreadable-hosts");
        Files.writeString(unreadableHosts, "127.0.0.1 localhost\n", StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(unreadableHosts, Set.of(PosixFilePermission.OWNER_WRITE));

        Result r = run(() -> new StatusCommand(unreadableHosts.toString(), state()).call());

        assertEquals(0, r.exit(), "reading the hosts file may fail, but status does not become an error: " + r.text());
        assertTrue(r.text().contains("could not read"), r.text());
        assertFalse(r.text().contains("Divergence"),
                "without being able to read the hosts file there is nothing to assert: " + r.text());
        assertTrue(r.text().contains("Blocked domains (1):"),
                "the blocked list remains: " + r.text());
    }
}
