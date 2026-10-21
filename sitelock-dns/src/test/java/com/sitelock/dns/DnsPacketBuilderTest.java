package com.sitelock.dns;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifica que a resposta de bloqueio está corretamente formada no nível dos bytes:
 * ID ecoado, flags, contadores, pergunta ecoada e answer com o sinkhole.
 */
class DnsPacketBuilderTest {

    /** Tamanho do pseudo-registro OPT do EDNS (RFC 6891). */
    private static final int OPT_LEN = 11;

    private static DnsQuery query(int id, String name, int type) throws Exception {
        byte[] raw = DnsPacketParserTest.queryBytes(id, name, type, 1);
        return DnsPacketParser.parse(raw, raw.length);
    }

    private static int u16(byte[] b, int off) {
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }

    private static long u32(byte[] b, int off) {
        return ((b[off] & 0xFFL) << 24) | ((b[off + 1] & 0xFFL) << 16)
                | ((b[off + 2] & 0xFFL) << 8) | (b[off + 3] & 0xFFL);
    }

    @Test
    void respostaABloqueadaBemFormada() throws Exception {
        DnsQuery q = query(0xBEEF, "www.tiktok.com", 1);
        byte[] resp = DnsPacketBuilder.buildBlockedResponse(q);

        int questionLen = q.questionSection().length;
        assertEquals(12 + questionLen + 12 + 4 + OPT_LEN, resp.length);

        // Header: ID ecoado, QR=1, RCODE=0, QD=1, AN=1, NS=0, AR=1 (OPT do EDNS).
        assertEquals(0xBEEF, u16(resp, 0));
        int flags = u16(resp, 2);
        assertTrue((flags & 0x8000) != 0, "QR deve ser 1");
        assertEquals(0, flags & 0x000F, "RCODE deve ser 0");
        assertTrue((flags & 0x0100) != 0, "RD deve ser copiado da pergunta");
        assertTrue((flags & 0x0080) != 0, "RA deve ser 1");
        assertEquals(1, u16(resp, 4));
        assertEquals(1, u16(resp, 6));
        assertEquals(0, u16(resp, 8));
        assertEquals(1, u16(resp, 10), "ARCOUNT deve ser 1 (OPT do EDNS)");

        // Pergunta ecoada byte a byte.
        byte[] echoed = new byte[questionLen];
        System.arraycopy(resp, 12, echoed, 0, questionLen);
        assertArrayEquals(q.questionSection(), echoed);

        // Answer: ponteiro 0xC00C, A/IN, TTL 60, RDLEN 4, 0.0.0.0.
        int a = 12 + questionLen;
        assertEquals(0xC0, resp[a] & 0xFF);
        assertEquals(0x0C, resp[a + 1] & 0xFF);
        assertEquals(1, u16(resp, a + 2));
        assertEquals(1, u16(resp, a + 4));
        long ttl = ((resp[a + 6] & 0xFFL) << 24) | ((resp[a + 7] & 0xFFL) << 16)
                | ((resp[a + 8] & 0xFFL) << 8) | (resp[a + 9] & 0xFFL);
        assertEquals(DnsPacketBuilder.TTL_BLOCKED_SECONDS, ttl);
        assertEquals(4, u16(resp, a + 10));
        assertEquals(0, resp[a + 12]);
        assertEquals(0, resp[a + 13]);
        assertEquals(0, resp[a + 14]);
        assertEquals(0, resp[a + 15]);
    }

    @Test
    void respostaAaaaUsaSinkholeIpv6() throws Exception {
        DnsQuery q = query(0x1234, "m.tiktok.com", 28);
        byte[] resp = DnsPacketBuilder.buildBlockedResponse(q);

        int questionLen = q.questionSection().length;
        assertEquals(12 + questionLen + 12 + 16 + OPT_LEN, resp.length);
        int a = 12 + questionLen;
        assertEquals(28, u16(resp, a + 2));
        assertEquals(1, u16(resp, a + 4));
        assertEquals(16, u16(resp, a + 10));
        for (int i = 0; i < 16; i++) {
            assertEquals(0, resp[a + 12 + i], "byte " + i + " do :: deve ser zero");
        }
    }

    @Test
    void tipoNaoBloqueavelRespondeNodata() throws Exception {
        DnsQuery q = query(0x1234, "tiktok.com", 15); // MX
        byte[] resp = DnsPacketBuilder.buildBlockedResponse(q);
        assertEquals(0, u16(resp, 6), "ANCOUNT deve ser 0 (NODATA)");
        assertEquals(0, u16(resp, 2) & 0x000F, "RCODE deve ser 0");
    }

    @Test
    void respostaNodataTemRcodeZeroEAncountZero() throws Exception {
        DnsQuery q = query(0x1111, "youtube.com", 15); // MX de um nome bloqueado
        byte[] resp = DnsPacketBuilder.buildNoDataResponse(q);

        assertEquals(12 + q.questionSection().length + OPT_LEN, resp.length);
        assertEquals(0x1111, u16(resp, 0));
        int flags = u16(resp, 2);
        assertTrue((flags & 0x8000) != 0, "QR deve ser 1");
        assertEquals(0, flags & 0x000F, "RCODE deve ser 0");
        assertEquals(1, u16(resp, 4), "QDCOUNT deve ser 1");
        assertEquals(0, u16(resp, 6), "ANCOUNT deve ser 0");
        assertEquals(0, u16(resp, 8), "NSCOUNT deve ser 0");
    }

