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

/**
 * Mantém JSON ({@link BlockStore}) e arquivo hosts ({@link SiteBlocker}) em sync.
 * Todo comando CLI chama {@link #sweepExpired()} no início.
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

    public record SweepResult(List<String> removed, List<String> failures,
                              boolean skippedDueToPermission, Map<String, BlockEntry> state) {
    }

    /**
     * Remove do hosts e do JSON os bloqueios já expirados.
     * Se o hosts não for gravável e houver expirados, nada muda e
     * {@code skippedDueToPermission} volta true.
     */
    public SweepResult sweepExpired() throws IOException {
        Map<String, BlockEntry> state = store.load();
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
        List<String> removed = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        for (String domain : expired) {
            try {
                blocker.unblock(domain);
                state.remove(domain);
                removed.add(domain);
            } catch (IOException e) {
                failures.add(domain + ": " + e.getMessage());
            }
        }
        if (!removed.isEmpty()) {
            store.save(state);
        }
        if (!failures.isEmpty()) {
            throw new IOException("Falha ao limpar expirados do hosts: " + String.join("; ", failures));
        }
        return new SweepResult(List.copyOf(removed), List.of(), false, state);
    }

    public boolean isHostsWritable() {
        if (Files.exists(hostsPath)) {
            return Files.isWritable(hostsPath);
        }
        Path parent = hostsPath.getParent();
        return parent == null || Files.isWritable(parent);
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
