package com.sitelock.cli;

import com.sitelock.core.CommandRunner;
import com.sitelock.core.ExpirationDaemonState;
import com.sitelock.core.OsType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * O comando de elevação é o do SO de quem está lendo.
 *
 * <p>No Windows não existe sudo: quem eleva é o terminal, aberto como
 * Administrador. Um "sudo siteblock daemon enable" é um comando que não
 * existe na máquina de quem está lendo — e o texto que aponta o caminho
 * errado é pior do que a falta de texto.
 *
 * <p>O SO é simulado pela property {@code os.name}, que é de onde a detecção
 * do projeto sai.
 */
class ElevacaoComandosTest {

    @TempDir
    Path tmp;

    private String savedOsName;
    private String savedJar;

    @BeforeEach
    void guardarProperties() {
        savedOsName = System.getProperty("os.name");
        savedJar = System.getProperty("siteblock.jar");
    }

    @AfterEach
    void restaurarProperties() {
        System.setProperty("os.name", savedOsName);
        if (savedJar == null) {
            System.clearProperty("siteblock.jar");
        } else {
            System.setProperty("siteblock.jar", savedJar);
        }
    }

    @Test
    void oStatusNaoMandaSudoNoWindows() throws Exception {
        System.setProperty("os.name", "Windows 11");
        Path hosts = tmp.resolve("hosts");
        Files.writeString(hosts, "127.0.0.1 localhost\n", StandardCharsets.UTF_8);
        Path estado = tmp.resolve("estado/blocks.json");
        Files.createDirectories(estado.getParent());
        assertEquals(0, new BlockCommand(List.of("tiktok.com"), "4h",
                hosts.toString(), estado.toString()).call());

        String saida = capturar(() -> new StatusCommand(hosts.toString(), estado.toString()).call());

        assertFalse(saida.contains("sudo"), "no Windows não existe sudo: " + saida);
        assertTrue(saida.contains("daemon enable"), saida);
        assertTrue(saida.contains("Administrador"), saida);
    }

    @Test
    void oDaemonStatusNaoMandaSudoNoWindows() {
        System.setProperty("os.name", "Windows 11");
        Path estado = tmp.resolve("estado/blocks.json");

        String saida = capturar(() -> new DaemonStatusCommand(estado.toString(),
                tmp.resolve("sys")).call());

        assertFalse(saida.contains("sudo"), "no Windows não existe sudo: " + saida);
        assertTrue(saida.contains("daemon enable"), saida);
        assertTrue(saida.contains("Administrador"), saida);
    }

    @Test
    void oDaemonStartNaoMandaSudoNoWindows() throws Exception {
        System.setProperty("os.name", "Windows 11");
        Path hosts = tmp.resolve("hosts");
        Files.writeString(hosts, "127.0.0.1 localhost\n", StandardCharsets.UTF_8);
        Path estado = tmp.resolve("estado");
        Files.createDirectories(estado);
        // O aviso do daemon só aparece quando o jar está de verdade: é o
        // caminho que o daemon executa, e o texto aponta o comando certo.
        System.setProperty("siteblock.jar", buildJar().toString());

        String saida;
        try {
            saida = capturar(() -> new DaemonStartCommand("60", hosts.toString(),
                    estado.resolve("blocks.json").toString()).call());
        } finally {
            matarDaemon(estado);
        }

        assertFalse(saida.contains("sudo"), "no Windows não existe sudo: " + saida);
        assertTrue(saida.contains("daemon enable"), saida);
        assertTrue(saida.contains("Administrador"), saida);
    }

    // ATENÇÃO: o instalador é forçado para Linux de propósito — no Windows o
    // autostart é uma Tarefa do Agendador, que não existe nesta máquina. O
    // texto da elevação vem do SO detectado, que é o que o teste mede.
    /**
     * O {@code daemon disable} só fala do daemon que continua rodando quando
     * o autostart existia.
     */
    @Test
    void oDaemonDisableNaoMandaSudoNoWindows() throws Exception {
        System.setProperty("os.name", "Windows 11");
        Path raiz = tmp.resolve("sys");
        CommandRunner runner = c -> 0;
        // systemd fixado como presente: o macOS da CI não tem /run/systemd/system.
        assertEquals(0, new DaemonEnableCommand(OsType.LINUX, raiz,
                jarDeExemplo().toString(), runner, true).call());

        String saida = capturar(() -> new DaemonDisableCommand(OsType.LINUX, raiz, runner).call());

        assertFalse(saida.contains("sudo"), "no Windows não existe sudo: " + saida);
        assertTrue(saida.contains("daemon stop"), saida);
        assertTrue(saida.contains("Administrador"), saida);
    }

    @Test
    void oDaemonEnableNaoMandaSudoNoWindows() throws Exception {
        System.setProperty("os.name", "Windows 11");
        Path raiz = tmp.resolve("sys");
        CommandRunner runner = c -> 0;

        String instalado = capturar(() -> new DaemonEnableCommand(OsType.WINDOWS, raiz,
                jarDeExemplo().toString(), runner).call());

        assertFalse(instalado.contains("sudo"), "no Windows não existe sudo: " + instalado);
        assertTrue(instalado.contains("daemon enable"), instalado);
        assertTrue(instalado.contains("Administrador"), instalado);

        // E a outra ponta: sem agendador, o caminho manual também não pode
        // ser um sudo.
        String semAgendador = capturar(() -> new DaemonEnableCommand(OsType.WINDOWS, raiz,
                jarDeExemplo().toString(), runner, false).call());

        assertFalse(semAgendador.contains("sudo"), "no Windows não existe sudo: " + semAgendador);
        assertTrue(semAgendador.contains("daemon start"), semAgendador);
        assertTrue(semAgendador.contains("Administrador"), semAgendador);
    }

    private Path jarDeExemplo() throws Exception {
        Path jar = tmp.resolve("build/siteblock-1.0.0.jar");
        Files.createDirectories(jar.getParent());
        Files.writeString(jar, "jar de mentira");
        return jar;
    }

    // Um jar de verdade, com o Main-Class do manifest e o classpath inteiro
    // apontado pelo atributo Class-Path — o daemon sobe com ele, como sobe
    // em produção.
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

    // Mata o daemon que o start subiu, se ele tiver subido.
    private static void matarDaemon(Path estado) {
        try {
            ExpirationDaemonState.read(estado).ifPresent(state -> {
                ProcessHandle.of(state.getPid()).ifPresent(ProcessosDeTeste::matar);
            });
        } catch (Exception e) {
            // o teste que importa é o texto da elevação
        }
    }

    // O que o comando imprimiu, no stdout e no stderr.
    private static String capturar(ThrowingRunnable r) {
        PrintStream outOriginal = System.out;
        PrintStream errOriginal = System.err;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream fake = new PrintStream(buffer, true, StandardCharsets.UTF_8);
        System.setOut(fake);
        System.setErr(fake);
        try {
            r.run();
        } catch (Exception e) {
            throw new AssertionError(e);
        } finally {
            System.setOut(outOriginal);
            System.setErr(errOriginal);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
