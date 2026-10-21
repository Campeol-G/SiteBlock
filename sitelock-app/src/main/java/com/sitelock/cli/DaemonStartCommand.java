package com.sitelock.cli;

import com.sitelock.core.DaemonCommandLine;
import com.sitelock.core.ExpirationDaemonState;
import com.sitelock.core.SiteLockPaths;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

/**
 * siteblock daemon start [--interval &lt;s&gt;] — sobe o daemon em background.
 *
 * <p>Não é preciso para o SiteBlock funcionar; sem ele, o prazo só é cumprido
 * quando você roda outro comando do siteblock. {@code daemon enable} é o que
 * faz isso acontecer sozinho a cada boot.
 */
@Command(name = "start",
        description = "Sobe o daemon de expiracao em background.",
        mixinStandardHelpOptions = true)
public class DaemonStartCommand implements Callable<Integer> {

    @Option(names = "--interval", defaultValue = "60", paramLabel = "<segundos>",
            description = "Teto do sono entre varreduras (padrao 60).")
    String intervalSeconds;

    @Option(names = "--hosts-file", hidden = true) String hostsFileOverride;
    @Option(names = "--state-file", hidden = true) String stateFileOverride;

    public DaemonStartCommand() {
    }

    DaemonStartCommand(String intervalSeconds, String hostsFileOverride, String stateFileOverride) {
        this.intervalSeconds = intervalSeconds;
        this.hostsFileOverride = hostsFileOverride;
        this.stateFileOverride = stateFileOverride;
    }

    @Override
    public Integer call() {
        var service = CliSupport.newService(hostsFileOverride, stateFileOverride);
        if (!service.isHostsWritable()) {
            // Recusa ANTES de forkar. Um filho que não escreve no hosts é um
            // daemon que sobe, varre, não remove nada, e ninguém percebe.
            System.err.println("Erro: " + CliSupport.elevationHint("daemon start"));
            return 1;
        }

        Path stateDir = CliSupport.stateDirFor(stateFileOverride);
        Optional<ExpirationDaemonState> existente;
        try {
            existente = ExpirationDaemonState.read(stateDir);
        } catch (IOException e) {
            System.err.println("Erro ao ler o estado do daemon: " + e.getMessage());
            return 1;
        }
        if (existente.isPresent() && ExpirationDaemonState.isAlive(existente.get())) {
            System.err.println("Já existe um daemon rodando (pid " + existente.get().getPid()
                    + "). Use 'siteblock daemon status' para ver, ou 'daemon stop' para encerrar.");
            return 1;
        }
        try {
            descartarRegistroOrfao(stateDir);
        } catch (IOException e) {
            System.err.println("Aviso: não consegui limpar o registro antigo do daemon: "
                    + e.getMessage());
        }

        List<String> cmd = buildChildCommand(stateDir, service.getHostsPath());
        Path logFile = ExpirationDaemonState.logPath(stateDir);
        final Process process;
        try {
            Files.createDirectories(stateDir);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
            pb.redirectErrorStream(true);
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

        // Quem escreve o daemon.json é o FILHO. O pai só espera ele aparecer e
        // confirmar que o PID registrado é o do processo vivo.
        boolean registrado = false;
        for (int i = 0; i < 40; i++) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            Optional<ExpirationDaemonState> state;
            try {
                state = ExpirationDaemonState.read(stateDir);
            } catch (IOException e) {
                System.err.println("Erro ao ler o registro do daemon: " + e.getMessage());
                return 1;
            }
            if (state.isPresent() && state.get().getPid() == pid) {
                registrado = true;
                break;
            }
            if (!process.isAlive()) {
                break;
            }
        }

        if (!registrado) {
            process.destroyForcibly();
            System.err.println("Erro: o daemon não subiu (pid " + pid + " encerrou).");
            System.out.println("Últimas linhas do log:");
            printLogTail(logFile);
            return 1;
        }

        System.out.println("Daemon de expiração iniciado (pid " + pid
                + ", teto de " + intervalSeconds + "s).");
        System.out.println("Bloqueios temporários agora vencem sozinhos. Log em: " + logFile);
        String aviso = caminhoGravavelPeloUsuario();
        if (aviso != null) {
            System.out.println(aviso);
        }
        return 0;
    }

