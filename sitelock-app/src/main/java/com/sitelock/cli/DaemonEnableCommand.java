package com.sitelock.cli;

import com.sitelock.core.AutostartInstaller;
import com.sitelock.core.AutostartPaths;
import com.sitelock.core.CommandRunner;
import com.sitelock.core.OsType;
import com.sitelock.core.SiteLockPaths;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Callable;

/**
 * siteblock daemon enable [--jar &lt;caminho&gt;] — instala o autostart.
 *
 * <p>É instalação privilegiada porque o daemon precisa escrever no
 * {@code /etc/hosts}. Não é novidade na curva: o uso normal do SiteBlock já
 * pede root.
 *
 * <p>E aqui está o ponto que faz a feature funcionar ou não: os caminhos são
 * resolvidos <b>agora</b> e embutidos no artefato. Um unit de sistema, uma
 * LaunchDaemon e uma {@code schtasks /ru SYSTEM} rodam como root sem
 * {@code SUDO_USER}, e aí {@code user.home} é {@code /root} — o daemon leria um
 * {@code blocks.json} que não é o do {@code sudo siteblock block}, e a feature
 * inteira viraria um no-op silencioso.
 *
 * <p>O jar também é <b>instalado</b>, não apontado de onde está. A unit
 * apontava para {@code target/siteblock-1.0.0.jar}, e um {@code mvn clean}
 * apagava o {@code target/}: a unit passava a apontar para o nada, o systemd
 * entrava em loop de crash e o prazo dos bloqueios parava de ser cumprido sem
 * mensagem que explicasse. A cópia mora num diretório do root, que só o root
 * escreve — o que também resolve o outro aviso do projeto, o de o daemon
 * executar código de um caminho que o usuário pode trocar.
 */
@Command(name = "enable",
        description = "Instala o daemon de expiracao para subir a cada boot.",
        mixinStandardHelpOptions = true)
public class DaemonEnableCommand implements Callable<Integer> {

    @Option(names = "--jar", paramLabel = "<caminho>",
            description = "Caminho absoluto do fat-jar.Padrao: a property siteblock.jar.")
    String jarPath;

    @Option(names = "--state-file", hidden = true) String stateFileOverride;

    // Injetados para teste; null = SO e raiz reais.
    OsType osOverride;
    Path systemRootOverride;
    CommandRunner runnerOverride;
    Boolean systemdPresenteOverride;

    public DaemonEnableCommand() {
    }

    DaemonEnableCommand(OsType os, Path systemRoot, String jarPath, CommandRunner runner) {
        this.osOverride = os;
        this.systemRootOverride = systemRoot;
        this.jarPath = jarPath;
        this.runnerOverride = runner;
    }

    DaemonEnableCommand(OsType os, Path systemRoot, String jarPath, CommandRunner runner,
            Boolean systemdPresente) {
        this(os, systemRoot, jarPath, runner);
        this.systemdPresenteOverride = systemdPresente;
    }

    @Override
    public Integer call() {
        OsType os = osOverride != null ? osOverride : OsType.detect();
        Path systemRoot = systemRootOverride != null ? systemRootOverride : Path.of("/");

        if (!temAgendador(os)) {
            System.err.println("Não encontrei " + nomeDoAgendador(os) + " nesta máquina.");
            System.err.println("Sem ele não dá para subir o daemon automaticamente. "
                    + "Você ainda pode rodar o daemon na mão, em outro terminal:");
            System.err.println("  " + CliSupport.sudoCommand("daemon start"));
            System.err.println("Ele libera os expirados enquanto estiver aberto — mas fechar "
                    + "o terminal encerra o daemon.");
            return 1;
        }

        if (!podeEscrever(diretorioDaInstalacao(os, systemRoot))) {
            System.err.println("Erro: " + CliSupport.elevationHint("daemon enable"));
            return 1;
        }

        AutostartPaths caminhos = new AutostartPaths(
                SiteLockPaths.getStateDir(),
                CliSupport.resolveHostsPath(null),
                caminhoDoJava(),
                caminhoDoJar(),
                System.getProperty("java.class.path", "."),
                usuarioReal(),
                os);

        // O jar é instalado antes do artefato, para que a unit aponte para a
        // cópia estável e não para o caminho de build.
        Path origem = caminhoDoJar() != null ? Paths.get(caminhoDoJar()) : null;
        AutostartInstaller.InstallResult copia =
                AutostartInstaller.installJar(os, systemRoot, origem);
        if (!copia.success()) {
            System.err.println("Erro: " + copia.detail());
            return 1;
        }
        String jarInstalado = copia.detail();

        AutostartPaths caminhosFinais = new AutostartPaths(
                caminhos.stateDir(), caminhos.hostsFile(), caminhos.javaBin(),
                jarInstalado, caminhos.classpath(), caminhos.realUserName(), os);

        AutostartInstaller installer = new AutostartInstaller(os, systemRoot, runnerOverride);
        AutostartInstaller.InstallResult result = installer.install(caminhosFinais);
        if (!result.success()) {
            System.err.println("Erro ao instalar o autostart: " + result.detail());
            return 1;
        }

        System.out.println("Daemon de expiração instalado. Vai subir a cada boot.");
        System.out.println("Jar: " + jarInstalado);
        String aviso = avisoDonoDoInstall(os, systemRoot);
        if (aviso != null) {
            System.out.println(aviso);
        }
        System.out.println("Depois de recompilar (mvn package), rode de novo: "
                + CliSupport.sudoCommand("daemon enable"));
        return 0;
    }

