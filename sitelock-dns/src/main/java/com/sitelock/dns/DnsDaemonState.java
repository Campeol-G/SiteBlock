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

  /**
   * Instante de início do PROCESSO registrado.
   *
   * <p>É o que prova a identidade do PID: o SO recicla números, e um PID
   * vivo não é necessariamente o daemon DNS. Nulo = registro escrito por uma
   * versão antiga, que não tinha o campo.
   */
  private Instant processStartedAt;

  /** Construtor para o Jackson. */
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

  /** Nulo = permanente (sem expiração). */
  public Instant getExpiresAt() {
    return expiresAt;
  }

  public void setExpiresAt(Instant expiresAt) {
    this.expiresAt = expiresAt;
  }

  /** Instante de início do processo que o registro descreve. */
  public Instant getProcessStartedAt() {
    return processStartedAt;
  }

  public void setProcessStartedAt(Instant processStartedAt) {
    this.processStartedAt = processStartedAt;
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
    Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
    registrarInstanteDoProcesso(state);
    // ATENÇÃO: escrever direto no destino deixaria o arquivo meio escrito se o
    // processo morresse no meio — e o registro quebrado tranca a máquina
    // (o `dns start` não conseguia mais ler o estado).
    Files.writeString(tmp, mapper().writerWithDefaultPrettyPrinter().writeValueAsString(state),
        StandardCharsets.UTF_8, StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    try {
      Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
    }
    SiteLockPaths.chownToRealUserIfSudo(target);
  }

  /**
   * Grava o instante de início do processo, quando o registro é sobre este
   * processo.
   *
   * <p>Só quem é o processo sabe o próprio instante de início — e é esse
   * instante que prova, depois, que o PID ainda é o nosso.
   */
  private static void registrarInstanteDoProcesso(DnsDaemonState state) {
    if (state.getProcessStartedAt() != null || state.getPid() != ProcessHandle.current().pid()) {
      return;
    }
    Optional<Instant> inicio = ProcessHandle.current().info().startInstant();
    if (inicio.isEmpty()) {
      return;
    }
    state.setProcessStartedAt(inicio.get());
    state.setStartedAt(inicio.get());
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
      // Um registro meio escrito não pode trancar a máquina: o pior sintoma
      // seria o `dns start` recusando subir o servidor DNS para sempre.
      return Optional.empty();
    }
  }

  public static void delete(Path stateDir) throws IOException {
    Files.deleteIfExists(statePath(stateDir));
  }

  /**
   * true se o PID registrado ainda for o processo que o registro descreve.
   *
   * <p>O SO recicla PID: o daemon morreu e o número foi dado a outro
   * processo. "O PID existe" mente, e o {@code dns stop} manda um SIGTERM
   * num processo que não é o SiteLock. A prova de identidade é o instante de
   * início do processo: dois processos não começam no mesmo instante.
   *
   * <p>Registro sem o instante de início (escrito por uma versão antiga)
   * continua valendo pelo PID.
   */
  public static boolean isAlive(DnsDaemonState state) {
    if (state == null || state.getPid() <= 0) {
      return false;
    }
    Optional<ProcessHandle> handle = ProcessHandle.of(state.getPid());
    if (handle.isEmpty() || !handle.get().isAlive()) {
      return false;
    }
    Instant registrado = state.getProcessStartedAt();
    if (registrado == null) {
      return true;
    }
    Optional<Instant> inicio = handle.get().info().startInstant();
    return inicio.isPresent() && inicio.get().equals(registrado);
  }
}
