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
 * Estado do daemon de expiração em {@code ~/.sitelock/daemon.json}.
 *
 * <p><b>Quem escreve:</b> o próprio daemon, no início da vida dele: sob
 * autostart (systemd, launchd, Task Scheduler) ele é executado direto, sem
 * pai nenhum, então quem escreve não é o pai do {@code daemon start}.
 *
 * <p>Sem esse registro não existiria PID depois de um boot, e o {@code daemon
 * status} diria "parado" com o processo rodando, enquanto o {@code daemon stop}
 * não teria o que matar.
 *
 * <p><b>{@code lastSweepAt} só é escrito quando o sweep removeu algo.</b>
 * Reescrever a cada iteração seria uma escrita de root no diretório do usuário,
 * o dia inteiro, para um campo que ninguém lê com essa frequência.
 *
 * <p>O arquivo registra assim o que a pessoa quer saber: quando foi a última
 * vez que algo expirou sozinha.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ExpirationDaemonState {

    private long pid;
    private Instant startedAt;
    private String stateDir;
    private Instant lastSweepAt;

    // Instante de início do PROCESSO registrado.
    // POR QUE: é o que prova a identidade do PID, porque o SO recicla números.
    // Nulo = registro escrito por uma versão antiga, que não tinha o campo.
    private Instant processStartedAt;

    /** Construtor para o Jackson. */
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

    /** Nulo = ainda não expirou nada desde que o daemon subiu. */
    public Instant getLastSweepAt() {
        return lastSweepAt;
    }

    public void setLastSweepAt(Instant lastSweepAt) {
        this.lastSweepAt = lastSweepAt;
    }

    /** Instante de início do processo que o registro descreve. */
    public Instant getProcessStartedAt() {
        return processStartedAt;
    }

    public void setProcessStartedAt(Instant processStartedAt) {
        this.processStartedAt = processStartedAt;
    }

    // ---- arquivos ----

    public static final String STATE_FILE_NAME = "daemon.json";
    public static final String LOG_FILE_NAME = "daemon.log";
    public static final String LOCK_FILE_NAME = "daemon.json.lock";

    // Serializa as escritas dentro da MESMA JVM.
    // POR QUE: lock de arquivo é por JVM, não por canal, e um segundo lock() na
    // mesma JVM estoura; o lock de arquivo serializa contra os OUTROS processos.
    private static final ReentrantLock TRAVA_DA_JVM = new ReentrantLock();

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
        comTrava(stateDir, () -> escreverRegistro(stateDir, state));
    }

    // A escrita do registro em si, sem a trava: só válida dentro da trava.
    private static void escreverRegistro(Path stateDir, ExpirationDaemonState state)
            throws IOException {
        SiteLockPaths.createDirectories(stateDir);
        Path target = statePath(stateDir);
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        registrarInstanteDoProcesso(state);
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

    // Executa a ação com o registro travado contra os outros processos.
    private static void comTrava(Path stateDir, Acao acao) throws IOException {
        SiteLockPaths.createDirectories(stateDir);
        TRAVA_DA_JVM.lock();
        try (FileChannel canal = FileChannel.open(lockPath(stateDir),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignorado = canal.lock()) {
            acao.executar();
        } finally {
            TRAVA_DA_JVM.unlock();
        }
    }

    // Uma ação que pode falhar com IOException.
    private interface Acao {
        void executar() throws IOException;
    }

    // Grava o instante de início quando o registro é sobre este processo.
    // POR QUE: só o processo sabe o próprio instante, e é ele que prova, depois,
    // que o PID ainda é o nosso. Registro de versão antiga é completado aqui.
    private static void registrarInstanteDoProcesso(ExpirationDaemonState state) {
        if (state.getProcessStartedAt() != null || state.getPid() != ProcessHandle.current().pid()) {
            return;
        }
        Optional<Instant> inicio = ProcessHandle.current().info().startInstant();
        if (inicio.isEmpty()) {
            return;
        }
        state.setProcessStartedAt(inicio.get());
        // O "Subiu em:" tem que ser o instante em que o processo subiu, e não
        // o instante em que o registro foi escrito.
        state.setStartedAt(inicio.get());
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
            // Um daemon.json meio escrito não pode derrubar o `daemon status`:
            // o pior sintoma seria um status que não diz nada justamente quando
            // há algo errado.
            return Optional.empty();
        }
    }

    public static void delete(Path stateDir) throws IOException {
        Files.deleteIfExists(statePath(stateDir));
    }

    /**
     * Atualiza só o último sweep, preservando o resto do registro.
     *
     * <p>Read-modify-write: sem a trava, outra escrita grava o PID novo durante
     * a leitura, e o update devolve o registro velho por cima — o PID do daemon
     * some e o {@code daemon status} diz que não há daemon rodando.
     */
    public static void updateLastSweep(Path stateDir, Instant at) throws IOException {
        comTrava(stateDir, () -> {
            Optional<ExpirationDaemonState> current = read(stateDir);
            if (current.isEmpty()) {
                return;
            }
            ExpirationDaemonState state = current.get();
            state.setLastSweepAt(at);
            escreverRegistro(stateDir, state);
        });
    }

    /**
     * true se o PID registrado ainda for o processo que o registro descreve.
     *
     * <p>O SO recicla PID: o número do daemon morto vai para outro processo, e o
     * {@code daemon stop} manda SIGTERM em quem não é o SiteLock. A prova é o
     * instante de início: dois processos não começam no mesmo instante.
     *
     * <p>Registro sem o instante de início (escrito por uma versão antiga)
     * continua valendo pelo PID.
     */
    public static boolean isAlive(ExpirationDaemonState state) {
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
