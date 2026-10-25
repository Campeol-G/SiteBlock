package com.sitelock.core;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * State of the expiration daemon in {@code ~/.sitelock/daemon.json}.
 *
 * <p><b>Who writes it:</b> the daemon itself, early in its life: under
 * autostart (systemd, launchd, Task Scheduler) it is executed directly, with no
 * parent at all, so the writer is not the parent of {@code daemon start}.
 *
 * <p>Without this record there would be no PID after a boot, and {@code daemon
 * status} would say "stopped" with the process running, while {@code daemon stop}
 * would have nothing to kill.
 *
 * <p><b>{@code lastSweepAt} is only written when the sweep removed something.</b>
 * Rewriting it on every iteration would be a root write into the user's directory,
 * all day long, for a field nobody reads that often.
 *
 * <p>The file thus records what the person actually wants to know: when something
 * last expired on its own.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ExpirationDaemonState {

    private long pid;
    private Instant startedAt;
    private String stateDir;
    private Instant lastSweepAt;

    // Start instant of the RECORDED process.
    // WHY: it is what proves the PID's identity, because the OS recycles numbers.
    // Null = record written by an old version that did not have the field.
    private Instant processStartedAt;

    /** Constructor for Jackson. */
    @JsonCreator
    public ExpirationDaemonState(
            @JsonProperty(value = "pid", required = true) long pid,
            @JsonProperty("startedAt") Instant startedAt,
            @JsonProperty("stateDir") String stateDir,
            @JsonProperty("lastSweepAt") Instant lastSweepAt) {
        this(pid, startedAt, stateDir, lastSweepAt, null);
    }

    public ExpirationDaemonState(long pid, Instant startedAt, String stateDir, Instant lastSweepAt,
            Instant processStartedAt) {
        this.pid = pid;
        this.startedAt = startedAt;
        this.stateDir = stateDir;
        this.lastSweepAt = lastSweepAt;
        this.processStartedAt = processStartedAt;
    }

    public ExpirationDaemonState() {
    }

    public long getPid() {
        return pid;
    }

    public void setPid(long pid) {
        this.pid = pid;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public String getStateDir() {
        return stateDir;
    }

    public void setStateDir(String stateDir) {
        this.stateDir = stateDir;
    }

    /** Null = nothing has expired since the daemon came up. */
    public Instant getLastSweepAt() {
        return lastSweepAt;
    }

    public void setLastSweepAt(Instant lastSweepAt) {
        this.lastSweepAt = lastSweepAt;
    }

    /** Start instant of the process the record describes. */
    public Instant getProcessStartedAt() {
        return processStartedAt;
    }

    public void setProcessStartedAt(Instant processStartedAt) {
        this.processStartedAt = processStartedAt;
    }

    // ---- files ----

    public static final String STATE_FILE_NAME = "daemon.json";
    public static final String LOG_FILE_NAME = "daemon.log";
    public static final String LOCK_FILE_NAME = "daemon.json.lock";

    // Serializes writes within the SAME JVM.
    // WHY: a file lock is per JVM, not per channel, and a second lock() in the
    // same JVM throws; the file lock serializes against the OTHER processes.
    private static final ReentrantLock JVM_LOCK = new ReentrantLock();

    public static Path statePath(Path stateDir) {
        return stateDir.resolve(STATE_FILE_NAME);
    }

    public static Path logPath(Path stateDir) {
        return stateDir.resolve(LOG_FILE_NAME);
    }

    public static Path lockPath(Path stateDir) {
        return stateDir.resolve(LOCK_FILE_NAME);
    }

    static ObjectMapper mapper() {
        ObjectMapper om = new ObjectMapper();
        om.registerModule(new JavaTimeModule());
        om.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        om.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        return om;
    }

    public static void write(Path stateDir, ExpirationDaemonState state) throws IOException {
        withLock(stateDir, () -> writeRecord(stateDir, state));
    }

    // Writing the record itself, without the lock: only valid inside the lock.
    private static void writeRecord(Path stateDir, ExpirationDaemonState state)
            throws IOException {
        SiteLockPaths.createDirectories(stateDir);
        Path target = statePath(stateDir);
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        recordProcessStartInstant(state);
        Files.writeString(tmp, mapper().writerWithDefaultPrettyPrinter().writeValueAsString(state),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
        SiteLockPaths.chownToUserIfConfigured(target);
        SiteLockPaths.chownToUserIfConfigured(lockPath(stateDir));
    }

    // Runs the action with the record locked against other processes.
    private static void withLock(Path stateDir, Action action) throws IOException {
        SiteLockPaths.createDirectories(stateDir);
        JVM_LOCK.lock();
        try (FileChannel channel = FileChannel.open(lockPath(stateDir),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            action.run();
        } finally {
            JVM_LOCK.unlock();
        }
    }

    // An action that can fail with IOException.
    private interface Action {
        void run() throws IOException;
    }

    // Records the start instant when the record is about this process.
    // WHY: only the process knows its own instant, and that is what proves, later,
    // that the PID is still ours. A record from an old version is completed here.
    private static void recordProcessStartInstant(ExpirationDaemonState state) {
        if (state.getProcessStartedAt() != null || state.getPid() != ProcessHandle.current().pid()) {
            return;
        }
        Optional<Instant> start = ProcessHandle.current().info().startInstant();
        if (start.isEmpty()) {
            return;
        }
        state.setProcessStartedAt(start.get());
        // The "Started at:" has to be the instant the process came up, not
        // the instant the record was written.
        state.setStartedAt(start.get());
    }

    public static Optional<ExpirationDaemonState> read(Path stateDir) throws IOException {
        Path target = statePath(stateDir);
        if (!Files.exists(target)) {
            return Optional.empty();
        }
        String text = Files.readString(target, StandardCharsets.UTF_8).trim();
        if (text.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(mapper().readValue(text, ExpirationDaemonState.class));
        } catch (IOException e) {
            // A half-written daemon.json must not take down `daemon status`:
            // the worst symptom would be a status that says nothing precisely when
            // something is wrong.
            return Optional.empty();
        }
    }

    public static void delete(Path stateDir) throws IOException {
        Files.deleteIfExists(statePath(stateDir));
    }

    /**
     * Updates only the last sweep, preserving the rest of the record.
     *
     * <p>Read-modify-write: without the lock, another write saves the new PID during
     * the read, and the update puts the old record back on top — the daemon's PID
     * disappears and {@code daemon status} says there is no daemon running.
     */
    public static void updateLastSweep(Path stateDir, Instant at) throws IOException {
        withLock(stateDir, () -> {
            Optional<ExpirationDaemonState> current = read(stateDir);
            if (current.isEmpty()) {
                return;
            }
            ExpirationDaemonState state = current.get();
            state.setLastSweepAt(at);
            writeRecord(stateDir, state);
        });
    }

    /**
     * True if the recorded PID is still the process the record describes.
     *
     * <p>The OS recycles PIDs: the dead daemon's number goes to another process, and
     * {@code daemon stop} sends SIGTERM to someone who is not SiteLock. The proof is the
     * start instant: two processes do not start at the same instant.
     *
     * <p>A record without the start instant (written by an old version)
     * is still trusted on the PID.
     */
    public static boolean isAlive(ExpirationDaemonState state) {
        if (state == null || state.getPid() <= 0) {
            return false;
        }
        Optional<ProcessHandle> handle = ProcessHandle.of(state.getPid());
        if (handle.isEmpty() || !handle.get().isAlive()) {
            return false;
        }
        Instant recorded = state.getProcessStartedAt();
        if (recorded == null) {
            return true;
        }
        Optional<Instant> start = handle.get().info().startInstant();
        return start.isPresent() && start.get().equals(recorded);
    }
}