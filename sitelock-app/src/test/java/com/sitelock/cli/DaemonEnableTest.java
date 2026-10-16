package com.sitelock.cli;

import com.sitelock.core.AutostartArtifact;
import com.sitelock.core.AutostartInstaller;
import com.sitelock.core.AutostartPaths;
import com.sitelock.core.CommandRunner;
import com.sitelock.core.OsType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * daemon enable/disable — a instalação no boot.
 *
 * <p>Testar o agendador é impossível: o CI roda em Linux e Windows e nunca em
 * macOS, e nada aqui tem systemd. O que dá para testar, e é onde o bug mora, é
 * o <b>artefato gerado</b> e as recusas — sem root, sem systemd, e o aviso de
 * caminho gravável pelo usuário.
 */
class DaemonEnableTest {

    @TempDir
    Path tmp;

    private final List<List<String>> comandos = new ArrayList<>();
    private final CommandRunner runner = c -> {
        comandos.add(List.copyOf(c));
        return 0;
    };

    /** Exit code + stdout/stderr de um comando. */
    private record Resultado(int exit, String texto) {
    }

    private Resultado rodar(java.util.concurrent.Callable<Integer> cmd) {
        PrintStream outOriginal = System.out;
        PrintStream errOriginal = System.err;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream fake = new PrintStream(buffer, true, StandardCharsets.UTF_8);
        System.setOut(fake);
        System.setErr(fake);
        int exit;
        try {
            exit = cmd.call();
        } catch (Exception e) {
            throw new AssertionError(e);
        } finally {
            System.setOut(outOriginal);
            System.setErr(errOriginal);
        }
        return new Resultado(exit, buffer.toString(StandardCharsets.UTF_8));
    }

    private Path raiz() {
        return tmp.resolve("sys");
    }

    private Path artefato(OsType os) {
        return AutostartInstaller.artifactPath(os, raiz());
    }

    private DaemonEnableCommand enable(OsType os, String jarPath) {
        return new DaemonEnableCommand(os, raiz(), jarPath, runner);
    }

    @Test
    void enableWritesTheArtifactAndCallsTheScheduler() {
        Resultado r = rodar(enable(OsType.LINUX, "/opt/sitelock/siteblock.jar")::call);

        assertEquals(0, r.exit(), r.texto());
        assertTrue(Files.exists(artefato(OsType.LINUX)), "a unit tem que existir depois do enable");
        String unit = ler(artefato(OsType.LINUX));
        assertTrue(unit.contains("ExecStart="), unit);
        assertTrue(unit.contains("__run-daemon"), unit);
        assertTrue(comandos.stream().anyMatch(c -> c.get(0).equals("systemctl")
                && c.contains("enable") && c.contains("--now")), comandos.toString());
    }

    @Test
    void enableEmbedsThePinnedPaths() {
        rodar(enable(OsType.LINUX, "/opt/sitelock/siteblock.jar")::call);

        String unit = ler(artefato(OsType.LINUX));
        assertTrue(unit.contains("-Dsitelock.stateDir="), unit);
        assertTrue(unit.contains("-Dsitelock.hostsFile="), unit);
    }

    @Test
    void enableRefusesWithoutRootAndSaysHow() throws Exception {
        // Um diretório que existe mas não é gravável é o caso real: /etc é
        // root-owned. chmod POSIX não existe no Windows, que também roda a CI —
        // mesmo truque de HostsFileManagerPermsTest.
        Path semPermissao = tmp.resolve("sem-permissao");
        try {
            // Só `etc` existe e é somente-leitura: é ele que a checagem de
            // escrita encontra subindo a partir de etc/systemd/system.
            Files.createDirectories(semPermissao.resolve("etc"));
            Files.setPosixFilePermissions(semPermissao.resolve("etc"),
                    PosixFilePermissions.fromString("r-xr-xr-x"));
        } catch (UnsupportedOperationException e) {
            return;
        }

        Resultado r = rodar(new DaemonEnableCommand(OsType.LINUX, tmp.resolve("sem-permissao"),
                "/opt/sitelock/siteblock.jar", runner)::call);

        assertEquals(1, r.exit(), r.texto());
        assertTrue(r.texto().toLowerCase().contains("sudo")
                        || r.texto().toLowerCase().contains("administrador"),
                "a saída tem que dizer como elevar: " + r.texto());
        assertTrue(comandos.isEmpty(), "nem deve chamar o agendador: " + comandos);
    }

