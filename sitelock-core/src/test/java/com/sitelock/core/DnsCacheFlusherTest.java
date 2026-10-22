package com.sitelock.core;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Testa apenas a <i>seleção</i> dos comandos de flush por SO, com runner fake.
 *
 * <p>Limitação conhecida (documentada no README e no {@link DnsCacheFlusher}):
 * a execução real do flush ({@code systemd-resolve}, {@code dscacheutil},
 * {@code ipconfig}) não é testada no CI em todos os SOs — exige runners
 * Windows/macOS + permissões. Validar manualmente.
 */
class DnsCacheFlusherTest {

    @Test
    void linuxTriesSystemdResolveThenResolvectl() {
        List<List<String>> cmds = DnsCacheFlusher.commandsFor(OsType.LINUX);
        assertEquals(2, cmds.size());
        assertEquals(List.of("systemd-resolve", "--flush-caches"), cmds.get(0));
        assertEquals(List.of("resolvectl", "flush-caches"), cmds.get(1));
    }

    @Test
    void macFlushesCacheAndHupsResponder() {
        List<List<String>> cmds = DnsCacheFlusher.commandsFor(OsType.MACOS);
        assertEquals(1, cmds.size());
        String joined = String.join(" ", cmds.get(0));
        assertTrue(joined.contains("dscacheutil -flushcache"), joined);
        assertTrue(joined.contains("killall -HUP mDNSResponder"), joined);
    }

    @Test
    void windowsUsesIpconfig() {
        List<List<String>> cmds = DnsCacheFlusher.commandsFor(OsType.WINDOWS);
        assertEquals(List.of(List.of("ipconfig", "/flushdns")), cmds);
    }

    @Test
    void flushSucceedsWhenFirstLinuxCommandWorks() {
        DnsCacheFlusher f = new DnsCacheFlusher(OsType.LINUX, true, cmd -> 0);
        assertTrue(f.flush().success());
    }

    @Test
    void linuxFallsBackToSecondCommandWhenFirstMissing() {
        List<List<String>> attempted = new ArrayList<>();
        DnsCacheFlusher f = new DnsCacheFlusher(OsType.LINUX, true, cmd -> {
            attempted.add(cmd);
            if (cmd.get(0).equals("systemd-resolve")) {
                throw new IOException("comando não encontrado: systemd-resolve");
            }
            return 0;
        });
        assertTrue(f.flush().success());
        assertEquals(2, attempted.size());
    }

    @Test
    void flushFailsGracefullyWhenAllCommandsFail() {
        DnsCacheFlusher f = new DnsCacheFlusher(OsType.LINUX, true, cmd -> 1);
        assertFalse(f.flush().success());
    }

    @Test
    void flushBestEffortNeverThrows() {
        DnsCacheFlusher f = new DnsCacheFlusher(OsType.WINDOWS, true, cmd -> {
            throw new IOException("sem ipconfig aqui");
        });
        assertDoesNotThrow(f::flushBestEffort);
        assertFalse(f.flushBestEffort());
    }

    // ---- O flush é best-effort; ele nunca pode abrir um diálogo de senha ----
    // Sem root, `systemd-resolve --flush-caches` não falha e segue em frente: o
    // systemd-resolved responde pela varlink e o polkit abre "Authentication
    // Required" no desktop pedindo senha. Um passo opcional não pode cobrar senha.

    @Test
    void nonRootNeverAttemptsSystemdFlush() {
        List<List<String>> attempted = new ArrayList<>();
        DnsCacheFlusher f = new DnsCacheFlusher(OsType.LINUX, false, cmd -> {
            attempted.add(cmd);
            return 0;
        });
        f.flush();
        assertTrue(attempted.isEmpty(),
                "sem root nada pode ser tentado, senão o polkit abre o diálogo: " + attempted);
    }

    @Test
    void rootAttemptsSystemdFlush() {
        List<List<String>> attempted = new ArrayList<>();
        DnsCacheFlusher f = new DnsCacheFlusher(OsType.LINUX, true, cmd -> {
            attempted.add(cmd);
            return 0;
        });
        assertTrue(f.flush().success());
        assertEquals(1, attempted.size(), "o primeiro comando que funciona encerra a tentativa");
        assertEquals(List.of("systemd-resolve", "--flush-caches"), attempted.get(0));
    }

    @Test
    void nonRootNeverAttemptsMacFlushEither() {
        List<List<String>> attempted = new ArrayList<>();
        new DnsCacheFlusher(OsType.MACOS, false, cmd -> {
            attempted.add(cmd);
            return 0;
        }).flush();
        assertTrue(attempted.isEmpty(), "dscacheutil também cobra elevação: " + attempted);
    }

    @Test
    void windowsAlwaysAttemptsFlush() {
        List<List<String>> attempted = new ArrayList<>();
        DnsCacheFlusher f = new DnsCacheFlusher(OsType.WINDOWS, false, cmd -> {
            attempted.add(cmd);
            return 0;
        });
        assertTrue(f.flush().success());
        assertEquals(List.of(List.of("ipconfig", "/flushdns")), attempted);
    }

    @Test
    void nonRootFailureSaysToRestartTheBrowserWithoutBlamingPermissions() {
        DnsCacheFlusher f = new DnsCacheFlusher(OsType.LINUX, false, cmd -> 0);
        java.io.ByteArrayOutputStream err = new java.io.ByteArrayOutputStream();
        java.io.PrintStream original = System.err;
        System.setErr(new java.io.PrintStream(err, true));
        try {
            f.flushBestEffort();
        } finally {
            System.setErr(original);
        }
        String saida = err.toString();
        assertTrue(saida.contains("reiniciar o navegador"), saida);
        assertFalse(saida.contains("Permission denied"), saida);
        assertFalse(saida.contains("systemd-resolve"),
                "não tentamos nada, então não há comando para culpar: " + saida);
    }

    @Test
    void realJvmIsDetectedAsRootOnlyWhenItIs() {
        boolean root = "root".equals(System.getProperty("user.name", ""));
        assertEquals(root, new DnsCacheFlusher(OsType.LINUX).isPrivileged());
    }
}
