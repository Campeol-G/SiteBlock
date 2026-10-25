package com.sitelock.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.TreeMap;

/**
 * Persistence for blocks.json with atomic writes and a cross-process lock.
 * Unreadable JSON becomes a {@link CorruptedStateException} without deleting anything.
 */
public class BlockStore {

    private static final TypeReference<Map<String, BlockEntry>> MAP_TYPE =
            new TypeReference<>() {
            };

    private final Path stateFile;
    private final Path lockFile;
    private final ObjectMapper mapper;

    public BlockStore(Path stateFile) {
        this.stateFile = stateFile;
        this.lockFile = stateFile.resolveSibling(stateFile.getFileName() + ".lock");
        this.mapper = defaultMapper();
    }

    /** Default constructor using {@link SiteLockPaths}. */
    public BlockStore() {
        this(SiteLockPaths.getStateFile());
    }

    static ObjectMapper defaultMapper() {
        ObjectMapper om = new ObjectMapper();
        om.registerModule(new JavaTimeModule());
        om.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        om.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        return om;
    }

    public Path getStateFile() {
        return stateFile;
    }

    /** Missing file becomes an empty map. Falls back to a direct read if the lock fails. */
    public Map<String, BlockEntry> load() throws IOException {
        if (!Files.exists(stateFile)) {
            return new TreeMap<>();
        }
        // Tries a shared read-only lock; falls back to a direct read.
        if (Files.exists(lockFile)) {
            try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.READ);
                 FileLock ignored = channel.tryLock(0L, Long.MAX_VALUE, true)) {
                return readAndParse();
            } catch (IOException | UnsupportedOperationException | OverlappingFileLockException e) {
                // Inaccessible lock (root-owned without sudo), FS without locks, or another thread
                // in the SAME JVM holding the lock (locks are per JVM: a concurrent tryLock throws
                // OverlappingFileLockException). Reading the atomic snapshot directly is safe.
                return readAndParse();
            }
        }
        return readAndParse();
    }

    private Map<String, BlockEntry> readAndParse() throws IOException {
            byte[] bytes;
            try {
                bytes = Files.readAllBytes(stateFile);
            } catch (IOException e) {
                throw new IOException("Failed to read " + stateFile + ": " + e.getMessage(), e);
            }
            String text = new String(bytes, StandardCharsets.UTF_8).trim();
            if (text.isEmpty()) {
                throw new CorruptedStateException(
                        "State file empty/corrupted: " + stateFile, null);
            }
            final Map<String, BlockEntry> parsed;
            try {
                parsed = mapper.readValue(text, MAP_TYPE);
            } catch (JsonProcessingException e) {
                throw new CorruptedStateException(
                        "State file corrupted: " + stateFile, e);
            }
            if (parsed == null) {
                throw new CorruptedStateException(
                        "State file corrupted (null content): " + stateFile, null);
            }
            Map<String, BlockEntry> result = new TreeMap<>();
            for (Map.Entry<String, BlockEntry> e : parsed.entrySet()) {
                BlockEntry entry = e.getValue();
                if (entry == null || entry.getDomain() == null || entry.getDomain().isBlank()) {
                    throw new CorruptedStateException(
                            "State file corrupted (entry without domain): " + stateFile, null);
                }
                result.put(entry.getDomain(), entry);
            }
            return result;
    }

    // WHY: load → transform → standalone save is an unprotected read-modify-write:
    // the save takes the lock, but the load happened earlier and outside it. With
    // the daemon running every minute, the window between the two is always open.

    // The case: the daemon reads the state, another terminal runs block, and the daemon writes
    // the stale state — the block disappears from the JSON but stays in the hosts file, which the
    // sweep (that walks the JSON) never cleans, and status says it is not blocked.

    // The write is a delta over what is on disk, not blind: whatever did not wait for the lock
    // (another version of the tool, a script, a manual edit) is not erased. It is the
    // same optimistic check that HostsFileManager.modifyHosts already does.
    /**
     * Runs a modification of the whole state under the file lock.
     *
     * @param body receives access to the already loaded state and a {@code save} that does not
     *             lock again
     */
    public <R> R inTransaction(TransactionBody<R> body) throws IOException {
        ensureParentExists(stateFile);
        ensureParentExists(lockFile);
        SiteLockPaths.chownToUserIfConfigured(stateFile.getParent());
        try (FileChannel channel = FileChannel.open(lockFile,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            SiteLockPaths.chownToUserIfConfigured(lockFile);
            return body.apply(new LockedState());
        } catch (OverlappingFileLockException e) {
            // Another thread in the SAME JVM. Not a data error: the caller decides.
            throw new IOException("Another process/thread is writing the state right now; "
                    + "try again.", e);
        }
    }

    // WHY: the save inside the transaction cannot be a blind write — what is
    // on disk may have changed since the load (by another writer that did not
    // wait for the lock), and erasing it is the lost update the transaction prevents.
    private final class LockedState implements Transaction {

        // What was written last: the base of the delta for the next write.
        private Map<String, BlockEntry> lastWritten = new TreeMap<>();

        @Override
        public Map<String, BlockEntry> load() throws IOException {
            // Same guard as load(): a missing file is empty state,
            // not an error. Without it the first `block` on a fresh machine
            // would fail, because there is still no file to read.
            Map<String, BlockEntry> state = Files.exists(stateFile)
                    ? readAndParse()
                    : new TreeMap<>();
            lastWritten = new TreeMap<>(state);
            return state;
        }

        @Override
        public void save(Map<String, BlockEntry> blocks) throws IOException {
            writeAtomically(mergeIntoDisk(blocks));
            lastWritten = new TreeMap<>(blocks);
        }

        // Applies the delta between what was written last and what we want to
        // write, on top of what is on disk right now.
        private Map<String, BlockEntry> mergeIntoDisk(Map<String, BlockEntry> blocks)
                throws IOException {
            Map<String, BlockEntry> onDisk = Files.exists(stateFile)
                    ? readAndParse()
                    : new TreeMap<>();
            Map<String, BlockEntry> merged = new TreeMap<>(onDisk);
            for (Map.Entry<String, BlockEntry> e : lastWritten.entrySet()) {
                BlockEntry wanted = blocks.get(e.getKey());
                if (wanted == null || !wanted.equals(e.getValue())) {
                    merged.remove(e.getKey());
                }
            }
            for (Map.Entry<String, BlockEntry> e : blocks.entrySet()) {
                BlockEntry before = lastWritten.get(e.getKey());
                if (before == null || !before.equals(e.getValue())) {
                    merged.put(e.getKey(), e.getValue());
                }
            }
            return merged;
        }
    }

    /** What can be done inside a transaction. */
    public interface Transaction {
        Map<String, BlockEntry> load() throws IOException;

        void save(Map<String, BlockEntry> blocks) throws IOException;
    }

    @FunctionalInterface
    public interface TransactionBody<R> {
        R apply(Transaction tx) throws IOException;
    }

    // Atomic write without locking again — only valid inside a transaction.
    private void writeAtomically(Map<String, BlockEntry> blocks) throws IOException {
        Path tmp = stateFile.resolveSibling(stateFile.getFileName() + ".tmp");
        String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(blocks);
        Files.writeString(tmp, json, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
        try {
            Files.move(tmp, stateFile,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, stateFile, StandardCopyOption.REPLACE_EXISTING);
        }
        SiteLockPaths.chownToUserIfConfigured(stateFile);
        SiteLockPaths.chownToUserIfConfigured(lockFile);
    }

    /** Saves the state atomically. */
    public void save(Map<String, BlockEntry> blocks) throws IOException {
        ensureParentExists(stateFile);
        ensureParentExists(lockFile);
        SiteLockPaths.chownToUserIfConfigured(stateFile.getParent());
        try (FileChannel channel = FileChannel.open(lockFile,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            writeAtomically(blocks);
        } catch (OverlappingFileLockException e) {
            throw new IOException("Another process/thread is writing the state right now; "
                    + "try again.", e);
        }
    }

    /** Deletes the state. Does not touch /etc/hosts. */
    public void clear() throws IOException {
        save(new TreeMap<>());
    }

    private static void ensureParentExists(Path file) throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }
}