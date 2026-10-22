package com.sitelock.cli;

import com.sitelock.dns.DnsDaemonState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * O {@code dns stop} lê a confirmação do teclado sem engolir o resto.
 *
 * <p>O leitor antigo era um {@code Scanner} novo por pergunta: ele lia o
 * stdin num buffer de 1 KB e consumia tudo o que a pessoa tinha digitado. A
 * pergunta seguinte não encontrava nada — era o defeito que já apertou o
 * {@code unblock} com dois domínios, em que o segundo era cancelado mesmo
 * com "SIM" digitado duas vezes.
 *
 * <p>O leitor é único para o processo todo: a linha que sobrou no teclado
 * continua disponível para quem vier a ler em seguida.
 */
class DnsStopLeitorTest {

    @TempDir
    Path tmp;

    @Test
    void aConfirmacaoNaoEngoleORestoDoTeclado() throws Exception {
        Process alvo = processoAuxiliar();
        try {
            Path stateFile = tmp.resolve("estado/blocks.json");
            Files.createDirectories(stateFile.getParent());
            DnsDaemonState.write(stateFile.getParent(), new DnsDaemonState(
                    alvo.pid(), 15399, "127.0.0.1", Instant.now(),
                    Instant.now().plusSeconds(3600)));

            InputStream original = System.in;
            System.setIn(new ByteArrayInputStream("SIM\nRESTO\n".getBytes(StandardCharsets.UTF_8)));
            int exit;
            String resto;
            try {
                exit = new DnsStopCommand(true, stateFile.toString()).call();
                // A leitura acontece com o teclado virtual ainda no lugar: o
                // stdin do processo de teste não devolve nada, e ler dele
                // trava.
                resto = CliSupport.readLine();
            } finally {
                System.setIn(original);
            }

            assertEquals(0, exit, "o SIM digitado tem que valer: a parada foi confirmada");
            assertEquals("RESTO", resto,
                    "a linha seguinte não pode ter sido engolida pelo leitor da confirmação");
        } finally {
            ProcessosDeTeste.matar(alvo);
            alvo.waitFor();
        }
    }

    // Um processo qualquer, que fica vivo até ser morto.
    private Process processoAuxiliar() throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(Paths.get(System.getProperty("java.home"), "bin", "java").toString());
        cmd.add("-cp");
        cmd.add(System.getProperty("java.class.path"));
        cmd.add(Dorme.class.getName());
        return new ProcessBuilder(cmd).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
    }

    /** Fica vivo até receber o sinal. */
    public static class Dorme {
        public static void main(String[] args) throws Exception {
            Thread.sleep(300_000);
        }
    }
}
