package com.sitelock.cli;

import com.sitelock.core.BlockEntry;
import com.sitelock.core.BlockStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * siteblock reset — apaga o estado, não toca no hosts.
 *
 * <p>O reset é o caminho de recuperação do estado corrompido, então ele tem que
 * funcionar justamente quando o arquivo está quebrado — e tem que recusar
 * quando ninguém pediu para apagar.
 */
class ResetCommandTest {

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

    private Map<String, BlockEntry> estado() throws Exception {
        return new BlockStore(Path.of(state())).load();
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

    @Test
    void resetComYesApagaOEstado() throws Exception {
        assertEquals(0, new BlockCommand(List.of("tiktok.com"), null,
                hosts().toString(), state()).call());
        assertTrue(estado().containsKey("tiktok.com"), "pré-condição: o estado tem o domínio");

        Resultado r = rodar(() -> new ResetCommand(true, state()).call());

        assertEquals(0, r.exit(), r.texto());
        assertFalse(estado().containsKey("tiktok.com"),
                "o reset tem que apagar o estado: " + estado());
        assertTrue(estado().isEmpty(), "o estado recriado tem que estar vazio: " + estado());
    }

    @Test
    void resetNaoTocaNoHosts() throws Exception {
        assertEquals(0, new BlockCommand(List.of("tiktok.com"), null,
                hosts().toString(), state()).call());
        String antes = Files.readString(hosts(), StandardCharsets.UTF_8);

        Resultado r = rodar(() -> new ResetCommand(true, state()).call());

        assertEquals(0, r.exit(), r.texto());
        assertEquals(antes, Files.readString(hosts(), StandardCharsets.UTF_8),
                "o reset só mexe no estado — o hosts continua com o bloqueio");
        assertTrue(r.texto().toLowerCase().contains("hosts"),
                "a saída tem que lembrar que o hosts não foi alterado: " + r.texto());
    }

    @Test
    void resetSemYesRecusaEApagaNada() throws Exception {
        assertEquals(0, new BlockCommand(List.of("tiktok.com"), null,
                hosts().toString(), state()).call());
        String antes = Files.readString(Path.of(state()), StandardCharsets.UTF_8);

        Resultado r = rodar(() -> new ResetCommand(false, state()).call());

        assertEquals(2, r.exit(), r.texto());
        assertEquals(antes, Files.readString(Path.of(state()), StandardCharsets.UTF_8),
                "sem --yes nada pode ser apagado");
        assertTrue(r.texto().contains("--yes"),
                "a recusa tem que dizer como confirmar: " + r.texto());
    }

    /**
     * O reset é o conserto do estado corrompido: é para isso que ele existe.
     */
    @Test
    void resetRecriaUmEstadoCorrompido() throws Exception {
        Files.createDirectories(Path.of(state()).getParent());
        Files.writeString(Path.of(state()), "{ isto não é json", StandardCharsets.UTF_8);

        Resultado r = rodar(() -> new ResetCommand(true, state()).call());

        assertEquals(0, r.exit(), r.texto());
        assertTrue(estado().isEmpty(), "o estado recriado tem que estar vazio: " + estado());
    }

    /**
     * Quando o estado não pode ser gravado, o reset tem que dizer que falhou.
     *
     * <p>O caminho do pai é um arquivo comum: nenhum sistema deixa criar o
     * diretório do estado por baixo dele.
     */
    @Test
    void resetFalhaComMensagemQuandoOEstadoNaoPodeSerGravado() throws Exception {
        Path arquivo = tmp.resolve("nao-e-diretorio");
        Files.writeString(arquivo, "sou um arquivo", StandardCharsets.UTF_8);
        String stateImpossivel = arquivo.resolve("estado/blocks.json").toString();

        Resultado r = rodar(() -> new ResetCommand(true, stateImpossivel).call());

        assertEquals(1, r.exit(), r.texto());
        assertTrue(r.texto().toLowerCase().contains("erro"),
                "a falha tem que ser dita: " + r.texto());
    }
}
