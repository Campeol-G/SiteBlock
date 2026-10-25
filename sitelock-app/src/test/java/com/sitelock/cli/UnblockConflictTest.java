package com.sitelock.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The unblock confirmation is typed before the transaction. If another process
 * changes one of the targets in that interval, the command cannot remove the new
 * version the person did not confirm.
 */
class UnblockConflictTest {

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

    @Test
    void individualUnblockDoesNotRemoveAnEntryThatChanged() throws Exception {
        assertEquals(0, new BlockCommand(List.of("tiktok.com"), "1h",
                hosts().toString(), state()).call());

        InputStream original = System.in;
        System.setIn(new StdinThatWrites(() -> {
            try {
                assertEquals(0, new BlockCommand(List.of("tiktok.com"), "10h",
                        hosts().toString(), state()).call());
            } catch (Exception e) {
                throw new UncheckedIOException(new java.io.IOException(e));
            }
        }, "YES\n"));

        int exit;
        try {
            exit = new UnblockCommand(List.of("tiktok.com"), true,
                    hosts().toString(), state()).call();
        } finally {
            System.setIn(original);
        }

        assertEquals(1, exit, "a conflict must fail, not undo the new block");
        String state = Files.readString(Path.of(state()), StandardCharsets.UTF_8);
        assertTrue(state.contains("tiktok.com"), state);
        assertTrue(Files.readString(hosts(), StandardCharsets.UTF_8)
                .contains("127.0.0.1 tiktok.com"), "the hosts file must not lose the line");
    }

    @Test
    void profileUnblockCancelsTheWholeBatchIfATargetChanged() throws Exception {
        assertEquals(0, new BlockCommand(List.of("tiktok.com"), "1h",
                hosts().toString(), state()).call());
        assertEquals(0, new ProfileSaveCommand("work", false,
                hosts().toString(), state()).call());

        InputStream original = System.in;
        System.setIn(new StdinThatWrites(() -> {
            try {
                assertEquals(0, new BlockCommand(List.of("tiktok.com"), "10h",
                        hosts().toString(), state()).call());
            } catch (Exception e) {
                throw new UncheckedIOException(new java.io.IOException(e));
            }
        }, "YES\n"));

        int exit;
        try {
            exit = new ProfileUnblockCommand("work", true,
                    hosts().toString(), state(), null).call();
        } finally {
            System.setIn(original);
        }

        assertEquals(1, exit, "one changed target cancels the batch");
        String state = Files.readString(Path.of(state()), StandardCharsets.UTF_8);
        assertTrue(state.contains("tiktok.com"), state);
        assertFalse(state.isBlank(), state);
        assertTrue(Files.readString(hosts(), StandardCharsets.UTF_8)
                .contains("127.0.0.1 tiktok.com"), "the hosts file must not lose the line");
    }

    // Fires the action on the first stdin read: it is exactly the interval
    // between the confirmation and the transaction.
    private static final class StdinThatWrites extends InputStream {

        private final Runnable onRead;
        private final byte[] content;
        private int pos;
        private boolean fired;

        StdinThatWrites(Runnable onRead, String content) {
            this.onRead = onRead;
            this.content = content.getBytes(StandardCharsets.UTF_8);
        }

        private void fire() {
            if (!fired) {
                fired = true;
                onRead.run();
            }
        }

        @Override
        public int read() {
            fire();
            return pos < content.length ? content[pos++] & 0xFF : -1;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            fire();
            if (pos >= content.length) {
                return -1;
            }
            int n = Math.min(len, content.length - pos);
            System.arraycopy(content, pos, b, off, n);
            pos += n;
            return n;
        }
    }
}
