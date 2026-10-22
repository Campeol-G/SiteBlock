package com.sitelock.core;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

/**
 * O perfil é comparado por valor, como o {@link BlockEntry}.
 *
 * <p>O {@code ProfileStore} grava um delta sobre o que está no disco, e o delta
 * precisa saber o que mudou. Por identidade, só um {@code Profile} novo parecia
 * mudado — o que funciona por acidente, e não por desenho: qualquer caminho que
 * devolvesse um perfil equivalente (ou que alterasse um perfil no lugar)
 * escrevia um delta errado.
 */
class ProfileEqualsTest {

    private static final Instant CRIADO = Instant.parse("2026-10-15T12:00:00Z");
    private static final Instant ATUALIZADO = Instant.parse("2026-10-15T18:30:00Z");

    private static Profile perfil(String... pares) {
        Map<String, ProfileEntry> entradas = new TreeMap<>();
        for (int i = 0; i < pares.length; i += 2) {
            entradas.put(pares[i], new ProfileEntry(Long.valueOf(pares[i + 1])));
        }
        return new Profile(CRIADO, ATUALIZADO, entradas);
    }

    @Test
    void doisPerfisComOMesmoConteudoSaoIguais() {
        Profile a = perfil("tiktok.com", "3600000");
        Profile b = perfil("tiktok.com", "3600000");

        assertNotSame(a, b, "o teste só vale se forem duas instâncias diferentes");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void umPerfilComUmaEntradaAMaisNaoEIgual() {
        Profile a = perfil("tiktok.com", "3600000");
        Profile b = perfil("tiktok.com", "3600000", "youtube.com", "0");

        assertNotEquals(a, b);
    }

    @Test
    void umPerfilComOutraDuracaoNaoEIgual() {
        Profile a = perfil("tiktok.com", "3600000");
        Profile b = perfil("tiktok.com", "7200000");

        assertNotEquals(a, b);
    }

    @Test
    void umPerfilAtualizadoDepoisNaoEIgual() {
        Profile a = perfil("tiktok.com", "3600000");
        Profile b = new Profile(CRIADO, ATUALIZADO.plusSeconds(60),
                perfil("tiktok.com", "3600000").getEntries());

        assertNotEquals(a, b);
    }

    @Test
    void umPerfilCriadoEmOutroMomentoNaoEIgual() {
        Profile a = perfil("tiktok.com", "3600000");
        Profile b = new Profile(CRIADO.minusSeconds(60), ATUALIZADO,
                perfil("tiktok.com", "3600000").getEntries());

        assertNotEquals(a, b);
    }

    @Test
    void asEntradasDoPerfilSaoComparadasPorValor() {
        ProfileEntry a = new ProfileEntry(3600000L);
        ProfileEntry b = new ProfileEntry(3600000L);

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, new ProfileEntry(7200000L));
    }

    @Test
    void permanenteNaoEIgualAComPrazo() {
        assertNotEquals(new ProfileEntry(null), new ProfileEntry(0L));
        assertEquals(new ProfileEntry(null), new ProfileEntry(null));
    }
}
