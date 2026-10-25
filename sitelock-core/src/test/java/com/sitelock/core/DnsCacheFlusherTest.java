package com.sitelock.core;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests only the per-OS <i>selection</i> of flush commands, with a fake runner.
 *
 * <p>Known limitation (documented in the README and in {@link DnsCacheFlusher}):
 * the real flush execution ({@code systemd-resolve}, {@code dscacheutil},
 * {@code ipconfig}) is not tested on CI for every OS — it requires Windows/macOS
 * runners + permissions. Validate manually.
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
                throw new IOException("command not found: systemd-resolve");
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
            throw new IOException("no ipconfig here");
        });
        assertDoesNotThrow(f::flushBestEffort);
        assertFalse(f.flushBestEffort());
    }

    // ---- The flush is best-effort; it must never open a password dialog ----
    // Without root, `systemd-resolve --flush-caches` does not fail and goes ahead: the
    // systemd-resolved answers over varlink and polkit opens "Authentication
    // Required" on the desktop asking for a password. An optional step must not demand one.

    @Test
    void nonRootNeverAttemptsSystemdFlush() {
        List<List<String>> attempted = new ArrayList<>();
        DnsCacheFlusher f = new DnsCacheFlusher(OsType.LINUX, false, cmd -> {
            attempted.add(cmd);
            return 0;
        });
        f.flush();
        assertTrue(attempted.isEmpty(),
                "without root nothing may be attempted, otherwise polkit opens the dialog: " + attempted);
    }

    @Test
    void rootAttemptsSystemdFlush() {
        List<List<String>> attempted = new ArrayList<>();
        DnsCacheFlusher f = new DnsCacheFlusher(OsType.LINUX, true, cmd -> {
            attempted.add(cmd);
            return 0;
        });
        assertTrue(f.flush().success());
        assertEquals(1, attempted.size(), "the first command that works ends the attempt");
        assertEquals(List.of("systemd-resolve", "--flush-caches"), attempted.get(0));
    }

    @Test
    void nonRootNeverAttemptsMacFlushEither() {
        List<List<String>> attempted = new ArrayList<>();
        new DnsCacheFlusher(OsType.MACOS, false, cmd -> {
            attempted.add(cmd);
            return 0;
        }).flush();
        assertTrue(attempted.isEmpty(), "dscacheutil also demands elevation: " + attempted);
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
        String output = err.toString();
        assertTrue(output.contains("restarting your browser"), output);
        assertFalse(output.contains("Permission denied"), output);
        assertFalse(output.contains("systemd-resolve"),
                "we attempted nothing, so there is no command to blame: " + output);
    }

    @Test
    void realJvmIsDetectedAsRootOnlyWhenItIs() {
        boolean root = "root".equals(System.getProperty("user.name", ""));
        assertEquals(root, new DnsCacheFlusher(OsType.LINUX).isPrivileged());
    }
}
