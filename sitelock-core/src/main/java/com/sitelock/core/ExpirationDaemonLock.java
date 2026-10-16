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
 * Trava de instância única do daemon, via {@code FileLock} retido pela vida
 * inteira do processo.
 *
 * <p>É o SO que libera um {@code FileLock} quando o processo morre — inclusive
 * no {@code kill -9}. Por isso ele serve aqui e um lock por PID não serve: um
 * lock por PID sobreviveria ao processo que o criou e travaria o próximo boot,
 * porque o PID gravado continuaria "vivo" na checagem.
 *
 * <p>Locks de arquivo são <b>por JVM</b>, não por canal: na mesma JVM, um
 * segundo {@code lock()} sobre o mesmo arquivo lança
 * {@link OverlappingFileLockException} (unchecked) em vez de bloquear. Por isso
 * {@link #acquire} converte essa exceção num erro com mensagem de "já existe um
 * daemon", que é o que a pessoa precisa ler.
 */
public final class ExpirationDaemonLock implements AutoCloseable {

    public static final String LOCK_FILE_NAME = "daemon.lock";

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
     * @return a trava retida, ou {@code null} se outro daemon já a tem
     * @throws IOException se o arquivo de lock não puder ser criado
     */
    public static ExpirationDaemonLock acquire(Path stateDir) throws IOException {
        Path lockFile = lockPath(stateDir);
        Files.createDirectories(stateDir);
        FileChannel channel = FileChannel.open(lockFile,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock lock;
        try {
            lock = channel.lock();
        } catch (OverlappingFileLockException e) {
            channel.close();
            throw new IOException(outroRodando(stateDir), e);
        } catch (IOException e) {
            channel.close();
            throw new IOException("Falha ao travar " + lockFile + ": " + e.getMessage(), e);
        }
        SiteLockPaths.chownToUserIfConfigured(lockFile);
        return new ExpirationDaemonLock(channel, lock, stateDir);
    }

    private static String outroRodando(Path stateDir) {
        String pid = "outro processo";
        try {
            Optional<ExpirationDaemonState> state = ExpirationDaemonState.read(stateDir);
            if (state.isPresent() && state.get().getPid() > 0) {
                pid = "pid " + state.get().getPid();
            }
        } catch (IOException ignored) {
            // Sem o registro, ainda assim a trava é a fonte da verdade.
        }
        return "Já existe um daemon de expiração rodando (" + pid + "). "
                + "Use 'siteblock daemon status' para ver, ou 'daemon stop' para encerrar.";
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