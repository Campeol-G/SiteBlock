package com.sitelock.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

// FIXME (2026-10-21, @ChicoMagnusOpus): the real flush execution is not tested
// on CI on every OS (it would require Windows/macOS runners + permissions) — the
// tests cover only the per-OS command selection. Validate manually.
/**
 * DNS cache cleanup after hosts modifications — a best-effort step.
 *
 * <p>A flush failure <b>never</b> prevents the block from being saved: it only produces the
 * {@link PermissionAdvisor#dnsFlushFailureWarning()} warning. Commands are run
 * via {@link ProcessBuilder}, with exit code and stderr captured and a 10s timeout.
 *
 * <ul>
 *   <li>Linux: tries {@code systemd-resolve --flush-caches}; if that fails
 *       (command missing on distros without systemd-resolved), tries
 *       {@code resolvectl flush-caches}. If both fail, it warns that the browser may need
 *       to be restarted.</li>
 *   <li>macOS: {@code dscacheutil -flushcache} + {@code killall -HUP mDNSResponder}.</li>
 *   <li>Windows: {@code ipconfig /flushdns}.</li>
 * </ul>
 */
public final class DnsCacheFlusher {

    /** Runs a command and returns the exit code (injectable for tests). */
    public interface CommandRunner extends com.sitelock.core.CommandRunner {
    }

    public record FlushResult(boolean success, String detail) {
    }

    private static final long TIMEOUT_SECONDS = 10;

    private final OsType osType;
    private final CommandRunner runner;
    private final boolean privileged;

    /** Uses the real OS and runs via {@link ProcessBuilder}. */
    public DnsCacheFlusher() {
        this(OsType.detect(), isPrivilegedProcess(), DnsCacheFlusher::runWithProcessBuilder);
    }

    /** Injects the OS (production with a simulated OS / tests). */
    public DnsCacheFlusher(OsType osType) {
        this(osType, isPrivilegedProcess(), DnsCacheFlusher::runWithProcessBuilder);
    }

    /** Injects the {@code os.name} value (for tests of the three branches). */
    public DnsCacheFlusher(String osName) {
        this(OsType.detect(osName), isPrivilegedProcess(), DnsCacheFlusher::runWithProcessBuilder);
    }

    // Injects OS + runner (tests use a fake, with no real process).
    DnsCacheFlusher(OsType osType, CommandRunner runner) {
        this(osType, isPrivilegedProcess(), runner);
    }

    // Injects OS + privilege + runner. This is the constructor the tests use
    // to prove the rule that matters: without root, nothing is attempted.
    DnsCacheFlusher(OsType osType, boolean privileged, CommandRunner runner) {
        this.osType = osType == null ? OsType.UNKNOWN : osType;
        this.privileged = privileged;
        this.runner = runner == null ? DnsCacheFlusher::runWithProcessBuilder : runner;
    }

    // WHY: on Unix only root can make the resolved flush happen — outside
    // of it, systemd-resolved accepts the call and polkit opens a dialog
    // asking for the password, and an optional step must not block waiting for a password.
    static boolean isPrivilegedProcess() {
        return "root".equals(System.getProperty("user.name", ""));
    }

    /**
     * Whether this process can attempt the flush without opening an authentication dialog.
     * Windows has no polkit: there the flush is always attempted, and it only depends on the
     * process already being an administrator.
     */
    public boolean isPrivileged() {
        return privileged || osType.isWindows();
    }

    // Commands attempted in order for the current OS (exposed for tests).
    List<List<String>> commandsForCurrentOs() {
        return commandsFor(osType);
    }

    // Per-OS command selection — pure, runs nothing.
    static List<List<String>> commandsFor(OsType os) {
        if (os != null && os.isWindows()) {
            return List.of(List.of("ipconfig", "/flushdns"));
        }
        if (os != null && os.isMac()) {
            // Equivalent to: dscacheutil -flushcache && killall -HUP mDNSResponder
            return List.of(List.of("sh", "-c", "dscacheutil -flushcache && killall -HUP mDNSResponder"));
        }
        // Linux and UNKNOWN: tries systemd-resolve, then resolvectl.
        return List.of(
                List.of("systemd-resolve", "--flush-caches"),
                List.of("resolvectl", "flush-caches"));
    }

