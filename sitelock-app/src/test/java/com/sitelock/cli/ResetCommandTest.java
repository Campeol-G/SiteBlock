package com.sitelock.cli;

import com.sitelock.core.BlockEntry;
import com.sitelock.core.BlockStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * siteblock reset — deletes the state, does not touch the hosts file.
 *
 * <p>reset is the recovery path for corrupted state, so it must work precisely
 * when the file is broken — and it must refuse when nobody asked to delete.
 */
class ResetCommandTest {

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

    private Map<String, BlockEntry> stateEntries() throws Exception {
        return new BlockStore(Path.of(state())).load();
    }

    private record Result(int exit, String text) {
    }

    private Result runCommand(Command cmd) throws Exception {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream fake = new PrintStream(buffer, true, StandardCharsets.UTF_8);
        System.setOut(fake);
        System.setErr(fake);
        int exit;
        try {
            exit = cmd.run();
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

    @Test
    void resetWithYesDeletesTheState() throws Exception {
        assertEquals(0, new BlockCommand(List.of("tiktok.com"), null,
                hosts().toString(), state()).call());
        assertTrue(stateEntries().containsKey("tiktok.com"), "precondition: the state has the domain");

        Result r = runCommand(() -> new ResetCommand(true, state()).call());

        assertEquals(0, r.exit(), r.text());
        assertFalse(stateEntries().containsKey("tiktok.com"),
                "reset must delete the state: " + stateEntries());
        assertTrue(stateEntries().isEmpty(), "the recreated state must be empty: " + stateEntries());
    }

    @Test
    void resetDoesNotTouchHosts() throws Exception {
        assertEquals(0, new BlockCommand(List.of("tiktok.com"), null,
                hosts().toString(), state()).call());
        String before = Files.readString(hosts(), StandardCharsets.UTF_8);

        Result r = runCommand(() -> new ResetCommand(true, state()).call());

        assertEquals(0, r.exit(), r.text());
        assertEquals(before, Files.readString(hosts(), StandardCharsets.UTF_8),
                "reset only changes the state — the hosts file keeps the block");
        assertTrue(r.text().toLowerCase().contains("hosts"),
                "the output must remind that the hosts file was not changed: " + r.text());
    }

    @Test
    void resetWithoutYesRefusesAndDeletesNothing() throws Exception {
        assertEquals(0, new BlockCommand(List.of("tiktok.com"), null,
                hosts().toString(), state()).call());
        String before = Files.readString(Path.of(state()), StandardCharsets.UTF_8);

        Result r = runCommand(() -> new ResetCommand(false, state()).call());

        assertEquals(2, r.exit(), r.text());
        assertEquals(before, Files.readString(Path.of(state()), StandardCharsets.UTF_8),
                "without --yes nothing may be deleted");
        assertTrue(r.text().contains("--yes"),
                "the refusal must say how to confirm: " + r.text());
    }

    /**
     * reset is the repair for corrupted state: that is what it exists for.
     */
    @Test
    void resetRecreatesACorruptedState() throws Exception {
        Files.createDirectories(Path.of(state()).getParent());
        Files.writeString(Path.of(state()), "{ this is not json", StandardCharsets.UTF_8);

        Result r = runCommand(() -> new ResetCommand(true, state()).call());

        assertEquals(0, r.exit(), r.text());
        assertTrue(stateEntries().isEmpty(), "the recreated state must be empty: " + stateEntries());
    }

    // WHY: the parent path is a regular file, and no system allows creating the
    // state directory underneath it.
    /**
     * When the state cannot be written, reset must say that it failed.
     */
    @Test
    void resetFailsWithAMessageWhenTheStateCannotBeWritten() throws Exception {
        Path file = tmp.resolve("not-a-directory");
        Files.writeString(file, "I am a file", StandardCharsets.UTF_8);
        String impossibleState = file.resolve("state/blocks.json").toString();

        Result r = runCommand(() -> new ResetCommand(true, impossibleState).call());

        assertEquals(1, r.exit(), r.text());
        assertTrue(r.text().toLowerCase().contains("error"),
                "the failure must be reported: " + r.text());
    }
}
