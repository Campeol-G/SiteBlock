package com.sitelock.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * O artefato de autostart é testado como <b>texto gerado</b>, não como agendador:
 * o CI roda em Linux e Windows, nunca em macOS, então o {@code systemctl}/
 * {@code launchctl}/{@code schtasks} de verdade não pode ser exercitado.
 *
 * <p>A asserção que importa é de substring: o texto tem que conter o
 * {@code stateDir} e o {@code hostsFile} absolutos — é ela que pega o daemon
 * apontando para {@code /root}.
 */
class AutostartArtifactTest {

    private static final String STATE_DIR = "/home/chico/.sitelock";
    private static final String HOSTS = "/etc/hosts";
    private static final String JAVA = "/usr/lib/jvm/temurin-26/bin/java";
    private static final String JAR = "/opt/sitelock/siteblock.jar";
    private static final String CP = "/opt/sitelock/classes";

    @TempDir
    Path tmp;

    private AutostartPaths caminhos(OsType os) {
        return new AutostartPaths(Path.of(STATE_DIR), Path.of(HOSTS), JAVA, JAR, CP, "chico", os);
    }

    private AutostartPaths caminhos(OsType os, String jarPath) {
        return new AutostartPaths(Path.of(STATE_DIR), Path.of(HOSTS), JAVA, jarPath, CP, "chico", os);
    }

    @Test
    void systemdUnitContainsPinnedPaths() {
        String unit = AutostartArtifact.systemdUnit(caminhos(OsType.LINUX));
        assertTrue(unit.contains("-Dsitelock.stateDir=" + STATE_DIR), unit);
        assertTrue(unit.contains("-Dsitelock.hostsFile=" + HOSTS), unit);
        assertTrue(unit.contains(JAVA), unit);
        assertTrue(unit.contains(JAR), unit);
        assertFalse(unit.contains("user.home"),
                "o artefato não pode depender de user.home: sob unit de sistema ele é /root");
    }

    @Test
    void systemdUnitIsASystemUnit() {
        String unit = AutostartArtifact.systemdUnit(caminhos(OsType.LINUX));
        assertTrue(unit.contains("[Install]"), unit);
        assertTrue(unit.contains("WantedBy=multi-user.target"), unit);
        assertFalse(unit.contains("User="),
                "com User= o daemon não escreve no /etc/hosts — é unit de sistema de propósito");
    }

    @Test
    void launchdPlistContainsPinnedPaths() {
        String plist = AutostartArtifact.launchdPlist(caminhos(OsType.MACOS));
        assertTrue(plist.contains("-Dsitelock.stateDir=" + STATE_DIR), plist);
        assertTrue(plist.contains("-Dsitelock.hostsFile=" + HOSTS), plist);
        assertTrue(plist.contains(JAVA), plist);
        assertTrue(plist.contains(JAR), plist);
        assertTrue(plist.contains("com.sitelock.sweeper"), plist);
        assertTrue(plist.contains("<key>RunAtLoad</key>"), plist);
        assertFalse(plist.contains("user.home"), plist);
    }

    @Test
    void schtasksCommandContainsPinnedPaths() {
        String cmd = AutostartArtifact.schtasksCommand(caminhos(OsType.WINDOWS));
        assertTrue(cmd.contains("-Dsitelock.stateDir=" + STATE_DIR), cmd);
        assertTrue(cmd.contains("-Dsitelock.hostsFile=" + HOSTS), cmd);
        assertTrue(cmd.contains("/sc ONLOGON"), cmd);
        assertTrue(cmd.contains("/rl HIGHEST"), cmd);
        assertTrue(cmd.contains("/tn " + AutostartArtifact.TASK_NAME), cmd);
    }

    @Test
    void windowsTriggersOnLogonNotOnBoot() {
        String cmd = AutostartArtifact.schtasksCommand(caminhos(OsType.WINDOWS));
        assertFalse(cmd.contains("/sc ONSTART"), cmd);
        assertFalse(cmd.contains("/ru SYSTEM"), cmd);
    }

    @Test
    void artifactEmbedsTheRealUserName() {
        assertTrue(AutostartArtifact.systemdUnit(caminhos(OsType.LINUX)).contains("-Dsitelock.user=chico"));
        assertTrue(AutostartArtifact.launchdPlist(caminhos(OsType.MACOS)).contains("-Dsitelock.user=chico"));
        assertTrue(AutostartArtifact.schtasksCommand(caminhos(OsType.WINDOWS)).contains("-Dsitelock.user=chico"));
    }

    @Test
    void artifactEscapesSpacesInPaths() {
        AutostartPaths comEspaco = new AutostartPaths(
                Path.of("/home/chico/My Projects/.sitelock"),
                Path.of(HOSTS), "/opt/My JDK/bin/java", "/opt/My JDK/siteblock.jar",
                CP, "chico", OsType.LINUX);
        String unit = AutostartArtifact.systemdUnit(comEspaco);

        // O systemd não faz split de shell: a citação vai no token inteiro,
        // property e caminho juntos, ou o espaço viraria outro argumento.
        assertTrue(unit.contains("\"-Dsitelock.stateDir=/home/chico/My Projects/.sitelock\""), unit);
        assertTrue(unit.contains("\"/opt/My JDK/bin/java\""), unit);
        assertTrue(unit.contains("\"/opt/My JDK/siteblock.jar\""), unit);
    }