    @Test
    void linuxWithoutSystemdPrintsManualInstructions() {
        DaemonEnableCommand cmd = new DaemonEnableCommand(OsType.LINUX, raiz(),
                "/opt/sitelock/siteblock.jar", runner, false);

        Resultado r = rodar(cmd::call);

        assertEquals(1, r.exit(), r.texto());
        assertFalse(Files.exists(artefato(OsType.LINUX)),
                "sem systemd não há o que escrever — e não pode fingir que deu certo");
        assertTrue(comandos.isEmpty(), "não pode chamar systemctl onde ele não existe: " + comandos);
        assertTrue(r.texto().contains("daemon start"),
                "tem que oferecer o caminho manual: " + r.texto());
    }

    @Test
    void enableWarnsAboutWritableJarPath() throws Exception {
        Path jarGravavel = Files.createDirectories(tmp.resolve("Meu SiteBlock")).resolve("siteblock.jar");
        Files.writeString(jarGravavel, "x");

        Resultado r = rodar(enable(OsType.LINUX, jarGravavel.toString())::call);

        assertEquals(0, r.exit(), "instalar mesmo assim é o esperado: " + r.texto());
        assertTrue(r.texto().contains(jarGravavel.toString()),
                "o aviso precisa dizer qual caminho: " + r.texto());
        assertTrue(r.texto().contains("root"), r.texto());
    }

    @Test
    void disableRemovesTheArtifact() {
        rodar(enable(OsType.LINUX, "/opt/sitelock/siteblock.jar")::call);
        assertTrue(Files.exists(artefato(OsType.LINUX)));

        Resultado r = rodar(new DaemonDisableCommand(OsType.LINUX, raiz(), runner)::call);

        assertEquals(0, r.exit(), r.texto());
        assertFalse(Files.exists(artefato(OsType.LINUX)));
        assertTrue(comandos.stream().anyMatch(c -> c.get(0).equals("systemctl")
                && c.contains("disable")), comandos.toString());
    }

    @Test
    void disableWithoutInstallIsNotAnError() {
        Resultado r = rodar(new DaemonDisableCommand(OsType.LINUX, raiz(), runner)::call);
        assertEquals(0, r.exit(), r.texto());
    }

    @Test
    void macOsArtifactIsAPlist() {
        rodar(enable(OsType.MACOS, "/opt/sitelock/siteblock.jar")::call);

        assertTrue(Files.exists(artefato(OsType.MACOS)));
        String plist = ler(artefato(OsType.MACOS));
        assertTrue(plist.contains("com.sitelock.sweeper"), plist);
        assertTrue(plist.contains("<key>RunAtLoad</key>"), plist);
        assertTrue(comandos.stream().anyMatch(c -> c.get(0).equals("launchctl")), comandos.toString());
    }

    @Test
    void artifactCommandLineAlwaysEndsWithTheDaemonInvocation() {
        AutostartPaths p = new AutostartPaths(Path.of("/home/chico/.sitelock"), Path.of("/etc/hosts"),
                "/usr/bin/java", "/opt/sitelock.jar", "/opt/classes", "chico", OsType.LINUX);
        List<String> cmd = AutostartArtifact.commandLine(p);

        assertEquals("daemon", cmd.get(cmd.size() - 4));
        assertEquals("__run-daemon", cmd.get(cmd.size() - 3));
        assertTrue(cmd.contains("-Dsitelock.user=chico"), cmd.toString());
    }

    private static String ler(Path p) {
        try {
            return Files.readString(p);
        } catch (Exception e) {
            throw new AssertionError("não consegui ler " + p, e);
        }
    }
}