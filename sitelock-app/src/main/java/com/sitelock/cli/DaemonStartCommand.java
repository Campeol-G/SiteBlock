package com.sitelock.cli;

import com.sitelock.core.DaemonCommandLine;
import com.sitelock.core.ExpirationDaemonState;
import com.sitelock.core.SiteLockPaths;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
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
            // O log é do filho, mas nasce do pai: rotacionar aqui é o que
            // garante que o tail mostre o erro desta execução, e não o da
            // última.
            prepararLog(logFile);
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

    // POR QUE: as duas properties de caminho são repassadas ao filho — sem elas
    // o start e o enable leriam arquivos diferentes. A linha sai da mesma fonte
    // (DaemonCommandLine): a JVM que executa o daemon é a mesma nos dois casos.

    // ATENÇÃO: quando as duas linhas eram montadas à mão, o enable deixava o
    // nome da classe depois do -jar e o start deixava as properties: nenhuma das
    // duas invocações subia, e nenhuma dizia por quê.
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

    // Teto de cada geração do log do daemon.
    static final long LOG_MAX_BYTES = 64 * 1024;

    // POR QUE: sem isto o log só cresce — o appendTo do filho mantém o que já
    // estava, e o printLogTail mostraria a cauda de uma execução antiga. O
    // anterior vai para daemon.log.1, aparado no teto, e o daemon.log recomeça.

    // A posse volta para o usuário real: o pai roda como root, e um log que só
    // o root lê quebra a promessa de que o daemon status funciona sem elevação.
    static void prepararLog(Path logFile) throws IOException {
        Path anterior = logFile.resolveSibling(logFile.getFileName() + ".1");
        if (Files.exists(logFile) && Files.size(logFile) > 0) {
            guardarGeracaoAnterior(logFile, anterior);
        }
        Path pai = logFile.getParent();
        if (pai != null) {
            Files.createDirectories(pai);
        }
        // TRUNCATE_EXISTING no mesmo inode: o arquivo continua sendo o mesmo
        // (com a mesma posse e permissões) — só o conteúdo é zerado.
        Files.writeString(logFile, "", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
        SiteLockPaths.chownToUserIfConfigured(logFile);
        SiteLockPaths.chownToUserIfConfigured(anterior);
    }

    // Guarda só a cauda do log anterior no arquivo rotacionado.
    private static void guardarGeracaoAnterior(Path origem, Path destino) throws IOException {
        byte[] cauda = lerCauda(origem, LOG_MAX_BYTES);
        Files.write(destino, cauda, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }

    // Os últimos max bytes do arquivo, sem a linha cortada no meio.
    private static byte[] lerCauda(Path arquivo, long max) throws IOException {
        try (SeekableByteChannel canal = Files.newByteChannel(arquivo, StandardOpenOption.READ)) {
            long tamanho = canal.size();
            long inicio = Math.max(0, tamanho - max);
            canal.position(inicio);
            ByteBuffer buffer = ByteBuffer.allocate((int) (tamanho - inicio));
            while (buffer.hasRemaining() && canal.read(buffer) > 0) {
            }
            byte[] cauda = buffer.array();
            if (inicio == 0) {
                return cauda; // o arquivo inteiro coube: nada foi cortado
            }
            // A cauda começa no meio de uma linha: ela vai fora.
            int quebra = primeiraLinha(cauda);
            return quebra < 0 ? cauda : Arrays.copyOfRange(cauda, quebra + 1, cauda.length);
        }
    }

    // Índice da primeira quebra de linha, ou -1.
    private static int primeiraLinha(byte[] bytes) {
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == '\n') {
                return i;
            }
        }
        return -1;
    }

    // ATENÇÃO: registro órfão trava o daemon start para sempre (não há --force)
    // e o daemon stop mandaria sinal para um processo alheio. Devolve true só
    // quando havia um órfão e ele foi removido.
    /**
     * A checagem de identidade não é só "o PID está vivo": o registro guarda o
     * instante de início do processo, e é ele que prova que o PID vivo ainda é
     * o nosso — o SO recicla números de PID. Registro antigo, sem o instante,
     * ainda vale pelo PID.
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

    // O daemon start executa o jar de onde ele está — normalmente o do build,
    // num diretório que o usuário pode trocar. É aviso, nunca recusa: o alvo é
    // o usuário distraído, não o atacante.

    // A correção já existe e é um comando: o daemon enable instala uma cópia
    // do jar num diretório do root e aponta a inicialização para lá.
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
                + CliSupport.elevatedCommand("daemon enable");
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

    // Espera o processo terminar; usado pelo daemon stop.
    static boolean esperarAte(Process process, long segundos) {
        try {
            return process.waitFor(segundos, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}