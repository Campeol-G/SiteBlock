package com.sitelock.core;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A regra do perfil: ele só pode <b>aumentar</b> o prazo, nunca encurtar.
 *
 * <p>É o que torna "aplicar o mesmo perfil 40 vezes no dia" seguro. Sem esta
 * regra, a segunda aplicação transformaria um bloqueio de 10h em "10h a partir
 * de agora" — encurtando a cada repetição, que é o oposto do que a pessoa
 * pediu ao digitar --for 10h.
 */
class ProfileMergeTest {

    private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");
    private static final Instant CREATED = Instant.parse("2026-10-15T08:00:00Z");

    private static long hours(long h) {
        return h * 3600_000L;
    }

    // Estado com um domínio, temporário (restam remainingMillis) ou permanente.
    private static Map<String, BlockEntry> current(String domain, Long remainingMillis) {
        Map<String, BlockEntry> state = new TreeMap<>();
        if (remainingMillis == null) {
            state.put(domain, new BlockEntry(domain, CREATED, null));
        } else {
            state.put(domain, new BlockEntry(domain, CREATED, NOW.plusMillis(remainingMillis)));
        }
        return state;
    }

    // Perfil que oferece offerMillis para o domínio, ou permanente se nulo.
    private static Profile offering(String domain, Long offerMillis) {
        Map<String, ProfileEntry> entries = new TreeMap<>();
        entries.put(domain, new ProfileEntry(offerMillis));
        return new Profile(CREATED, CREATED, entries);
    }

    @Test
    void absentPlusTemporaryIsAdded() {
        Map<String, ProfileMerge.Decision> out =
                ProfileMerge.merge(new TreeMap<>(), offering("tiktok.com", hours(8)), NOW);
        assertEquals(ProfileMerge.Action.ADDED, out.get("tiktok.com").action());
        assertEquals(NOW.plusMillis(hours(8)), out.get("tiktok.com").entry().getExpiresAt());
    }

    @Test
    void absentPlusPermanentIsAdded() {
        Map<String, ProfileMerge.Decision> out =
                ProfileMerge.merge(new TreeMap<>(), offering("tiktok.com", null), NOW);
        assertEquals(ProfileMerge.Action.ADDED, out.get("tiktok.com").action());
        assertNull(out.get("tiktok.com").entry().getExpiresAt());
    }

    @Test
    void temporaryRaised() {
        Map<String, ProfileMerge.Decision> out = ProfileMerge.merge(
                current("tiktok.com", 30 * 60_000L), offering("tiktok.com", hours(8)), NOW);
        assertEquals(ProfileMerge.Action.RAISED, out.get("tiktok.com").action());
        assertEquals(NOW.plusMillis(hours(8)), out.get("tiktok.com").entry().getExpiresAt());
    }

    @Test
    void temporaryLongerIsKept() {
        Map<String, ProfileMerge.Decision> out = ProfileMerge.merge(
                current("tiktok.com", hours(10)), offering("tiktok.com", hours(8)), NOW);
        assertEquals(ProfileMerge.Action.KEPT, out.get("tiktok.com").action());
    }

    @Test
    void temporaryEqualIsKept() {
        Map<String, ProfileMerge.Decision> out = ProfileMerge.merge(
                current("tiktok.com", hours(8)), offering("tiktok.com", hours(8)), NOW);
        assertEquals(ProfileMerge.Action.KEPT, out.get("tiktok.com").action());
    }

    @Test
    void temporaryRaisedToPermanent() {
        Map<String, ProfileMerge.Decision> out = ProfileMerge.merge(
                current("tiktok.com", 30 * 60_000L), offering("tiktok.com", null), NOW);
        assertEquals(ProfileMerge.Action.RAISED, out.get("tiktok.com").action());
        assertNull(out.get("tiktok.com").entry().getExpiresAt());
    }

    @Test
    void permanentStaysPermanentWhenProfileOffersTime() {
        Map<String, ProfileMerge.Decision> out = ProfileMerge.merge(
                current("tiktok.com", null), offering("tiktok.com", hours(8)), NOW);
        assertEquals(ProfileMerge.Action.KEPT, out.get("tiktok.com").action());
    }

