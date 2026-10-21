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
 * <p><b>{@code tryLock}, não {@code lock}.</b> Locks de arquivo são por JVM,
 * não por canal: na mesma JVM, um segundo {@code lock()} lança
 * {@link OverlappingFileLockException}. Mas entre <b>processos</b>, {@code
 * lock()} BLOQUEIA — e foi o que a primeira versão fazia, o que transformava
 * uma segunda instância num processo pendurado para sempre (e, com
 * {@code Restart=always}, num processo que o systemd reporta como ativo).
 * {@link #acquire} usa {@code tryLock} com uma espera curta e, ao não conseguir,
 * diz quem está segurando.
 */
public final class ExpirationDaemonLock implements AutoCloseable {

    public static final String LOCK_FILE_NAME = "daemon.lock";

    /** Tempo total de espera antes de desistir da trava. */
    private static final int TENTATIVAS = 20;
    private static final long INTERVALO_MS = 250;

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
     * @return a trava retida pela vida do processo
     * @throws IOException se outro daemon já a tem, ou se o arquivo não puder
     *                     ser criado
     */
    public static ExpirationDaemonLock acquire(Path stateDir) throws IOException {
        Path lockFile = lockPath(stateDir);
        Files.createDirectories(stateDir);
        FileChannel channel = FileChannel.open(lockFile,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock lock = null;
        try {
            for (int tentativa = 0; tentativa < TENTATIVAS; tentativa++) {
                try {
                    lock = channel.tryLock();
                } catch (OverlappingFileLockException e) {
                    lock = null; // outra thread da MESMA JVM já tem
                }
                if (lock != null) {
                    SiteLockPaths.chownToUserIfConfigured(lockFile);
                    return new ExpirationDaemonLock(channel, lock, stateDir);
                }
                try {
                    Thread.sleep(INTERVALO_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            channel.close();
            throw new IOException(outroRodando(stateDir));
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