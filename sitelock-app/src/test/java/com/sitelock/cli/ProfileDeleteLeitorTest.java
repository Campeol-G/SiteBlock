package com.sitelock.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * O {@code profile delete} lê a confirmação do teclado sem engolir o resto.
 *
 * <p>O leitor era um {@code BufferedReader} novo por chamada: ele lia o stdin
 * num bloco e ficava com o resto da linha digitada no próprio buffer, que
 * morria com ele. A linha seguinte do teclado desaparecia.
 *
 * <p>O leitor é único para o processo todo: a linha que sobrou continua
 * disponível para quem vier a ler em seguida.
 */
class ProfileDeleteLeitorTest {

    @TempDir
    Path tmp;

    @Test
    void aConfirmacaoNaoEngoleORestoDoTeclado() throws Exception {
        Path hosts = tmp.resolve("hosts");
        Files.writeString(hosts, "127.0.0.1 localhost\n", StandardCharsets.UTF_8);
        Path stateFile = tmp.resolve("estado/blocks.json");
        Files.createDirectories(stateFile.getParent());
        assertEquals(0, new BlockCommand(List.of("tiktok.com"), null,
                hosts.toString(), stateFile.toString()).call());
        assertEquals(0, new ProfileSaveCommand("trabalho", false,
                hosts.toString(), stateFile.toString()).call());

        InputStream original = System.in;
        System.setIn(new ByteArrayInputStream("SIM\nRESTO\n".getBytes(StandardCharsets.UTF_8)));
        int exit;
        String resto;
        try {
            exit = new ProfileDeleteCommand("trabalho", null, stateFile.toString()).call();
            // A leitura acontece com o teclado virtual ainda no lugar: o
            // stdin do processo de teste não devolve nada, e ler dele trava.
            resto = CliSupport.readLine();
        } finally {
            System.setIn(original);
        }

        assertEquals(0, exit, "o SIM digitado tem que valer: o perfil foi apagado");
        assertEquals("RESTO", resto,
                "a linha seguinte não pode ter sido engolida pelo leitor da confirmação");
    }
}
