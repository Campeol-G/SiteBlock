package com.sitelock.cli;

import com.sitelock.core.CommandRunner;
import com.sitelock.core.ExpirationDaemonState;
import com.sitelock.core.OsType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The elevation command matches the reader's OS.
 *
 * <p>On Windows there is no sudo: elevation is done by the terminal, opened as
 * Administrator. A "sudo siteblock daemon enable" is a command that does not
 * exist on the reader's machine — and text pointing to the wrong path is worse
 * than missing text.
 *
 * <p>The OS is simulated by the {@code os.name} property, which is where the
 * project's detection comes from.
 */
class ElevationCommandsTest {

    @TempDir
    Path tmp;

    private String savedOsName;
    private String savedJar;

    @BeforeEach
    void saveProperties() {
        savedOsName = System.getProperty("os.name");
        savedJar = System.getProperty("siteblock.jar");
    }

    @AfterEach
    void restoreProperties() {
        System.setProperty("os.name", savedOsName);
        if (savedJar == null) {
            System.clearProperty("siteblock.jar");
        } else {
            System.setProperty("siteblock.jar", savedJar);
        }
    }

    @Test
    void statusDoesNotSendSudoOnWindows() throws Exception {
        System.setProperty("os.name", "Windows 11");
        Path hosts = tmp.resolve("hosts");
        Files.writeString(hosts, "127.0.0.1 localhost\n", StandardCharsets.UTF_8);
        Path stateFile = tmp.resolve("state/blocks.json");
        Files.createDirectories(stateFile.getParent());
        assertEquals(0, new BlockCommand(List.of("tiktok.com"), "4h",
                hosts.toString(), stateFile.toString()).call());

        String output = capture(() -> new StatusCommand(hosts.toString(), stateFile.toString()).call());

        assertFalse(output.contains("sudo"), "on Windows there is no sudo: " + output);
        assertTrue(output.contains("daemon enable"), output);
        assertTrue(output.contains("Administrator"), output);
    }

    @Test
    void daemonStatusDoesNotSendSudoOnWindows() {
        System.setProperty("os.name", "Windows 11");
        Path stateFile = tmp.resolve("state/blocks.json");

        String output = capture(() -> new DaemonStatusCommand(stateFile.toString(),
                tmp.resolve("sys")).call());

        assertFalse(output.contains("sudo"), "on Windows there is no sudo: " + output);
        assertTrue(output.contains("daemon enable"), output);
        assertTrue(output.contains("Administrator"), output);
    }

