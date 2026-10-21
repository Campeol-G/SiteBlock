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

    /** Construtor para o Jackson. */
    @JsonCreator
    public ExpirationDaemonState(
            @JsonProperty(value = "pid", required = true) long pid,
            @JsonProperty("startedAt") Instant startedAt,
            @JsonProperty("stateDir") String stateDir,
            @JsonProperty("lastSweepAt") Instant lastSweepAt) {
        this.pid = pid;
        this.startedAt = startedAt;
        this.stateDir = stateDir;
        this.lastSweepAt = lastSweepAt;
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

    /** true se o PID registrado ainda corresponde a um processo vivo. */
    public static boolean isAlive(ExpirationDaemonState state) {
        if (state == null || state.getPid() <= 0) {
            return false;
        }
        return ProcessHandle.of(state.getPid()).map(ProcessHandle::isAlive).orElse(false);
    }
}