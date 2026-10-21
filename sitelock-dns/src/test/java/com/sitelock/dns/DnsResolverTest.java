package com.sitelock.dns;

import com.sitelock.core.BlockEntry;
import com.sitelock.core.BlockStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;

import static com.sitelock.dns.DnsResolver.Decision.BLOCK;
import static com.sitelock.dns.DnsResolver.Decision.BLOCK_NODATA;
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
    void nomeBloqueadoNuncaEhEncaminhadoQualquerQueSejaOTipo() {
        Map<String, BlockEntry> s = state(permanent("youtube.com"));
        int[] tipos = {1, 2, 5, 6, 12, 15, 16, 28, 33, 35, 41, 43, 44, 52, 64, 65, 255, 257};
        for (int tipo : tipos) {
            assertNotEquals(FORWARD, DnsResolver.decide("youtube.com", tipo, s, NOW),
                    "tipo " + tipo + " nao pode escapar do bloqueio");
        }
    }

    @Test
    void outrosTiposDeNomeBloqueadoRespondemNodata() {
        Map<String, BlockEntry> s = state(permanent("tiktok.com"));
        assertEquals(BLOCK_NODATA, DnsResolver.decide("tiktok.com", 15, s, NOW)); // MX
        assertEquals(BLOCK_NODATA, DnsResolver.decide("www.tiktok.com", 16, s, NOW)); // TXT
        assertEquals(BLOCK_NODATA, DnsResolver.decide("tiktok.com", 2, s, NOW)); // NS
        assertEquals(BLOCK_NODATA, DnsResolver.decide("tiktok.com", 5, s, NOW)); // CNAME
        assertEquals(BLOCK_NODATA, DnsResolver.decide("tiktok.com", 255, s, NOW)); // ANY
    }

    @Test
    void dominioNaoBloqueadoContinuaEncaminhandoQualquerTipo() {
        Map<String, BlockEntry> s = state(permanent("youtube.com"));
        assertEquals(FORWARD, DnsResolver.decide("google.com", 15, s, NOW));
        assertEquals(FORWARD, DnsResolver.decide("google.com", 16, s, NOW));
        assertEquals(FORWARD, DnsResolver.decide("notyoutube.com", 15, s, NOW));
    }

    @Test
    void isBlockedCobreBlockEBlockNodata() {
        assertTrue(BLOCK.isBlocked());
        assertTrue(BLOCK_NODATA.isBlocked());
        assertFalse(FORWARD.isBlocked());
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

    @Test
    void consultaCnameDeNomeBloqueadoNaoEhEncaminhada() {
        Map<String, BlockEntry> s = state(permanent("www.microsoft.com-c-3.edgekey.net"));
        assertEquals(BLOCK_NODATA,
                DnsResolver.decide("www.microsoft.com-c-3.edgekey.net", 5, s, NOW));
        assertEquals(BLOCK,
                DnsResolver.decide("www.microsoft.com-c-3.edgekey.net", 1, s, NOW));
    }

    @Test
    void nomeLivreComCnameParaBloqueadoAindaEhEncaminhado() {
        // Backlog conhecido: a decisão olha só o nome consultado. A cadeia de CNAMEs
        // é resolvida pelo upstream, que devolve o IP real do alvo na mesma resposta.
        // Fechar isso exige inspecionar a section answer do upstream (fora do escopo
        // da decisão).
        Map<String, BlockEntry> s = state(permanent("www.microsoft.com-c-3.edgekey.net"));
        assertEquals(FORWARD, DnsResolver.decide("www.microsoft.com", 1, s, NOW));
    }

    @Test
    void decideInstanciaRegistraTipoNaoAaaaDeNomeBloqueado(@TempDir Path dir) throws Exception {
        Path state = dir.resolve("blocks.json");
        Files.writeString(state, "{\"youtube.com\":{\"domain\":\"youtube.com\","
                + "\"createdAt\":\"2026-01-01T11:00:00Z\",\"expiresAt\":null}}");
        DnsResolver resolver = new DnsResolver(new BlockStore(state), Clock.systemUTC());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream original = System.out;
        DnsResolver.Decision decision;
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            decision = resolver.decide("youtube.com", 15);
        } finally {
            System.setOut(original);
        }

        assertEquals(BLOCK_NODATA, decision);
        String log = out.toString(StandardCharsets.UTF_8);
        assertTrue(log.contains("youtube.com"), "o log deve citar o nome: " + log);
        assertTrue(log.contains("15"), "o log deve citar o tipo: " + log);
    }

    @Test
    void listaEmUnicodeCasaComConsultaEmPunycode() {
        Map<String, BlockEntry> s = state(permanent("пример.рф"));
        assertEquals(BLOCK, DnsResolver.decide("xn--e1afmkfd.xn--p1ai", 1, s, NOW));
        assertEquals(BLOCK, DnsResolver.decide("пример.рф", 1, s, NOW));
        assertEquals(BLOCK, DnsResolver.decide("www.пример.рф", 1, s, NOW));
    }

    @Test
    void listaEmPunycodeCasaComConsultaEmUnicode() {
        Map<String, BlockEntry> s = state(permanent("xn--e1afmkfd.xn--p1ai"));
        assertEquals(BLOCK, DnsResolver.decide("пример.рф", 1, s, NOW));
        assertEquals(BLOCK, DnsResolver.decide("xn--e1afmkfd.xn--p1ai", 28, s, NOW));
    }

    @Test
    void nomeIdnNaoBloqueadoContinuaEncaminhado() {
        Map<String, BlockEntry> s = state(permanent("youtube.com"));
        assertEquals(FORWARD, DnsResolver.decide("пример.рф", 1, s, NOW));
    }

    @Test
    void decideInstanciaNaoLogaConsultaDeTipoA(@TempDir Path dir) throws Exception {        Path state = dir.resolve("blocks.json");
        Files.writeString(state, "{\"youtube.com\":{\"domain\":\"youtube.com\","
                + "\"createdAt\":\"2026-01-01T11:00:00Z\",\"expiresAt\":null}}");
        DnsResolver resolver = new DnsResolver(new BlockStore(state), Clock.systemUTC());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream original = System.out;
        DnsResolver.Decision decision;
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            decision = resolver.decide("youtube.com", 1);
        } finally {
            System.setOut(original);
        }

        assertEquals(BLOCK, decision);
        assertFalse(out.toString(StandardCharsets.UTF_8).contains("youtube.com"),
                "consulta A/AAAA nao deve gerar o log de tipo nao-A/AAAA");
    }
}
