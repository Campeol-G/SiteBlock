package com.sitelock.cli;

import com.sitelock.core.BlockEntry;
import com.sitelock.core.AutostartInstaller;
import com.sitelock.core.BlockStore;
import com.sitelock.core.ExpirationDaemonLock;
import com.sitelock.core.ExpirationDaemonState;
import com.sitelock.core.OsType;
import com.sitelock.core.HostsFileManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The expiration loop and its lifecycle.
 *
 * <p>The test that matters here is
 * {@link #pinnedStateDirIsUsedWhenSudoUserIsAbsent()}: it starts the daemon in a
 * subprocess with {@code SUDO_USER} removed from the environment and {@code user.home}
 * pointing at an empty directory, and asserts that it operated on the pinned
 * {@code stateDir}. A daemon that fell back to {@code user.home} would read root's
 * {@code blocks.json}, find nothing, and every other test would stay green — it is a
 * silent no-op, and no hand-written test would see it.
 */
class DaemonCliTest {

    @TempDir
    Path tmp;

    private Path hosts;

    private Path hosts() throws Exception {
        if (hosts == null) {
            hosts = tmp.resolve("hosts");
            Files.writeString(hosts, "127.0.0.1 localhost\n", StandardCharsets.UTF_8);
        }
        return hosts;
    }

    private Path stateDir() {
        return tmp.resolve("state");
    }

    private Path stateFile() {
        return stateDir().resolve("blocks.json");
    }

    private void seed(Map<String, Instant> entries) throws Exception {
        Files.createDirectories(stateDir());
        Map<String, BlockEntry> state = new TreeMap<>();
        Instant created = Instant.parse("2026-10-15T08:00:00Z");
        for (Map.Entry<String, Instant> e : entries.entrySet()) {
            state.put(e.getKey(), new BlockEntry(e.getKey(), created, e.getValue()));
        }
        new BlockStore(stateFile()).save(state);
    }

    private static Map<String, Instant> deadlines(Object... pairs) {
        Map<String, Instant> m = new TreeMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            m.put((String) pairs[i], (Instant) pairs[i + 1]);
        }
        return m;
    }

    private String state() throws Exception {
        return Files.readString(stateFile(), StandardCharsets.UTF_8);
    }

    private String hostsContent() throws Exception {
        return Files.readString(hosts(), StandardCharsets.UTF_8);
    }

    // ---- running the daemon in a subprocess ----

    private List<String> daemonCommand(String... extras) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(Paths.get(System.getProperty("java.home"), "bin", "java").toString());
        cmd.add("-cp");
        cmd.add(System.getProperty("java.class.path"));
        cmd.add("-Dsitelock.stateDir=" + stateDir());
        cmd.add("-Dsitelock.hostsFile=" + hosts());
        cmd.add("-Duser.home=" + tmp.resolve("empty-home"));
        cmd.add("-Dsiteblock.user=" + System.getProperty("user.name", ""));
        // The test log is read as UTF-8; on Windows the process default is the
        // native code page, which breaks the "next turn" read.
        cmd.add("-Dstdout.encoding=UTF-8");
        cmd.add("-Dstderr.encoding=UTF-8");
        cmd.add("com.sitelock.SiteLockApplication");
        cmd.add("daemon");
        cmd.add("__run-daemon");
        for (String e : extras) {
            cmd.add(e);
        }
        return cmd;
    }

    private Process start(String... extras) throws Exception {
        Files.createDirectories(tmp.resolve("empty-home"));
        ProcessBuilder pb = new ProcessBuilder(daemonCommand(extras));
        // No SUDO_USER: this is what systemd, launchd, and Task Scheduler do.
        pb.environment().remove("SUDO_USER");
        pb.redirectErrorStream(true);
        // Output goes to a file, not to a pipe: a loop daemon never closes stdout,
        // and reading the stream to EOF would hang the test forever.
        log = tmp.resolve("daemon-output.log");
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
        return pb.start();
    }

    private Path log;

    private String daemonOutput() {
        try {
            return Files.exists(log) ? Files.readString(log, StandardCharsets.UTF_8) : "";
        } catch (Exception e) {
            return "";
        }
    }

    // ---- the tests ----

    @Test
    void pinnedStateDirIsUsedWhenSudoUserIsAbsent() throws Exception {
        HostsFileManager.blockInFile(hosts(), tmp.resolve("hosts.backup"), tmp.resolve("hosts.lock"),
                List.of("expired.com"));
        seed(deadlines("expired.com", Instant.parse("2020-01-01T00:00:00Z")));

        Process p = start("--once");
        int exit = p.waitFor();
        String output = daemonOutput();

        assertEquals(0, exit, "the daemon failed:\n" + output);
        assertFalse(hostsContent().contains("expired.com"),
                "the pinned hosts file should have lost the domain — if it did not, the daemon "
                        + "read user.home instead of the stateDir:\n" + output);
        assertFalse(state().contains("expired.com"), "the pinned state should have lost the domain");
    }

    @Test
    void onceSweepRemovesNothingWhenNothingExpired() throws Exception {
        HostsFileManager.blockInFile(hosts(), tmp.resolve("hosts.backup"), tmp.resolve("hosts.lock"),
                List.of("permanent.com"));
        seed(deadlines("permanent.com", null));
        String before = hostsContent();

        Process p = start("--once");
        int exit = p.waitFor();
        String output = daemonOutput();

        assertEquals(0, exit, output);
        assertEquals(before, hostsContent(), "a permanent block must not be touched");
        assertTrue(state().contains("permanent.com"));
    }

    @Test
    void childWritesItsOwnStateFile() throws Exception {
        seed(deadlines("permanent.com", null));
        Files.createDirectories(tmp.resolve("empty-home"));

        Process p = start("--interval", "1");
        Path daemonJson = ExpirationDaemonState.statePath(stateDir());

        try {
            ExpirationDaemonState state = null;
            for (int i = 0; i < 100 && state == null; i++) {
                Thread.sleep(100);
                if (Files.exists(daemonJson)) {
                    state = ExpirationDaemonState.read(stateDir()).orElse(null);
                }
            }
            assertTrue(state != null,
                "the daemon must write its own daemon.json — under autostart there is no parent "
                        + "to write it for the child");
            assertEquals(p.pid(), state.getPid(), "the recorded PID must be the daemon's own");
            assertEquals(stateDir().toString(), state.getStateDir(),
                    "the recorded stateDir must be the pinned one, not the one from user.home");
        } finally {
            p.destroyForcibly();
            p.waitFor();
        }

        // WARNING: destroyForcibly is SIGKILL, the shutdown hook does NOT run, and the
        // orphan record is left behind — that is why the single-instance lock is a
        // FileLock and not "the PID exists".
        if (Files.exists(daemonJson)) {
            assertFalse(ExpirationDaemonState.isAlive(
                            ExpirationDaemonState.read(stateDir()).orElse(null)),
                    "a record of a dead process must be recognized as dead");
        }
    }

    @Test
    void concurrentBlocksSurviveADaemonSweep() throws Exception {
        // WHY: without the transaction in BlockStore, the sweep writes the state it
        // read earlier and the concurrent block disappears from the JSON — it stays stuck in
        // the hosts file with no record, and the sweep never repairs it because it walks the JSON.
        seed(deadlines("expired.com", Instant.parse("2020-01-01T00:00:00Z")));

        Process daemon = start("--interval", "1");
        try {
            for (int i = 0; i < 100 && !Files.exists(ExpirationDaemonState.statePath(stateDir())); i++) {
                Thread.sleep(100);
            }
            assertTrue(daemon.isAlive(), "the daemon did not start");

            for (int i = 0; i < 5; i++) {
                assertEquals(0, new BlockCommand(List.of("concurrent" + i + ".com"), null,
                        hosts().toString(), stateFile().toString()).call());
                Thread.sleep(150);
            }

            // Let the sweep complete a few more times.
            Thread.sleep(1500);

            String state = Files.readString(stateFile(), StandardCharsets.UTF_8);
            for (int i = 0; i < 5; i++) {
                assertTrue(state.contains("concurrent" + i + ".com"),
                        "concurrent block " + i + " was erased by the sweep:\n" + state);
            }
            assertFalse(state.contains("expired.com"), "the expired entry must be gone");
        } finally {
            daemon.destroyForcibly();
            daemon.waitFor();
        }
    }

    @Test
    void theLoopSurvivesCorruptedState() throws Exception {
        // The test needs the LOOP, not --once: the --once path exits with 1
        // and never iterates, so it proves nothing about "the daemon comes back".
        Files.createDirectories(stateDir());
        Files.writeString(stateFile(), "{ not json", StandardCharsets.UTF_8);

        Process p = start("--interval", "1");
        try {
            String accumulated = "";
            for (int i = 0; i < 40 && !accumulated.contains("corrupted"); i++) {
                Thread.sleep(100);
                accumulated = daemonOutput();
            }
            assertTrue(p.isAlive(), "the daemon must not die because of corrupted state — "
                    + "it is the one that keeps warning:\n" + accumulated);
            assertTrue(accumulated.toLowerCase().contains("corrupted"), accumulated);
            assertTrue(accumulated.contains("next turn"),
                    "it must say that it will try again: " + accumulated);
        } finally {
            p.destroyForcibly();
            p.waitFor();
        }
    }

    @Test
    void onceSweepFailsLoudlyOnCorruptedState() throws Exception {
        Files.createDirectories(stateDir());
        Files.writeString(stateFile(), "{ not json", StandardCharsets.UTF_8);

        Process p = start("--once");
        int exit = p.waitFor();
        String output = daemonOutput();

        assertEquals(1, exit, "corrupted state is an error: " + output);
        assertTrue(output.toLowerCase().contains("corrupted"), output);
        assertFalse(output.contains("Exception in thread"), "it must not dump a stack trace:\n" + output);
    }

    @Test
    void theLoopHoldsTheLockWhileAlive() throws Exception {
        // --once holds no lock by design, so measuring "lock released" after it
        // measures nothing. What matters is the LOOP holding the lock, which is what
        // prevents two instances.
        seed(deadlines("permanent.com", null));
        Process p = start("--interval", "1");
        try {
            Path daemonJson = ExpirationDaemonState.statePath(stateDir());
            for (int i = 0; i < 100 && !Files.exists(daemonJson); i++) {
                Thread.sleep(100);
            }
            IOException error = assertThrows(IOException.class,
                    () -> ExpirationDaemonLock.acquire(stateDir()),
                    "while the daemon is alive, no other instance may take the lock");
            assertTrue(error.getMessage().contains("already running"), error.getMessage());
        } finally {
            p.destroyForcibly();
            p.waitFor();
        }
    }

    @Test
    void lockIsReleasedWhenTheProcessDies() throws Exception {
        seed(deadlines("permanent.com", null));
        Process p = start("--interval", "1");
        Path daemonJson = ExpirationDaemonState.statePath(stateDir());
        for (int i = 0; i < 100 && !Files.exists(daemonJson); i++) {
            Thread.sleep(100);
        }
        p.destroyForcibly();
        p.waitFor();

        // The OS releases the FileLock when the process dies; that is why the lock is a
        // FileLock and not "the PID exists".
        ExpirationDaemonLock lock = ExpirationDaemonLock.acquire(stateDir());
        assertTrue(lock != null);
        lock.close();
    }

    // WARNING: Windows has no SIGTERM. Process.destroy() calls TerminateProcess
    // and does NOT run shutdown hooks; the hook path can only be exercised on Unix.
    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void sigtermRunsFinalSweepBeforeExit() throws Exception {
        // The daemon is sleeping for 60s. The first expired entry proves that the initial
        // sweep finished (and the loop went to sleep); then the second one is seeded, and only
        // the shutdown sweep can remove it.
        HostsFileManager.blockInFile(hosts(), tmp.resolve("hosts.backup"),
                tmp.resolve("hosts.lock"), List.of("initial.com"));
        seed(deadlines("initial.com", Instant.parse("2020-01-01T00:00:00Z")));

        Process p = start("--interval", "60");
        Path daemonJson = ExpirationDaemonState.statePath(stateDir());
        int exit = -1;
        try {
            for (int i = 0; i < 100 && !Files.exists(daemonJson); i++) {
                Thread.sleep(100);
            }
            assertTrue(Files.exists(daemonJson), "the daemon did not record its own state");

            for (int i = 0; i < 100 && state().contains("initial.com"); i++) {
                Thread.sleep(100);
            }
            assertFalse(state().contains("initial.com"),
                    "the initial sweep did not finish; the test cannot isolate the hook");
            Thread.sleep(200); // let the loop enter the long sleep

            seed(deadlines("expired.com", Instant.parse("2020-01-01T00:00:00Z")));
            HostsFileManager.blockInFile(hosts(), tmp.resolve("hosts.backup"),
                    tmp.resolve("hosts.lock"), List.of("expired.com"));

            assertTrue(p.isAlive(), "the daemon died before SIGTERM:\n" + daemonOutput());
            p.destroy(); // SIGTERM: triggers the shutdown hook
            exit = p.waitFor();
        } finally {
            if (p.isAlive()) {
                p.destroyForcibly();
                p.waitFor();
            }
        }

        assertFalse(hostsContent().contains("expired.com"),
                "the shutdown hook must release the expired entry from the hosts file (exit=" + exit + "):\n"
                        + hostsContent()
                        + "\n--- state ---\n" + state()
                        + "\n--- daemon ---\n" + daemonOutput());
        assertFalse(state().contains("expired.com"),
                "the shutdown hook must release the expired entry from the state:\n" + state()
                        + "\n--- daemon ---\n" + daemonOutput());
    }

    // ---- lifecycle commands ----

    @Test
    void startRefusesWithoutRoot() throws Exception {
        Path notWritable = tmp.resolve("no-parent/hosts");
        seed(deadlines("permanent.com", null));

        Capture output = captureErr(() -> new DaemonStartCommand("60", notWritable.toString(),
                stateFile().toString()).call());

        assertEquals(1, output.exit());
        assertTrue(output.text().contains("sudo") || output.text().toLowerCase().contains("permission"),
                output.text());
        assertFalse(Files.exists(ExpirationDaemonState.statePath(stateDir())),
                "it must not have forked a child that could not write to the hosts file");
    }

    @Test
    void startRefusesWhenDaemonAlreadyRunning() throws Exception {
        seed(deadlines("permanent.com", null));
        ExpirationDaemonState.write(stateDir(), new ExpirationDaemonState(
                ProcessHandle.current().pid(), Instant.now(), stateDir().toString(), null));

        int exit = new DaemonStartCommand("60", hosts().toString(), stateFile().toString()).call();

        assertEquals(1, exit);
    }

    @Test
    void staleRegistrationIsDiscardedInsteadOfBlockingForever() throws Exception {
        // A PID that does not exist plus a record on disk: without this, start refuses
        // forever, and there is no --force to unlock it.
        seed(deadlines("permanent.com", null));
        ExpirationDaemonState.write(stateDir(), new ExpirationDaemonState(
                Integer.MAX_VALUE + 7, Instant.now(), stateDir().toString(), null));

        assertTrue(DaemonStartCommand.discardOrphanRecord(stateDir()));
        assertFalse(Files.exists(ExpirationDaemonState.statePath(stateDir())));
    }

    @Test
    void liveRegistrationIsNeverDiscarded() throws Exception {
        seed(deadlines("permanent.com", null));
        ExpirationDaemonState.write(stateDir(), new ExpirationDaemonState(
                ProcessHandle.current().pid(), Instant.now(), stateDir().toString(), null));

        assertFalse(DaemonStartCommand.discardOrphanRecord(stateDir()),
                "a record of a live process must not be deleted");
        assertTrue(Files.exists(ExpirationDaemonState.statePath(stateDir())));
    }

    @Test
    void statusReportsNoDaemonWhenPidFileIsMissing() throws Exception {
        Capture output = captureOut(() -> new DaemonStatusCommand(stateFile().toString()).call());
        assertEquals(0, output.exit());
        assertTrue(output.text().contains("not running"), output.text());
    }

    // On Windows autostart is a Task Scheduler job and isEnabled queries the real
    // schtasks; it is not possible to seed the artifact like here.
    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void statusSaysTheInstalledJarIsMissing() throws Exception {
        // The symptom of mvn clean: the unit points at a jar that no longer
        // exists. Without this line, status would only say "not running" and the
        // user would have no place to start.
        seed(deadlines("permanent.com", null));
        // Temporary root: the test cannot touch the real /opt, which belongs to
        // root — and the install directory does not even exist there.
        OsType os = OsType.detect();
        Path root = tmp.resolve("sys");
        Path jar = AutostartInstaller.installJarPath(os, root);
        Files.createDirectories(jar.getParent());
        Files.writeString(jar, "jar");
        Files.delete(jar);
        // The unit must exist: the jar warning only appears when autostart is
        // installed — otherwise there is no daemon to complain about.
        Path unit = AutostartInstaller.artifactPath(os, root);
        Files.createDirectories(unit.getParent());
        Files.writeString(unit, "[Unit]\n");
        try {
            Capture r = captureOut(() -> new DaemonStatusCommand(stateFile().toString(), root).call());
            assertTrue(r.text().contains("DOES NOT EXIST") || r.text().contains("no longer exists"),
                    r.text());
            assertTrue(r.text().contains("daemon enable"),
                    "it must say how to fix it: " + r.text());
        } finally {
            Files.deleteIfExists(jar);
            Files.deleteIfExists(unit);
        }
    }

    @Test
    void statusNeverRepeatsSudo() throws Exception {
        seed(deadlines("permanent.com", null));
        Capture r = captureOut(() -> new DaemonStatusCommand(stateFile().toString()).call());
        assertFalse(r.text().contains("sudo sudo"),
                "the output must not repeat sudo:\n" + r.text());
    }

    @Test
    void statusWorksWithoutRoot() throws Exception {
        seed(deadlines("permanent.com", null));
        Instant sweep = Instant.parse("2026-10-15T11:00:00Z");
        ExpirationDaemonState.write(stateDir(), new ExpirationDaemonState(
                ProcessHandle.current().pid(), Instant.now(), stateDir().toString(), sweep));

        Capture output = captureOut(() -> new DaemonStatusCommand(stateFile().toString()).call());

        assertEquals(0, output.exit());
        assertTrue(output.text().contains(String.valueOf(ProcessHandle.current().pid())), output.text());
        // The instant is stored in UTC and shown in the machine's time zone: the test
        // formats it the same way instead of pinning "11:00".
        String expected = DateTimeFormatter.ofPattern("dd/MM HH:mm")
                .withZone(ZoneId.systemDefault()).format(sweep);
        assertTrue(output.text().contains(expected), "expected '" + expected + "':\n" + output.text());
    }

    @Test
    void stopReportsWhenNoDaemonIsRunning() {
        int exit = new DaemonStopCommand(stateFile().toString()).call();
        assertEquals(1, exit);
    }

    // ---- output capture ----

    private record Capture(int exit, String text) {
    }

    private static Capture captureOut(IntCall r) throws Exception {
        return capture(r, false);
    }

    private static Capture captureErr(IntCall r) throws Exception {
        return capture(r, true);
    }

    private static Capture capture(IntCall r, boolean stderr) throws Exception {
        PrintStream original = stderr ? System.err : System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream fake = new PrintStream(buffer, true, StandardCharsets.UTF_8);
        if (stderr) {
            System.setErr(fake);
        } else {
            System.setOut(fake);
        }
        int exit;
        try {
            exit = r.run();
        } finally {
            if (stderr) {
                System.setErr(original);
            } else {
                System.setOut(original);
            }
        }
        return new Capture(exit, buffer.toString(StandardCharsets.UTF_8));
    }

    @FunctionalInterface
    private interface IntCall {
        int run() throws Exception;
    }
}
