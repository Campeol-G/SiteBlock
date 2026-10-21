package com.sitelock.cli;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * O {@code block} que recusa.
 *
 * <p>Sem escrita no hosts, o comando tem que parar antes de mexer em
 * qualquer coisa: o estado gravado e o hosts intocado são a diferença entre
 * "não bloqueou" e "bloqueou sem registro em lugar nenhum".
 */
class BlockCommandTest {

    @TempDir
    Path tmp;

    private Path hosts;

    private Path hosts() throws Exception {
        if (hosts == null) {
            hosts = tmp.resolve("hosts");
            Files.writeString(hosts, "127.0.0.1 localhost\n", StandardCharsets.UTF_8);
        }
        return hosts;
    }

    private String state() {
        return tmp.resolve("estado/blocks.json").toString();
    }

    private record Resultado(int exit, String texto) {
    }

    private Resultado rodar(Comando cmd) throws Exception {
        PrintStream outOriginal = System.out;
        PrintStream errOriginal = System.err;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream fake = new PrintStream(buffer, true, StandardCharsets.UTF_8);
        System.setOut(fake);
        System.setErr(fake);
        int exit;
        try {
            exit = cmd.run();
        } catch (Exception e) {
            throw new AssertionError(e);
        } finally {
            System.setOut(outOriginal);
            System.setErr(errOriginal);
        }
        return new Resultado(exit, buffer.toString(StandardCharsets.UTF_8));
    }

    @FunctionalInterface
    private interface Comando {
        int run() throws Exception;
    }

    /**
     * Sem escrita no hosts, o block recusa — e não deixa rastro.
     *
     * <p>POR QUE: o estado é gravado antes do hosts. Se a recusa não vier
     * antes da gravação, o JSON diz "bloqueado" e o hosts não tem a linha: o
     * domínio fica preso no hosts sem registro, e o sweep (que varre o JSON)
     * nunca o limpa.
     */
    @Test
    void recusaComCodigo1QuandoOHostsNaoEhGravavel() throws Exception {
        Path arquivoHosts = hosts();
        String antes = Files.readString(arquivoHosts, StandardCharsets.UTF_8);
        // File.setReadOnly() funciona nos três SOs: no Unix é o bit de escrita,
        // no Windows o atributo de somente-leitura.
        assertTrue(arquivoHosts.toFile().setReadOnly(),
                "não deu para tirar a escrita do hosts de teste");
        Assumptions.assumeFalse(Files.isWritable(arquivoHosts),
                "neste ambiente o hosts continua gravável (root?): a recusa não valeria nada");

        Resultado r = rodar(() -> new BlockCommand(List.of("tiktok.com"), "4h",
                arquivoHosts.toString(), state()).call());

        assertEquals(1, r.exit(), r.texto());
        assertTrue(r.texto().toLowerCase().contains("sudo")
                        || r.texto().toLowerCase().contains("administrador"),
                "a recusa tem que dizer como elevar: " + r.texto());
        assertEquals(antes, Files.readString(arquivoHosts, StandardCharsets.UTF_8),
                "o hosts não pode ser tocado quando o comando recusa");
        assertFalse(Files.exists(Path.of(state())),
                "o estado não pode ser criado quando o comando recusa");
    }

    /**
     * No Windows a recusa fala em Administrador, não em sudo.
     *
     * <p>Só dá para verificar nesta máquina: a dica de elevação segue o SO
     * real, e simular o Windows não prova o texto que quem usa Windows lê.
     */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void noWindowsARecusaFalaEmAdministradorENaoEmSudo() throws Exception {
        Path arquivoHosts = hosts();
        assertTrue(arquivoHosts.toFile().setReadOnly(),
                "não deu para tirar a escrita do hosts de teste");
        Assumptions.assumeFalse(Files.isWritable(arquivoHosts),
                "neste ambiente o hosts continua gravável: a recusa não valeria nada");

        Resultado r = rodar(() -> new BlockCommand(List.of("tiktok.com"), "4h",
                arquivoHosts.toString(), state()).call());

        assertEquals(1, r.exit(), r.texto());
        assertTrue(r.texto().toLowerCase().contains("administrador"),
                "no Windows quem eleva é o terminal: " + r.texto());
        assertFalse(r.texto().toLowerCase().contains("sudo"),
                "no Windows não existe sudo: " + r.texto());
    }
}
