package com.sitelock.core;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

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
 * Estado do daemon de expiração em {@code ~/.sitelock/daemon.json}.
 *
 * <p><b>Quem escreve:</b> o próprio daemon, no início da vida dele. A primeira
 * versão do design dizia que quem escreve é o pai do {@code daemon start} — e
 * isso está errado sob autostart, que é justamente o caso que importa: o
 * systemd, o launchd e o Task Scheduler executam o daemon <b>direto</b>, sem
 * pai nenhum. Se ninguém escrevesse, depois de um boot não existiria PID, e
 * então {@code daemon status} diria "parado" com o processo rodando, {@code
 * daemon stop} não teria o que matar, e o aviso de "não há daemon vivo"
 * dispararia numa máquina configurada corretamente.
 *
 * <p><b>{@code lastSweepAt} só é escrito quando o sweep removeu algo.</b>
 * Reescrever a cada iteração seria uma escrita de root no diretório do usuário a
 * cada minuto, o dia inteiro, para atualizar um campo que ninguém lê com essa
 * frequência. Assim, o que o arquivo registra é justamente o que a pessoa quer
 * saber: quando foi a última vez que alguma coisa expirou sozinha.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ExpirationDaemonState {

    private long pid;
    private Instant startedAt;
    private String stateDir;
    private Instant lastSweepAt;

    /**
     * Instante de início do PROCESSO registrado.
     *
     * <p>É o que prova a identidade do PID: o SO recicla números, e um PID
     * vivo não é necessariamente o daemon. Nulo = registro escrito por uma
     * versão antiga, que não tinha o campo.
     */
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

    public static void write(Path stateDir, ExpirationDaemonState state) throws IOException {
        Files.createDirectories(stateDir);
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
    }

    /**
     * Grava o instante de início do processo, quando o registro é sobre este
     * processo.
     *
     * <p>Só quem é o processo sabe o próprio instante de início — e é esse
     * instante que prova, depois, que o PID ainda é o nosso. Um registro escrito
     * por uma versão antiga (sem o campo) é completado na próxima escrita.
     */
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

    /** Atualiza só o último sweep, preservando o resto do registro. */
    public static void updateLastSweep(Path stateDir, Instant at) throws IOException {
        Optional<ExpirationDaemonState> current = read(stateDir);
        if (current.isEmpty()) {
            return;
        }
        ExpirationDaemonState state = current.get();
        state.setLastSweepAt(at);
        write(stateDir, state);
    }

    /**
     * true se o PID registrado ainda for o processo que o registro descreve.
     *
     * <p>O SO recicla PID: o daemon morreu e o número foi dado a outro
     * processo. "O PID existe" mente, e o {@code daemon stop} manda um SIGTERM
     * num processo que não é o SiteLock. A prova de identidade é o instante de
     * início do processo: dois processos não começam no mesmo instante.
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