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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Persistência do profiles.json. O contrato que importa mais é o do fim do
 * {@code BlockStore}: JSON ilegível vira {@link CorruptedStateException} e
 * <b>não apaga nada</b>. Um perfil é trabalho digitado; perder um por causa de
 * uma aspa sobrando seria a pior falha possível aqui.
 */
class ProfileStoreTest {

    @TempDir
    Path tmp;

    private ProfileStore store() {
        return new ProfileStore(tmp.resolve("profiles.json"));
    }

    private static Profile profile(Instant at, Map<String, Long> entries) {
        Map<String, ProfileEntry> m = new TreeMap<>();
        entries.forEach((d, ms) -> m.put(d, new ProfileEntry(ms)));
        return new Profile(at, at, m);
    }

    /** {@code Map.of} não aceita valor nulo, e "permanente" é justamente {@code null}. */
    private static Map<String, Long> durations(Object... pares) {
        Map<String, Long> m = new TreeMap<>();
        for (int i = 0; i < pares.length; i += 2) {
            m.put((String) pares[i], (Long) pares[i + 1]);
        }
        return m;
    }

    @Test
    void roundTripsProfiles() throws Exception {
        Instant at = Instant.parse("2026-10-15T14:02:11Z");
        Map<String, Profile> profiles = new TreeMap<>();
        profiles.put("trabalho", profile(at, durations("youtube.com", 28800000L, "tiktok.com", null)));
        profiles.put("fim-de-semana", profile(at, durations("instagram.com", 3600000L)));

        store().save(profiles);
        Map<String, Profile> read = store().load();

        assertEquals(2, read.size());
        Profile t = read.get("trabalho");
        assertEquals(at, t.getCreatedAt());
        assertEquals(at, t.getUpdatedAt());
        assertEquals(Long.valueOf(28800000L), t.getEntries().get("youtube.com").getDurationMillis());
        assertEquals(Long.valueOf(3600000L),
                read.get("fim-de-semana").getEntries().get("instagram.com").getDurationMillis());
    }

    @Test
    void missingFileLoadsEmpty() throws Exception {
        assertTrue(store().load().isEmpty());
    }

    @Test
    void corruptedJsonThrowsAndKeepsFile() throws Exception {
        Path file = tmp.resolve("profiles.json");
        String lixo = "{ nao é json";
        Files.writeString(file, lixo, StandardCharsets.UTF_8);

        ProfileStore store = store();
        assertThrows(CorruptedStateException.class, store::load);
        assertEquals(lixo, Files.readString(file, StandardCharsets.UTF_8),
                "corrupção não pode apagar o arquivo — o perfil é trabalho digitado");
    }

    @Test
    void deleteRemovesOnlyTheNamedProfile() throws Exception {
        Instant at = Instant.parse("2026-10-15T14:02:11Z");
        Map<String, Profile> profiles = new TreeMap<>();
        profiles.put("a", profile(at, durations("x.com", 1000L)));
        profiles.put("b", profile(at, durations("y.com", 1000L)));
        ProfileStore store = store();
        store.save(profiles);

        store.delete("a");

        Map<String, Profile> read = store.load();
        assertEquals(1, read.size());
        assertTrue(read.containsKey("b"));
    }

    /**
     * Apagar um perfil não pode apagar o que outro terminal escreveu.
     *
     * <p>O {@code delete} solto era um {@code load} seguido de um {@code save}:
     * a receita que outro terminal gravou no meio do caminho sumia junto. O
     * teste é determinístico — o outro escritor grava o arquivo por fora da
     * trava, e o {@code delete} tem que sair de lá sem tocar no que não é dele.
     */
    @Test
    void deleteNaoApagaOQueFoiEscritoPorOutro() throws Exception {
        Path file = tmp.resolve("profiles.json");
        ProfileStore store = new ProfileStore(file);
        store.save(Map.of("x", profile(Instant.parse("2026-10-15T14:02:11Z"),
                durations("x.com", 1000L))));

        // "Outro terminal": enquanto o delete carrega o arquivo, outro escritor
        // grava a receita "y" por fora da trava.
        ProfileStore espiao = new ProfileStore(file) {
            @Override
            public Map<String, Profile> load() throws IOException {
                Map<String, Profile> perfis = super.load();
                Map<String, Profile> concorrente = new TreeMap<>(perfis);
                concorrente.put("y", profile(Instant.parse("2026-10-15T14:02:11Z"),
                        durations("y.com", 1000L)));
                Files.writeString(file, ProfileStore.defaultMapper()
                        .writerWithDefaultPrettyPrinter()
                        .writeValueAsString(new ProfileDocument(
                                ProfileDocument.CURRENT_VERSION, concorrente)),
                        StandardCharsets.UTF_8);
                return perfis;
            }
        };

        espiao.delete("x");

        Map<String, Profile> depois = store.load();
        assertFalse(depois.containsKey("x"), "o perfil apagado tem que sumir: " + depois.keySet());
        assertTrue(depois.containsKey("y"),
                "a escrita concorrente não pode se perder: " + depois.keySet());
    }

    @Test
    void invalidNamesAreRejected() {
        for (String nome : new String[]{"", " trabalho", "trabalho ", "a b", "../escape",
                "perfil\tcom\ttab", "x".repeat(65), "perfil/../etc"}) {
            assertThrows(IllegalArgumentException.class, () -> ProfileNames.require(nome),
                    "deveria rejeitar: '" + nome + "'");
            assertTrue(!ProfileNames.isValid(nome), "isValid deveria dizer não para '" + nome + "'");
        }
    }

    @Test
    void validNamesAreAccepted() {
        assertTrue(ProfileNames.isValid("trabalho"));
        assertTrue(ProfileNames.isValid("Perfil-1"));
        assertTrue(ProfileNames.isValid("a"));
        assertTrue(ProfileNames.isValid("x".repeat(64)));
        assertEquals("trabalho", ProfileNames.require("trabalho"));
    }

    @Test
    void nullDurationMeansPermanent() throws Exception {
        Instant at = Instant.parse("2026-10-15T14:02:11Z");
        store().save(Map.of("p", profile(at, durations("tiktok.com", null))));

        Long duracao = store().load().get("p").getEntries().get("tiktok.com").getDurationMillis();
        assertNull(duracao, "permanente é null, não 0");
    }

    @Test
    void versionIsWrittenAtTheRoot() throws Exception {
        store().save(Map.of());
        String json = Files.readString(tmp.resolve("profiles.json"), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"version\""), "o formato vai evoluir e precisa de onde versionar: " + json);
    }
}