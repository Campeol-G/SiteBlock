package com.sitelock.cli;

import com.sitelock.core.AutostartArtifact;
import com.sitelock.core.AutostartPaths;
import com.sitelock.core.ExpirationDaemonState;
import com.sitelock.core.OsType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A linha de comando que sobe o daemon tem que funcionar de verdade.
 *
 * <p>Este é o teste que reproduz o defeito original. A unit do systemd executa
 * a linha gerada pelo {@link AutostartArtifact}; se a linha estiver malformada,
 * o processo morre com código 2 e o systemd entra em loop de crash — que foi
 * exatamente o que aconteceu: milhares de restarts e nenhum daemon no ar.
 *
 * <p>Por isso o teste não confere a linha com {@code assertEquals} e para aí.
 * Ele <b>executa</b> a linha contra um jar de verdade e exige que o daemon
 * suba. Um {@code assert} sobre a forma da lista passaria com
 * {@code java -jar x.jar -Dfoo=1 Main} — que é justamente a linha quebrada.
 */
class DaemonLaunchTest {

    @TempDir
    Path tmp;

    // ---- a linha de comando do fork do "daemon start" ----

    @Test
    void childCommandPutsPropertiesBeforeTheJar() {
        String saved = System.getProperty("siteblock.jar");
        System.setProperty("siteblock.jar", "/opt/sitelock/siteblock.jar");
        try {
            List<String> cmd = new DaemonStartCommand("60", null, null)
                    .buildChildCommand(tmp.resolve("estado"), tmp.resolve("hosts"));

            int prop = cmd.indexOf("-Dsitelock.stateDir=" + tmp.resolve("estado").toAbsolutePath());
            int jar = cmd.indexOf("-jar");
            assertTrue(prop >= 0 && jar >= 0 && prop < jar,
                    "depois do -jar a property vira argumento do programa e o picocli recusa: " + cmd);
        } finally {
            if (saved == null) {
                System.clearProperty("siteblock.jar");
            } else {
                System.setProperty("siteblock.jar", saved);
            }
        }
    }

    @Test
    void childCommandWithJarDropsTheMainClass() {
        String saved = System.getProperty("siteblock.jar");
        System.setProperty("siteblock.jar", "/opt/sitelock/siteblock.jar");
        try {
            List<String> cmd = new DaemonStartCommand("60", null, null)
                    .buildChildCommand(tmp.resolve("estado"), tmp.resolve("hosts"));

            assertFalse(cmd.contains("com.sitelock.SiteLockApplication"),
                    "com -jar o nome da classe é redundante e quebra o daemon: " + cmd);
        } finally {
            if (saved == null) {
                System.clearProperty("siteblock.jar");
            } else {
                System.setProperty("siteblock.jar", saved);
            }
        }
    }

    // ---- a prova de que a linha gerada sobe o daemon ----

    @Test
    void theGeneratedCommandActuallyStartsTheDaemon() throws Exception {
        Path jar = buildJar();
        Path hosts = tmp.resolve("hosts");
        Files.writeString(hosts, "127.0.0.1 localhost\n", StandardCharsets.UTF_8);
        Path estado = tmp.resolve("estado");
        Files.createDirectories(estado);

        AutostartPaths caminhos = new AutostartPaths(
                estado, hosts,
                Paths.get(System.getProperty("java.home"), "bin", "java").toString(),
                jar.toString(), "", null, OsType.LINUX);

        List<String> cmd = AutostartArtifact.commandLine(caminhos);
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(
                        tmp.resolve("daemon.log").toFile()))
                .start();

        try {
            Path daemonJson = ExpirationDaemonState.statePath(estado);
            for (int i = 0; i < 150 && !Files.exists(daemonJson); i++) {
                Thread.sleep(100);
            }

            assertTrue(Files.exists(daemonJson),
                    "o daemon não subiu. Linha: " + cmd + "\nLog:\n" + log());
            assertTrue(p.isAlive(), "o daemon subiu e morreu:\n" + log());
        } finally {
            p.destroyForcibly();
            p.waitFor();
        }
    }

    private String log() {
        try {
            Path l = tmp.resolve("daemon.log");
            return Files.exists(l) ? Files.readString(l, StandardCharsets.UTF_8) : "(sem log)";
        } catch (Exception e) {
            return "(sem log)";
        }
    }

    // Um jar de verdade, com o Main-Class do manifest e o classpath inteiro
    // no atributo Class-Path: a mesma forma do fat-jar que o run.sh usa e
    // que a unit do systemd consome. Um jar vazio não exercitaria o -jar.
    private Path buildJar() throws Exception {
        StringBuilder classPath = new StringBuilder();
        for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
            if (entry.isBlank()) {
                continue;
            }
            if (classPath.length() > 0) {
                classPath.append(' ');
            }
            classPath.append(Path.of(entry).toUri());
        }

        Manifest mf = new Manifest();
        mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        mf.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "com.sitelock.SiteLockApplication");
        mf.getMainAttributes().put(Attributes.Name.CLASS_PATH, classPath.toString());

        Path jar = tmp.resolve("siteblock-teste.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar), mf)) {
            // POR QUE: jar sem entradas — as classes vêm do Class-Path do manifest.
        }
        return jar;
    }
}
