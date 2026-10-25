package com.sitelock.dns;

import com.sitelock.core.SiteLockPaths;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

/**
 * Short-lived lock for {@code dns start}: it stops two processes from both reading
 * empty state, starting a daemon each, and the last one overwriting the other's record.
 *
 * <p>The OS releases the lock when the process dies, so there is no orphan file to
 * clean up. This is a file lock, not a PID lock.
 */
public final class DnsDaemonLock implements AutoCloseable {

    public static final String LOCK_FILE_NAME = "dns-server.start.lock";

    private final FileChannel channel;
    private final FileLock lock;

    private DnsDaemonLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    public static Path lockPath(Path stateDir) {
        return stateDir.resolve(LOCK_FILE_NAME);
    }

    /**
     * Tries to take the lock without waiting.
     *
     * @return the lock, or empty if another {@code dns start} already holds it
     */
    public static Optional<DnsDaemonLock> tryAcquire(Path stateDir) throws IOException {
        SiteLockPaths.createDirectories(stateDir);
        Path path = lockPath(stateDir);
        FileChannel channel = FileChannel.open(path,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock lock = null;
        try {
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException e) {
                lock = null; // another thread or process in the same JVM holds it
            }
            if (lock == null) {
                channel.close();
                return Optional.empty();
            }
            SiteLockPaths.chownToUserIfConfigured(path);
            return Optional.of(new DnsDaemonLock(channel, lock));
        } catch (IOException | RuntimeException e) {
            if (lock != null) {
                try {
                    lock.release();
                } catch (IOException ignored) {
                }
            }
            try {
                channel.close();
            } catch (IOException ignored) {
            }
            throw e;
        }
    }

    @Override
    public void close() throws IOException {
        try {
            lock.release();
        } finally {
            channel.close();
        }
    }
}
