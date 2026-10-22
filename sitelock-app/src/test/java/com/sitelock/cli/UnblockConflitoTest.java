package com.sitelock.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A confirmação do desbloqueio é digitada antes da transação. Se outro processo
 * mudar um dos alvos nesse intervalo, o comando não pode remover a versão nova
 * que a pessoa não confirmou.
 */
class UnblockConflitoTest {

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
        return tmp.resolve("state/blocks.json").toString();
    }

    @Test
    void unblockIndividualNaoRemoveEntradaQueMudou() throws Exception {
        assertEquals(0, new BlockCommand(List.of("tiktok.com"), "1h",
                hosts().toString(), state()).call());

        InputStream original = System.in;
        System.setIn(new StdinQueGrava(() -> {
            try {
                assertEquals(0, new BlockCommand(List.of("tiktok.com"), "10h",
                        hosts().toString(), state()).call());
            } catch (Exception e) {
                throw new UncheckedIOException(new java.io.IOException(e));
            }
        }, "SIM\n"));

        int exit;
        try {
            exit = new UnblockCommand(List.of("tiktok.com"), true,
                    hosts().toString(), state()).call();
        } finally {
            System.setIn(original);
        }

        assertEquals(1, exit, "conflito tem que falhar, não desfazer o block novo");
        String estado = Files.readString(Path.of(state()), StandardCharsets.UTF_8);
        assertTrue(estado.contains("tiktok.com"), estado);
        assertTrue(Files.readString(hosts(), StandardCharsets.UTF_8)
                .contains("127.0.0.1 tiktok.com"), "o hosts não pode perder a linha");
    }

    @Test
    void profileUnblockCancelaOLoteInteiroSeUmAlvoMudou() throws Exception {
        assertEquals(0, new BlockCommand(List.of("tiktok.com"), "1h",
                hosts().toString(), state()).call());
        assertEquals(0, new ProfileSaveCommand("trabalho", false,
                hosts().toString(), state()).call());

        InputStream original = System.in;
        System.setIn(new StdinQueGrava(() -> {
            try {
                assertEquals(0, new BlockCommand(List.of("tiktok.com"), "10h",
                        hosts().toString(), state()).call());
            } catch (Exception e) {
                throw new UncheckedIOException(new java.io.IOException(e));
            }
        }, "SIM\n"));

        int exit;
        try {
            exit = new ProfileUnblockCommand("trabalho", true,
                    hosts().toString(), state(), null).call();
        } finally {
            System.setIn(original);
        }

        assertEquals(1, exit, "um alvo alterado cancela o lote");
        String estado = Files.readString(Path.of(state()), StandardCharsets.UTF_8);
        assertTrue(estado.contains("tiktok.com"), estado);
        assertFalse(estado.isBlank(), estado);
        assertTrue(Files.readString(hosts(), StandardCharsets.UTF_8)
                .contains("127.0.0.1 tiktok.com"), "o hosts não pode perder a linha");
    }

    // Dispara a ação na primeira leitura do stdin: é exatamente o intervalo
    // entre a confirmação e a transação.
    private static final class StdinQueGrava extends InputStream {

        private final Runnable aoLer;
        private final byte[] conteudo;
        private int pos;
        private boolean disparou;

        StdinQueGrava(Runnable aoLer, String conteudo) {
            this.aoLer = aoLer;
            this.conteudo = conteudo.getBytes(StandardCharsets.UTF_8);
        }

        private void dispara() {
            if (!disparou) {
                disparou = true;
                aoLer.run();
            }
        }

        @Override
        public int read() {
            dispara();
            return pos < conteudo.length ? conteudo[pos++] & 0xFF : -1;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            dispara();
            if (pos >= conteudo.length) {
                return -1;
            }
            int n = Math.min(len, conteudo.length - pos);
            System.arraycopy(conteudo, pos, b, off, n);
            pos += n;
            return n;
        }
    }
}
