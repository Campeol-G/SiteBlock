package com.sitelock.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * State and single-instance lock of the daemon.
 *
 * <p>The lock is a {@code FileLock} and not "it is running because the PID exists": a
 * {@code kill -9} does not orphan a {@code FileLock}, because the OS releases it when
 * the process dies. A PID-based lock would outlive the process and block the
 * next boot.
 */
class ExpirationDaemonStateTest {

    @TempDir
    Path tmp;

    private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");

    @Test
    void roundTripPreservesEveryField() throws Exception {
        Instant last = NOW.plusSeconds(900);
        ExpirationDaemonState state = new ExpirationDaemonState(4242, NOW,
                tmp.toString(), last);

        ExpirationDaemonState.write(tmp, state);
        Optional<ExpirationDaemonState> read = ExpirationDaemonState.read(tmp);

        assertTrue(read.isPresent());
        assertEquals(4242, read.get().getPid());
        assertEquals(NOW, read.get().getStartedAt());
        assertEquals(tmp.toString(), read.get().getStateDir());
        assertEquals(last, read.get().getLastSweepAt());
    }

    @Test
    void missingFileReadsEmpty() throws Exception {
        assertTrue(ExpirationDaemonState.read(tmp).isEmpty());
    }

    @Test
    void emptyFileReadsEmpty() throws Exception {
        Files.writeString(ExpirationDaemonState.statePath(tmp), "   ", StandardCharsets.UTF_8);
        assertTrue(ExpirationDaemonState.read(tmp).isEmpty());
    }

    @Test
    void updateLastSweepKeepsPidAndStartedAt() throws Exception {
        ExpirationDaemonState.write(tmp, new ExpirationDaemonState(4242, NOW, tmp.toString(), null));

        Instant later = NOW.plusSeconds(300);
        ExpirationDaemonState.updateLastSweep(tmp, later);

        ExpirationDaemonState state = ExpirationDaemonState.read(tmp).orElseThrow();
        assertEquals(later, state.getLastSweepAt());
        assertEquals(4242, state.getPid(), "updating the sweep must not change the record's owner");
        assertEquals(NOW, state.getStartedAt());
        assertEquals(tmp.toString(), state.getStateDir());
    }

    // WHY: the write replaces the whole file instead of truncating it —
    // a plain Files.writeString leaves the daemon.json empty between truncate and write,
    // and whoever reads at that instant ("daemon status", "daemon stop") gets "no daemon".
    @Test
    void writeReplacesTheFileInsteadOfTruncating() throws Exception {
        Path target = ExpirationDaemonState.statePath(tmp);
        ExpirationDaemonState.write(tmp, new ExpirationDaemonState(1, NOW, tmp.toString(), null));
        String before = fileIdentity(target);
        ExpirationDaemonState.write(tmp, new ExpirationDaemonState(2, NOW, tmp.toString(), null));
        String later = fileIdentity(target);

        Assumptions.assumeFalse(before.isEmpty(), "this OS does not expose the file identity");
        assertNotEquals(before, later,
                "the write has to be atomic: a new file replaces the old one, "
                        + "otherwise a reader in the middle of the write sees a truncated record");
        assertFalse(Files.exists(target.resolveSibling(target.getFileName() + ".tmp")),
                "the temporary file has to disappear after the swap");
    }

    // File identity: two files with the same identity are the same file.
    private static String fileIdentity(Path file) throws IOException {
        Object key = Files.readAttributes(file, BasicFileAttributes.class).fileKey();
        if (key != null) {
            return key.toString();
        }
        try {
            return Files.getAttribute(file, "unix:ino").toString();
        } catch (UnsupportedOperationException | IOException e) {
            return "";
        }
    }

    // WHY: the OS recycles PIDs, and "the PID exists" lies: daemon stop would
    // send a SIGTERM to a process that is not SiteLock.
    @Test
    void aRecycledPidDoesNotPassAsALivingDaemon() {
        ExpirationDaemonState recycled = new ExpirationDaemonState(
                ProcessHandle.current().pid(), NOW, "/x", null, NOW);

        assertFalse(ExpirationDaemonState.isAlive(recycled),
                "the PID exists, but it is not the process the record describes");
    }

    @Test
    void theProcessThatWroteTheRecordIsAlive() {
        Instant start = ProcessHandle.current().info().startInstant().orElseThrow();
        ExpirationDaemonState state = new ExpirationDaemonState(
                ProcessHandle.current().pid(), start, "/x", null, start);

        assertTrue(ExpirationDaemonState.isAlive(state),
                "the process that wrote the record has to be recognized as alive");
    }

    @Test
    void anOldRecordWithoutTheInstantKeepsWorking() throws Exception {
        // A daemon.json written by an old version has no start
        // instant: without it, only the PID check remains.
        Files.writeString(ExpirationDaemonState.statePath(tmp),
                "{\"pid\":" + ProcessHandle.current().pid()
                        + ",\"startedAt\":\"2026-10-15T12:00:00Z\",\"stateDir\":\"/x\"}",
                StandardCharsets.UTF_8);

        ExpirationDaemonState state = ExpirationDaemonState.read(tmp).orElseThrow();

        assertNull(state.getProcessStartedAt(), "the old record does not have the new field");
        assertTrue(ExpirationDaemonState.isAlive(state),
                "an old record has to keep working: only the PID check");
    }

