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
            System.err.println("  sudo " + CliSupport.sudoCommand("daemon start"));
            System.err.println("Ele libera os expirados enquanto estiver aberto — mas fechar "
                    + "o terminal encerra o daemon.");
            return 1;
        }

        Path artefato = AutostartInstaller.artifactPath(os, systemRoot);
        if (!podeEscrever(artefato)) {
            System.err.println("Erro: " + CliSupport.elevationHint("daemon enable"));
            return 1;
        }

        AutostartPaths caminhos = new AutostartPaths(
                SiteLockPaths.getStateDir(),
                CliSupport.resolveHostsPath(null),
                caminhoDoJava(),
                caminhoDoJar(),
                System.getProperty("java.class.path", "."),
                System.getProperty("user.name"),
                os);

        AutostartInstaller installer = new AutostartInstaller(os, systemRoot, runnerOverride);
        AutostartInstaller.InstallResult result = installer.install(caminhos);
        if (!result.success()) {
            System.err.println("Erro ao instalar o autostart: " + result.detail());
            return 1;
        }

        System.out.println("Daemon de expiração instalado para subir a cada boot.");
        System.out.println("Artefato: " + artefato);
        String aviso = avisoCaminhoGravavel();
        if (aviso != null) {
            System.out.println(aviso);
        }
        System.out.println("Confira com: siteblock daemon status");
        return 0;
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
 * Dá para criar o artefato aqui?
 *
 * <p>Sobe até o ancestral existente mais próximo, porque o diretório ainda pode
 * não existir — {@code /etc/systemd/system} não existe numa máquina sem
 * systemd, mas é o próprio {@code install} que o cria. O que decide é se
 * chegamos a um diretório gravável antes de subir a raiz.
 */
    private static boolean podeEscrever(Path artefato) {
        Path parent = artefato.getParent();
        if (parent == null) {
            return false;
        }
        Path existente = parent;
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
     * O daemon roda como root e executa código de um caminho que o usuário pode
     * trocar. Isto é aviso, nunca recusa: no install documentado o jar vive em
     * ~/Projects/.../target/, gravável por construção, e um portão rígido
     * quebraria o caminho de instalação no primeiro dia. O alvo é o usuário
     * distraído — quem quer burlar já edita o /etc/hosts com sudo.
     */
    private String avisoCaminhoGravavel() {
        String jar = caminhoDoJar();
        if (jar == null) {
            return null;
        }
        Path p;
        try {
            p = Paths.get(jar).toAbsolutePath();
        } catch (RuntimeException e) {
            return null;
        }
        Path parent = p.getParent();
        if (parent == null || !Files.isWritable(parent)) {
            return null;
        }
        return "Aviso: o daemon roda como root e vai executar o jar em " + p
                + ", dentro de um diretório que você pode alterar. Se isso te incomodar, "
                + "copie o jar para um diretório que só o root escreva e rode: "
                + "sudo siteblock daemon enable --jar /opt/sitelock/siteblock.jar";
    }
}