    @Test
    void daemonStartDoesNotSendSudoOnWindows() throws Exception {
        System.setProperty("os.name", "Windows 11");
        Path hosts = tmp.resolve("hosts");
        Files.writeString(hosts, "127.0.0.1 localhost\n", StandardCharsets.UTF_8);
        Path state = tmp.resolve("state");
        Files.createDirectories(state);
        // The daemon warning only appears when the jar is real: it is the
        // path the daemon executes, and the text points to the right command.
        Path jarPath = buildJar();
        System.setProperty("siteblock.jar", jarPath.toString());

        String output;
        try {
            output = capture(() -> new DaemonStartCommand("60", hosts.toString(),
                    state.resolve("blocks.json").toString()).call());
        } finally {
            killDaemon(state);
            try {
                Thread.sleep(500);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            // On Windows the child JVM can keep the jar memory-mapped for a
            // moment after exit; @TempDir cleanup would otherwise fail. Delete
            // it here with retries and ignore any leftover.
            deleteQuietlyWithRetry(jarPath);
        }

        assertFalse(output.contains("sudo"), "on Windows there is no sudo: " + output);
        assertTrue(output.contains("daemon enable"), output);
        assertTrue(output.contains("Administrator"), output);
    }

    // NOTE: the installer is forced to Linux on purpose — on Windows the
    // autostart is a Scheduler Task, which does not exist on this machine.
    // The elevation text comes from the detected OS, which is what the test
    // measures.
    /**
     * {@code daemon disable} only mentions the daemon that keeps running when
     * autostart existed.
     */
    @Test
    void daemonDisableDoesNotSendSudoOnWindows() throws Exception {
        System.setProperty("os.name", "Windows 11");
        Path root = tmp.resolve("sys");
        CommandRunner runner = c -> 0;
        // systemd pinned as present: the macOS CI does not have /run/systemd/system.
        assertEquals(0, new DaemonEnableCommand(OsType.LINUX, root,
                exampleJar().toString(), runner, true).call());

        String output = capture(() -> new DaemonDisableCommand(OsType.LINUX, root, runner).call());

        assertFalse(output.contains("sudo"), "on Windows there is no sudo: " + output);
        assertTrue(output.contains("daemon stop"), output);
        assertTrue(output.contains("Administrator"), output);
    }

    @Test
    void daemonEnableDoesNotSendSudoOnWindows() throws Exception {
        System.setProperty("os.name", "Windows 11");
        Path root = tmp.resolve("sys");
        CommandRunner runner = c -> 0;

        String installed = capture(() -> new DaemonEnableCommand(OsType.WINDOWS, root,
                exampleJar().toString(), runner).call());

        assertFalse(installed.contains("sudo"), "on Windows there is no sudo: " + installed);
        assertTrue(installed.contains("daemon enable"), installed);
        assertTrue(installed.contains("Administrator"), installed);

        // And the other side: without a scheduler, the manual path also cannot
        // be a sudo.
        String withoutScheduler = capture(() -> new DaemonEnableCommand(OsType.WINDOWS, root,
                exampleJar().toString(), runner, false).call());

        assertFalse(withoutScheduler.contains("sudo"), "on Windows there is no sudo: " + withoutScheduler);
        assertTrue(withoutScheduler.contains("daemon start"), withoutScheduler);
        assertTrue(withoutScheduler.contains("Administrator"), withoutScheduler);
    }

    private Path exampleJar() throws Exception {
        Path jar = tmp.resolve("build/siteblock-1.0.0.jar");
        Files.createDirectories(jar.getParent());
        Files.writeString(jar, "fake jar");
        return jar;
    }

    // A real jar, with the Main-Class manifest attribute and the full classpath
    // pointed to by the Class-Path attribute — the daemon starts with it, as it
    // does in production.
    private Path buildJar() throws Exception {
        StringBuilder classPath = new StringBuilder();
        for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
            if (entry.isBlank()) {
                continue;
            }
            if (classPath.length() > 0) {
                classPath.append(' ');
            }
            classPath.append(Path.of(entry).toUri());
        }
        Manifest mf = new Manifest();
        mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        mf.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "com.sitelock.SiteLockApplication");
        mf.getMainAttributes().put(Attributes.Name.CLASS_PATH, classPath.toString());

        Path jar = tmp.resolve("siteblock-test.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar), mf)) {
            // WHY: a jar with no entries — the classes come from the manifest's Class-Path.
        }
        return jar;
    }

    // Kills the daemon that start brought up, if it started.
    private static void killDaemon(Path state) {
        try {
            ExpirationDaemonState.read(state).ifPresent(snapshot -> {
                ProcessHandle.of(snapshot.getPid()).ifPresent(TestProcesses::kill);
            });
        } catch (Exception e) {
            // the test that matters is the elevation text
        }
    }

    // Best-effort deletion for files that Windows may still have locked right
    // after the child JVM exits. Never throws: a leftover temp file is better
    // than a failed test.
    private static void deleteQuietlyWithRetry(Path file) {
        for (int i = 0; i < 50; i++) {
            try {
                Files.deleteIfExists(file);
                return;
            } catch (Exception e) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    // What the command printed, on stdout and stderr.
    private static String capture(ThrowingRunnable r) {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream fake = new PrintStream(buffer, true, StandardCharsets.UTF_8);
        System.setOut(fake);
        System.setErr(fake);
        try {
            r.run();
        } catch (Exception e) {
            throw new AssertionError(e);
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
