package com.sitelock.cli;

import com.sitelock.dns.DnsDaemonState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The real {@code dns start}: it brings up a daemon on a high port and talks to it.
 *
 * <p>The DNS module tests had no socket test; here the daemon really comes up
 * (another process), which is the path the user takes.
 */
class DnsStartCommandTest {

    @TempDir
    Path tmp;

    private static final int PORT = 15399;

    @Test
    void allowFromReachesTheDaemon() throws Exception {
        Path stateFile = tmp.resolve("blocks.json");
        Files.writeString(stateFile, "{}", StandardCharsets.UTF_8);
        // The daemon state lives in the same directory as --state-file.
        Path state = tmp;

        int exit = new CommandLine(new DnsCommand()).execute(
                "start",
                "--port", String.valueOf(PORT),
                "--upstream", "127.0.0.1",
                "--allow-from", "203.0.113.0/24",
                "--state-file", stateFile.toString());

        try {
            assertEquals(0, exit, "the daemon should have started");
            Path log = DnsDaemonState.logPath(state);
            String logText = Files.readString(log, StandardCharsets.UTF_8);
            assertTrue(logText.contains("SITELOCK_DNS_LISTENING"),
                    "the daemon must announce that it is listening: " + logText);
            assertTrue(logText.contains("203.0.113.0/24"),
                    "the allowed origin must reach the daemon: " + logText);
            DnsDaemonState snapshot = DnsDaemonState.read(state).orElseThrow();
            assertTrue(snapshot.getProcessStartedAt() != null,
                    "the parent must record the child's identity, not only the PID");
            assertTrue(DnsDaemonState.isAlive(snapshot),
                    "the child's record must pass the identity check");
        } finally {
            stop(state);
        }
    }

    /**
     * A corrupted record must not lock the machine: the user needs to be able to
     * start the DNS server without deleting a file by hand.
     */
    @Test
    void corruptedRecordDoesNotPreventTheServerFromStarting() throws Exception {
        Path stateFile = tmp.resolve("blocks.json");
        Files.writeString(stateFile, "{}", StandardCharsets.UTF_8);
        Files.writeString(tmp.resolve("dns-server.json"),
                "{ \"pid\": 12, \"port\":", StandardCharsets.UTF_8);

        int exit = new CommandLine(new DnsCommand()).execute(
                "start",
                "--port", String.valueOf(PORT + 1),
                "--upstream", "127.0.0.1",
                "--state-file", stateFile.toString());
        try {
            assertEquals(0, exit,
                    "a corrupted record must not prevent the DNS server from starting");
            DnsDaemonState snapshot = DnsDaemonState.read(tmp).orElseThrow();
            assertTrue(snapshot.getPid() > 0, "the new record must be valid");
        } finally {
            stop(tmp);
        }
    }

    // Takes down the daemon the test started (the PID is in the state record).
    private void stop(Path state) {
        final Optional<DnsDaemonState> snapshot;
        try {
            snapshot = DnsDaemonState.read(state);
        } catch (IOException ignored) {
            return;
        }
        if (snapshot.isEmpty() || snapshot.get().getPid() <= 0) {
            return;
        }

        long pid = snapshot.get().getPid();
        ProcessHandle handle = ProcessHandle.of(pid).orElse(null);
        if (handle == null) {
            return;
        }
        TestProcesses.kill(handle);
        if (handle.isAlive()) {
            throw new AssertionError("the daemon did not die: pid " + pid);
        }

        // Windows may hold the log handle for a moment after the kill; deleting it
        // here (with retry) keeps @TempDir from failing during cleanup.
        Path log = DnsDaemonState.logPath(state);
        for (int i = 0; i < 50; i++) {
            try {
                Files.deleteIfExists(log);
                return;
            } catch (IOException e) {
                sleep(100);
            }
        }
        throw new AssertionError("the daemon log is still locked: " + log
                + " (pid " + pid + ", alive=" + handle.isAlive() + ")");
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
