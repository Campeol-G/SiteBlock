package com.sitelock.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Estado e lock de instância única do daemon.
 *
 * <p>O lock é {@code FileLock} e não "está rodando porque o PID existe": um
 * {@code kill -9} não órfãoa um {@code FileLock}, porque o SO o libera na
 * morte do processo. Um lock por PID sobreviveria ao processo e travaria o
 * próximo boot.
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

        Instant depois = NOW.plusSeconds(300);
        ExpirationDaemonState.updateLastSweep(tmp, depois);

        ExpirationDaemonState state = ExpirationDaemonState.read(tmp).orElseThrow();
        assertEquals(depois, state.getLastSweepAt());
        assertEquals(4242, state.getPid(), "atualizar o sweep não pode trocar o dono do registro");
        assertEquals(NOW, state.getStartedAt());
        assertEquals(tmp.toString(), state.getStateDir());
    }

    @Test
    void deleteRemovesTheStateFile() throws Exception {
        ExpirationDaemonState.write(tmp, new ExpirationDaemonState(1, NOW, tmp.toString(), null));
        ExpirationDaemonState.delete(tmp);
        assertFalse(Files.exists(ExpirationDaemonState.statePath(tmp)));
    }

    @Test
    void isAliveFollowsThePid() {
        ExpirationDaemonState vivo =
                new ExpirationDaemonState(ProcessHandle.current().pid(), NOW, tmp.toString(), null);
        assertTrue(ExpirationDaemonState.isAlive(vivo));

        // PID 0 e um PID improvável não são processos vivos.
        assertFalse(ExpirationDaemonState.isAlive(new ExpirationDaemonState(0, NOW, "/x", null)));
        assertFalse(ExpirationDaemonState.isAlive(
                new ExpirationDaemonState(Integer.MAX_VALUE + 7, NOW, "/x", null)));
        assertFalse(ExpirationDaemonState.isAlive(null));
    }

    @Test
    void acquireIsExclusive() throws Exception {
        ExpirationDaemonLock primeiro = ExpirationDaemonLock.acquire(tmp);
        assertNotNull(primeiro);

        IOException erro = assertThrows(IOException.class, () -> ExpirationDaemonLock.acquire(tmp));
        assertTrue(erro.getMessage().contains("daemon de expiração rodando"), erro.getMessage());
        assertTrue(erro.getMessage().contains("daemon stop"), "a mensagem tem que dizer o que fazer");

        primeiro.close();
        // Liberado: outro daemon pode pegar.
        ExpirationDaemonLock segundo = ExpirationDaemonLock.acquire(tmp);
        assertNotNull(segundo);
        segundo.close();
    }

    @Test
    void lockFileIsCreatedInTheStateDir() throws Exception {
        Path estado = tmp.resolve("estado");
        ExpirationDaemonLock lock = ExpirationDaemonLock.acquire(estado);
        try {
            assertTrue(Files.exists(estado.resolve("daemon.lock")));
        } finally {
            lock.close();
        }
    }

    @Test
    void chownToUserIfConfiguredFollowsTheProperty() throws Exception {
        String usuarioAtual = System.getProperty("user.name", "");
        if ("root".equals(usuarioAtual)) {
            return; // rodando como root, chown para si mesmo não prova nada
        }
        Path arquivo = tmp.resolve("estado.json");
        Files.writeString(arquivo, "{}", StandardCharsets.UTF_8);

        System.setProperty("sitelock.user", usuarioAtual);
        SiteLockPaths.chownToUserIfConfigured(arquivo);

        String dono = Files.getOwner(arquivo).getName();
        assertEquals(usuarioAtual, dono,
                "com -Dsitelock.user o daemon devolve o arquivo ao dono mesmo sem SUDO_USER");
    }

    @Test
    void chownToUserIfConfiguredWithoutPropertyDoesNothing() throws Exception {
        System.clearProperty("sitelock.user");
        Path arquivo = tmp.resolve("sem-property.json");
        Files.writeString(arquivo, "{}", StandardCharsets.UTF_8);
        String antes = Files.getOwner(arquivo).getName();

        SiteLockPaths.chownToUserIfConfigured(arquivo);

        assertEquals(antes, Files.getOwner(arquivo).getName(),
                "sem a property, o comportamento é o de sempre: só SUDO_USER devolve a posse");
    }
}