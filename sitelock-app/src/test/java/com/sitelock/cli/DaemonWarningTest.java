package com.sitelock.cli;

import com.sitelock.core.BlockEntry;
import com.sitelock.core.BlockStore;
import com.sitelock.core.ExpirationDaemonState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The missing-daemon warning.
 *
 * <p>Without it, the feature stays invisible and the old behavior remains what
 * people assume is normal: they type {@code --for 4h}, the deadline passes, and the
 * site stays blocked because nobody told them another command was needed.
 *
 * <p>Trigger rule: warn when there is <b>no live daemon</b>. Autostart is not
 * checked here — even with autostart installed, the daemon may not be up yet on that boot.
 */
class DaemonWarningTest {

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

    private Path daemonJson() {
        return tmp.resolve("state/daemon.json");
    }

    private Result run(ThrowingRunnable r) throws Exception {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream fake = new PrintStream(buffer, true, StandardCharsets.UTF_8);
        System.setOut(fake);
        System.setErr(fake);
        try {
            r.run();
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
        return new Result(buffer.toString(StandardCharsets.UTF_8));
    }

    private record Result(String text) {
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private void installLiveDaemon() throws Exception {
        ExpirationDaemonState.write(tmp.resolve("state"), new ExpirationDaemonState(
                ProcessHandle.current().pid(), Instant.now(), tmp.resolve("state").toString(), null));
    }

    @Test
    void blockWithDurationWarnsWhenNoDaemonExists() throws Exception {
        Result r = run(() -> new BlockCommand(List.of("tiktok.com"), "30m",
                hosts().toString(), state()).call());

        assertTrue(r.text().contains("WARNING:"), r.text());
        assertTrue(r.text().contains("daemon enable"),
                "the warning must name the command: " + r.text());
        assertTrue(r.text().contains("only enforced"),
                "the warning must state the real consequence: " + r.text());
    }

    @Test
    void blockPermanentDoesNotWarn() throws Exception {
        Result r = run(() -> new BlockCommand(List.of("tiktok.com"), null,
                hosts().toString(), state()).call());

        // The block must have happened: otherwise the absence of the warning
        // would be true by accident, not because the block is permanent.
        assertTrue(r.text().contains("Blocked tiktok.com"), r.text());
        assertFalse(r.text().contains("WARNING:"),
                "a permanent block has no deadline to expire: " + r.text());
    }

    @Test
    void blockDoesNotWarnWhenDaemonIsRunning() throws Exception {
        installLiveDaemon();

        Result r = run(() -> new BlockCommand(List.of("tiktok.com"), "30m",
                hosts().toString(), state()).call());

        assertTrue(r.text().contains("Blocked tiktok.com"), r.text());
        assertFalse(r.text().contains("WARNING:"),
                "with a live daemon, the warning would be noise: " + r.text());
    }

    @Test
    void applyWithTemporaryWarnsWhenNoDaemonExists() throws Exception {
        assertEquals(0, new BlockCommand(List.of("tiktok.com"), "4h", hosts().toString(), state()).call());
        assertEquals(0, new ProfileSaveCommand("work", false, hosts().toString(), state()).call());

        Result r = run(() -> new ProfileApplyCommand("work", hosts().toString(),
                state(), null).call());

        assertTrue(r.text().contains("WARNING:"), r.text());
        assertTrue(r.text().contains("daemon enable"), r.text());
    }

    @Test
    void statusShowsDaemonLineWhenTemporaryIsActive() throws Exception {
        assertEquals(0, new BlockCommand(List.of("tiktok.com"), "4h", hosts().toString(), state()).call());

        Result r = run(() -> new StatusCommand(hosts().toString(), state()).call());

        assertTrue(r.text().contains("daemon enable"),
                "status must show the command that turns the daemon on: " + r.text());
    }

    @Test
    void statusWithoutTemporaryHasNoDaemonLine() throws Exception {
        assertEquals(0, new BlockCommand(List.of("tiktok.com"), null, hosts().toString(), state()).call());

        Result r = run(() -> new StatusCommand(hosts().toString(), state()).call());

        assertTrue(r.text().contains("Blocked domains (1):"),
                "the block must be listed: " + r.text());
        assertFalse(r.text().contains("daemon enable"),
                "with no active temporary block there is nothing to warn about: " + r.text());
    }

    @Test
    void statusWithOnlyPermanentsHasNoDaemonLine() throws Exception {
        Map<String, BlockEntry> state = new TreeMap<>();
        state.put("tiktok.com", new BlockEntry("tiktok.com", Instant.now(), null));
        Files.createDirectories(tmp.resolve("state"));
        new BlockStore(Path.of(state())).save(state);

        Result r = run(() -> new StatusCommand(hosts().toString(), state()).call());

        assertTrue(r.text().contains("Blocked domains (1):"),
                "the block must be listed: " + r.text());
        assertFalse(r.text().contains("daemon enable"), r.text());
    }
}