    @Test
    void artifactFallsBackToClasspathWhenNoJar() {
        String unit = AutostartArtifact.systemdUnit(caminhos(OsType.LINUX, null));
        assertTrue(unit.contains("-cp " + CP), unit);
        assertFalse(unit.contains("-jar"), unit);

        String plist = AutostartArtifact.launchdPlist(caminhos(OsType.MACOS, null));
        assertTrue(plist.contains("<string>-cp</string>"), plist);
        assertTrue(plist.contains("<string>" + CP + "</string>"), plist);
    }

    @Test
    void commandLineWithJarDoesNotRepeatTheMainClass() {
        // Com -jar, a JVM lê o Main-Class do manifest: repetir o nome da classe
        // aqui entrega um argumento órfão ao picocli, e o daemon morre com
        // código 2 antes de varrer qualquer coisa.
        List<String> cmd = AutostartArtifact.commandLine(caminhos(OsType.LINUX));
        assertFalse(cmd.contains("com.sitelock.SiteLockApplication"),
                "com -jar o nome da classe é redundante e quebra o daemon: " + cmd);
    }

    @Test
    void jvmPropertiesComeBeforeTheJar() {
        // Depois do -jar a JVM para de ler opções: o que vier ali é argumento do
        // programa, e o picocli recusa com "Unknown options".
        List<String> cmd = AutostartArtifact.commandLine(caminhos(OsType.LINUX));
        int prop = cmd.indexOf("-Dsitelock.stateDir=" + STATE_DIR);
        int jar = cmd.indexOf("-jar");
        assertTrue(prop >= 0 && jar >= 0 && prop < jar,
                "a property da JVM tem que vir antes do -jar: " + cmd);
    }

    @Test
    void commandLineWithClasspathKeepsTheMainClass() {
        // Sem -jar não há manifest: a JVM precisa do nome da classe.
        List<String> cmd = AutostartArtifact.commandLine(caminhos(OsType.LINUX, null));
        assertTrue(cmd.contains("com.sitelock.SiteLockApplication"),
                "sem -jar o nome da classe é obrigatório: " + cmd);
        assertTrue(cmd.indexOf("-cp") < cmd.indexOf("com.sitelock.SiteLockApplication"),
                "o classpath tem que vir antes da classe: " + cmd);
    }

    @Test
    void artifactPathPerOs() {
        assertEquals(Path.of("/etc/systemd/system/sitelock-sweeper.service"),
                AutostartInstaller.artifactPath(OsType.LINUX, Path.of("/")));
        assertEquals(Path.of("/Library/LaunchDaemons/com.sitelock.sweeper.plist"),
                AutostartInstaller.artifactPath(OsType.MACOS, Path.of("/")));
    }

    @Test
    void installThenDisableIsSymmetric() {
        RecordingRunner runner = new RecordingRunner();
        AutostartInstaller installer = new AutostartInstaller(OsType.LINUX, tmp, runner);

        assertFalse(AutostartInstaller.isEnabled(OsType.LINUX, tmp));

        AutostartInstaller.InstallResult installed = installer.install(caminhos(OsType.LINUX));
        assertTrue(installed.success(), installed.detail());
        assertTrue(Files.exists(installer.artifactPath()), "o artefato tem que existir depois do enable");
        assertTrue(AutostartInstaller.isEnabled(OsType.LINUX, tmp));

        AutostartInstaller.InstallResult removed = installer.disable();
        assertTrue(removed.success(), removed.detail());
        assertFalse(Files.exists(installer.artifactPath()), "o disable tem que remover o que o enable criou");
        assertFalse(AutostartInstaller.isEnabled(OsType.LINUX, tmp));
    }

    @Test
    void installRunsTheScheduler() {
        RecordingRunner runner = new RecordingRunner();
        new AutostartInstaller(OsType.LINUX, tmp, runner).install(caminhos(OsType.LINUX));
        assertTrue(runner.comandos.stream().anyMatch(c -> c.get(0).equals("systemctl")
                && c.contains("enable") && c.contains("--now")), runner.comandos.toString());

        RecordingRunner mac = new RecordingRunner();
        new AutostartInstaller(OsType.MACOS, tmp, mac).install(caminhos(OsType.MACOS));
        assertTrue(mac.comandos.stream().anyMatch(c -> c.get(0).equals("launchctl")
                && c.contains("bootstrap")), mac.comandos.toString());

        RecordingRunner win = new RecordingRunner();
        new AutostartInstaller(OsType.WINDOWS, tmp, win).install(caminhos(OsType.WINDOWS));
        assertTrue(win.comandos.stream().anyMatch(c -> c.get(0).equals("schtasks")
                && c.contains("/create")), win.comandos.toString());
    }

    @Test
    void disableRunsTheStopCommand() {
        RecordingRunner runner = new RecordingRunner();
        AutostartInstaller installer = new AutostartInstaller(OsType.LINUX, tmp, runner);
        installer.install(caminhos(OsType.LINUX));
        runner.comandos.clear();

        installer.disable();

        assertTrue(runner.comandos.stream().anyMatch(c -> c.get(0).equals("systemctl")
                && c.contains("disable") && c.contains("--now")), runner.comandos.toString());
    }

    private static final class RecordingRunner implements CommandRunner {
        final List<List<String>> comandos = new ArrayList<>();

        @Override
        public int run(List<String> command) {
            comandos.add(List.copyOf(command));
            return 0;
        }
    }
}