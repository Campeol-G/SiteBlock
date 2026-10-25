package com.sitelock.cli;

import com.sitelock.core.ExpirationDaemonState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code daemon stop} against a daemon running as root.
 *
 * <p>A normal user cannot signal another user's process: the JVM throws
 * {@code SecurityException}. Without handling, picocli prints the exception
 * stack — the ugliest way to say "run it with sudo".
 */
class DaemonStopSecurityTest {

    @TempDir
    Path tmp;

    @Test
    void osRefusalBecomesAMessageWithAnElevationHint() throws Exception {
        Path state = tmp.resolve("state");
        Files.createDirectories(state);
        ExpirationDaemonState.write(state, new ExpirationDaemonState(
                ProcessHandle.current().pid(), Instant.now(), state.toString(), null));

        DaemonStopCommand cmd = new DaemonStopCommand(state.resolve("blocks.json").toString()) {
            @Override
            void stopProcess(ProcessHandle handle) {
                throw new SecurityException("the process uid cannot be signaled");
            }
        };

        String output = captureError(() -> cmd.call());

        assertTrue(output.contains("permission"),
                "the message must say permission was missing: " + output);
        assertTrue(output.contains("daemon stop"),
                "the message must say how to repeat the command: " + output);
        assertFalse(output.contains("Exception in thread"),
                "it must not throw a stack: " + output);
        assertFalse(output.contains("\tat "),
                "it must not throw a stack: " + output);
    }

    @Test
    void exitCodeIsAnError() throws Exception {
        Path state = tmp.resolve("state");
        Files.createDirectories(state);
        ExpirationDaemonState.write(state, new ExpirationDaemonState(
                ProcessHandle.current().pid(), Instant.now(), state.toString(), null));

        DaemonStopCommand cmd = new DaemonStopCommand(state.resolve("blocks.json").toString()) {
            @Override
            void stopProcess(ProcessHandle handle) {
                throw new SecurityException("the process uid cannot be signaled");
            }
        };

        int[] code = new int[1];
        captureError(() -> code[0] = cmd.call());

        assertEquals(1, code[0], "no permission to stop is an error");
    }

    private static String captureError(Command r) throws Exception {
        PrintStream original = System.err;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setErr(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        try {
            r.run();
        } finally {
            System.setErr(original);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    @FunctionalInterface
    private interface Command {
        int run() throws Exception;
    }
}
