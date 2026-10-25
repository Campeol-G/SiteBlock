package com.sitelock.core;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

/**
 * Single-instance lock for the daemon, via a {@code FileLock} held for the whole
 * life of the process.
 *
 * <p>It is the OS that releases a {@code FileLock} when the process dies — even on
 * {@code kill -9}. That is why a PID-based lock is no good: it survives the process
 * that created it and blocks the next boot, because the PID still looks alive.
 *
 * <p><b>{@code tryLock}, not {@code lock}.</b> A file lock is per JVM, not
 * per channel: a second {@code lock()} in the same JVM throws
 * {@link OverlappingFileLockException}.
 *
 * <p>Between <b>processes</b>, {@code lock()} blocks — that is what the first
 * version did: the second instance hung forever, and systemd
 * reported it as active.
 */
public final class ExpirationDaemonLock implements AutoCloseable {

    public static final String LOCK_FILE_NAME = "daemon.lock";

    // Total wait time before giving up: ATTEMPTS * INTERVAL_MS.
    private static final int ATTEMPTS = 20;
    private static final long INTERVAL_MS = 250;

    private final FileChannel channel;
    private final FileLock lock;
    private final Path stateDir;

    private ExpirationDaemonLock(FileChannel channel, FileLock lock, Path stateDir) {
        this.channel = channel;
        this.lock = lock;
        this.stateDir = stateDir;
    }

    public static Path lockPath(Path stateDir) {
        return stateDir.resolve(LOCK_FILE_NAME);
    }

    /**
     * Acquires the lock, waiting before giving up, and reports who is holding it.
     *
     * @return the lock held for the life of the process
     * @throws IOException if another daemon already has it, or if the file cannot
     *                     be created
     */
    public static ExpirationDaemonLock acquire(Path stateDir) throws IOException {
        Path lockFile = lockPath(stateDir);
        SiteLockPaths.createDirectories(stateDir);
        FileChannel channel = FileChannel.open(lockFile,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock lock = null;
        try {
            for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
                try {
                    lock = channel.tryLock();
                } catch (OverlappingFileLockException e) {
                    lock = null; // another thread in the SAME JVM already has it
                }
                if (lock != null) {
                    SiteLockPaths.chownToUserIfConfigured(lockFile);
                    return new ExpirationDaemonLock(channel, lock, stateDir);
                }
                try {
                    Thread.sleep(INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            channel.close();
            throw new IOException(otherRunning(stateDir));
        } catch (IOException e) {
            if (lock == null) {
                try {
                    channel.close();
                } catch (IOException ignored) {
                }
            }
            throw e;
        }
    }

    private static String otherRunning(Path stateDir) {
        String pid = "another process";
        try {
            Optional<ExpirationDaemonState> state = ExpirationDaemonState.read(stateDir);
            if (state.isPresent() && state.get().getPid() > 0) {
                pid = "pid " + state.get().getPid();
            }
        } catch (IOException ignored) {
            // Without the record, the lock is still the source of truth.
        }
        return "An expiration daemon is already running (" + pid + "). "
                + "Use 'siteblock daemon status' to see it, or 'daemon stop' to stop it.";
    }

    public Path getStateDir() {
        return stateDir;
    }

    @Override
    public void close() throws IOException {
        try {
            if (lock.isValid()) {
                lock.release();
            }
        } finally {
            channel.close();
        }
    }
}