package com.sitelock.cli;

import com.sitelock.blocker.HostsFileBlocker;
import com.sitelock.blocker.SiteBlocker;
import com.sitelock.core.BlockService;
import com.sitelock.core.BlockStore;
import com.sitelock.core.DnsCacheFlusher;
import com.sitelock.core.ExpirationDaemonState;
import com.sitelock.core.OsType;
import com.sitelock.core.PermissionAdvisor;
import com.sitelock.core.SiteLockPaths;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;

// Helpers shared by the CLI commands.
final class CliSupport {

    static final String PROFILES_FILE_NAME = "profiles.json";

    private CliSupport() {
    }

    static Path resolveHostsPath(String override) {
        if (override != null && !override.isBlank()) {
            return Paths.get(override);
        }
        return SiteLockPaths.getHostsPath();
    }

    static Path resolveStateFile(String override) {
        if (override != null && !override.isBlank()) {
            return Paths.get(override);
        }
        return SiteLockPaths.getStateFile();
    }

    static Path resolveBackupFile(String stateFileOverride) {
        if (stateFileOverride != null && !stateFileOverride.isBlank()) {
            Path sf = Paths.get(stateFileOverride);
            return sf.resolveSibling("hosts.backup");
        }
        return SiteLockPaths.getBackupFile();
    }

    // State directory, considering the --state-file override.
    // WHY: without it, a command would read the real ~/.sitelock daemon.json
    // while operating on the test file — same reasoning as DnsDaemonState.stateDirFor.
    static java.nio.file.Path stateDirFor(String stateFileOverride) {
        if (stateFileOverride != null && !stateFileOverride.isBlank()) {
            java.nio.file.Path sf = Paths.get(stateFileOverride);
            java.nio.file.Path parent = sf.getParent();
            return parent != null ? parent : Paths.get(".");
        }
        return SiteLockPaths.getStateDir();
    }

    // Where profiles.json lives: sibling of blocks.json, for the same reason as
    // hosts.backup and hosts.lock — the tests' --state-file points the whole
    // directory, without needing a new flag.
    static Path resolveProfilesFile(String stateFileOverride) {
        if (stateFileOverride != null && !stateFileOverride.isBlank()) {
            return Paths.get(stateFileOverride).resolveSibling(PROFILES_FILE_NAME);
        }
        return SiteLockPaths.getStateDir().resolve(PROFILES_FILE_NAME);
    }

    // WHY: the rest of the project injects Clock instead of calling Instant.now(),
    // so tests can use a controlled time — the command that records an
    // instant has to use the same one.
    static Clock clock() {
        return Clock.systemUTC();
    }

    static BlockService newService(String hostsOverride, String stateOverride) {
        Path hostsPath = resolveHostsPath(hostsOverride);
        Path stateFile = resolveStateFile(stateOverride);
        Path backupFile = resolveBackupFile(stateOverride);
        Path hostsLock = stateFile.resolveSibling("hosts.lock");
        BlockStore store = new BlockStore(stateFile);
        HostsFileBlocker blocker = new HostsFileBlocker(hostsPath, backupFile, hostsLock);
        return new BlockService(store, blocker, Clock.systemUTC(), hostsPath);
    }

    // With the blocker injected, for tests that need the hosts write to
    // fail — it is the only way to prove that the profile apply rollback
    // happens, since nobody is going to make /etc/hosts unwritable in the test.
    static BlockService newService(String hostsOverride, String stateOverride, SiteBlocker blocker) {
        Path stateFile = resolveStateFile(stateOverride);
        return new BlockService(new BlockStore(stateFile), blocker, Clock.systemUTC(),
                resolveHostsPath(hostsOverride));
    }

    // OS-specific elevation hint (from PermissionAdvisor). On Windows
    // instructs "Run as administrator"; on Unix, "sudo".
    static String elevationHint(String subcommand) {
        return new PermissionAdvisor(osType()).elevationHint(SiteLockPaths.getHostsPath(), subcommand);
    }