    @Test
    void writeRecordsTheProcessStartInstant() throws Exception {
        ExpirationDaemonState state = new ExpirationDaemonState(
                ProcessHandle.current().pid(), NOW, tmp.toString(), null);

        ExpirationDaemonState.write(tmp, state);

        ExpirationDaemonState readBack = ExpirationDaemonState.read(tmp).orElseThrow();
        assertEquals(ProcessHandle.current().info().startInstant().orElseThrow(),
                readBack.getProcessStartedAt(),
                "the record has to store the start instant of the PROCESS");
        assertEquals(readBack.getProcessStartedAt(), readBack.getStartedAt(),
                "the 'Started at:' has to show the process start instant");
    }

    // WHY: updateLastSweep is a read-modify-write. Without the lock it reads the record
    // and another write saves the new PID in that interval — updateLastSweep
    // puts the old record back on top, and the daemon's PID disappears.
    @Test
    void concurrentWritesDoNotRevertThePid() throws Exception {
        ExpirationDaemonState.write(tmp, new ExpirationDaemonState(1, NOW, tmp.toString(), null));

        int iterations = 300;
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> writer = pool.submit(() -> {
                barrier.await();
                for (int i = 0; i < iterations; i++) {
                    ExpirationDaemonState.write(tmp,
                            new ExpirationDaemonState(1000 + i, NOW, tmp.toString(), null));
                }
                return null;
            });
            Future<?> updater = pool.submit(() -> {
                barrier.await();
                for (int i = 0; i < iterations; i++) {
                    ExpirationDaemonState.updateLastSweep(tmp, NOW.plusSeconds(i));
                }
                return null;
            });
            writer.get(60, TimeUnit.SECONDS);
            updater.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        ExpirationDaemonState state = ExpirationDaemonState.read(tmp).orElseThrow();
        assertEquals(1000 + iterations - 1, state.getPid(),
                "the PID cannot go backwards: an updateLastSweep returned the stale record");
    }

    @Test
    void deleteRemovesTheStateFile() throws Exception {
        ExpirationDaemonState.write(tmp, new ExpirationDaemonState(1, NOW, tmp.toString(), null));
        ExpirationDaemonState.delete(tmp);
        assertFalse(Files.exists(ExpirationDaemonState.statePath(tmp)));
    }

    @Test
    void isAliveFollowsThePid() {
        ExpirationDaemonState alive =
                new ExpirationDaemonState(ProcessHandle.current().pid(), NOW, tmp.toString(), null);
        assertTrue(ExpirationDaemonState.isAlive(alive));

        // PID 0 and an implausible PID are not live processes.
        assertFalse(ExpirationDaemonState.isAlive(new ExpirationDaemonState(0, NOW, "/x", null)));
        assertFalse(ExpirationDaemonState.isAlive(
                new ExpirationDaemonState(Integer.MAX_VALUE + 7, NOW, "/x", null)));
        assertFalse(ExpirationDaemonState.isAlive(null));
    }

    @Test
    void acquireIsExclusive() throws Exception {
        ExpirationDaemonLock first = ExpirationDaemonLock.acquire(tmp);
        assertNotNull(first);

        IOException error = assertThrows(IOException.class, () -> ExpirationDaemonLock.acquire(tmp));
        assertTrue(error.getMessage().contains("expiration daemon is already running"), error.getMessage());
        assertTrue(error.getMessage().contains("daemon stop"), "the message has to say what to do");

        first.close();
        // Released: another daemon can take it.
        ExpirationDaemonLock second = ExpirationDaemonLock.acquire(tmp);
        assertNotNull(second);
        second.close();
    }

    @Test
    void lockFileIsCreatedInTheStateDir() throws Exception {
        Path stateDir = tmp.resolve("state");
        ExpirationDaemonLock lock = ExpirationDaemonLock.acquire(stateDir);
        try {
            assertTrue(Files.exists(stateDir.resolve("daemon.lock")));
        } finally {
            lock.close();
        }
    }

