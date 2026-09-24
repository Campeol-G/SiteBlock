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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

/**
 * Estado do daemon DNS em {@code ~/.sitelock/dns-server.json}: quem está
 * rodando,
 * em qual porta/upstream e até quando (--for). Lido por {@code dns stop/status}
 * para sinalizar o processo certo e para aplicar a regra de --force.
 *
 * <p>
 * O arquivo fica no state dir do usuário real (mesmo sob sudo, via
 * {@link SiteLockPaths}), então o daemon iniciado com sudo e o {@code dns stop}
 * enxergam o mesmo arquivo.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class DnsDaemonState {

  private long pid;
  private int port;
  private String upstream;
  private Instant startedAt;
  private Instant expiresAt;

  /** Construtor para o Jackson. */
  @JsonCreator
  public DnsDaemonState(
      @JsonProperty(value = "pid", required = true) long pid,
      @JsonProperty(value = "port", required = true) int port,
      @JsonProperty("upstream") String upstream,
      @JsonProperty("startedAt") Instant startedAt,
      @JsonProperty("expiresAt") Instant expiresAt) {
    this.pid = pid;
    this.port = port;
    this.upstream = upstream;
    this.startedAt = startedAt;
    this.expiresAt = expiresAt;
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

  /** Nulo = permanente (sem expiração). */
  public Instant getExpiresAt() {
    return expiresAt;
  }

  public void setExpiresAt(Instant expiresAt) {
    this.expiresAt = expiresAt;
  }

  /** true se temporário (--for) e ainda dentro do prazo. */
  public boolean isTemporaryAndActive(Instant now) {
    return expiresAt != null && now.isBefore(expiresAt);
  }

  // ---- arquivos ----

  public static final String STATE_FILE_NAME = "dns-server.json";
  public static final String LOG_FILE_NAME = "dns-server.log";

  /** Diretório de estado levando em conta o override de --state-file da CLI. */
  public static Path stateDirFor(String stateFileOverride) {
    if (stateFileOverride != null && !stateFileOverride.isBlank()) {
      Path sf = Path.of(stateFileOverride);
      Path parent = sf.getParent();
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
    Files.createDirectories(stateDir);
    Path target = statePath(stateDir);
    String json = mapper().writerWithDefaultPrettyPrinter().writeValueAsString(state);
    Files.writeString(target, json, StandardCharsets.UTF_8);
    SiteLockPaths.chownToRealUserIfSudo(target);
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
    return Optional.of(mapper().readValue(text, DnsDaemonState.class));
  }

  public static void delete(Path stateDir) throws IOException {
    Files.deleteIfExists(statePath(stateDir));
  }

  /** true se o PID registrado ainda corresponde a um processo vivo. */
  public static boolean isAlive(DnsDaemonState state) {
    if (state == null || state.getPid() <= 0) {
      return false;
    }
    return ProcessHandle.of(state.getPid()).map(ProcessHandle::isAlive).orElse(false);
  }
}
