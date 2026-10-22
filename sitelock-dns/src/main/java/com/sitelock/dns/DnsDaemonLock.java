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
 * Trava curta do {@code dns start}: impede que dois processos leiam o estado
 * vazio, subam um daemon cada um e o último sobrescreva o registro do outro.
 *
 * <p>O SO libera a trava quando o processo morre, então não há arquivo órfão
 * para limpar. O lock é de arquivo, não de PID.
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
     * Tenta a trava sem esperar.
     *
     * @return a trava, ou vazio se outro {@code dns start} já a tem
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
                lock = null; // outra thread/processo da mesma JVM
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
