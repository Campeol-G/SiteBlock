package com.sitelock.dns;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

// The DNS daemon record (dns-server.json). It is read by dns start, dns status,
// and dns stop: a broken record locks the machine out, and a record pointing at
// the wrong PID makes stop signal an unrelated process.
class DnsDaemonStateTest {

    @TempDir
    Path tmp;

    private Path stateFile() {
        return DnsDaemonState.statePath(tmp);
    }

    @Test
    void writeAndReadRoundTrip() throws Exception {
        Instant now = Instant.parse("2026-01-01T12:00:00Z");
        DnsDaemonState.write(tmp, new DnsDaemonState(4242, 5353, "1.1.1.1", now, null));

        DnsDaemonState loaded = DnsDaemonState.read(tmp).orElseThrow();
        assertEquals(4242, loaded.getPid());
        assertEquals(5353, loaded.getPort());
        assertEquals("1.1.1.1", loaded.getUpstream());
        assertEquals(now, loaded.getStartedAt());
    }

    // A half-written JSON must not blow up: the record would become orphaned
    // garbage and the user could never start the DNS server again without
    // deleting the file by hand.
    @Test
    void corruptedJsonReturnsEmptyRecord() throws Exception {
        Files.createDirectories(tmp);
        Files.writeString(stateFile(), "{ \"pid\": 12, \"port\":", StandardCharsets.UTF_8);

        assertTrue(DnsDaemonState.read(tmp).isEmpty(),
                "invalid JSON must become an empty record, not an exception");
    }

    // The write is atomic: the file is swapped by inode, never half-written.
    @Test
    void writeIsAtomic() throws Exception {
        Instant now = Instant.parse("2026-01-01T12:00:00Z");
        DnsDaemonState.write(tmp, new DnsDaemonState(1, 53, "1.1.1.1", now, null));
        Object keyBefore = Files.readAttributes(stateFile(), BasicFileAttributes.class).fileKey();
        assumeTrue(keyBefore != null, "the FS does not expose fileKey");

        DnsDaemonState.write(tmp, new DnsDaemonState(2, 53, "1.1.1.1", now, null));
        Object keyAfter = Files.readAttributes(stateFile(), BasicFileAttributes.class).fileKey();

        assertNotEquals(keyBefore, keyAfter,
                "the write must go through .tmp + rename (the inode changes)");
        assertFalse(Files.exists(tmp.resolve("dns-server.json.tmp")),
                "the temporary file must not be left behind");
    }

    // The OS recycles PIDs: the number existing does not prove the process is the
    // daemon. The start instant is the proof.
    @Test
    void recycledPidDoesNotCountAsLiveDaemon() throws Exception {
        Files.createDirectories(tmp);
        Files.writeString(stateFile(), "{\"pid\":" + ProcessHandle.current().pid()
                + ",\"port\":53,\"upstream\":\"1.1.1.1\","
                + "\"startedAt\":\"2026-01-01T12:00:00Z\",\"expiresAt\":null,"
                + "\"processStartedAt\":\"2020-01-01T00:00:00Z\"}", StandardCharsets.UTF_8);

        DnsDaemonState state = DnsDaemonState.read(tmp).orElseThrow();
        assertFalse(DnsDaemonState.isAlive(state),
                "another process's PID is not the daemon: stop would signal the wrong process");
    }

    // A record written by an older version (without the instant) is accepted by PID.
    @Test
    void oldRecordWithoutStartInstantStillCountsByPid() throws Exception {
        Files.createDirectories(tmp);
        Files.writeString(stateFile(), "{\"pid\":" + ProcessHandle.current().pid()
                + ",\"port\":53,\"upstream\":\"1.1.1.1\","
                + "\"startedAt\":\"2026-01-01T12:00:00Z\",\"expiresAt\":null}", StandardCharsets.UTF_8);

        DnsDaemonState state = DnsDaemonState.read(tmp).orElseThrow();
        assertTrue(DnsDaemonState.isAlive(state),
                "an old record (without processStartedAt) is still valid by PID");
    }

    // Whoever writes the record stores the process's own start instant.
    @Test
    void writeStoresTheProcessStartInstant() throws Exception {
        Instant now = Instant.parse("2026-01-01T12:00:00Z");
        DnsDaemonState.write(tmp, new DnsDaemonState(
                ProcessHandle.current().pid(), 53, "1.1.1.1", now, null));

        DnsDaemonState loaded = DnsDaemonState.read(tmp).orElseThrow();
        assertTrue(DnsDaemonState.isAlive(loaded),
                "the current process's record must be recognized as alive");
    }

    @Test
    void deleteIfOwnedDoesNotDeleteAnotherPidsRecord() throws Exception {
        Instant now = Instant.parse("2026-01-01T12:00:00Z");
        DnsDaemonState.write(tmp, new DnsDaemonState(111, 53, "1.1.1.1", now, null));

        DnsDaemonState.deleteIfOwned(tmp, 222);

        assertTrue(DnsDaemonState.read(tmp).isPresent(),
                "a failed dns start must not delete another daemon's record");
    }

    @Test
    void deleteIfOwnedDeletesItsOwnRecord() throws Exception {
        Instant now = Instant.parse("2026-01-01T12:00:00Z");
        DnsDaemonState.write(tmp, new DnsDaemonState(111, 53, "1.1.1.1", now, null));

        DnsDaemonState.deleteIfOwned(tmp, 111);

        assertTrue(DnsDaemonState.read(tmp).isEmpty(),
                "the record owner must be able to clear it");
    }
}
