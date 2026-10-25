package com.sitelock.dns;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sitelock.core.SiteLockPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Optional;

// WHY: the file lives in the real user's state dir (even under sudo, through
// SiteLockPaths) so the daemon started with sudo and `dns stop` see the same file.
/**
 * State of the DNS daemon in {@code ~/.sitelock/dns-server.json}: what is running,
 * on which port/upstream, and until when (--for). Read by {@code dns stop/status}
 * to signal the right process and to apply the --force rule.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class DnsDaemonState {

  private long pid;
  private int port;
  private String upstream;
  private Instant startedAt;
  private Instant expiresAt;

  // Start instant of the recorded PROCESS: it is what proves the PID's identity
  // (the OS recycles numbers, and a live PID is not necessarily the DNS daemon).
  // Null = record written by an older version that lacked the field.
  private Instant processStartedAt;

  /** Constructor for Jackson. */
  @JsonCreator
  public DnsDaemonState(
      @JsonProperty(value = "pid", required = true) long pid,
      @JsonProperty(value = "port", required = true) int port,
      @JsonProperty("upstream") String upstream,
      @JsonProperty("startedAt") Instant startedAt,
      @JsonProperty("expiresAt") Instant expiresAt,
      @JsonProperty("processStartedAt") Instant processStartedAt) {
    this.pid = pid;
    this.port = port;
    this.upstream = upstream;
    this.startedAt = startedAt;
    this.expiresAt = expiresAt;
    this.processStartedAt = processStartedAt;
  }

  public DnsDaemonState(
      long pid, int port, String upstream, Instant startedAt, Instant expiresAt) {
    this(pid, port, upstream, startedAt, expiresAt, null);
  }

  public DnsDaemonState() {
  }

  public long getPid() {
    return pid;
  }

  public void setPid(long pid) {
    this.pid = pid;
  }

  public int getPort() {
    return port;
  }

  public void setPort(int port) {
    this.port = port;
  }

  public String getUpstream() {
    return upstream;
  }

  public void setUpstream(String upstream) {
    this.upstream = upstream;
  }

  public Instant getStartedAt() {
    return startedAt;
  }

  public void setStartedAt(Instant startedAt) {
    this.startedAt = startedAt;
  }

  /** Null = permanent (no expiration). */
  public Instant getExpiresAt() {
    return expiresAt;
  }

  public void setExpiresAt(Instant expiresAt) {
    this.expiresAt = expiresAt;
  }

  /** Start instant of the process the record describes. */
  public Instant getProcessStartedAt() {
    return processStartedAt;
  }

  public void setProcessStartedAt(Instant processStartedAt) {
    this.processStartedAt = processStartedAt;
  }

  /** True if temporary (--for) and still within its deadline. */
  public boolean isTemporaryAndActive(Instant now) {
    return expiresAt != null && now.isBefore(expiresAt);
  }

  // ---- files ----

  public static final String STATE_FILE_NAME = "dns-server.json";
  public static final String LOG_FILE_NAME = "dns-server.log";

  /** State directory, taking the CLI's --state-file override into account. */
  public static Path stateDirFor(String stateFileOverride) {
    if (stateFileOverride != null && !stateFileOverride.isBlank()) {
      Path stateFile = Path.of(stateFileOverride);
      Path parent = stateFile.getParent();
      return parent != null ? parent : Path.of(".");
    }
    return SiteLockPaths.getStateDir();
  }

  public static Path statePath(Path stateDir) {
    return stateDir.resolve(STATE_FILE_NAME);
  }

  public static Path logPath(Path stateDir) {
    return stateDir.resolve(LOG_FILE_NAME);
  }

  static ObjectMapper mapper() {
    ObjectMapper om = new ObjectMapper();
    om.registerModule(new JavaTimeModule());
    om.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    om.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    return om;
  }

  public static void write(Path stateDir, DnsDaemonState state) throws IOException {
    SiteLockPaths.createDirectories(stateDir);
    Path target = statePath(stateDir);
    Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
    recordProcessStartInstant(state);
    // NOTE: writing straight to the destination would leave a half-written file if
    // the process died mid-write — and a broken record locks the machine out
    // (`dns start` could no longer read the state).
    Files.writeString(tmp, mapper().writerWithDefaultPrettyPrinter().writeValueAsString(state),
        StandardCharsets.UTF_8, StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    try {
      Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
    }
    SiteLockPaths.chownToUserIfConfigured(target);
  }

  // Records the process start instant when the record describes this process:
  // only the process itself knows its own start instant — and that instant is
  // what later proves the PID is still ours.
  private static void recordProcessStartInstant(DnsDaemonState state) {
    if (state.getProcessStartedAt() != null || state.getPid() != ProcessHandle.current().pid()) {
      return;
    }
    Optional<Instant> start = ProcessHandle.current().info().startInstant();
    if (start.isEmpty()) {
      return;
    }
    state.setProcessStartedAt(start.get());
    state.setStartedAt(start.get());
  }

  public static Optional<DnsDaemonState> read(Path stateDir) throws IOException {
    Path target = statePath(stateDir);
    if (!Files.exists(target)) {
      return Optional.empty();
    }
    String text = Files.readString(target, StandardCharsets.UTF_8).trim();
    if (text.isEmpty()) {
      return Optional.empty();
    }
    try {
      return Optional.of(mapper().readValue(text, DnsDaemonState.class));
    } catch (IOException e) {
      // A half-written record must not lock the machine out: the worst symptom
      // would be `dns start` refusing to start the DNS server forever.
      return Optional.empty();
    }
  }

  public static void delete(Path stateDir) throws IOException {
    Files.deleteIfExists(statePath(stateDir));
  }

  /**
   * Deletes the record only if it still describes the given process.
   *
   * <p>A failed `dns start` must not delete another daemon's record if that daemon
   * started in the meantime; PID ownership is the only cheap local proof.
   */
  public static void deleteIfOwned(Path stateDir, long pid) throws IOException {
    Optional<DnsDaemonState> current = read(stateDir);
    if (current.isPresent() && current.get().getPid() == pid) {
      delete(stateDir);
    }
  }

  // WHY: the OS recycles PIDs, so "the PID exists" lies — `dns stop` would send
  // SIGTERM to a process that is not SiteLock. The identity proof is the process
  // start instant: two processes do not start at the same instant.
  /**
   * True if the recorded PID is still the process the record describes.
   *
   * <p>A record without the start instant (older version) is still accepted by PID.
   */
  public static boolean isAlive(DnsDaemonState state) {
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