    /**
     * Runs the flush. Never throws to the CLI caller:
     * returns a {@link FlushResult} and the caller decides whether to log the warning.
     */
    public FlushResult flush() {
        List<List<String>> commands = commandsForCurrentOs();
        if (!isPrivileged()) {
            // Without elevation nothing is attempted: on Linux and macOS the call goes
            // through systemd-resolved/dscacheutil, which open a password prompt
            // (polkit) to complete a step that is optional.
            return new FlushResult(false, "");
        }
        List<String> errors = new ArrayList<>();
        for (List<String> cmd : commands) {
            try {
                int exit = runner.run(cmd);
                if (exit == 0) {
                    return new FlushResult(true, "flush OK via: " + String.join(" ", cmd));
                }
                errors.add(String.join(" ", cmd) + " (exit " + exit + ")");
            } catch (IOException e) {
                // Command missing or failing (e.g. no systemd-resolved) — try the next one.
                errors.add(String.join(" ", cmd) + " (" + e.getMessage() + ")");
            } catch (Exception e) {
                errors.add(String.join(" ", cmd) + " (error: " + e.getMessage() + ")");
            }
        }
        // On Linux, exhausting both variants is still a "warning", not a fatal error.
        return new FlushResult(false, String.join("; ", errors));
    }

    /**
     * Best-effort shortcut for the CLI commands: runs it and, on failure, prints the
     * standard warning to stderr without throwing and without changing the command's exit code.
     *
     * @return true if the flush worked, false if it only warned.
     */
    public boolean flushBestEffort() {
        FlushResult r;
        try {
            r = flush();
        } catch (Exception e) {
            r = new FlushResult(false, String.valueOf(e.getMessage()));
        }
        if (!r.success()) {
            System.err.println("Warning: " + PermissionAdvisor.dnsFlushFailureWarning());
            if (r.detail() != null && !r.detail().isBlank()) {
                System.err.println("(flush DNS: " + r.detail() + ")");
            } else if (!isPrivileged()) {
                // Honest detail: we attempted nothing because elevation is missing. Blaming
                // "Permission denied" here would suggest the block failed, and it
                // did not — whoever runs privileged never reaches this path.
                System.err.println("(flush DNS: only runs with elevation; the block itself was saved.)");
            } else if (osType == OsType.LINUX || osType == OsType.UNKNOWN) {
                System.err.println("(flush DNS: try 'systemd-resolve --flush-caches' or "
                        + "'resolvectl flush-caches'; if missing, restart the browser.)");
            }
        }
        return r.success();
    }

    public OsType getOsType() {
        return osType;
    }

    static int runWithProcessBuilder(List<String> command) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            throw new IOException("command not found: " + command.get(0).toLowerCase(Locale.ROOT), e);
        }
        // Output is drained in parallel with waitFor. Draining after waiting
        // blocks when the command writes more than the pipe buffer.
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        Thread drain = Thread.ofVirtual().name("dns-flush-output").start(() -> {
            try (InputStream in = p.getInputStream()) {
                in.transferTo(buffer);
            } catch (IOException ignored) {
                // The timeout/exit code already says what matters; partial output is a detail.
            }
        });
        boolean done;
        try {
            done = p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            awaitDrain(drain);
            throw new IOException("timeout waiting for DNS flush", e);
        }
        if (!done) {
            p.destroyForcibly();
            awaitDrain(drain);
            throw new IOException("timeout (> " + TIMEOUT_SECONDS + "s) on DNS flush");
        }
        awaitDrain(drain);
        String out = buffer.toString(StandardCharsets.UTF_8);
        int exit = p.exitValue();
        if (exit != 0 && !out.isBlank()) {
            throw new IOException("exit " + exit + ": " + out.strip().split("\n")[0]);
        }
        return exit;
    }

    private static void awaitDrain(Thread drain) {
        try {
            drain.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}