    // The elevation command, in the vocabulary of each OS: on Unix, sudo with
    // the real binary path (the ~/.local/bin is outside secure_path); on
    // Windows elevation is done by the terminal, and the text comes from
    // PermissionAdvisor.
    static String elevatedCommand(String subcommand) {
        if (osType().isWindows()) {
            return elevationHint(subcommand);
        }
        return sudoCommand(subcommand);
    }

    // The OS of the elevation texts.
    // NOTE: the binary is the same on all three OSes, and the text changes —
    // a "sudo siteblock daemon enable" on Windows does not exist on the
    // reader's machine.
    static OsType osType() {
        return OsType.detect();
    }

    // WHY: kept only for compatibility with existing tests/code — delegates
    // to elevationHint.
    @Deprecated
    static String sudoHint(String subcommand) {
        return elevationHint(subcommand);
    }

    // Builds the hint with sudo using the real path of the binary, because
    // ~/.local/bin is often outside sudo's secure_path. On Windows the
    // "sudo" prefix does not apply, but the method stays for compatibility.
    static String sudoCommand(String subcommand) {
        String bin = System.getProperty("siteblock.bin", "siteblock");
        String display = "/usr/local/bin/siteblock".equals(bin) ? "siteblock" : bin;
        if (display.contains(" ")) {
            display = "\"" + display + "\"";
        }
        String args = subcommand == null || subcommand.isBlank() ? "" : " " + subcommand;
        return "sudo " + display + args;
    }

    // Warns when a time-bound block has no daemon to enforce it: without one the
    // feature is invisible and the deadline only expires when someone opens a
    // terminal. WHY: prints to stdout as "WARNING:" because it is not an error.

    // NOTE: the trigger is only "no daemon alive" — autostart is not checked here.
    static void warnIfNoDaemon(String stateFileOverride) {
        if (isDaemonAlive(stateFileOverride)) {
            return;
        }
        System.out.println("WARNING: this block has a deadline, but no expiration daemon is running.");
        System.out.println("       The deadline is only enforced when you run another siteblock command "
                + "(e.g. 'siteblock status').");
        System.out.println("       To make the daemon run by itself, including after reboot:");
        System.out.println("       " + elevatedCommand("daemon enable"));
    }

    // true if a daemon is registered and alive. Without elevation: it is just reading.
    static boolean isDaemonAlive(String stateFileOverride) {
        try {
            return ExpirationDaemonState.read(stateDirFor(stateFileOverride))
                    .map(ExpirationDaemonState::isAlive)
                    .orElse(false);
        } catch (IOException e) {
            // Not finding the record is the same as having no daemon: the warning serves.
            return false;
        }
    }

    // WHY: a single reader for the whole process, not one per question. The old
    // Scanner swallowed the following answers into its 1 KB buffer, so the second
    // confirmation came back null and the command reported that it did not unblock.

    // NOTE: the reader is rebuilt when System.in changes — this lets a test swap
    // stdin without a stale reader answering for the new one.
    static String readLine() {
        try {
            InputStream current = System.in;
            if (stdin == null || stdinStream != current) {
                stdinStream = current;
                stdin = new BufferedReader(new InputStreamReader(current, StandardCharsets.UTF_8));
            }
            return stdin.readLine();
        } catch (IOException e) {
            return null;
        }
    }

    // The stdin reader of the process.
    private static BufferedReader stdin;

    // Where the reader came from: if System.in changes, the reader swaps too.
    private static InputStream stdinStream;

    // Flushes the DNS cache after a hosts modification — best-effort: never throws
    // an exception nor changes the command's exit code; only warns.
    static void flushDnsBestEffort() {
        try {
            new DnsCacheFlusher().flushBestEffort();
        } catch (Exception ignored) {
        }
    }
}
