package com.sitelock.cli;

import com.sitelock.core.BlockEntry;
import com.sitelock.core.BlockStore;
import com.sitelock.core.DurationParser;
import com.sitelock.core.OsType;
import com.sitelock.core.PermissionAdvisor;
import com.sitelock.core.SiteLockPaths;
import com.sitelock.dns.DnsDaemonLock;
import com.sitelock.dns.DnsDaemonState;
import com.sitelock.dns.DnsServer;
import com.sitelock.dns.NetworkUtils;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * siteblock dns start [--port 53] [--upstream 1.1.1.1] [--for 2h].
 *
 * <p>Sobe o servidor DNS em background e retorna na hora. O daemon reaproveita a lista
 * de bloqueados atual: {@code block}/{@code unblock} em outro terminal valem na próxima
 * consulta. Com {@code --for}, o {@code dns stop} antes do prazo exige {@code --force}.
 */
@Command(name = "start",
        description = "Inicia o servidor DNS local em background.",
        mixinStandardHelpOptions = true)
public class DnsStartCommand implements Callable<Integer> {

    // O log é append entre execuções; acima disto, a próxima subida o arquiva.
    // 5 MiB guarda várias subidas e ainda abre rápido no printLogTail.
    static final long LOG_ROTATE_BYTES = 5L * 1024 * 1024;
    static final String LISTENING_MARKER = "SITELOCK_DNS_LISTENING";

    @Option(names = "--port", paramLabel = "<porta>", defaultValue = "53",
            description = "Porta UDP local (default: 53; use 5300 p/ testar sem sudo).")
    int port;

    @Option(names = "--upstream", paramLabel = "<ip>", defaultValue = "1.1.1.1",
            description = "DNS público para encaminhar o não-bloqueado (default: 1.1.1.1).")
    String upstream;

    @Option(names = "--for", paramLabel = "<duracao>",
            description = "Desliga sozinho após a duração. Ex.: 30m, 2h, 1d. Omitido = até 'dns stop'.")
    String forDuration;

    @Option(names = "--allow-from", paramLabel = "<lista>",
            description = "Origens liberadas além das locais (IP ou CIDR, separados por vírgula).")
    String allowFrom;

    @Option(names = "--state-file", hidden = true) String stateFileOverride;

    public DnsStartCommand() {
    }

    DnsStartCommand(int port, String upstream, String forDuration, String stateFileOverride) {
        this.port = port;
        this.upstream = upstream;
        this.forDuration = forDuration;
        this.stateFileOverride = stateFileOverride;
    }

    @Override
    public Integer call() {
        if (port < 1 || port > 65535) {
            System.err.println("Erro: porta inválida: " + port + " (use 1-65535).");
            return 2;
        }

        final Duration duration;
        try {
            duration = forDuration == null ? null : DurationParser.parse(forDuration);
        } catch (IllegalArgumentException e) {
            System.err.println("Erro: " + e.getMessage());
            return 2;
        }

        final InetAddress upstreamAddr;
        try {
            upstreamAddr = InetAddress.getByName(upstream);
        } catch (Exception e) {
            System.err.println("Erro: upstream inválido '" + upstream + "': " + e.getMessage());
            return 2;
        }

        // ATENÇÃO: porta privilegiada (< 1024) exige elevação só nos Unix. No
        // Windows o Administrador não se chama "root"; o bind abaixo falha e o
        // log mostra o motivo, sem um falso aviso de sudo antes de tentar.
        if (requiresElevationForPort(port, OsType.detect()) && !isRoot()) {
            String retry = "dns start" + (forDuration != null ? " --for " + forDuration : "");
            System.err.println("Erro: " + new PermissionAdvisor().privilegedPortHint(port, retry));
            return 1;
        }

        // Valida antes de forkar: erro de digitação no --allow-from não pode
        // aparecer só depois que o daemon já subiu.
        try {
            DnsServer.parseAllowFrom(allowFrom);
        } catch (IllegalArgumentException e) {
            System.err.println("Erro: " + e.getMessage());
            return 2;
        }

        Path stateDir = DnsDaemonState.stateDirFor(stateFileOverride);
        Path stateFile = stateFileOverride != null && !stateFileOverride.isBlank()
                ? Paths.get(stateFileOverride)
                : stateDir.resolve("blocks.json");

        // A trava cobre a leitura do estado E a subida do filho. Sem ela, dois
        // `dns start` simultâneos leem "parado", sobem dois daemons e o último
        // registro apaga o do primeiro. O SO solta a trava se o processo morrer.
        final DnsDaemonLock lock;
        try {
            lock = DnsDaemonLock.tryAcquire(stateDir).orElse(null);
        } catch (IOException e) {
            System.err.println("Erro ao obter a trava de inicialização do DNS: " + e.getMessage());
            return 1;
        }
        if (lock == null) {
            System.err.println("Erro: outro 'siteblock dns start' está em andamento. "
                    + "Aguarde ele terminar e tente de novo.");
            return 1;
        }
        try (DnsDaemonLock ignored = lock) {
            return startLocked(stateDir, stateFile, duration, upstreamAddr);
        } catch (IOException e) {
            System.err.println("Erro ao liberar a trava de inicialização do DNS: " + e.getMessage());
            return 1;
        }
    }

