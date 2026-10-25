package com.sitelock.cli;

import com.sitelock.core.ExpirationDaemonState;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The daemon log: one per run, with a controlled size and readable by the user.
 *
 * <p>The log is created by the parent of {@code daemon start}, which runs as
 * root, and the child only APPENDS to it. Without rotation, the file grows
 * forever and {@code printLogTail} — which exists to show the error from NOW —
 * shows the tail of an old run.
 */
class DaemonLogRotationTest {

    @TempDir
    Path tmp;

    @Test
    void theLogStartsEmptySoTheTailShowsTheCurrentError() throws Exception {
        Path log = ExpirationDaemonState.logPath(tmp);
        Files.writeString(log, "picocli help text from an old run\n".repeat(20),
                StandardCharsets.UTF_8);

        DaemonStartCommand.prepareLog(log);

        assertEquals("", Files.readString(log, StandardCharsets.UTF_8),
                "the log must start empty: the tail shows the error from NOW, not from another day");
    }

    @Test
    void thePreviousRunStaysInTheRotatedLog() throws Exception {
        Path log = ExpirationDaemonState.logPath(tmp);
        Files.writeString(log, "old error\n", StandardCharsets.UTF_8);

        DaemonStartCommand.prepareLog(log);

        Path rotated = log.resolveSibling(log.getFileName() + ".1");
        assertTrue(Files.exists(rotated), "the previous log must not be thrown away");
        assertTrue(Files.readString(rotated, StandardCharsets.UTF_8).contains("old error"),
                "the previous log must remain readable in the rotated file");
    }

    @Test
    void theRotatedLogHasABoundedSize() throws Exception {
        Path log = ExpirationDaemonState.logPath(tmp);
        Files.writeString(log, "x".repeat((int) (DaemonStartCommand.LOG_MAX_BYTES * 3)),
                StandardCharsets.UTF_8);

        DaemonStartCommand.prepareLog(log);

        Path rotated = log.resolveSibling(log.getFileName() + ".1");
        long size = Files.size(rotated);
        assertTrue(size <= DaemonStartCommand.LOG_MAX_BYTES,
                "the previous log must fit under the ceiling, but it has " + size + " bytes");
    }

    @Test
    void logOwnershipGoesBackToTheRealUser() throws Exception {
        // The daemon runs as root: without chown, the log is readable only by it —
        // and the promise that status works without elevation goes away.
        String user = System.getProperty("user.name", "");
        UserPrincipalLookupService lookup = tmp.getFileSystem().getUserPrincipalLookupService();
        GroupPrincipal expected;
        try {
            expected = lookup.lookupPrincipalByGroupName(user);
        } catch (Exception e) {
            expected = null;
        }
        Optional<String> other = secondaryGroup(user);
        Assumptions.assumeTrue(expected != null && other.isPresent(),
                "requires a group named after the user and one of its secondary groups");

        Path log = ExpirationDaemonState.logPath(tmp);
        Files.writeString(log, "content\n", StandardCharsets.UTF_8);
        PosixFileAttributeView view = Files.getFileAttributeView(log, PosixFileAttributeView.class);
        view.setGroup(lookup.lookupPrincipalByGroupName(other.get()));

        String saved = System.getProperty("sitelock.user");
        System.setProperty("sitelock.user", user);
        try {
            DaemonStartCommand.prepareLog(log);
        } finally {
            if (saved == null) {
                System.clearProperty("sitelock.user");
            } else {
                System.setProperty("sitelock.user", saved);
            }
        }

        assertEquals(expected.getName(), view.readAttributes().group().getName(),
                "the log must go back to the real user, like daemon.json");
    }

    /**
     * The real command: the log the child writes must be from this run, not the
     * tail of the previous one.
     */
    @Test
    void theRealStartRotatesTheLog() throws Exception {
        Path hosts = tmp.resolve("hosts");
        Files.writeString(hosts, "127.0.0.1 localhost\n", StandardCharsets.UTF_8);
        Path state = tmp.resolve("state");
        Files.createDirectories(state);
        Path log = ExpirationDaemonState.logPath(state);
        Files.writeString(log, "error from an old run\n", StandardCharsets.UTF_8);

        int exit;
        try {
            exit = new DaemonStartCommand("60", hosts.toString(),
                    state.resolve("blocks.json").toString()).call();
        } finally {
            killDaemon(state);
        }

        assertEquals(0, exit, "the daemon had to come up");
        String content = Files.readString(log, StandardCharsets.UTF_8);
        assertFalse(content.contains("old run"),
                "the log still has text from the previous run:\n" + content);
        assertTrue(content.contains("expiration daemon up"),
                "the log must be from the current run:\n" + content);
    }

    // Kills the daemon that start brought up, if it came up.
    private static void killDaemon(Path state) {
        try {
            ExpirationDaemonState.read(state).ifPresent(snapshot -> {
                ProcessHandle.of(snapshot.getPid()).ifPresent(TestProcesses::kill);
            });
        } catch (IOException e) {
            // the test that matters is the rotation one
        }
    }

    // A group the user belongs to that is not their primary group.
    private static Optional<String> secondaryGroup(String user) {
        Path etc = Path.of("/etc/group");
        if (!Files.isReadable(etc)) {
            return Optional.empty();
        }
        try {
            for (String line : Files.readAllLines(etc, StandardCharsets.UTF_8)) {
                String[] parts = line.split(":");
                if (parts.length < 4 || parts[0].equals(user)) {
                    continue;
                }
                for (String member : parts[3].split(",")) {
                    if (member.equals(user)) {
                        return Optional.of(parts[0]);
                    }
                }
            }
        } catch (IOException e) {
            return Optional.empty();
        }
        return Optional.empty();
    }
}
