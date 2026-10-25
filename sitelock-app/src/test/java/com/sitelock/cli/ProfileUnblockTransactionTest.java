package com.sitelock.cli;

import com.sitelock.blocker.SiteBlocker;
import com.sitelock.core.BlockEntry;
import com.sitelock.core.BlockStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code profile unblock} writes the state inside a transaction.
 *
 * <p>It releases several domains at once, and the state is the place that says
 * what is blocked. If {@code load} and {@code save} stay outside the same lock,
 * a block another terminal made in between is erased from the JSON — and the
 * domain stays in {@code /etc/hosts} with no record anywhere. The sweep walks
 * the JSON, so it never cleans it up.
 *
 * <p>The two tests are deterministic: one asks the state file's lock whether it
 * is held at the moment the command writes to the hosts file, and the other
 * makes another terminal write at the instant the person types YES.
 */
class ProfileUnblockTransactionTest {

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

    private String blocks() throws Exception {
        return Files.readString(Path.of(state()), StandardCharsets.UTF_8);
    }

    @Test
    void hostsWriteHappensInsideTheStateTransaction() throws Exception {
        assertEquals(0, new BlockCommand(List.of("tiktok.com"), null,
                hosts().toString(), state()).call());
        assertEquals(0, new ProfileSaveCommand("work", false,
                hosts().toString(), state()).call());

        boolean[] held = {false};
        SiteBlocker spy = new SiteBlocker() {
            @Override
            public void block(String domain) {
            }

            @Override
            public void unblock(String domain) {
            }

            @Override
            public void blockAll(List<String> domains) {
            }

            @Override
            public void unblockAll(List<String> domains) {
                held[0] = stateLockIsHeld(state());
            }
        };

        int exit = new ProfileUnblockCommand("work", true, hosts().toString(),
                state(), "YES", spy).call();

        assertEquals(0, exit);
        assertTrue(held[0],
                "the hosts write must happen inside the transaction, with the lock held");
    }

    @Test
    void blockMadeInAnotherTerminalMustNotDisappear() throws Exception {
        assertEquals(0, new BlockCommand(List.of("tiktok.com"), null,
                hosts().toString(), state()).call());
        assertEquals(0, new ProfileSaveCommand("work", false,
                hosts().toString(), state()).call());

        // The person is about to type YES. At the instant the command reads the
        // answer, another terminal blocks new.com: that is the window in which the
        // state is loaded and not yet saved.
        InputStream original = System.in;
        System.setIn(new StdinThatWrites(() -> {
            try {
                blocksFromAnotherTerminal("new.com");
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, "YES\n"));

        int exit;
        try {
            exit = new ProfileUnblockCommand("work", true, hosts().toString(),
                    state(), null).call();
        } finally {
            System.setIn(original);
        }

        assertEquals(0, exit);
        String state = blocks();
        assertFalse(state.contains("tiktok.com"), state);
        assertTrue(state.contains("new.com"),
                "the block from another terminal must not be erased: " + state);
    }

    // Simulates another terminal running siteblock block <domain>.
    private void blocksFromAnotherTerminal(String domain) throws IOException {
        BlockStore store = new BlockStore(Path.of(state()));
        Map<String, BlockEntry> current = store.load();
        current.put(domain, new BlockEntry(domain, Instant.now(), null));
        store.save(current);
    }

    // WARNING: in the same JVM, a tryLock on a file already locked by it throws
    // OverlappingFileLockException — that is how we learn the transaction holds the lock.
    private static boolean stateLockIsHeld(String stateFile) {
        Path lock = Path.of(stateFile).resolveSibling("blocks.json.lock");
        try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.WRITE)) {
            FileLock acquired = channel.tryLock();
            if (acquired == null) {
                return true;
            }
            acquired.release();
            return false;
        } catch (OverlappingFileLockException e) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    // A stdin that triggers an action on the first read: it is the test's
    // deterministic hook, because reading the confirmation is the point at which
    // the command has the state loaded and not yet saved.
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
