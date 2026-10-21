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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * O delta do {@link ProfileStore}.
 *
 * <p>O store grava um delta sobre o que está no disco, e não o arquivo
 * inteiro: uma escrita concorrente de quem não espera a trava não pode ser
 * apagada. Para isso, o store precisa saber o que mudou — e a comparação
 * precisa ser por valor. Por identidade, um perfil devolvido sem alteração
 * parecia mudado, e a gravação apagava a versão que outro escritor tinha
 * gravado por cima.
 */
class ProfileDeltaTest {

    @TempDir
    Path tmp;

    private static final Instant CRIADO = Instant.parse("2026-10-15T12:00:00Z");
    private static final Instant AGORA = Instant.parse("2026-10-15T18:30:00Z");

    private Path file() {
        return tmp.resolve("profiles.json");
    }

    private ProfileStore store() {
        return new ProfileStore(file());
    }

    private static Profile perfil(Long duracao) {
        Map<String, ProfileEntry> entradas = new TreeMap<>();
        entradas.put("x.com", new ProfileEntry(duracao));
        return new Profile(CRIADO, AGORA, entradas);
    }

    /** Grava o arquivo por fora da trava, como um escritor que não a respeita. */
    private void escreverPorForaDaTrava(Map<String, Profile> perfis) throws IOException {
        Files.writeString(file(), ProfileStore.defaultMapper()
                .writerWithDefaultPrettyPrinter()
                .writeValueAsString(new ProfileDocument(ProfileDocument.CURRENT_VERSION, perfis)),
                StandardCharsets.UTF_8);
    }

    @Test
    void umPerfilAlteradoTemQueSerGravado() throws Exception {
        ProfileStore store = store();
        store.save(Map.of("x", perfil(1000L)));

        store.inTransaction(tx -> {
            Map<String, Profile> perfis = tx.load();
            Map<String, ProfileEntry> entradas = new TreeMap<>(perfis.get("x").getEntries());
            entradas.put("y.com", new ProfileEntry(2000L));
            perfis.put("x", new Profile(CRIADO, AGORA.plusSeconds(60), entradas));
            tx.save(perfis);
            return null;
        });

        Map<String, ProfileEntry> entries = store.load().get("x").getEntries();
        assertTrue(entries.containsKey("y.com"),
                "a entrada nova tem que estar no disco: " + entries.keySet());
    }

    @Test
    void umPerfilQueNaoMudouNaoPodeApagarAVersaoDeOutroEscritor() throws Exception {
        ProfileStore store = store();
        store.save(Map.of("x", perfil(1000L)));

        store.inTransaction(tx -> {
            Map<String, Profile> perfis = tx.load();
            // A transação devolve o perfil sem mexer nele — mas como um objeto
            // novo, do jeito que um comando que remonte a receita devolveria.
            perfis.put("x", new Profile(CRIADO, AGORA, new TreeMap<>(
                    perfis.get("x").getEntries())));
            // Nesse meio-tempo, outro escritor muda o mesmo perfil no disco.
            escreverPorForaDaTrava(Map.of("x", perfil(9999L)));
            tx.save(perfis);
            return null;
        });

        assertEquals(Long.valueOf(9999L),
                store.load().get("x").getEntries().get("x.com").getDurationMillis(),
                "um perfil que não mudou não pode sobrescrever a versão de outro escritor");
    }

    @Test
    void umPerfilAlteradoNoLugarTemQueSerGravado() throws Exception {
        ProfileStore store = store();
        store.save(Map.of("x", perfil(1000L)));

        store.inTransaction(tx -> {
            Map<String, Profile> perfis = tx.load();
            // A alteração acontece no próprio perfil carregado, e não num
            // objeto novo: o delta tem que enxergar a mudança.
            perfis.get("x").getEntries().put("y.com", new ProfileEntry(2000L));
            tx.save(perfis);
            return null;
        });

        Map<String, ProfileEntry> entries = store.load().get("x").getEntries();
        assertTrue(entries.containsKey("y.com"),
                "a alteração no lugar tem que chegar ao disco: " + entries.keySet());
    }
}
