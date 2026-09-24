package com.sitelock.dns;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifica que a resposta de bloqueio está corretamente formada no nível dos bytes:
 * ID ecoado, flags, contadores, pergunta ecoada e answer com o sinkhole.
 */
class DnsPacketBuilderTest {

    private static DnsQuery query(int id, String name, int type) throws Exception {
        byte[] raw = DnsPacketParserTest.queryBytes(id, name, type, 1);
        return DnsPacketParser.parse(raw, raw.length);
    }

    private static int u16(byte[] b, int off) {
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }

    @Test
    void respostaABloqueadaBemFormada() throws Exception {
        DnsQuery q = query(0xBEEF, "www.tiktok.com", 1);
        byte[] resp = DnsPacketBuilder.buildBlockedResponse(q);

        int questionLen = q.questionSection().length;
        assertEquals(12 + questionLen + 12 + 4, resp.length);

        // Header: ID ecoado, QR=1, RCODE=0, QD=1, AN=1, NS=AR=0.
        assertEquals(0xBEEF, u16(resp, 0));
        int flags = u16(resp, 2);
        assertTrue((flags & 0x8000) != 0, "QR deve ser 1");
        assertEquals(0, flags & 0x000F, "RCODE deve ser 0");
        assertTrue((flags & 0x0100) != 0, "RD deve ser copiado da pergunta");
        assertTrue((flags & 0x0080) != 0, "RA deve ser 1");
        assertEquals(1, u16(resp, 4));
        assertEquals(1, u16(resp, 6));
        assertEquals(0, u16(resp, 8));
        assertEquals(0, u16(resp, 10));

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
        assertEquals(12 + questionLen + 12 + 16, resp.length);
        int a = 12 + questionLen;
        assertEquals(28, u16(resp, a + 2));
        assertEquals(1, u16(resp, a + 4));
        assertEquals(16, u16(resp, a + 10));
        for (int i = 0; i < 16; i++) {
            assertEquals(0, resp[a + 12 + i], "byte " + i + " do :: deve ser zero");
        }
    }

    @Test
    void tipoNaoBloqueavelRejeitado() throws Exception {
        DnsQuery q = query(0x1234, "tiktok.com", 15); // MX
        assertThrows(IllegalArgumentException.class,
                () -> DnsPacketBuilder.buildBlockedResponse(q));
    }

    @Test
    void servfailSemAnswersERcode2() throws Exception {
        DnsQuery q = query(0x4321, "exemplo.com", 1);
        byte[] resp = DnsPacketBuilder.buildErrorResponse(q, 2);

        assertEquals(12 + q.questionSection().length, resp.length);
        assertEquals(0x4321, u16(resp, 0));
        int flags = u16(resp, 2);
        assertTrue((flags & 0x8000) != 0);
        assertEquals(2, flags & 0x000F);
        assertEquals(1, u16(resp, 4));
        assertEquals(0, u16(resp, 6));
    }
}
