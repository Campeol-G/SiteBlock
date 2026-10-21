package com.sitelock.cli;

import com.sitelock.dns.DnsDaemonState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * O daemon filho tem que enxergar o mesmo estado do pai.
 *
 * <p>Sem {@code -Dsitelock.stateDir} e {@code -Dsitelock.user}, um daemon
 * iniciado como root (autostart, ou root sem SUDO_USER) opera em
 * {@code /root/.sitelock} enquanto o {@code dns status} olha
 * {@code ~/.sitelock}: o status diz "PARADO" com o servidor no ar, e o
 * {@code dns stop} não consegue encerrá-lo.
 *
 * <p>O teste lê a linha de comando real do processo filho ({@code /proc}).
 */
class DnsStartPropriedadesTest {

    @TempDir
    Path tmp;

    private static final int PORTA = 15396;

    @Test
    void oFilhoRecebeOMesmoStateDirEOUsuario() throws Exception {
        Path estado = tmp.resolve("estado");
        Files.createDirectories(estado);
        String stateDirOriginal = System.getProperty("sitelock.stateDir");
        String userOriginal = System.getProperty("sitelock.user");
        System.setProperty("sitelock.stateDir", estado.toAbsolutePath().toString());
        System.setProperty("sitelock.user", "fulano");
        try {
            int exit = new CommandLine(new DnsCommand()).execute(
                    "start",
                    "--port", String.valueOf(PORTA),
                    "--upstream", "127.0.0.1");
            try {
                assertEquals(0, exit, "o daemon deveria ter subido");
                DnsDaemonState state = DnsDaemonState.read(estado).orElseThrow();
                List<String> linha = linhaDeProcesso(state.getPid());
                assertTrue(linha.contains("-Dsitelock.stateDir=" + estado.toAbsolutePath()),
                        "o filho tem que usar o mesmo state dir do pai: " + linha);
                assertTrue(linha.contains("-Dsitelock.user=fulano"),
                        "o filho tem que saber a quem devolver a posse dos arquivos: " + linha);
            } finally {
                encerrar(estado);
            }
        } finally {
            restaura("sitelock.stateDir", stateDirOriginal);
            restaura("sitelock.user", userOriginal);
        }
    }

    /** A linha de comando do processo, lida do /proc. */
    private static List<String> linhaDeProcesso(long pid) throws IOException {
        Path cmdline = Path.of("/proc", String.valueOf(pid), "cmdline");
        assumeTrue(Files.exists(cmdline), "precisa de /proc para ler a linha de comando");
        String bruto = Files.readString(cmdline, StandardCharsets.ISO_8859_1);
        return List.of(bruto.split("\0"));
    }

    private static void restaura(String propriedade, String valor) {
        if (valor == null) {
            System.clearProperty(propriedade);
        } else {
            System.setProperty(propriedade, valor);
        }
    }

    /** Derruba o daemon que o teste subiu (o PID está no registro de estado). */
    private void encerrar(Path estado) {
        try {
            DnsDaemonState.read(estado).ifPresent(state -> {
                ProcessosDeTeste.matarPid(state.getPid());
            });
        } catch (IOException ignored) {
            // o teste só precisa que o processo não fique pendurado
        }
    }
}