    @Test
    void permanentStaysPermanentWithPermanentProfile() {
        Map<String, ProfileMerge.Decision> out = ProfileMerge.merge(
                current("tiktok.com", null), offering("tiktok.com", null), NOW);
        assertEquals(ProfileMerge.Action.KEPT, out.get("tiktok.com").action());
    }

    @Test
    void keptIsTheSameInstance() {
        Map<String, BlockEntry> state = current("tiktok.com", hours(10));
        BlockEntry original = state.get("tiktok.com");

        Map<String, ProfileMerge.Decision> out =
                ProfileMerge.merge(state, offering("tiktok.com", hours(8)), NOW);

        assertSame(original, out.get("tiktok.com").entry(),
                "um KEPT não pode reescrever o BlockEntry: criado/expira mudariam o prazo");
        assertEquals(original.getCreatedAt(), out.get("tiktok.com").entry().getCreatedAt());
        assertEquals(original.getExpiresAt(), out.get("tiktok.com").entry().getExpiresAt());
    }

    @Test
    void raisedKeepsOriginalCreatedAt() {
        Map<String, BlockEntry> state = current("tiktok.com", 30 * 60_000L);

        Map<String, ProfileMerge.Decision> out =
                ProfileMerge.merge(state, offering("tiktok.com", hours(8)), NOW);

        assertEquals(CREATED, out.get("tiktok.com").entry().getCreatedAt(),
                "o bloqueio começou às 08:00 e continua tendo começado às 08:00");
    }

    @Test
    void expiredEntryCountsAsAbsent() {
        // O sweep roda antes do apply e já removeu os expirados; se algum
        // sobreviveu (sem permissão no hosts), ele é tratado como ausente —
        // senão o perfil renasceria um prazo que já venceu.
        Map<String, BlockEntry> state = current("tiktok.com", -3600_000L);

        Map<String, ProfileMerge.Decision> out =
                ProfileMerge.merge(state, offering("tiktok.com", hours(8)), NOW);

        assertEquals(ProfileMerge.Action.ADDED, out.get("tiktok.com").action());
        assertEquals(NOW.plusMillis(hours(8)), out.get("tiktok.com").entry().getExpiresAt());
    }

    @Test
    void applyingTwiceIsIdempotent() {
        Profile perfil = offering("tiktok.com", hours(8));
        Map<String, BlockEntry> state = new TreeMap<>();

        Map<String, ProfileMerge.Decision> primeira = ProfileMerge.merge(state, perfil, NOW);
        state = aplicar(state, primeira);

        Map<String, ProfileMerge.Decision> segunda = ProfileMerge.merge(state, perfil, NOW);
        state = aplicar(state, segunda);

        assertEquals(ProfileMerge.Action.KEPT, segunda.get("tiktok.com").action());
        assertEquals(primeira.get("tiktok.com").entry(), segunda.get("tiktok.com").entry(),
                "a segunda aplicação não pode mexer no prazo de novo");
    }

    @Test
    void decisionsCoverEveryDomainOfTheProfile() {
        Map<String, ProfileEntry> entries = new TreeMap<>();
        entries.put("a.com", new ProfileEntry(hours(1)));
        entries.put("b.com", new ProfileEntry(null));
        Profile perfil = new Profile(CREATED, CREATED, entries);

        Map<String, BlockEntry> state = current("b.com", 5 * 3600_000L);

        Map<String, ProfileMerge.Decision> out = ProfileMerge.merge(state, perfil, NOW);

        assertEquals(2, out.size(), "KEPT também volta no mapa — quem chama decide o que vai para o hosts");
        assertEquals(ProfileMerge.Action.ADDED, out.get("a.com").action());
        assertEquals(ProfileMerge.Action.RAISED, out.get("b.com").action());
    }

    // Simula o que o comando faz: aplica as decisões ao mapa de estado.
    private static Map<String, BlockEntry> aplicar(Map<String, BlockEntry> state,
            Map<String, ProfileMerge.Decision> decisions) {
        Map<String, BlockEntry> next = new HashMap<>(state);
        decisions.forEach((domain, d) -> next.put(domain, d.entry()));
        return next;
    }
}