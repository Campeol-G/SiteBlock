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
        DnsCacheFlusher f = new DnsCacheFlusher(OsType.LINUX, cmd -> 0);
        assertTrue(f.flush().success());
    }

    @Test
    void linuxFallsBackToSecondCommandWhenFirstMissing() {
        List<List<String>> attempted = new ArrayList<>();
        DnsCacheFlusher f = new DnsCacheFlusher(OsType.LINUX, cmd -> {
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
        DnsCacheFlusher f = new DnsCacheFlusher(OsType.LINUX, cmd -> 1);
        assertFalse(f.flush().success());
    }

    @Test
    void flushBestEffortNeverThrows() {
        DnsCacheFlusher f = new DnsCacheFlusher(OsType.WINDOWS, cmd -> {
            throw new IOException("sem ipconfig aqui");
        });
        assertDoesNotThrow(f::flushBestEffort);
        assertFalse(f.flushBestEffort());
    }
}
