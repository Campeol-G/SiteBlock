package com.sitelock.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A transação do {@link BlockStore}.
 *
 * <p>O defeito que a transação existe para impedir: o {@code load → transforma →
 * save} solto é um read-modify-write sem proteção. O {@code save} trava, mas o
 * {@code load} acontece antes e fora — e o bloqueio feito por outro terminal
 * nesse intervalo é apagado do JSON enquanto continua no {@code /etc/hosts}.
 *
 * <p>O teste da escrita concorrente é determinístico de propósito: o outro
 * escritor escreve o arquivo por fora da trava (como faria outra versão da
 * ferramenta, um script ou uma edição manual), e a transação tem que aplicar a
 * própria mudança <b>por cima</b> do que está no disco, sem apagar o que o
 * outro escreveu.
 */
class BlockStoreTransactionTest {

    @TempDir
    Path tmp;

    private static final Instant AGORA = Instant.parse("2026-10-15T12:00:00Z");

    private static BlockEntry entrada(String domain) {
        return new BlockEntry(domain, AGORA, null);
    }

    /** Grava o estado por fora da trava, como um escritor que não a respeita. */
    private void escreverPorForaDaTrava(Path stateFile, Map<String, BlockEntry> estado)
            throws IOException {
        Files.writeString(stateFile, BlockStore.defaultMapper()
                .writerWithDefaultPrettyPrinter().writeValueAsString(estado),
                StandardCharsets.UTF_8);
    }

    @Test
    void escritaConcorrenteNaoSePerde() throws Exception {
        Path stateFile = tmp.resolve("blocks.json");
        BlockStore store = new BlockStore(stateFile);
        Map<String, BlockEntry> inicial = new TreeMap<>();
        inicial.put("a.com", entrada("a.com"));
        store.save(inicial);

        store.inTransaction(tx -> {
            Map<String, BlockEntry> state = tx.load();
            state.put("b.com", entrada("b.com"));

            // Outra via: enquanto a transação segura a trava, alguém grava o
            // estado por fora dela. É o "block de outro terminal": lê o que
            // está no disco, acrescenta o domínio e grava.
            Map<String, BlockEntry> concorrente = new TreeMap<>(store.load());
            concorrente.put("c.com", entrada("c.com"));
            escreverPorForaDaTrava(stateFile, concorrente);

            tx.save(state);
            return null;
        });

        Map<String, BlockEntry> depois = store.load();
        assertTrue(depois.containsKey("c.com"),
                "a escrita concorrente não pode se perder: " + depois.keySet());
        assertTrue(depois.containsKey("b.com"),
                "a mudança da transação tem que estar lá: " + depois.keySet());
        assertTrue(depois.containsKey("a.com"),
                "o que já existia tem que continuar: " + depois.keySet());
    }

    @Test
    void aTransacaoGravaOrdenado() throws Exception {
        Path stateFile = tmp.resolve("blocks.json");
        BlockStore store = new BlockStore(stateFile);

        store.inTransaction(tx -> {
            Map<String, BlockEntry> state = tx.load();
            state.put("zeta.com", entrada("zeta.com"));
            state.put("alfa.com", entrada("alfa.com"));
            state.put("mike.com", entrada("mike.com"));
            tx.save(state);
            return null;
        });

        String json = Files.readString(stateFile, StandardCharsets.UTF_8);
        int zeta = json.indexOf("zeta.com");
        int alfa = json.indexOf("alfa.com");
        int mike = json.indexOf("mike.com");
        assertTrue(alfa < mike && mike < zeta,
                "o JSON tem que sair ordenado: " + json);
    }
}