    private int startLocked(Path stateDir, Path stateFile, Duration duration,
                            InetAddress upstreamAddr) {
        try {
            Optional<DnsDaemonState> existing = DnsDaemonState.read(stateDir);
            if (existing.isPresent() && DnsDaemonState.isAlive(existing.get())) {
                System.err.println("Erro: o servidor DNS já está rodando (pid "
                        + existing.get().getPid() + ", porta " + existing.get().getPort() + ").");
                System.err.println("Use 'siteblock dns status' para ver ou 'siteblock dns stop' para encerrar.");
                return 1;
            }
            if (existing.isPresent()) {
                DnsDaemonState.delete(stateDir); // PID morto: limpa estado obsoleto.
            }
        } catch (IOException e) {
            System.err.println("Erro ao ler estado do daemon: " + e.getMessage());
            return 1;
        }

        List<String> cmd = buildChildCommand(upstreamAddr.getHostAddress());
        Path logFile = DnsDaemonState.logPath(stateDir);
        final long logOffset;
        try {
            logOffset = prepareLog(logFile);
        } catch (IOException e) {
            System.err.println("Erro ao preparar o log do daemon: " + e.getMessage());
            return 1;
        }
        final Process process;
        try {
            SiteLockPaths.createDirectories(stateDir);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
            pb.redirectErrorStream(true);
            // stdin: PIPE (default) com EOF imediato — o daemon nunca lê stdin,
            // e DISCARD não é válido para entrada (só saída/erro).
            process = pb.start();
            SiteLockPaths.chownToUserIfConfigured(logFile);
            try {
                process.getOutputStream().close();
            } catch (IOException ignored) {
            }
        } catch (IOException e) {
            System.err.println("Erro ao iniciar o daemon: " + e.getMessage());
            return 1;
        }

        long pid = process.pid();
        Instant now = Instant.now(Clock.systemUTC());
        Instant expiresAt = duration == null ? null : now.plus(duration);
        try {
            // O pai vê o instante de início do FILHO; gravá-lo aqui fecha a
            // janela em que o registro existe sem identidade e vale só pelo PID.
            DnsDaemonState state = new DnsDaemonState(
                    pid, port, upstreamAddr.getHostAddress(), now, expiresAt);
            process.info().startInstant().ifPresent(state::setProcessStartedAt);
            DnsDaemonState.write(stateDir, state);
        } catch (IOException e) {
            System.err.println("Erro ao registrar o daemon (pid " + pid + "): " + e.getMessage());
            process.destroyForcibly();
            return 1;
        }

        // Dá até ~3s para o filho fazer bind; se morrer, mostra o motivo do log.
        boolean vivo = false;
        boolean escutando = false;
        for (int i = 0; i < 6; i++) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (!ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
                vivo = false;
                break;
            }
            vivo = true;
            if (logShowsListening(logFile, logOffset)) {
                escutando = true;
                break;
            }
        }
        if (!vivo) {
            System.err.println("Erro: o servidor DNS não subiu (pid " + pid + " encerrou).");
            printLogTail(logFile);
            try {
                DnsDaemonState.deleteIfOwned(stateDir, pid);
            } catch (IOException ignored) {
            }
            return 1;
        }
        // O processo vivo não é o mesmo que servidor escutando: o daemon pode
        // estar vivo e nunca abrir a porta (o watchdog dormia e tentava de novo,
        // para sempre). Só a marca no log prova que está atendendo.
        if (!escutando) {
            System.err.println("Erro: o servidor DNS não subiu: o processo (pid " + pid
                    + ") está vivo, mas não anunciou que está escutando.");
            process.destroyForcibly();
            printLogTail(logFile);
            try {
                DnsDaemonState.deleteIfOwned(stateDir, pid);
            } catch (IOException ignored) {
            }
            return 1;
        }

