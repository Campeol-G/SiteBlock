package com.sitelock.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * O jar do daemon é instalado numa cópia estável.
 *
 * <p>O problema que isto resolve: a unit apontava para
 * {@code ~/Projects/.../target/siteblock-1.0.0.jar}, e um {@code mvn clean}
 * apaga o {@code target/}. A unit passava a apontar para o nada, o systemd
 * entrava em loop de crash, e o prazo dos bloqueios parava de ser cumprido
 * sem nenhuma mensagem que explicasse o motivo.
 *
 * <p>Além de sobreviver ao {@code mvn clean}, a cópia vive num diretório do
 * root — e isso resolve o outro aviso do projeto: o daemon deixa de executar
 * código de um caminho que o usuário pode trocar.
 */
class JarInstallTest {

    @TempDir
    Path tmp;

    private Path jarDeExemplo(String conteudo) throws IOException {
        Path jar = tmp.resolve("siteblock-1.0.0.jar");
        Files.writeString(jar, conteudo, StandardCharsets.UTF_8);
        return jar;
    }

    @Test
    void installDirPerOs() {
        assertEquals(Path.of("/opt/sitelock"),
                AutostartInstaller.installDir(OsType.LINUX, Path.of("/")));
        assertEquals(Path.of("/usr/local/lib/sitelock"),
                AutostartInstaller.installDir(OsType.MACOS, Path.of("/")));
        assertEquals(Path.of("/ProgramData/SiteBlock"),
                AutostartInstaller.installDir(OsType.WINDOWS, Path.of("/")));
    }

    @Test
    void installJarPathSitsInsideTheInstallDir() {
        assertEquals(Path.of("/opt/sitelock/siteblock.jar"),
                AutostartInstaller.installJarPath(OsType.LINUX, Path.of("/")));
    }

    @Test
    void installCopiesTheJarToTheStableLocation() throws Exception {
        Path origem = jarDeExemplo("versao 1");
        Path destino = AutostartInstaller.installJarPath(OsType.LINUX, tmp);

        AutostartInstaller.InstallResult r = AutostartInstaller.installJar(OsType.LINUX, tmp, origem);

        assertTrue(r.success(), r.detail());
        assertTrue(Files.exists(destino), "o jar tem que existir em " + destino);
        assertEquals("versao 1", Files.readString(destino, StandardCharsets.UTF_8));
    }

    @Test
    void installOverwritesAnOlderCopy() throws Exception {
        Path origem = jarDeExemplo("versao 1");
        AutostartInstaller.installJar(OsType.LINUX, tmp, origem);

        Files.writeString(origem, "versao 2", StandardCharsets.UTF_8);
        AutostartInstaller.installJar(OsType.LINUX, tmp, origem);

        assertEquals("versao 2", Files.readString(
                AutostartInstaller.installJarPath(OsType.LINUX, tmp), StandardCharsets.UTF_8),
                "reinstalar tem que atualizar a cópia — é assim que o daemon passa a rodar a "
                        + "versão nova depois de um rebuild");
    }

    @Test
    void installFailsClearlyWhenTheSourceIsMissing() {
        Path origem = tmp.resolve("nao-existe.jar");

        AutostartInstaller.InstallResult r =
                AutostartInstaller.installJar(OsType.LINUX, tmp, origem);

        assertFalse(r.success());
        assertTrue(r.detail().contains("nao-existe.jar"), r.detail());
    }

    @Test
    void installDoesNotCopyOntoItself() throws Exception {
        Path destino = AutostartInstaller.installJarPath(OsType.LINUX, tmp);
        Files.createDirectories(destino.getParent());
        Files.writeString(destino, "original", StandardCharsets.UTF_8);

        AutostartInstaller.InstallResult r =
                AutostartInstaller.installJar(OsType.LINUX, tmp, destino);

        assertTrue(r.success(), r.detail());
        assertEquals("original", Files.readString(destino, StandardCharsets.UTF_8),
                "copiar o jar para cima de si mesmo o apagaria");
    }

