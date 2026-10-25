package com.sitelock.cli;

import com.sitelock.blocker.SiteBlocker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Commands that write state must do so <b>inside</b> a transaction, with the
 * state lock held from start to finish.
 *
 * <p>Outside of it, the {@code load} and {@code save} of the same command sit in
 * separate windows: a {@code block} made in another terminal during that interval
 * is erased from the JSON — and the domain stays in {@code /etc/hosts} with no
 * record anywhere. The sweep walks the JSON, so it never cleans it up.
 *
 * <p>The test is deterministic: the spy asks the state file's lock whether it is
 * held at the moment the command writes to the hosts file.
 */
class TransactionStateTest {

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

    // WARNING: in the same JVM, a tryLock on a file already locked by it throws
    // OverlappingFileLockException — that is how we learn the transaction holds the lock.
    private static boolean stateLockIsHeld(String stateFile) {
        Path lock = Path.of(stateFile).resolveSibling("blocks.json.lock");
        try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.WRITE)) {
            FileLock acquired = channel.tryLock();
            if (acquired == null) {
                // Another JVM holds the lock: that also counts as held.
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

    @Test
    void applyKeepsTheStateLockHeldWhileWritingToHosts() throws Exception {
        assertEquals(0, new BlockCommand(List.of("tiktok.com"), "4h",
                hosts().toString(), state()).call());
        assertEquals(0, new ProfileSaveCommand("work", false,
                hosts().toString(), state()).call());
        // The state is now weaker than the recipe: apply has something to change.
        assertEquals(0, new BlockCommand(List.of("tiktok.com"), "30m",
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
                held[0] = stateLockIsHeld(state());
            }

            @Override
            public void unblockAll(List<String> domains) {
            }
        };

        int exit = new ProfileApplyCommand("work", hosts().toString(), state(), spy).call();

        assertEquals(0, exit);
        assertTrue(held[0],
                "the hosts write must happen inside the transaction, with the lock held");
    }
}
