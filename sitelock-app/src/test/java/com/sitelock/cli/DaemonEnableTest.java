package com.sitelock.cli;

import com.sitelock.core.AutostartArtifact;
import com.sitelock.core.AutostartInstaller;
import com.sitelock.core.AutostartPaths;
import com.sitelock.core.CommandRunner;
import com.sitelock.core.OsType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * daemon enable/disable — installing at boot.
 *
 * <p>Testing the scheduler is impossible: CI runs on Linux and Windows, never on
 * macOS, and nothing here has systemd. What can be tested, and where the bug
 * lives, is the <b>generated artifact</b> and the refusals — without root,
 * without systemd, and the warning about a path writable by the user.
 */
class DaemonEnableTest {

    @TempDir
    Path tmp;

    private final List<List<String>> commands = new ArrayList<>();
    private final CommandRunner runner = c -> {
        commands.add(List.copyOf(c));
        return 0;
    };

    // Exit code + stdout/stderr of a command.
    private record Result(int exit, String text) {
    }

    private Result run(java.util.concurrent.Callable<Integer> cmd) {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream fake = new PrintStream(buffer, true, StandardCharsets.UTF_8);
        System.setOut(fake);
        System.setErr(fake);
        int exit;
        try {
            exit = cmd.call();
        } catch (Exception e) {
            throw new AssertionError(e);
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
        return new Result(exit, buffer.toString(StandardCharsets.UTF_8));
    }

    private Path root() {
        return tmp.resolve("sys");
    }

    // A real jar: enable copies the file, and copying a missing file fails.
    private Path sampleJar() throws Exception {
        Path jar = tmp.resolve("build/siteblock-1.0.0.jar");
        Files.createDirectories(jar.getParent());
        Files.writeString(jar, "fake jar");
        return jar;
    }

    private Path artifact(OsType os) {
        return AutostartInstaller.artifactPath(os, root());
    }

    private DaemonEnableCommand enable(OsType os, String jarPath) {
        // Pins systemd as present: the test describes the artifact, and on CI's
        // macOS the real detection does not find /run/systemd/system.
        return new DaemonEnableCommand(os, root(), jarPath, runner, true);
    }

    @Test
    void enableWritesTheArtifactAndCallsTheScheduler() throws Exception {
        Result r = run(enable(OsType.LINUX, sampleJar().toString())::call);

        assertEquals(0, r.exit(), r.text());
        assertTrue(Files.exists(artifact(OsType.LINUX)), "the unit must exist after enable");
        String unit = read(artifact(OsType.LINUX));
        assertTrue(unit.contains("ExecStart="), unit);
        assertTrue(unit.contains("__run-daemon"), unit);
        assertTrue(commands.stream().anyMatch(c -> c.get(0).equals("systemctl")
                && c.contains("enable") && c.contains("--now")), commands.toString());
    }

    @Test
    void enableEmbedsThePinnedPaths() throws Exception {
        run(enable(OsType.LINUX, sampleJar().toString())::call);

        String unit = read(artifact(OsType.LINUX));
        assertTrue(unit.contains("-Dsitelock.stateDir="), unit);
        assertTrue(unit.contains("-Dsitelock.hostsFile="), unit);
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void enableRefusesWithoutRootAndSaysHow() throws Exception {
        // A directory that exists but is not writable is the real case: /etc is
        // root-owned. POSIX chmod does not exist on Windows, which also runs
        // CI — there the test is skipped, not green without verification.
        Assumptions.assumeFalse("root".equals(System.getProperty("user.name")),
                "root ignores permissions: the unwritable directory would still be writable");
        Path unwritable = tmp.resolve("unwritable");
        // Only `etc` exists and is read-only: that is what the write check
        // finds while walking up from etc/systemd/system.
        Files.createDirectories(unwritable.resolve("etc"));
        Files.setPosixFilePermissions(unwritable.resolve("etc"),
                PosixFilePermissions.fromString("r-xr-xr-x"));

        Result r = run(new DaemonEnableCommand(OsType.LINUX, tmp.resolve("unwritable"),
                sampleJar().toString(), runner, true)::call);

        assertEquals(1, r.exit(), r.text());
        assertTrue(r.text().toLowerCase().contains("sudo")
                        || r.text().toLowerCase().contains("administrator"),
                "the output must say how to elevate: " + r.text());
        assertTrue(commands.isEmpty(), "it must not call the scheduler: " + commands);
    }

    @Test
    void enableOnWindowsDoesNotThrow() throws Exception {
        // Windows is supported by the project, and enable used to throw an NPE:
        // artifactPath returns null there, and the elevation check dereferenced it.
        Result r = run(enable(OsType.WINDOWS, sampleJar().toString())::call);

        assertEquals(0, r.exit(), r.text());
        assertTrue(commands.stream().anyMatch(c -> c.get(0).equals("schtasks")
                && c.contains("/create")), commands.toString());
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void enableOnWindowsRefusesWithoutElevationWhenTheJarDirectoryIsNotWritable() throws Exception {
        // On Windows there is no artifact file: what must be writable is the
        // directory where the jar is installed.
        Assumptions.assumeFalse("root".equals(System.getProperty("user.name")),
                "root ignores permissions: the unwritable directory would still be writable");
        Path windowsRoot = tmp.resolve("sys-win");
        Path dir = windowsRoot.resolve("ProgramData/SiteBlock");
        Files.createDirectories(dir);
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("r-xr-xr-x"));

        Result r = run(new DaemonEnableCommand(OsType.WINDOWS, windowsRoot,
                sampleJar().toString(), runner)::call);

        assertEquals(1, r.exit(), r.text());
        assertTrue(r.text().toLowerCase().contains("sudo")
                        || r.text().toLowerCase().contains("administrator"),
                "the output must say how to elevate: " + r.text());
        assertTrue(commands.isEmpty(), "it must not call the scheduler: " + commands);
    }

    @Test
    void linuxWithoutSystemdPrintsManualInstructions() throws Exception {
        DaemonEnableCommand cmd = new DaemonEnableCommand(OsType.LINUX, root(),
                sampleJar().toString(), runner, false);

        Result r = run(cmd::call);

        assertEquals(1, r.exit(), r.text());
        assertFalse(Files.exists(artifact(OsType.LINUX)),
                "without systemd there is nothing to write — and it cannot pretend it worked");
        assertTrue(commands.isEmpty(), "it must not call systemctl where it does not exist: " + commands);
        assertTrue(r.text().contains("daemon start"),
                "it must offer the manual path: " + r.text());
    }

    @Test
    void enableInstallsTheJarInAStableLocationAndPointsThere() throws Exception {
        Path source = sampleJar();
        Result r = run(enable(OsType.LINUX, source.toString())::call);

        assertEquals(0, r.exit(), r.text());
        Path stable = AutostartInstaller.installJarPath(OsType.LINUX, root());
        assertTrue(Files.exists(stable), "the jar must be at " + stable);
        assertTrue(r.text().contains(stable.toString()), r.text());

        String unit = read(AutostartInstaller.artifactPath(OsType.LINUX, root()));
        assertTrue(unit.contains(stable.toString()),
                "the unit must point at the stable copy: " + unit);
        assertFalse(unit.contains(source.toString()),
                "the unit must not point at the build jar — that is what mvn clean deletes: " + unit);
    }

    @Test
    void enableRefreshesTheInstalledCopyOnASecondRun() throws Exception {
        Path source = sampleJar();
        assertEquals(0, run(enable(OsType.LINUX, source.toString())::call).exit());

        Files.writeString(source, "new version", StandardCharsets.UTF_8);
        assertEquals(0, run(enable(OsType.LINUX, source.toString())::call).exit());

        assertEquals("new version", Files.readString(
                AutostartInstaller.installJarPath(OsType.LINUX, root()), StandardCharsets.UTF_8),
                "reinstalling must update the copy — that is how the daemon runs the new version");
    }

    @Test
    void theOutputNeverRepeatsSudo() throws Exception {
        // sudoCommand() already includes "sudo"; prefixing it again produced
        // "sudo sudo siteblock daemon enable", which is a command that does not exist.
        Result r = run(enable(OsType.LINUX, sampleJar().toString())::call);

        assertFalse(r.text().contains("sudo sudo"),
                "the output must not repeat sudo:\n" + r.text());
        assertTrue(r.text().contains("daemon enable"),
                "the output must show the command:\n" + r.text());
        // On Windows, enable simulated as Linux uses the host hint, which is
        // "Administrator"; the exact Unix command is only valid on Unix.
        if (!OsType.detect().isWindows()) {
            assertTrue(r.text().contains("sudo siteblock daemon enable"),
                    "the output must show the command:\n" + r.text());
        }
    }

    @Test
    void theOutputIsShort() throws Exception {
        // Three lines: what happened, where the jar went, and what to do after
        // rebuilding. The artifact path is internal and helps no one.
        Result r = run(enable(OsType.LINUX, sampleJar().toString())::call);

        // The directory owner warning is conditional: in production /opt/sitelock
        // is created by root and it does not appear. In tests the directory belongs
        // to the user, so it appears — and that is why it is left out of the count.
        long lines = r.text().strip().lines()
                .filter(l -> !l.startsWith("Warning:"))
                .count();
        assertTrue(lines <= 3,
                "the enable output must not have more than 3 lines:\n" + r.text());
        assertFalse(r.text().contains("Artifact:"),
                "the artifact path is internal:\n" + r.text());
    }

    @Test
    void enableFailsClearlyWhenTheSourceJarIsMissing() {
        Result r = run(enable(OsType.LINUX, tmp.resolve("missing.jar").toString())::call);

        assertEquals(1, r.exit(), r.text());
        assertTrue(r.text().contains("missing.jar"), r.text());
        assertTrue(r.text().contains("mvn package"), "it must say how to build the jar: " + r.text());
    }

    @Test
    void theUnitPointsAtTheJarPathAndNotAtASentence() throws Exception {
        // Running enable from the ALREADY INSTALLED jar is the natural way to
        // update the version — and it is what generated the invalid ExecStart: the
        // detail() from installJar was a sentence, and the unit started pointing at it.
        Path source = sampleJar();
        assertEquals(0, run(enable(OsType.LINUX, source.toString())::call).exit());

        Path installed = AutostartInstaller.installJarPath(OsType.LINUX, root());
        Result r = run(enable(OsType.LINUX, installed.toString())::call);

        assertEquals(0, r.exit(), r.text());
        String unit = read(artifact(OsType.LINUX));
        assertEquals(installed.toString(), jarPathInUnit(unit),
                "the unit must point at the jar: " + unit);
        assertFalse(unit.contains("The jar is already in place"),
                "the unit must not point at a sentence: " + unit);
        assertTrue(r.text().contains("Jar: " + installed), r.text());
    }

    @Test
    void disableRemovesTheArtifact() throws Exception {
        run(enable(OsType.LINUX, sampleJar().toString())::call);
        assertTrue(Files.exists(artifact(OsType.LINUX)));

        Result r = run(new DaemonDisableCommand(OsType.LINUX, root(), runner)::call);

        assertEquals(0, r.exit(), r.text());
        assertFalse(Files.exists(artifact(OsType.LINUX)));
        assertTrue(commands.stream().anyMatch(c -> c.get(0).equals("systemctl")
                && c.contains("disable")), commands.toString());
    }

    @Test
    void disableWithoutInstallIsNotAnError() {
        Result r = run(new DaemonDisableCommand(OsType.LINUX, root(), runner)::call);
        assertEquals(0, r.exit(), r.text());
    }

    // WARNING: on Windows there is no root uid and no chown — what protects the
    // jar is the folder's permission, and the file owner does not say who can
    // write to it. The instruction "Run: sudo chown root ..." is impossible to follow.
    /**
     * The directory owner warning must not ask for {@code chown} on Windows.
     */
    @Test
    void onWindowsTheOwnerWarningDoesNotAskForChown() throws Exception {
        Result r = run(enable(OsType.WINDOWS, sampleJar().toString())::call);

        assertEquals(0, r.exit(), r.text());
        assertFalse(r.text().contains("chown"),
                "on Windows there is no chown and no root: " + r.text());
    }

    @Test
    void macOsArtifactIsAPlist() throws Exception {
        run(enable(OsType.MACOS, sampleJar().toString())::call);

        assertTrue(Files.exists(artifact(OsType.MACOS)));
        String plist = read(artifact(OsType.MACOS));
        assertTrue(plist.contains("com.sitelock.sweeper"), plist);
        assertTrue(plist.contains("<key>RunAtLoad</key>"), plist);
        assertTrue(commands.stream().anyMatch(c -> c.get(0).equals("launchctl")), commands.toString());
    }

    @Test
    void artifactCommandLineAlwaysEndsWithTheDaemonInvocation() {
        AutostartPaths p = new AutostartPaths(Path.of("/home/chico/.sitelock"), Path.of("/etc/hosts"),
                "/usr/bin/java", "/opt/sitelock.jar", "/opt/classes", "chico", OsType.LINUX);
        List<String> cmd = AutostartArtifact.commandLine(p);

        assertEquals("daemon", cmd.get(cmd.size() - 4));
        assertEquals("__run-daemon", cmd.get(cmd.size() - 3));
        assertTrue(cmd.contains("-Dsitelock.user=chico"), cmd.toString());
    }

    // The jar path on the unit's ExecStart line.
    private static String jarPathInUnit(String unit) {
        for (String line : unit.split("\n")) {
            if (!line.startsWith("ExecStart=")) {
                continue;
            }
            int i = line.indexOf("-jar ");
            if (i < 0) {
                continue;
            }
            String rest = line.substring(i + "-jar ".length()).trim();
            if (rest.startsWith("\"")) {
                return rest.substring(1, rest.indexOf('"', 1));
            }
            return rest.split("\\s+")[0];
        }
        throw new AssertionError("the unit has no -jar:\n" + unit);
    }

    private static String read(Path p) {
        try {
            return Files.readString(p);
        } catch (Exception e) {
            throw new AssertionError("could not read " + p, e);
        }
    }
}
