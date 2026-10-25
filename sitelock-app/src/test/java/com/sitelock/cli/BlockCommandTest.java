package com.sitelock.cli;

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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code block} that refuses.
 *
 * <p>Without write access to the hosts file, the command must stop before touching
 * anything: a saved state with an untouched hosts file is the difference between
 * "did not block" and "blocked with no record anywhere".
 */
class BlockCommandTest {

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

    private String state() {
        return tmp.resolve("state/blocks.json").toString();
    }

    private record Result(int exit, String text) {
    }

    private Result run(Command command) throws Exception {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream fake = new PrintStream(buffer, true, StandardCharsets.UTF_8);
        System.setOut(fake);
        System.setErr(fake);
        int exit;
        try {
            exit = command.run();
        } catch (Exception e) {
            throw new AssertionError(e);
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
        return new Result(exit, buffer.toString(StandardCharsets.UTF_8));
    }

    @FunctionalInterface
    private interface Command {
        int run() throws Exception;
    }

    // WHY: the state is written before the hosts file. If the refusal does not come
    // before the write, the JSON says "blocked" while the hosts file lacks the line: the
    // domain is stuck with no record, and the sweep (which walks the JSON) never cleans it up.
    /**
     * Without write access to the hosts file, block refuses — and leaves no trace.
     */
    @Test
    void refusesWithExitCode1WhenHostsIsNotWritable() throws Exception {
        Path hostsFile = hosts();
        String before = Files.readString(hostsFile, StandardCharsets.UTF_8);
        // File.setReadOnly() works on all three OSes: the write bit on Unix, the
        // read-only attribute on Windows.
        assertTrue(hostsFile.toFile().setReadOnly(),
                "could not remove write access from the test hosts file");
        Assumptions.assumeFalse(Files.isWritable(hostsFile),
                "in this environment the hosts file is still writable (root?): the refusal would prove nothing");

        Result r = run(() -> new BlockCommand(List.of("tiktok.com"), "4h",
                hostsFile.toString(), state()).call());

        assertEquals(1, r.exit(), r.text());
        assertTrue(r.text().toLowerCase().contains("sudo")
                        || r.text().toLowerCase().contains("administrator"),
                "the refusal must say how to elevate: " + r.text());
        assertEquals(before, Files.readString(hostsFile, StandardCharsets.UTF_8),
                "the hosts file must not be touched when the command refuses");
        assertFalse(Files.exists(Path.of(state())),
                "the state must not be created when the command refuses");
    }

    // WHY: this can only be verified on this machine — the elevation hint follows
    // the real OS, and simulating Windows does not prove the text a Windows user reads.
    /**
     * On Windows the refusal mentions Administrator, not sudo.
     */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void onWindowsRefusalMentionsAdministratorNotSudo() throws Exception {
        Path hostsFile = hosts();
        assertTrue(hostsFile.toFile().setReadOnly(),
                "could not remove write access from the test hosts file");
        Assumptions.assumeFalse(Files.isWritable(hostsFile),
                "in this environment the hosts file is still writable: the refusal would prove nothing");

        Result r = run(() -> new BlockCommand(List.of("tiktok.com"), "4h",
                hostsFile.toString(), state()).call());

        assertEquals(1, r.exit(), r.text());
        assertTrue(r.text().toLowerCase().contains("administrator"),
                "on Windows the terminal elevates: " + r.text());
        assertFalse(r.text().toLowerCase().contains("sudo"),
                "on Windows there is no sudo: " + r.text());
    }
}
