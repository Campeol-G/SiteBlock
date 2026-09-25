package com.sitelock.cli;

import com.sitelock.core.BlockEntry;
import com.sitelock.core.BlockStore;
import com.sitelock.core.DurationParser;
import com.sitelock.core.PermissionAdvisor;
import com.sitelock.dns.DnsDaemonState;
import com.sitelock.dns.NetworkUtils;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
 * <p>Sobe o servidor DNS como processo em background (daemon) e retorna na hora.
 * O daemon reaproveita a lista de bloqueados atual (blocks.json); {@code block}/
 * {@code unblock} em outro terminal valem já na próxima consulta, sem reiniciar.
 *
 * <p>Com {@code --for}, o daemon desliga sozinho ao expirar — e {@code dns stop}
 * antes disso exige {@code --force} + confirmação digitada (mesma regra do
 * {@code unblock} antecipado).
 */
@Command(name = "start",
        description = "Inicia o servidor DNS local em background.",
        mixinStandardHelpOptions = true)
public class DnsStartCommand implements Callable<Integer> {

    @Option(names = "--port", paramLabel = "<porta>", defaultValue = "53",
            description = "Porta UDP local (default: 53; use 5300 p/ testar sem sudo).")
    int port;

    @Option(names = "--upstream", paramLabel = "<ip>", defaultValue = "1.1.1.1",
            description = "DNS público para encaminhar o não-bloqueado (default: 1.1.1.1).")
    String upstream;

    @Option(names = "--for", paramLabel = "<duracao>",
            description = "Desliga sozinho após a duração. Ex.: 30m, 2h, 1d. Omitido = até 'dns stop'.")
    String forDuration;

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

        // Falha rápida e clara para porta privilegiada (< 1024 exige elevação nos
        // três SOs), em vez de exceção genérica no daemon. A detecção via
        // user.name=="root" funciona por acidente no Windows (nunca é root, então
        // o aviso dispara); a mensagem vem do PermissionAdvisor centralizado.
        // Limitação: o Java não detecta de forma confiável se já roda como
        // Administrador no Windows — em caso de dúvida, o bind falha e o log mostra.
        if (port < 1024 && !isRoot()) {
            String retry = "dns start" + (forDuration != null ? " --for " + forDuration : "");
            System.err.println("Erro: " + new PermissionAdvisor().privilegedPortHint(port, retry));
            return 1;
        }

        Path stateDir = DnsDaemonState.stateDirFor(stateFileOverride);
        Path stateFile = stateFileOverride != null && !stateFileOverride.isBlank()
                ? Paths.get(stateFileOverride)
                : stateDir.resolve("blocks.json");

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
        final Process process;
        try {
            Files.createDirectories(stateDir);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
            pb.redirectErrorStream(true);
            // stdin: PIPE (default) com EOF imediato — o daemon nunca lê stdin,
            // e DISCARD não é válido para entrada (só saída/erro).
            process = pb.start();
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
            DnsDaemonState.write(stateDir,
                    new DnsDaemonState(pid, port, upstreamAddr.getHostAddress(), now, expiresAt));
        } catch (IOException e) {
            System.err.println("Erro ao registrar o daemon (pid " + pid + "): " + e.getMessage());
            process.destroyForcibly();
            return 1;
        }

        // Dá até ~3s para o filho fazer bind; se morrer, mostra o motivo do log.
        boolean alive = false;
        for (int i = 0; i < 6; i++) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
                alive = true;
                if (logShowsListening(logFile)) {
                    break;
                }
            } else {
                alive = false;
                break;
            }
        }
        if (!alive) {
            System.err.println("Erro: o servidor DNS não subiu (pid " + pid + " encerrou).");
            printLogTail(logFile);
            try {
                DnsDaemonState.delete(stateDir);
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
        String binProp = System.getProperty("siteblock.bin");
        if (binProp != null && !binProp.isBlank()) {
            cmd.add("-Dsiteblock.bin=" + binProp);
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
        if (stateFileOverride != null && !stateFileOverride.isBlank()) {
            cmd.add("--state-file");
            cmd.add(stateFileOverride);
        }
        return cmd;
    }

    private static boolean isRoot() {
        return "root".equals(System.getProperty("user.name"));
    }

    private static boolean logShowsListening(Path logFile) {
        try {
            String text = Files.readString(logFile);
            return text.contains("SITELOCK_DNS_LISTENING");
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