    /**
     * O mesmo desenho de fork do {@code dns start}, com uma diferença que a
     * feature depende: as duas properties de caminho são repassadas ao filho.
     * Sem elas, o {@code start} e o {@code enable} leriam arquivos diferentes.
     *
     * <p>A forma da linha é a mesma do {@code daemon enable}, e sai da mesma
     * fonte: {@link DaemonCommandLine}. A diferença entre os dois caminhos é
     * que o {@code enable} roda sob o agendador e o {@code start} roda aqui — mas
     * a JVM que executa o daemon é a mesma, e a linha que a constrói tem que
     * ser a mesma. Quando as duas eram montadas à mão, o {@code enable} deixava
     * o nome da classe depois do {@code -jar} e o {@code start} deixava as
     * properties depois do {@code -jar}: nenhuma das duas invocações subia, e
     * nenhuma das duas dizia por quê.
     */
    List<String> buildChildCommand(Path stateDir, Path hostsPath) {
        String javaHome = System.getProperty("java.home", "");
        Path javaBin = Paths.get(javaHome, "bin", "java");
        String javaCmd = Files.isExecutable(javaBin) ? javaBin.toString() : "java";

        List<String> properties = new ArrayList<>();
        String binProp = System.getProperty("siteblock.bin");
        if (binProp != null && !binProp.isBlank()) {
            properties.add("-Dsiteblock.bin=" + binProp);
        }
        properties.add("-Dsitelock.stateDir=" + stateDir.toAbsolutePath());
        properties.add("-Dsitelock.hostsFile=" + hostsPath.toAbsolutePath());
        // O filho herda o SUDO_USER do pai, mas e se o pai roda como root sem
        // sudo (su, container)? Aí ninguém saberia a quem devolver a posse dos
        // arquivos que o daemon escreve — e o status passaria a pedir elevação.
        SiteLockPaths.realUserName().ifPresent(u -> properties.add("-Dsitelock.user=" + u));

        List<String> appArgs = new ArrayList<>(
                List.of("daemon", "__run-daemon", "--interval", intervalSeconds));
        if (stateFileOverride != null && !stateFileOverride.isBlank()) {
            appArgs.add("--state-file");
            appArgs.add(stateFileOverride);
        }

        return DaemonCommandLine.build(javaCmd, System.getProperty("siteblock.jar"),
                System.getProperty("java.class.path", "."), properties, appArgs);
    }

    /**
     * Descarta um registro de daemon cujo processo não existe mais.
     *
     * <p>Um boot antigo que morreu deixa o {@code daemon.json} para trás. Como a
     * checagem é só "o PID está vivo" e o SO recicla PID, o registro pode passar
     * a apontar para um processo alheio — ou simplesmente continuar lá para
     * sempre. No primeiro caso o {@code daemon stop} mandaria um sinal para um
     * processo que não é nosso; no segundo, o {@code daemon start} recusaria
     * para sempre, sem haver --force para destravar.
     *
     * @return true se havia um registro órfão e ele foi removido
     */
    static boolean descartarRegistroOrfao(Path stateDir) throws IOException {
        Optional<ExpirationDaemonState> existente = ExpirationDaemonState.read(stateDir);
        if (existente.isEmpty() || ExpirationDaemonState.isAlive(existente.get())) {
            return false;
        }
        ExpirationDaemonState.delete(stateDir);
        System.out.println("Registro de daemon antigo (pid " + existente.get().getPid()
                + ") descartado: o processo não está mais rodando.");
        return true;
    }

    /**
     * O {@code daemon start} executa o jar de onde ele está — normalmente o do
     * build, num diretório que o usuário pode trocar. Isto é aviso, nunca
     * recusa: o alvo é o usuário distraído, não o atacante (quem quer burlar já
     * edita o /etc/hosts com sudo, que é uma linha a mais).
     *
     * <p>A correção já existe e é um comando: o {@code daemon enable} instala
     * uma cópia do jar num diretório do root e aponta a inicialização para lá.
     */
    private String caminhoGravavelPeloUsuario() {
        String jar = System.getProperty("siteblock.jar");
        if (jar == null || jar.isBlank()) {
            return null;
        }
        Path p;
        try {
            p = Paths.get(jar).toAbsolutePath();
        } catch (RuntimeException e) {
            return null;
        }
        return "Aviso: o daemon roda como root e executa o jar em " + p
                + ", que você pode trocar. Para evitar isso, rode: "
                + CliSupport.sudoCommand("daemon enable");
    }

    private static void printLogTail(Path logFile) {
        try {
            List<String> linhas = Files.readAllLines(logFile);
            for (int i = Math.max(0, linhas.size() - 10); i < linhas.size(); i++) {
                System.err.println("  " + linhas.get(i));
            }
        } catch (IOException ignored) {
        }
    }

    /** Espera o processo terminar; usado pelo {@code daemon stop}. */
    static boolean esperarAte(Process process, long segundos) {
        try {
            return process.waitFor(segundos, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}