    @Test
    void chownToUserIfConfiguredFollowsTheProperty() throws Exception {
        String currentUser = System.getProperty("user.name", "");
        Assumptions.assumeFalse(currentUser.isBlank(), "the OS did not say who the user is");
        Assumptions.assumeFalse("root".equals(currentUser),
                "running as root, chown to itself proves nothing");

        Path file = tmp.resolve("state.json");
        Files.writeString(file, "{}", StandardCharsets.UTF_8);

        UserPrincipalLookupService lookup = file.getFileSystem().getUserPrincipalLookupService();
        Assumptions.assumeTrue(Files.getFileAttributeView(file, PosixFileAttributeView.class) != null,
                "without POSIX there is no group to verify");
        String userGroup = groupNameOfUser(currentUser, lookup);
        Assumptions.assumeTrue(userGroup != null,
                "the user has no group with the same name: chown does not change the group");
        String secondaryGroup = secondaryGroupOfUser(currentUser, userGroup);
        Assumptions.assumeTrue(secondaryGroup != null,
                "the user has no secondary group: no way to tell chown from a no-op");

        Assumptions.assumeTrue(changeGroup(file, lookup, secondaryGroup),
                "could not change the group of the test file");
        assertEquals(secondaryGroup, groupName(file),
                "precondition: the file has to start with the changed group");

        System.setProperty("sitelock.user", currentUser);
        SiteLockPaths.chownToUserIfConfigured(file);

        assertEquals(currentUser, Files.getOwner(file).getName(),
                "with -Dsitelock.user the daemon hands the file back to its owner even without SUDO_USER");
        assertEquals(userGroup, groupName(file),
                "chown has to return the group to the user's: with chown turned into a no-op "
                        + "the file keeps the changed group");
    }

    @Test
    void chownToUserIfConfiguredWithoutPropertyDoesNothing() throws Exception {
        String currentUser = System.getProperty("user.name", "");
        Assumptions.assumeFalse(currentUser.isBlank(), "the OS did not say who the user is");
        String sudoUser = System.getenv("SUDO_USER");
        Assumptions.assumeTrue(sudoUser == null || sudoUser.isBlank()
                        || "root".equals(sudoUser.strip()),
                "under sudo the path without the property is different: SUDO_USER hands ownership back");

        Path file = tmp.resolve("no-property.json");
        Files.writeString(file, "{}", StandardCharsets.UTF_8);

        UserPrincipalLookupService lookup = file.getFileSystem().getUserPrincipalLookupService();
        Assumptions.assumeTrue(Files.getFileAttributeView(file, PosixFileAttributeView.class) != null,
                "without POSIX there is no group to verify");
        String userGroup = groupNameOfUser(currentUser, lookup);
        Assumptions.assumeTrue(userGroup != null,
                "the user has no group with the same name: chown does not change the group");
        String secondaryGroup = secondaryGroupOfUser(currentUser, userGroup);
        Assumptions.assumeTrue(secondaryGroup != null,
                "the user has no secondary group: no way to tell chown from a no-op");
        Assumptions.assumeTrue(changeGroup(file, lookup, secondaryGroup),
                "could not change the group of the test file");

        System.clearProperty("sitelock.user");
        SiteLockPaths.chownToUserIfConfigured(file);

        assertEquals(secondaryGroup, groupName(file),
                "without the property chown cannot run: the group has to stay as it was");
        assertEquals(currentUser, Files.getOwner(file).getName(),
                "without the property, the behavior is the usual one: only SUDO_USER hands ownership back");
    }

    // WHY: the property lives in the surefire JVM (one per module). If it stays set
    // after a failure, every following save goes through the chown path — and test
    // execution order starts to matter.
    @AfterEach
    void clearUserProperty() {
        System.clearProperty("sitelock.user");
    }

    // WARNING: not every system has a group with the same name as the user (on macOS
    // the primary group is called "staff"). Without that group chown does not change the
    // group, and the test would have nothing to verify.
    private static String groupNameOfUser(String user, UserPrincipalLookupService lookup) {
        try {
            GroupPrincipal group = lookup.lookupPrincipalByGroupName(user);
            return group.getName();
        } catch (IOException | IllegalArgumentException e) {
            return null;
        }
    }

    // WHY: without a different group there is no way to change the file's group — and
    // the group is the only observable difference between "chown ran" and "chown was
    // a no-op".
    private static String secondaryGroupOfUser(String user, String chownGroup) {
        Path groupFile = Path.of("/etc/group");
        if (!Files.isReadable(groupFile)) {
            return null;
        }
        try {
            for (String line : Files.readAllLines(groupFile, StandardCharsets.UTF_8)) {
                String[] parts = line.split(":");
                if (parts.length < 4 || parts[0].equals(chownGroup)) {
                    continue;
                }
                for (String member : parts[3].split(",")) {
                    if (user.equals(member.strip())) {
                        return parts[0];
                    }
                }
            }
        } catch (IOException e) {
            return null;
        }
        return null;
    }

    // Changes the file's group (the owner may move it to a group they belong to).
    private static boolean changeGroup(Path file, UserPrincipalLookupService lookup, String group) {
        try {
            PosixFileAttributeView posix =
                    Files.getFileAttributeView(file, PosixFileAttributeView.class);
            posix.setGroup(lookup.lookupPrincipalByGroupName(group));
            return true;
        } catch (IOException | IllegalArgumentException | UnsupportedOperationException e) {
            return false;
        }
    }

    // The file's group, by name.
    private static String groupName(Path file) throws IOException {
        return Files.readAttributes(file, PosixFileAttributes.class).group().getName();
    }
}