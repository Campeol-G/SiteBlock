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
 * Persistence of {@code profiles.json} with atomic write and inter-process lock.
 *
 * <p>Same discipline as {@link BlockStore}: two simultaneous {@code save profile}
 * operations cannot leave the file half-written, and unreadable JSON becomes
 * {@link CorruptedStateException} without deleting anything. The path comes from
 * the constructor because the CLI layer decides {@code stateDir}.
 */
public class ProfileStore {

    private static final TypeReference<Map<String, Profile>> MAP_TYPE =
            new TypeReference<>() {
            };

    private final Path profilesFile;
    private final Path lockFile;
    private final ObjectMapper mapper;

    public ProfileStore(Path profilesFile) {
        this.profilesFile = profilesFile;
        this.lockFile = profilesFile.resolveSibling(profilesFile.getFileName() + ".lock");
        this.mapper = defaultMapper();
    }

    static ObjectMapper defaultMapper() {
        ObjectMapper om = new ObjectMapper();
        om.registerModule(new JavaTimeModule());
        om.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        om.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        return om;
    }

    public Path getProfilesFile() {
        return profilesFile;
    }

    /** Missing file becomes an empty map. Reads the snapshot directly if the lock fails. */
    public Map<String, Profile> load() throws IOException {
        if (!Files.exists(profilesFile)) {
            return new TreeMap<>();
        }
        if (Files.exists(lockFile)) {
            try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.READ);
                 FileLock ignored = channel.tryLock(0L, Long.MAX_VALUE, true)) {
                return readAndParse();
            } catch (IOException | UnsupportedOperationException | OverlappingFileLockException e) {
                // WARNING: inaccessible lock (e.g. root-owned without sudo), FS without locks, or another
                // thread in the SAME JVM already holds it: locks are per JVM, so a concurrent tryLock
                // throws OverlappingFileLockException (unchecked). Reading the snapshot directly is safe.
                return readAndParse();
            }
        }
        return readAndParse();
    }

    private Map<String, Profile> readAndParse() throws IOException {
        final String text;
        try {
            text = Files.readString(profilesFile, StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            throw new IOException("Failed to read " + profilesFile + ": " + e.getMessage(), e);
        }
        if (text.isEmpty()) {
            throw new CorruptedStateException(
                    "Profiles file empty/corrupted: " + profilesFile, null);
        }
        final ProfileDocument doc;
        try {
            doc = mapper.readValue(text, ProfileDocument.class);
        } catch (JsonProcessingException e) {
            throw new CorruptedStateException(
                    "Profiles file corrupted: " + profilesFile, e);
        }
        if (doc == null || doc.getProfiles() == null) {
            throw new CorruptedStateException(
                    "Profiles file corrupted (null content): " + profilesFile, null);
        }
        return new TreeMap<>(doc.getProfiles());
    }

    /** Saves the profiles atomically. */
    public void save(Map<String, Profile> profiles) throws IOException {
        Map<String, Profile> toSave = profiles != null ? new TreeMap<>(profiles) : new TreeMap<>();
        ensureParentExists(profilesFile);
        ensureParentExists(lockFile);
        try (FileChannel channel = FileChannel.open(lockFile,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            writeAtomically(toSave);
        } catch (OverlappingFileLockException e) {
            throw new IOException("Another process/thread is writing the recipes right now; "
                    + "try again.", e);
        }
    }

    /**
     * Executes a recipe modification under the file lock.
     *
     * <p>Two simultaneous {@code profile add} operations cannot get lost: without
     * the lock, the last writer would erase the other domain. A recipe is typed
     * work; losing it is worse than losing block state, which is rebuilt.
     *
     * <p>The write is a delta over what is on disk: whoever writes without
     * waiting for the lock risks a lost update.
     *
     * @param body receives access to already loaded recipes and a {@code save}
     *             that does not lock again
     */
    public <R> R inTransaction(TransactionBody<R> body) throws IOException {
        ensureParentExists(profilesFile);
        ensureParentExists(lockFile);
        try (FileChannel channel = FileChannel.open(lockFile,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            return body.apply(new LockedProfiles());
        } catch (OverlappingFileLockException e) {
            throw new IOException("Another process/thread is writing the recipes right now; "
                    + "try again.", e);
        }
    }

    /** What can be done inside a recipe transaction. */
    public interface Transaction {
        Map<String, Profile> load() throws IOException;

        void save(Map<String, Profile> profiles) throws IOException;
    }

    @FunctionalInterface
    public interface TransactionBody<R> {
        R apply(Transaction tx) throws IOException;
    }

    // A transaction: the loaded recipes and what was written last.
    // WHY: the save inside it cannot be a blind write — what is on
    // disk may have changed since the load, and erasing it is the lost update.
    private final class LockedProfiles implements Transaction {

        // What was written last: the base of the delta for the next write.
        private Map<String, Profile> lastWritten = new TreeMap<>();

        @Override
        public Map<String, Profile> load() throws IOException {
            // The public load already knows how to read without the lock — and the transaction already holds
            // the lock, so it takes the direct path, which is what matters here.
            Map<String, Profile> loaded = ProfileStore.this.load();
            lastWritten = copy(loaded);
            return loaded;
        }

        @Override
        public void save(Map<String, Profile> profiles) throws IOException {
            writeAtomically(mergeIntoDisk(profiles));
            lastWritten = copy(profiles);
        }

        // Applies the delta between what was written last and what we want to
        // write, on top of what is on disk right now.
        private Map<String, Profile> mergeIntoDisk(Map<String, Profile> profiles)
                throws IOException {
            Map<String, Profile> onDisk = Files.exists(profilesFile)
                    ? readAndParse()
                    : new TreeMap<>();
            Map<String, Profile> merged = new TreeMap<>(onDisk);
            for (Map.Entry<String, Profile> e : lastWritten.entrySet()) {
                Profile wanted = profiles.get(e.getKey());
                if (wanted == null || !wanted.equals(e.getValue())) {
                    merged.remove(e.getKey());
                }
            }
            for (Map.Entry<String, Profile> e : profiles.entrySet()) {
                Profile before = lastWritten.get(e.getKey());
                if (before == null || !before.equals(e.getValue())) {
                    merged.put(e.getKey(), e.getValue());
                }
            }
            return merged;
        }
    }

    // WHY: the comparison copy has to be the state as it was before the
    // change — keeping the same loaded objects would make an in-place change
    // move the base along with it, and the delta would see no change at all.
    private static Map<String, Profile> copy(Map<String, Profile> profiles) {
        Map<String, Profile> copy = new TreeMap<>();
        for (Map.Entry<String, Profile> e : profiles.entrySet()) {
            Profile p = e.getValue();
            if (p == null) {
                copy.put(e.getKey(), null);
                continue;
            }
            Map<String, ProfileEntry> entries = new TreeMap<>();
            for (Map.Entry<String, ProfileEntry> entry : p.getEntries().entrySet()) {
                ProfileEntry value = entry.getValue();
                entries.put(entry.getKey(),
                        value == null ? null : new ProfileEntry(value.getDurationMillis()));
            }
            // No-arg constructor and setters: that is how to copy the
            // dates as they are, including when updatedAt is null.
            Profile fresh = new Profile();
            fresh.setCreatedAt(p.getCreatedAt());
            fresh.setUpdatedAt(p.getUpdatedAt());
            fresh.setEntries(entries);
            copy.put(e.getKey(), fresh);
        }
        return copy;
    }

    private void writeAtomically(Map<String, Profile> toSave) throws IOException {
        ensureParentExists(profilesFile);
        ensureParentExists(lockFile);
        Path tmp = profilesFile.resolveSibling(profilesFile.getFileName() + ".tmp");
        String json = mapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(new ProfileDocument(ProfileDocument.CURRENT_VERSION, toSave));
        Files.writeString(tmp, json, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
        try {
            Files.move(tmp, profilesFile,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, profilesFile, StandardCopyOption.REPLACE_EXISTING);
        }
        SiteLockPaths.chownToUserIfConfigured(profilesFile);
        SiteLockPaths.chownToUserIfConfigured(lockFile);
    }

    /** Removes a profile. A missing name is not an error — deleting what does not exist is the desired state. */
    public void delete(String name) throws IOException {
        inTransaction(tx -> {
            Map<String, Profile> profiles = tx.load();
            if (profiles.remove(name) != null) {
                tx.save(profiles);
            }
            return null;
        });
    }

    private static void ensureParentExists(Path file) throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            SiteLockPaths.createDirectories(parent);
        }
    }
}