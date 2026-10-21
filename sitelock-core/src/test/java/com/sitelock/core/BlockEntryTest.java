package com.sitelock.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * O {@code createdAt} de um bloqueio.
 *
 * <p>Ele responde "desde quando este bloqueio existe", e é o que o
 * {@code ProfileMerge} preserva quando um prazo é elevado. Um arquivo escrito
 * à mão — ou por uma versão antiga — pode não ter o campo; nesse caso o
 * instante é <b>desconhecido</b>, e inventar "agora" muda a semântica sem
 * nenhum erro visível: a data exibida mente.
 */
class BlockEntryTest {

    private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");

    @Test
    void entradaSemCreatedAtFicaComCreatedAtNulo() {
        BlockEntry entrada = new BlockEntry("tiktok.com", null, null);

        assertNull(entrada.getCreatedAt(),
                "sem o campo no arquivo não há instante a inventar");
    }

    @Test
    void jsonSemCreatedAtNaoGeraCreatedAt() throws Exception {
        BlockEntry entrada = BlockStore.defaultMapper()
                .readValue("{\"domain\": \"tiktok.com\", \"expiresAt\": null}", BlockEntry.class);

        assertNull(entrada.getCreatedAt(),
                "um blocks.json sem o campo não pode ganhar a data de leitura");
    }

    @Test
    void roundTripPreservaCreatedAtNulo(@TempDir Path tmp) throws Exception {
        Path stateFile = tmp.resolve("blocks.json");
        BlockStore store = new BlockStore(stateFile);
        Map<String, BlockEntry> blocks = new TreeMap<>();
        blocks.put("tiktok.com", new BlockEntry("tiktok.com", null, null));
        store.save(blocks);

        Map<String, BlockEntry> loaded = store.load();

        assertNull(loaded.get("tiktok.com").getCreatedAt(),
                "o estado não pode manufacturear o instante que o arquivo não tem");
    }

    @Test
    void prazoElevadoNaoFabricaCreatedAt() {
        Map<String, BlockEntry> estado = new TreeMap<>();
        estado.put("tiktok.com", new BlockEntry("tiktok.com", null, NOW.plusSeconds(1800)));
        Map<String, ProfileEntry> entradas = new TreeMap<>();
        entradas.put("tiktok.com", new ProfileEntry(8 * 3600_000L));
        Profile perfil = new Profile(NOW, NOW, entradas);

        Map<String, ProfileMerge.Decision> out = ProfileMerge.merge(estado, perfil, NOW);

        assertEquals(ProfileMerge.Action.RAISED, out.get("tiktok.com").action());
        assertNull(out.get("tiktok.com").entry().getCreatedAt(),
                "elevar o prazo não pode inventar a origem do bloqueio");
    }

    @Test
    void mantidoContinuaSendoAMesmaInstancia() {
        Map<String, BlockEntry> estado = new TreeMap<>();
        estado.put("tiktok.com", new BlockEntry("tiktok.com", null, NOW.plusSeconds(3600)));
        BlockEntry original = estado.get("tiktok.com");
        Map<String, ProfileEntry> entradas = new TreeMap<>();
        entradas.put("tiktok.com", new ProfileEntry(30 * 60_000L));
        Profile perfil = new Profile(NOW, NOW, entradas);

        Map<String, ProfileMerge.Decision> out = ProfileMerge.merge(estado, perfil, NOW);

        assertEquals(ProfileMerge.Action.KEPT, out.get("tiktok.com").action());
        assertSame(original, out.get("tiktok.com").entry(),
                "um KEPT devolve a mesma instância: o prazo não pode ser reescrito");
    }
}