    @Test
    void theArtifactPointsAtTheInstalledCopyNotAtTheSource() throws Exception {
        Path origem = jarDeExemplo("versao 1");
        Path destino = AutostartInstaller.installJarPath(OsType.LINUX, tmp);
        AutostartInstaller.installJar(OsType.LINUX, tmp, origem);

        AutostartPaths caminhos = new AutostartPaths(
                Path.of("/home/chico/.sitelock"), Path.of("/etc/hosts"),
                "/usr/bin/java", destino.toString(), "/opt/classes", "chico", OsType.LINUX);
        String unit = AutostartArtifact.systemdUnit(caminhos);

        assertTrue(unit.contains(destino.toString()), unit);
        assertFalse(unit.contains(origem.toString()),
                "a unit não pode apontar para o jar de origem — é o que o mvn clean apaga:\n" + unit);
    }

    @Test
    void theInstalledCopyIsNotNextToTheBuildOutput() {
        Path origem = Path.of("/home/chico/Projects/SiteBlock/sitelock-app/target/siteblock-1.0.0.jar");
        Path destino = AutostartInstaller.installJarPath(OsType.LINUX, Path.of("/"));

        assertNotEquals(origem, destino);
        assertFalse(destino.toString().contains("target"),
                "o destino não pode estar dentro de target/: " + destino);
    }

    @Test
    void installDirIsCreatedWithTheJarInsideIt() throws Exception {
        Path origem = jarDeExemplo("versao 1");
        Path dir = AutostartInstaller.installDir(OsType.LINUX, tmp);

        assertFalse(Files.exists(dir));
        AutostartInstaller.installJar(OsType.LINUX, tmp, origem);

        assertTrue(Files.isDirectory(dir));
        assertTrue(Files.exists(dir.resolve("siteblock.jar")));
    }

    @Test
    void oResultadoSeparaOCaminhoDoJarDaMensagem() throws Exception {
        Path origem = jarDeExemplo("versao 1");

        AutostartInstaller.InstallResult r =
                AutostartInstaller.installJar(OsType.LINUX, tmp, origem);

        Path destino = AutostartInstaller.installJarPath(OsType.LINUX, tmp);
        assertTrue(r.success(), r.detail());
        assertEquals(destino, r.path(),
                "o caminho do jar vem separado da mensagem — misturados, a frase virava "
                        + "caminho da unit");
        assertTrue(r.detail().contains(destino.toString()), r.detail());
    }

    @Test
    void oResultadoTemOCaminhoQuandoOJarJaEstaNoLugar() throws Exception {
        // É o caso do enable rodado a partir do jar já instalado: o detail()
        // era a frase "O jar já está no lugar: ..." e a unit passava a apontar
        // para ela.
        Path destino = AutostartInstaller.installJarPath(OsType.LINUX, tmp);
        Files.createDirectories(destino.getParent());
        Files.writeString(destino, "original", StandardCharsets.UTF_8);

        AutostartInstaller.InstallResult r =
                AutostartInstaller.installJar(OsType.LINUX, tmp, destino);

        assertTrue(r.success(), r.detail());
        assertEquals(destino, r.path());
    }

    @Test
    void oInstallEDoDisableTrazemOCaminhoDoArtefato() {
        AutostartInstaller installer =
                new AutostartInstaller(OsType.LINUX, tmp, c -> 0);
        AutostartPaths caminhos = new AutostartPaths(
                Path.of("/home/chico/.sitelock"), Path.of("/etc/hosts"),
                "/usr/bin/java", "/opt/sitelock/siteblock.jar", "/opt/classes",
                "chico", OsType.LINUX);

        AutostartInstaller.InstallResult installed = installer.install(caminhos);
        assertTrue(installed.success(), installed.detail());
        assertEquals(installer.artifactPath(), installed.path());

        AutostartInstaller.InstallResult removed = installer.disable();
        assertTrue(removed.success(), removed.detail());
        assertEquals(installer.artifactPath(), removed.path());
    }

    @Test
    void listOfFilesInInstallDirIsJustTheJar() throws Exception {
        Path origem = jarDeExemplo("versao 1");
        AutostartInstaller.installJar(OsType.LINUX, tmp, origem);

        try (var stream = Files.list(AutostartInstaller.installDir(OsType.LINUX, tmp))) {
            List<String> nomes = stream.map(p -> p.getFileName().toString()).toList();
            assertEquals(List.of("siteblock.jar"), nomes);
        }
    }
}
