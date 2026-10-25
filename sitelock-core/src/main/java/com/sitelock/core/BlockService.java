package com.sitelock.core;

import com.sitelock.blocker.SiteBlocker;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Keeps the JSON ({@link BlockStore}) and the hosts file ({@link SiteBlocker}) in sync.
 * Every CLI command calls {@link #sweepExpired()} at the start.
 */
public class BlockService {

    private final BlockStore store;
    private final SiteBlocker blocker;
    private final Clock clock;
    private final Path hostsPath;

    public BlockService(BlockStore store, SiteBlocker blocker, Clock clock, Path hostsPath) {
        this.store = store;
        this.blocker = blocker;
        this.clock = clock;
        this.hostsPath = hostsPath;
    }

    /**
     * Result of a sweep.
     *
     * @param removed domains actually released from the hosts file and the state
     * @param failures always empty — the batch unblock is atomic (everything comes out or
     *                 nothing does), so the field only remains for signature
     *                 compatibility; callers read {@code removed}
     * @param skippedDueToPermission true when the hosts file is not writable and nothing
     *                 was attempted
     * @param state the state after the sweep
     */
    public record SweepResult(List<String> removed, List<String> failures,
                              boolean skippedDueToPermission, Map<String, BlockEntry> state) {
    }

    // WHY: the load → unblockAll → save runs entirely inside a BlockStore transaction:
    // outside it, a block from another terminal between the load and the save would be erased from
    // the state, and the domain would be stuck in the hosts file with no record (a window that is always open).
    /**
     * Removes the already expired blocks from the hosts file and the JSON.
     * If the hosts file is not writable and there are expired blocks, nothing changes and
     * {@code skippedDueToPermission} comes back true.
     */
    public SweepResult sweepExpired() throws IOException {
        return store.inTransaction(tx -> {
            Map<String, BlockEntry> state = tx.load();
            Instant now = Instant.now(clock);
            List<String> expired = new ArrayList<>();
            for (BlockEntry e : state.values()) {
                if (e.isExpired(now)) {
                    expired.add(e.getDomain());
                }
            }
            if (expired.isEmpty()) {
                return new SweepResult(List.of(), List.of(), false, state);
            }
            if (!isHostsWritable()) {
                return new SweepResult(List.of(), List.of(), true, state);
            }
            try {
                blocker.unblockAll(expired);
            } catch (IOException e) {
                // The batch is atomic: either the whole hosts file was rewritten, or none of it was.
                // That is why the state is not touched — the next sweep tries again,
                // instead of leaving the domain stuck in the JSON (which the sweep does not walk).
                List<String> failures = new ArrayList<>();
                for (String domain : expired) {
                    failures.add(domain + ": " + e.getMessage());
                }
                throw new IOException("Failed to clear expired entries from the hosts file: "
                        + String.join("; ", failures), e);
            }
            state.keySet().removeAll(expired);
            tx.save(state);
            return new SweepResult(List.copyOf(expired), List.of(), false, state);
        });
    }

    public boolean isHostsWritable() {
        if (Files.exists(hostsPath)) {
            return Files.isWritable(hostsPath);
        }
        Path parent = hostsPath.getParent();
        return parent == null || Files.isWritable(parent);
    }

    // WHY: status runs without elevation and needs to check the hosts file against the
    // state — isHostsWritable() is about WRITING, not READING, and only reading matters here.
    /**
     * The content of the hosts file.
     *
     * @throws IOException if the hosts file could not be read
     */
    public String readHostsContent() throws IOException {
        return HostsFileManager.readHosts(hostsPath);
    }

    // WHY: load, save and the hosts write stay under the same lock as the
    // BlockStore — outside it, a block from another terminal between the load and the save
    // would be erased from the JSON, and the domain would remain in the hosts file with no record.

    // The order is State → hosts and not the reverse: the reverse path traps the domain
    // forever (the sweep walks the JSON, not the hosts file). The chosen order leaves the
    // domain free if the process dies halfway — fail-open and silent.
    /**
     * Applies a state change and the hosts write as one unit.
     *
     * @param transform what to do with the loaded state; receives a copy
     *                  that may be mutated freely, and the transaction to save with
     */
    public <R> R inTransaction(StateTransform<R> transform, HostsWriter<R> hosts)
            throws IOException {
        return store.inTransaction(tx -> {
            Map<String, BlockEntry> before = tx.load();
            Map<String, BlockEntry> working = new TreeMap<>(before);
            R result = transform.apply(working, tx);
            try {
                hosts.write(result, working, tx);
            } catch (IOException e) {
                // The hosts file refused: the state has to be rolled back, otherwise the JSON
                // says "blocked" and the hosts file has no line.
                try {
                    tx.save(before);
                } catch (IOException rollback) {
                    e.addSuppressed(rollback);
                }
                throw e;
            }
            return result;
        });
    }

    /** What is done with the loaded state, inside the transaction. */
    @FunctionalInterface
    public interface StateTransform<R> {
        R apply(Map<String, BlockEntry> state, BlockStore.Transaction tx) throws IOException;
    }

    /** The hosts write, inside the transaction. */
    @FunctionalInterface
    public interface HostsWriter<R> {
        void write(R result, Map<String, BlockEntry> state, BlockStore.Transaction tx)
                throws IOException;
    }

    public BlockStore getStore() {
        return store;
    }

    public SiteBlocker getBlocker() {
        return blocker;
    }

    public Clock getClock() {
        return clock;
    }

    public Path getHostsPath() {
        return hostsPath;
    }
}