    /**
 * O usuário a quem o daemon deve devolver a posse dos arquivos que escreve.
 *
 * <p>NÃO é {@code user.name}. O {@code daemon enable} roda com sudo, e sob sudo
 * a JVM resolve {@code user.name} a partir do uid — que é 0, ou seja,
 * {@code "root"}. Embutilo embutiria {@code -Dsitelock.user=root}, e o
 * {@code chownToUserIfConfigured} jogaria fora na hora (ele recusa root
 * explicitamente), então a devolução de posse nunca aconteceria: o primeiro
 * sweep do daemon deixaria o {@code blocks.json} do root, e o próprio
 * {@code reset --yes} que a mensagem de estado corrompido recomenda deixaria de
 * funcionar sem sudo.
 *
 * <p>{@link SiteLockPaths#realUserName()} é o certo: lê {@code SUDO_USER} e já
 * rejeita {@code root}. Quando o enable é disparado sem sudo — de um pipeline,
 * por exemplo — devolve vazio e o artefato simplesmente não embute usuário.
 */
private String usuarioReal() {
        return SiteLockPaths.realUserName().orElse(null);
    }

    /**
     * systemd ausente é comum: container, WSL, distro enxuta. Fingir que
     * funcionou seria pior que dizer que não deu — a pessoa acharia que está
     * coberta quando não está.
     */
    private boolean temAgendador(OsType os) {
        if (systemdPresenteOverride != null) {
            return systemdPresenteOverride;
        }
        if (os.isWindows() || os.isMac()) {
            return true;
        }
        return Files.isDirectory(Path.of("/run/systemd/system"));
    }

    private static String nomeDoAgendador(OsType os) {
        if (os.isMac()) {
            return "launchctl";
        }
        return "systemd";
    }

    /**
     * O diretório que precisa ser gravável para a instalação acontecer.
     *
     * <p>No Linux e no macOS é o diretório do artefato ({@code /etc/systemd/system},
     * {@code /Library/LaunchDaemons}). No Windows não existe artefato em arquivo —
     * a definição vive na Tarefa do Agendador —, e quem decide é o diretório onde
     * o jar é instalado ({@code ProgramData/SiteBlock}).
     */
    private static Path diretorioDaInstalacao(OsType os, Path systemRoot) {
        Path artefato = AutostartInstaller.artifactPath(os, systemRoot);
        if (artefato != null) {
            Path parent = artefato.getParent();
            return parent != null ? parent : artefato;
        }
        return AutostartInstaller.installDir(os, systemRoot);
    }

    /**
     * Dá para criar o artefato aqui?
     *
     * <p>Sobe até o ancestral existente mais próximo, porque o diretório ainda pode
     * não existir — {@code /etc/systemd/system} não existe numa máquina sem
     * systemd, mas é o próprio {@code install} que o cria. O que decide é se
     * chegamos a um diretório gravável antes de subir a raiz.
     *
     * <p>ATENÇÃO: o argumento é o diretório que precisa ser gravável, e não o
     * arquivo do artefato — no Windows não existe arquivo, e quem decide é o
     * diretório de instalação do jar.
     */
    private static boolean podeEscrever(Path diretorio) {
        if (diretorio == null) {
            return false;
        }
        Path existente = diretorio;
        while (existente != null && !Files.exists(existente)) {
            existente = existente.getParent();
        }
        return existente != null && Files.isWritable(existente);
    }

    private static String caminhoDoJava() {
        String javaHome = System.getProperty("java.home", "");
        Path javaBin = Paths.get(javaHome, "bin", "java");
        return Files.isExecutable(javaBin) ? javaBin.toString() : "java";
    }

    /** A property primeiro; o classpath é reserva, e é um jar só no uso documentado. */
    private String caminhoDoJar() {
        if (jarPath != null && !jarPath.isBlank()) {
            return jarPath;
        }
        String prop = System.getProperty("siteblock.jar");
        return prop == null || prop.isBlank() ? null : prop;
    }

    /**
     * O jar do daemon fica num diretório que só o root escreve — e é isso que
     * o aviso antigo cobria, com o caminho do build no lugar.
     *
     * <p>Agora o aviso é o oposto: se o diretório de instalação NÃO for do
     * root, a proteção não vale nada, porque o usuário pode trocar o jar que o
     * daemon executa. Isso acontece quando o diretório já existia e era do
     * usuário — e aí é preciso dizer, porque é a diferença entre o daemon
     * executar código do root e executar código do usuário como root.
     */
    private String avisoDonoDoInstall(OsType os, Path systemRoot) {
        Path dir = AutostartInstaller.installDir(os, systemRoot);
        try {
            String dono = Files.getOwner(dir).getName();
            if (!"root".equals(dono.toLowerCase(java.util.Locale.ROOT))) {
                return "Aviso: " + dir + " não é do root — o daemon executaria um jar que você "
                        + "pode trocar. Rode: sudo chown root " + dir;
            }
        } catch (Exception e) {
            // Sem permissão de ler o dono: não dá para afirmar nada, e o aviso
            // padrão seria alarmar sem motivo.
        }
        return null;
    }
}
