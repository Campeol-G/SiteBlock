package com.sitelock.dns;

import com.sitelock.core.BlockEntry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;

import static com.sitelock.dns.DnsResolver.Decision.BLOCK;
import static com.sitelock.dns.DnsResolver.Decision.FORWARD;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Lógica "bloquear vs encaminhar" isolada da rede: só mapas em memória, sem sockets.
 */
class DnsResolverTest {

    private static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");

    private static Map<String, BlockEntry> state(BlockEntry... entries) {
        Map<String, BlockEntry> m = new TreeMap<>();
        for (BlockEntry e : entries) {
            m.put(e.getDomain(), e);
        }
        return m;
    }

    private static BlockEntry permanent(String domain) {
        return new BlockEntry(domain, NOW.minusSeconds(60), null);
    }

    private static BlockEntry temporary(String domain, Instant expiresAt) {
        return new BlockEntry(domain, NOW.minusSeconds(60), expiresAt);
    }

    @Test
    void dominioExatoBloqueiaAeAaaa() {
        Map<String, BlockEntry> s = state(permanent("tiktok.com"));
        assertEquals(BLOCK, DnsResolver.decide("tiktok.com", 1, s, NOW));
        assertEquals(BLOCK, DnsResolver.decide("tiktok.com", 28, s, NOW));
    }

    @Test
    void subdominiosBloqueiam() {
        Map<String, BlockEntry> s = state(permanent("tiktok.com"));
        assertEquals(BLOCK, DnsResolver.decide("www.tiktok.com", 1, s, NOW));
        assertEquals(BLOCK, DnsResolver.decide("m.tiktok.com", 28, s, NOW));
        assertEquals(BLOCK, DnsResolver.decide("a.b.tiktok.com", 1, s, NOW));
    }

    @Test
    void sufixoParcialNaoBloqueia() {
        Map<String, BlockEntry> s = state(permanent("tiktok.com"));
        // "eviltiktok.com" termina com "tiktok.com" mas NÃO com ".tiktok.com".
        assertEquals(FORWARD, DnsResolver.decide("eviltiktok.com", 1, s, NOW));
        assertEquals(FORWARD, DnsResolver.decide("tiktok.com.evil.com", 1, s, NOW));
        assertEquals(FORWARD, DnsResolver.decide("google.com", 1, s, NOW));
    }

    @Test
    void outrosTiposSempreEncaminham() {
        Map<String, BlockEntry> s = state(permanent("tiktok.com"));
        assertEquals(FORWARD, DnsResolver.decide("tiktok.com", 15, s, NOW)); // MX
        assertEquals(FORWARD, DnsResolver.decide("www.tiktok.com", 16, s, NOW)); // TXT
        assertEquals(FORWARD, DnsResolver.decide("tiktok.com", 2, s, NOW)); // NS
    }

    @Test
    void expiradoNaoBloqueiaTemporarioAtivoBloqueia() {
        Map<String, BlockEntry> s = state(
                temporary("velho.com", NOW.minusSeconds(1)),
                temporary("novo.com", NOW.plusSeconds(3600)));
        assertEquals(FORWARD, DnsResolver.decide("velho.com", 1, s, NOW));
        assertEquals(BLOCK, DnsResolver.decide("novo.com", 1, s, NOW));
        assertEquals(BLOCK, DnsResolver.decide("www.novo.com", 28, s, NOW));
    }

    @Test
    void caixaAltaEPontoFinalNormalizados() {
        Map<String, BlockEntry> s = state(permanent("tiktok.com"));
        assertEquals(BLOCK, DnsResolver.decide("WWW.TikTok.COM.", 1, s, NOW));
        assertEquals(BLOCK, DnsResolver.decide("TIKTOK.COM", 28, s, NOW));
    }

    @Test
    void estadoVazioOuRaizEncaminha() {
        assertEquals(FORWARD, DnsResolver.decide("tiktok.com", 1, Map.of(), NOW));
        assertEquals(FORWARD, DnsResolver.decide("", 1, state(permanent("tiktok.com")), NOW));
    }
}
