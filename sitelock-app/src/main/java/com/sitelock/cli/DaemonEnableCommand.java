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

// ATENÇÃO: os caminhos são resolvidos agora e embutidos no artefato — o
// agendador roda como root sem SUDO_USER, e aí user.home é /root: o daemon
// leria o blocks.json de outro usuário, e a feature viraria um no-op silencioso.

// POR QUE: o jar é copiado, não apontado de onde está — a unit apontava para
// target/, que um mvn clean apaga, e ela entrava em loop de crash. A cópia mora
// num diretório que só o root escreve.

/**
 * siteblock daemon enable [--jar &lt;caminho&gt;] — instala o autostart.
 *
 * <p>É instalação privilegiada: o daemon precisa escrever no {@code /etc/hosts},
 * e o uso normal do SiteBlock já pede root.
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
            System.err.println("  " + CliSupport.elevatedCommand("daemon start"));
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
        Path jarInstalado = copia.path();
        if (jarInstalado == null) {
            // ATENÇÃO: sem o caminho não há unit possível — melhor falhar aqui do
            // que escrever um ExecStart apontando para o nada.
            System.err.println("Erro: " + copia.detail());
            return 1;
        }

        AutostartPaths caminhosFinais = new AutostartPaths(
                caminhos.stateDir(), caminhos.hostsFile(), caminhos.javaBin(),
                jarInstalado.toString(), caminhos.classpath(), caminhos.realUserName(), os);

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
                + CliSupport.elevatedCommand("daemon enable"));
        return 0;
    }

    // POR QUE: a posse dos arquivos que o daemon escreve volta para o usuário
    // real, e não é user.name: sob sudo a JVM resolve user.name pelo uid (0 =
    // "root"), que o chownToUserIfConfigured recusa — sem devolução, o reset
    // --yes deixaria de funcionar sem sudo.

    // ATENÇÃO: SiteLockPaths.realUserName() lê SUDO_USER e já rejeita root;
    // sem sudo — de um pipeline, por exemplo — devolve vazio e o artefato não
    // embute usuário.
private String usuarioReal() {
        return SiteLockPaths.realUserName().orElse(null);
    }

    // systemd ausente é comum: container, WSL, distro enxuta. Fingir que
    // funcionou seria pior que dizer que não deu — a pessoa acharia que está
    // coberta quando não está.
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

    // O diretório que precisa ser gravável: no Linux e no macOS é o diretório
    // do artefato (/etc/systemd/system, /Library/LaunchDaemons); no Windows não
    // existe artefato em arquivo, e decide o diretório do jar (ProgramData/SiteBlock).
    private static Path diretorioDaInstalacao(OsType os, Path systemRoot) {
        Path artefato = AutostartInstaller.artifactPath(os, systemRoot);
        if (artefato != null) {
            Path parent = artefato.getParent();
            return parent != null ? parent : artefato;
        }
        return AutostartInstaller.installDir(os, systemRoot);
    }

    // Sobe até o ancestral existente mais próximo, porque o diretório ainda pode
    // não existir (/etc/systemd/system não existe numa máquina sem systemd):
    // o que decide é se chegamos a um diretório gravável.

    // ATENÇÃO: o argumento é o diretório que precisa ser gravável, e não o
    // arquivo do artefato — no Windows não existe arquivo.
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

    // A property primeiro; o classpath é reserva, e é um jar só no uso documentado.
    private String caminhoDoJar() {
        if (jarPath != null && !jarPath.isBlank()) {
            return jarPath;
        }
        String prop = System.getProperty("siteblock.jar");
        return prop == null || prop.isBlank() ? null : prop;
    }

    // O jar do daemon fica num diretório que só o root escreve: se o diretório de
    // instalação NÃO for do root — o caso de um diretório que já existia e era do
    // usuário — o daemon executa código do usuário como root.
    private String avisoDonoDoInstall(OsType os, Path systemRoot) {
        if (os.isWindows()) {
            // ATENÇÃO: no Windows não existe uid root nem chown — quem protege o
            // jar é a ACL da pasta, e o dono do ProgramData é o usuário que
            // instalou. Pedir um chown ali é pedir um comando que não existe.
            return null;
        }
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