    @Test
    void buildResponseEscolheSinkholeOuNodata() throws Exception {
        DnsQuery a = query(0x2222, "tiktok.com", 1);
        assertEquals(1, u16(DnsPacketBuilder.buildResponse(a, DnsResolver.Decision.BLOCK), 6),
                "BLOCK tem 1 answer (sinkhole)");
        DnsQuery mx = query(0x2223, "tiktok.com", 15);
        assertEquals(0, u16(DnsPacketBuilder.buildResponse(mx, DnsResolver.Decision.BLOCK_NODATA), 6),
                "BLOCK_NODATA tem 0 answer");
        assertThrows(IllegalArgumentException.class,
                () -> DnsPacketBuilder.buildResponse(a, DnsResolver.Decision.FORWARD));
    }

    @Test
    void respostaDeBloqueioTemBitAA() throws Exception {
        DnsQuery q = query(0xBEEF, "www.tiktok.com", 1);
        byte[] resp = DnsPacketBuilder.buildBlockedResponse(q);
        int flags = u16(resp, 2);
        assertTrue((flags & 0x0400) != 0, "AA deve ser 1: o servidor e a autoridade do nome bloqueado");
        assertEquals(0x8580, flags, "flags devem ser QR|AA|RD|RA");
    }

    @Test
    void respostaNodataTemBitAA() throws Exception {
        DnsQuery q = query(0x1111, "youtube.com", 15);
        byte[] resp = DnsPacketBuilder.buildNoDataResponse(q);
        assertTrue((u16(resp, 2) & 0x0400) != 0, "AA deve ser 1 no NODATA de nome bloqueado");
    }

    @Test
    void respostaDeErroNaoTemBitAA() throws Exception {
        DnsQuery q = query(0x4321, "exemplo.com", 1);
        byte[] resp = DnsPacketBuilder.buildErrorResponse(q, 2);
        assertEquals(0, u16(resp, 2) & 0x0400,
                "AA deve ser 0 no SERVFAIL: nao somos autoridade do nome");
    }

    @Test
    void questionSectionPublicoDevolveCopia() throws Exception {
        DnsQuery q = query(0x1, "www.tiktok.com", 1);
        byte[] primeira = q.questionSection();
        byte[] segunda = q.questionSection();
        assertNotSame(primeira, segunda, "o accessor publico deve devolver uma copia");
        primeira[0] = 0x7F; // mexer na cópia não pode alterar a consulta
        assertNotEquals(0x7F, q.questionSection()[0] & 0xFF);
    }

    @Test
    void questionSectionInternoNaoClona() throws Exception {
        DnsQuery q = query(0x2, "www.tiktok.com", 1);
        assertSame(q.questionSectionInternal(), q.questionSectionInternal(),
                "o accessor interno nao deve clonar: a resposta usa o array direto");
    }

    @Test
    void respostaIncluiOptMinimoDoEdns() throws Exception {
        DnsQuery q = query(0x7777, "youtube.com", 15);
        byte[] resp = DnsPacketBuilder.buildNoDataResponse(q);

        assertEquals(1, u16(resp, 10), "ARCOUNT deve ser 1 (OPT do EDNS)");
        int opt = resp.length - 11;
        assertEquals(0x00, resp[opt] & 0xFF, "NAME do OPT deve ser a raiz");
        assertEquals(41, u16(resp, opt + 1), "TYPE do OPT deve ser 41");
        assertEquals(4096, u16(resp, opt + 3), "CLASS deve ser o payload size padrao");
        assertEquals(0L, u32(resp, opt + 5), "TTL do OPT deve ser 0");
        assertEquals(0, u16(resp, opt + 9), "RDLEN do OPT deve ser 0");
    }

    @Test
    void respostaEchoaOPayloadSizeDoCliente() throws Exception {
        byte[] raw = DnsPacketParserTest.queryBytesWithOpt(0x7778, "youtube.com", 15, 1232);
        DnsQuery q = DnsPacketParser.parse(raw, raw.length);
        assertEquals(1232, q.ednsPayloadSize());

        byte[] resp = DnsPacketBuilder.buildBlockedResponse(q);
        int opt = resp.length - 11;
        assertEquals(1232, u16(resp, opt + 3), "CLASS do OPT deve ecoar o payload size do cliente");
    }

    @Test
    void servfailSemAnswersERcode2() throws Exception {        DnsQuery q = query(0x4321, "exemplo.com", 1);
        byte[] resp = DnsPacketBuilder.buildErrorResponse(q, 2);

        assertEquals(12 + q.questionSection().length + OPT_LEN, resp.length);
        assertEquals(0x4321, u16(resp, 0));
        int flags = u16(resp, 2);
        assertTrue((flags & 0x8000) != 0);
        assertEquals(2, flags & 0x000F);
        assertEquals(1, u16(resp, 4));
        assertEquals(0, u16(resp, 6));
    }
}