        System.out.println("Servidor DNS iniciado (pid " + pid + ", porta " + port
                + ", upstream " + upstreamAddr.getHostAddress() + ").");
        NetworkUtils.printStartupInfo(port, upstreamAddr.getHostAddress());
        System.out.println("Domínios bloqueados agora: " + countActive(stateFile)
                + " (mudanças via 'block'/'unblock' valem na próxima consulta, sem reiniciar).");
        if (expiresAt != null) {
            System.out.println("Desligamento automático em "
                    + DurationParser.formatRemaining(duration)
                    + " (--for); 'dns stop' antes disso exige --force.");
        }
        System.out.println("AVISO: enquanto os dispositivos apontarem para cá, se este servidor "
                + "cair eles perdem a internet inteira. Para reverter, volte o DNS do "
                + "dispositivo para automático/DHCP. Log em: " + logFile);
        return 0;
    }

    private List<String> buildChildCommand(String upstreamIp) {
        String javaHome = System.getProperty("java.home", "");
        Path javaBin = Paths.get(javaHome, "bin", "java");
        String javaCmd = Files.isExecutable(javaBin) ? javaBin.toString() : "java";
        String classpath = System.getProperty("java.class.path", ".");

        List<String> cmd = new ArrayList<>();
        cmd.add(javaCmd);
        // O log do filho é lido como UTF-8 pelo tail e pelos testes; sem isto o
        // Windows grava na codepage nativa e a leitura quebra.
        cmd.add("-Dstdout.encoding=UTF-8");
        cmd.add("-Dstderr.encoding=UTF-8");
        String binProp = System.getProperty("siteblock.bin");
        if (binProp != null && !binProp.isBlank()) {
            cmd.add("-Dsiteblock.bin=" + binProp);
        }
        // O filho tem que enxergar o MESMO estado do pai: sem estas properties, um
        // daemon iniciado como root sem SUDO_USER (autostart) opera em /root/.sitelock
        // enquanto o `dns status` olha ~/.sitelock — o status diz "PARADO" com o
        // servidor no ar, e o `dns stop` não o encerra.
        cmd.add("-Dsitelock.stateDir="
                + DnsDaemonState.stateDirFor(stateFileOverride).toAbsolutePath());
        String usuario = System.getProperty("sitelock.user");
        if (usuario == null || usuario.isBlank()) {
            usuario = SiteLockPaths.realUserName().orElse(null);
        }
        if (usuario != null && !usuario.isBlank()) {
            cmd.add("-Dsitelock.user=" + usuario);
        }
        cmd.add("-cp");
        cmd.add(classpath);
        cmd.add("com.sitelock.SiteLockApplication");
        cmd.add("dns");
        cmd.add("__run-daemon");
        cmd.add("--port");
        cmd.add(String.valueOf(port));
        cmd.add("--upstream");
        cmd.add(upstreamIp);
        if (forDuration != null) {
            cmd.add("--for");
            cmd.add(forDuration);
        }
        if (allowFrom != null && !allowFrom.isBlank()) {
            cmd.add("--allow-from");
            cmd.add(allowFrom);
        }
        if (stateFileOverride != null && !stateFileOverride.isBlank()) {
            cmd.add("--state-file");
            cmd.add(stateFileOverride);
        }
        return cmd;
    }

    private static boolean isRoot() {
        return "root".equals(System.getProperty("user.name"));
    }

    /**
     * Porta &lt; 1024 exige elevação só no Unix. No Windows a elevação é um
     * token de Administrador, não o nome de usuário "root"; tentar o bind e
     * mostrar o log é mais útil do que recusar antes de tentar.
     */
    static boolean requiresElevationForPort(int port, OsType os) {
        return port > 0 && port < 1024 && os != null && !os.isWindows();
    }

    /**
     * Devolve o offset a partir do qual a subida atual escreve no log.
     *
     * <p>Log acima de {@link #LOG_ROTATE_BYTES} vira {@code .1} e o offset é
     * zero; assim a marca de escuta de uma execução antiga não é confundida
     * com a desta.
     */
    static long prepareLog(Path logFile) throws IOException {
        if (!Files.exists(logFile)) {
            return 0;
        }
        long size = Files.size(logFile);
        if (size <= LOG_ROTATE_BYTES) {
            return size;
        }
        Path arquivado = logFile.resolveSibling(logFile.getFileName() + ".1");
        Files.move(logFile, arquivado, StandardCopyOption.REPLACE_EXISTING);
        return 0;
    }

    static boolean logShowsListening(Path logFile, long offset) {
        byte[] needle = LISTENING_MARKER.getBytes(StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(logFile, StandardOpenOption.READ)) {
            if (offset > channel.size()) {
                return false; // arquivo trocado/truncado durante a subida
            }
            channel.position(offset);
            ByteBuffer buffer = ByteBuffer.allocate(8192);
            int matched = 0;
            while (channel.read(buffer) > 0) {
                buffer.flip();
                while (buffer.hasRemaining()) {
                    byte b = buffer.get();
                    if (b == needle[matched]) {
                        matched++;
                        if (matched == needle.length) {
                            return true;
                        }
                    } else {
                        matched = b == needle[0] ? 1 : 0;
                    }
                }
                buffer.clear();
            }
            return false;
        } catch (IOException e) {
            return false;
        }
    }

    private static void printLogTail(Path logFile) {
        try {
            List<String> lines = Files.readAllLines(logFile);
            int from = Math.max(0, lines.size() - 20);
            System.err.println("--- últimas linhas do log (" + logFile + ") ---");
            lines.subList(from, lines.size()).forEach(l -> System.err.println(l));
        } catch (IOException e) {
            System.err.println("(não foi possível ler o log: " + e.getMessage() + ")");
        }
    }

    private static String countActive(Path stateFile) {
        try {
            Map<String, BlockEntry> state = new BlockStore(stateFile).load();
            Instant now = Instant.now(Clock.systemUTC());
            long active = state.values().stream().filter(e -> !e.isExpired(now)).count();
            return String.valueOf(active);
        } catch (Exception e) {
            return "? (estado ilegível: " + e.getMessage() + ")";
        }
    }
}
