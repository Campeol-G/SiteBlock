package com.sitelock.cli;

import com.sitelock.dns.DnsDaemonState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The child daemon must see the same state as the parent.
 *
 * <p>Without {@code -Dsitelock.stateDir} and {@code -Dsitelock.user}, a daemon
 * started as root (autostart, or root without SUDO_USER) operates in
 * {@code /root/.sitelock} while {@code dns status} looks at
 * {@code ~/.sitelock}: status says "STOPPED" with the server running, and
 * {@code dns stop} cannot stop it.
 *
 * <p>The test reads the child process's real command line ({@code /proc}).
 */
class DnsStartPropertiesTest {

    @TempDir
    Path tmp;

    private static final int PORT = 15396;

    @Test
    void childReceivesTheSameStateDirAndUser() throws Exception {
        Path state = tmp.resolve("state");
        Files.createDirectories(state);
        String originalStateDir = System.getProperty("sitelock.stateDir");
        String originalUser = System.getProperty("sitelock.user");
        System.setProperty("sitelock.stateDir", state.toAbsolutePath().toString());
        System.setProperty("sitelock.user", "someone");
        try {
            int exit = new CommandLine(new DnsCommand()).execute(
                    "start",
                    "--port", String.valueOf(PORT),
                    "--upstream", "127.0.0.1");
            try {
                assertEquals(0, exit, "the daemon should have started");
                DnsDaemonState snapshot = DnsDaemonState.read(state).orElseThrow();
                List<String> commandLine = processCommandLine(snapshot.getPid());
                assertTrue(commandLine.contains("-Dsitelock.stateDir=" + state.toAbsolutePath()),
                        "the child must use the same state dir as the parent: " + commandLine);
                assertTrue(commandLine.contains("-Dsitelock.user=someone"),
                        "the child must know whom to return file ownership to: " + commandLine);
            } finally {
                stop(state);
            }
        } finally {
            restore("sitelock.stateDir", originalStateDir);
            restore("sitelock.user", originalUser);
        }
    }

    // The process command line, read from /proc.
    private static List<String> processCommandLine(long pid) throws IOException {
        Path cmdline = Path.of("/proc", String.valueOf(pid), "cmdline");
        assumeTrue(Files.exists(cmdline), "requires /proc to read the command line");
        String raw = Files.readString(cmdline, StandardCharsets.ISO_8859_1);
        return List.of(raw.split("\0"));
    }

    private static void restore(String property, String value) {
        if (value == null) {
            System.clearProperty(property);
        } else {
            System.setProperty(property, value);
        }
    }

    // Takes down the daemon the test started (the PID is in the state record).
    private void stop(Path state) {
        try {
            DnsDaemonState.read(state).ifPresent(snapshot -> {
                TestProcesses.killPid(snapshot.getPid());
            });
        } catch (IOException ignored) {
            // the test only needs the process not to be left hanging
        }
    }
}
