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

    /** Um jar de verdade: o enable copia o arquivo, e copia de arquivo inexistente falha. */
    private Path jarDeExemplo() throws Exception {
        Path jar = tmp.resolve("build/siteblock-1.0.0.jar");
        Files.createDirectories(jar.getParent());
        Files.writeString(jar, "jar de mentira");
        return jar;
    }

    private Path artefato(OsType os) {
        return AutostartInstaller.artifactPath(os, raiz());
    }

    private DaemonEnableCommand enable(OsType os, String jarPath) {
        return new DaemonEnableCommand(os, raiz(), jarPath, runner);
    }

    @Test
    void enableWritesTheArtifactAndCallsTheScheduler() throws Exception {
        Resultado r = rodar(enable(OsType.LINUX, jarDeExemplo().toString())::call);

        assertEquals(0, r.exit(), r.texto());
        assertTrue(Files.exists(artefato(OsType.LINUX)), "a unit tem que existir depois do enable");
        String unit = ler(artefato(OsType.LINUX));
        assertTrue(unit.contains("ExecStart="), unit);
        assertTrue(unit.contains("__run-daemon"), unit);
        assertTrue(comandos.stream().anyMatch(c -> c.get(0).equals("systemctl")
                && c.contains("enable") && c.contains("--now")), comandos.toString());
    }

    @Test
    void enableEmbedsThePinnedPaths() throws Exception {
        rodar(enable(OsType.LINUX, jarDeExemplo().toString())::call);

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
                jarDeExemplo().toString(), runner)::call);

        assertEquals(1, r.exit(), r.texto());
        assertTrue(r.texto().toLowerCase().contains("sudo")
                        || r.texto().toLowerCase().contains("administrador"),
                "a saída tem que dizer como elevar: " + r.texto());
        assertTrue(comandos.isEmpty(), "nem deve chamar o agendador: " + comandos);
    }

    @Test
    void enableNoWindowsNaoEstouraExcecao() throws Exception {
        // O Windows é suportado pelo projeto, e o enable estourava NPE: o
        // artifactPath devolve null lá, e a checagem de elevação dereferenciava.
        Resultado r = rodar(enable(OsType.WINDOWS, jarDeExemplo().toString())::call);

        assertEquals(0, r.exit(), r.texto());
        assertTrue(comandos.stream().anyMatch(c -> c.get(0).equals("schtasks")
                && c.contains("/create")), comandos.toString());
    }

    @Test
    void enableNoWindowsRecusaSemElevacaoQuandoODiretorioDoJarNaoEhGravavel() throws Exception {
        // No Windows não existe artefato em arquivo: o que precisa ser
        // gravável é o diretório onde o jar é instalado.
        Path raizWin = tmp.resolve("sys-win");
        Path dir = raizWin.resolve("ProgramData/SiteBlock");
        Files.createDirectories(dir);
        try {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("r-xr-xr-x"));
        } catch (UnsupportedOperationException e) {
            // Sem POSIX não há como simular diretório sem escrita (o CI também
            // roda no Windows). Mesma técnica de enableRefusesWithoutRootAndSaysHow.
            return;
        }

        Resultado r = rodar(new DaemonEnableCommand(OsType.WINDOWS, raizWin,
                jarDeExemplo().toString(), runner)::call);

        assertEquals(1, r.exit(), r.texto());
        assertTrue(r.texto().toLowerCase().contains("sudo")
                        || r.texto().toLowerCase().contains("administrador"),
                "a saída tem que dizer como elevar: " + r.texto());
        assertTrue(comandos.isEmpty(), "nem deve chamar o agendador: " + comandos);
    }

    @Test
    void linuxWithoutSystemdPrintsManualInstructions() throws Exception {
        DaemonEnableCommand cmd = new DaemonEnableCommand(OsType.LINUX, raiz(),
                jarDeExemplo().toString(), runner, false);

        Resultado r = rodar(cmd::call);

        assertEquals(1, r.exit(), r.texto());
        assertFalse(Files.exists(artefato(OsType.LINUX)),
                "sem systemd não há o que escrever — e não pode fingir que deu certo");
        assertTrue(comandos.isEmpty(), "não pode chamar systemctl onde ele não existe: " + comandos);
        assertTrue(r.texto().contains("daemon start"),
                "tem que oferecer o caminho manual: " + r.texto());
    }

    @Test
    void enableInstallsTheJarInAStableLocationAndPointsThere() throws Exception {
        Path origem = jarDeExemplo();
        Resultado r = rodar(enable(OsType.LINUX, origem.toString())::call);

        assertEquals(0, r.exit(), r.texto());
        Path estavel = AutostartInstaller.installJarPath(OsType.LINUX, raiz());
        assertTrue(Files.exists(estavel), "o jar tem que estar em " + estavel);
        assertTrue(r.texto().contains(estavel.toString()), r.texto());

        String unit = ler(AutostartInstaller.artifactPath(OsType.LINUX, raiz()));
        assertTrue(unit.contains(estavel.toString()),
                "a unit tem que apontar para a cópia estável: " + unit);
        assertFalse(unit.contains(origem.toString()),
                "a unit não pode apontar para o jar de build — é o que o mvn clean apaga: " + unit);
    }

    @Test
    void enableRefreshesTheInstalledCopyOnASecondRun() throws Exception {
        Path origem = jarDeExemplo();
        assertEquals(0, rodar(enable(OsType.LINUX, origem.toString())::call).exit());

        Files.writeString(origem, "versao nova", StandardCharsets.UTF_8);
        assertEquals(0, rodar(enable(OsType.LINUX, origem.toString())::call).exit());

        assertEquals("versao nova", Files.readString(
                AutostartInstaller.installJarPath(OsType.LINUX, raiz()), StandardCharsets.UTF_8),
                "reinstalar tem que atualizar a cópia — é assim que o daemon roda a versão nova");
    }

    @Test
    void theOutputNeverRepeatsSudo() throws Exception {
        // O sudoCommand() já traz o "sudo"; prefixar de novo produzia
        // "sudo sudo siteblock daemon enable", que é o comando que não existe.
        Resultado r = rodar(enable(OsType.LINUX, jarDeExemplo().toString())::call);

        assertFalse(r.texto().contains("sudo sudo"),
                "a saída não pode repetir o sudo:\n" + r.texto());
        assertTrue(r.texto().contains("sudo siteblock daemon enable"),
                "a saída tem que mostrar o comando:\n" + r.texto());
    }

    @Test
    void theOutputIsShort() throws Exception {
        // Três linhas: o que aconteceu, onde ficou o jar, e o que fazer depois
        // de recompilar. O caminho do artefato é interno e não ajuda ninguém.
        Resultado r = rodar(enable(OsType.LINUX, jarDeExemplo().toString())::call);

        // O aviso do dono do diretório é condicional: em produção o /opt/sitelock
        // é criado pelo root e ele não aparece. Nos testes o diretório é do
        // usuário, então ele aparece — e por isso fica de fora da contagem.
        long linhas = r.texto().strip().lines()
                .filter(l -> !l.startsWith("Aviso:"))
                .count();
        assertTrue(linhas <= 3,
                "a saída do enable não deve ter mais que 3 linhas:\n" + r.texto());
        assertFalse(r.texto().contains("Artefato:"),
                "o caminho do artefato é interno:\n" + r.texto());
    }

    @Test
    void enableFailsClearlyWhenTheSourceJarIsMissing() {
        Resultado r = rodar(enable(OsType.LINUX, tmp.resolve("nao-existe.jar").toString())::call);

        assertEquals(1, r.exit(), r.texto());
        assertTrue(r.texto().contains("nao-existe.jar"), r.texto());
        assertTrue(r.texto().contains("mvn package"), "tem que dizer como gerar o jar: " + r.texto());
    }

    @Test
    void aUnitApontaParaOCaminhoDoJarEAoParaUmaFrase() throws Exception {
        // Rodar o enable a partir do jar JÁ INSTALADO é o caminho natural para
        // atualizar a versão — e é o que gerava o ExecStart inválido: o detail()
        // do installJar era uma frase, e a unit passava a apontar para ela.
        Path origem = jarDeExemplo();
        assertEquals(0, rodar(enable(OsType.LINUX, origem.toString())::call).exit());

        Path instalado = AutostartInstaller.installJarPath(OsType.LINUX, raiz());
        Resultado r = rodar(enable(OsType.LINUX, instalado.toString())::call);

        assertEquals(0, r.exit(), r.texto());
        String unit = ler(artefato(OsType.LINUX));
        assertEquals(instalado.toString(), caminhoDoJarNaUnit(unit),
                "a unit tem que apontar para o jar: " + unit);
        assertFalse(unit.contains("O jar já está no lugar"),
                "a unit não pode apontar para uma frase: " + unit);
        assertTrue(r.texto().contains("Jar: " + instalado), r.texto());
    }

    @Test
    void disableRemovesTheArtifact() throws Exception {
        rodar(enable(OsType.LINUX, jarDeExemplo().toString())::call);
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
    void macOsArtifactIsAPlist() throws Exception {
        rodar(enable(OsType.MACOS, jarDeExemplo().toString())::call);

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

    /** O caminho do jar na linha ExecStart da unit. */
    private static String caminhoDoJarNaUnit(String unit) {
        for (String linha : unit.split("\n")) {
            if (!linha.startsWith("ExecStart=")) {
                continue;
            }
            int i = linha.indexOf("-jar ");
            if (i < 0) {
                continue;
            }
            String resto = linha.substring(i + "-jar ".length()).trim();
            if (resto.startsWith("\"")) {
                return resto.substring(1, resto.indexOf('"', 1));
            }
            return resto.split("\\s+")[0];
        }
        throw new AssertionError("a unit não tem -jar:\n" + unit);
    }

    private static String ler(Path p) {
        try {
            return Files.readString(p);
        } catch (Exception e) {
            throw new AssertionError("não consegui ler " + p, e);
        }
    }
}