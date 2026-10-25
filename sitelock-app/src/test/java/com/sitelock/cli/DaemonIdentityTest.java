package com.sitelock.cli;

import com.sitelock.core.ExpirationDaemonState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The daemon identity, seen from the outside.
 *
 * <p>The record is written by the daemon itself, in a different process from the
 * one that reads it. For {@code daemon status} to say "running" and for {@code
 * daemon stop} to find the right process, the record must carry the process
 * start instant — and it must match what the OS reports.
 */
class DaemonIdentityTest {

    @TempDir
    Path tmp;

    @Test
    void recordWrittenByAnotherProcessProvesIdentity() throws Exception {
        Path hosts = tmp.resolve("hosts");
        Files.writeString(hosts, "127.0.0.1 localhost\n", StandardCharsets.UTF_8);
        Path state = tmp.resolve("state");
        Files.createDirectories(state);

        Process daemon = new ProcessBuilder(daemonCommand(state, hosts))
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
        try {
            Path record = state.resolve("daemon.json");
            for (int i = 0; i < 150 && !Files.exists(record); i++) {
                Thread.sleep(100);
            }

            ExpirationDaemonState snapshot = ExpirationDaemonState.read(state).orElseThrow();
            assertNotNull(snapshot.getProcessStartedAt(),
                    "the record must store the start instant of the process that wrote it");
            assertTrue(ExpirationDaemonState.isAlive(snapshot),
                    "a reader in another process must recognize the daemon as alive");
        } finally {
            TestProcesses.kill(daemon);
            daemon.waitFor();
        }
    }

    private List<String> daemonCommand(Path state, Path hosts) {
        List<String> cmd = new ArrayList<>();
        cmd.add(Paths.get(System.getProperty("java.home"), "bin", "java").toString());
        cmd.add("-cp");
        cmd.add(System.getProperty("java.class.path"));
        cmd.add("-Dsitelock.stateDir=" + state.toAbsolutePath());
        cmd.add("-Dsitelock.hostsFile=" + hosts.toAbsolutePath());
        cmd.add("com.sitelock.SiteLockApplication");
        cmd.add("daemon");
        cmd.add("__run-daemon");
        cmd.add("--interval");
        cmd.add("60");
        return cmd;
    }
}
