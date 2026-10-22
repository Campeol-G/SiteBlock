package com.sitelock.cli;

import com.sitelock.dns.DnsDaemonState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * O {@code dns start} só pode anunciar sucesso depois de ver a marca de
 * "estou escutando" no log do daemon.
 *
 * <p>O daemon filho é um processo de verdade, e o teste usa um {@code main}
 * compiles de mentira: ele fica vivo, mas nunca anuncia que está escutando —
 * exatamente o que acontecia quando o watchdog engolia a primeira falha. O
 * {@code start} antigo olhava só "o processo está vivo" e imprimia
 * "Servidor DNS iniciado".
 */
class DnsStartEscutaTest {

    @TempDir
    Path tmp;

    private static final int PORTA = 15398;

    @Test
    void semAMarcaDeEscutaOStartNaoAnunciaSucesso() throws Exception {
        Path stub = compilarDaemonMudo();
        String classpathOriginal = System.getProperty("java.class.path");
        System.setProperty("java.class.path", stub + File.pathSeparator + classpathOriginal);

        Path stateFile = tmp.resolve("blocks.json");
        Files.writeString(stateFile, "{}", StandardCharsets.UTF_8);
        Path estado = tmp; // o estado do daemon mora junto do --state-file

        ByteArrayOutputStream saida = new ByteArrayOutputStream();
        PrintStream original = System.out;
        System.setOut(new PrintStream(saida, true, StandardCharsets.UTF_8));
        int exit;
        try {
            exit = new CommandLine(new DnsCommand()).execute(
                    "start",
                    "--port", String.valueOf(PORTA),
                    "--state-file", stateFile.toString());
        } finally {
            System.setOut(original);
            System.setProperty("java.class.path", classpathOriginal);
            encerrar(estado);
        }

        assertEquals(1, exit,
                "sem a marca de escuta o start tem que falhar: " + saida.toString(StandardCharsets.UTF_8));
        assertFalse(saida.toString(StandardCharsets.UTF_8).contains("Servidor DNS iniciado"),
                "não pode anunciar que o servidor subiu");
    }

    // Compila um com.sitelock.SiteLockApplication de mentira: ele fica vivo
    // sem nunca escrever a marca de escuta.
    private Path compilarDaemonMudo() throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assumeTrue(compiler != null, "o teste precisa de um JDK (javac)");

        Path fontes = tmp.resolve("fonte-stub/com/sitelock");
        Files.createDirectories(fontes);
        Path fonte = fontes.resolve("SiteLockApplication.java");
        Files.writeString(fonte,
                "package com.sitelock;\n"
                        + "public class SiteLockApplication {\n"
                        + "    public static void main(String[] args) throws Exception {\n"
                        + "        Thread.sleep(600000);\n"
                        + "    }\n"
                        + "}\n", StandardCharsets.UTF_8);

        Path saida = tmp.resolve("classes-stub");
        Files.createDirectories(saida);
        int rc = compiler.run(null, null, null,
                "-d", saida.toAbsolutePath().toString(), fonte.toAbsolutePath().toString());
        assertEquals(0, rc, "não conseguiu compilar o daemon de mentira");
        return saida;
    }

    // Derruba o daemon que o teste subiu (o PID está no registro de estado).
    private void encerrar(Path estado) {
        try {
            Optional<DnsDaemonState> state = DnsDaemonState.read(estado);
            assertNotNull(state);
            if (state.isPresent() && state.get().getPid() > 0) {
                ProcessHandle.of(state.get().getPid()).ifPresent(ProcessosDeTeste::matar);
            }
        } catch (Exception ignored) {
            // o teste só precisa que o processo não fique pendurado
        }
    }
}
