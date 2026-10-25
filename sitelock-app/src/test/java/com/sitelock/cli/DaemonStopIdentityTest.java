package com.sitelock.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code daemon stop} and the recycled PID.
 *
 * <p>The OS recycles process numbers: the daemon died and its PID was given to
 * another process. If {@code daemon stop} trusted only "the PID exists", it
 * would send SIGTERM to a process that is not SiteLock.
 */
class DaemonStopIdentityTest {

    @TempDir
    Path tmp;

    @Test
    void stopDoesNotKillAProcessThatIsNotTheDaemon() throws Exception {
        Process target = auxiliaryProcess();
        Path state = tmp.resolve("state");
        Files.createDirectories(state);
        // The record describes ANOTHER process: the PID is the target's, the identity
        // (start instant) is not.
        Files.writeString(state.resolve("daemon.json"),
                "{\"pid\":" + target.pid()
                        + ",\"startedAt\":\"2026-10-15T12:00:00Z\",\"stateDir\":\"" + state
                        + "\",\"processStartedAt\":\"2026-10-15T12:00:00Z\"}",
                StandardCharsets.UTF_8);

        int exit;
        try {
            exit = new DaemonStopCommand(state.resolve("blocks.json").toString()).call();
            assertTrue(target.isAlive(),
                    "daemon stop must not signal a process that is not the daemon");
        } finally {
            TestProcesses.kill(target);
            target.waitFor();
        }

        assertEquals(1, exit, "with no daemon of ours, stop must refuse");
    }

    // Any process, which stays alive until it is killed.
    private Process auxiliaryProcess() throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(Paths.get(System.getProperty("java.home"), "bin", "java").toString());
        cmd.add("-cp");
        cmd.add(System.getProperty("java.class.path"));
        cmd.add(Sleep.class.getName());
        return new ProcessBuilder(cmd).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
    }

    /** Stays alive until it receives the signal. */
    public static class Sleep {
        public static void main(String[] args) throws Exception {
            Thread.sleep(300_000);
        }
    }
}
