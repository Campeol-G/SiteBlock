package com.sitelock.dns;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

// Verifies that the block response is correctly formed at the byte level:
// echoed ID, flags, counters, echoed question, and an answer with the sinkhole.
class DnsPacketBuilderTest {

    // Size of the EDNS OPT pseudo-record (RFC 6891).
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
    void blockedResponseIsWellFormed() throws Exception {
        DnsQuery q = query(0xBEEF, "www.tiktok.com", 1);
        byte[] resp = DnsPacketBuilder.buildBlockedResponse(q);

        int questionLen = q.questionSection().length;
        assertEquals(12 + questionLen + 12 + 4 + OPT_LEN, resp.length);

        // Header: echoed ID, QR=1, RCODE=0, QD=1, AN=1, NS=0, AR=1 (EDNS OPT).
        assertEquals(0xBEEF, u16(resp, 0));
        int flags = u16(resp, 2);
        assertTrue((flags & 0x8000) != 0, "QR should be 1");
        assertEquals(0, flags & 0x000F, "RCODE should be 0");
        assertTrue((flags & 0x0100) != 0, "RD should be copied from the question");
        assertTrue((flags & 0x0080) != 0, "RA should be 1");
        assertEquals(1, u16(resp, 4));
        assertEquals(1, u16(resp, 6));
        assertEquals(0, u16(resp, 8));
        assertEquals(1, u16(resp, 10), "ARCOUNT should be 1 (EDNS OPT)");

        // Question echoed byte for byte.
        byte[] echoed = new byte[questionLen];
        System.arraycopy(resp, 12, echoed, 0, questionLen);
        assertArrayEquals(q.questionSection(), echoed);

        // Answer: pointer 0xC00C, A/IN, TTL 60, RDLEN 4, 0.0.0.0.
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
    void aaaaResponseUsesIpv6Sinkhole() throws Exception {
        DnsQuery q = query(0x1234, "m.tiktok.com", 28);
        byte[] resp = DnsPacketBuilder.buildBlockedResponse(q);

        int questionLen = q.questionSection().length;
        assertEquals(12 + questionLen + 12 + 16 + OPT_LEN, resp.length);
        int a = 12 + questionLen;
        assertEquals(28, u16(resp, a + 2));
        assertEquals(1, u16(resp, a + 4));
        assertEquals(16, u16(resp, a + 10));
        for (int i = 0; i < 16; i++) {
            assertEquals(0, resp[a + 12 + i], "byte " + i + " of :: should be zero");
        }
    }

    @Test
    void nonBlockableTypeAnswersNodata() throws Exception {
        DnsQuery q = query(0x1234, "tiktok.com", 15); // MX
        byte[] resp = DnsPacketBuilder.buildBlockedResponse(q);
        assertEquals(0, u16(resp, 6), "ANCOUNT should be 0 (NODATA)");
        assertEquals(0, u16(resp, 2) & 0x000F, "RCODE should be 0");
    }

    @Test
    void nodataResponseHasZeroRcodeAndAncount() throws Exception {
        DnsQuery q = query(0x1111, "youtube.com", 15); // MX for a blocked name
        byte[] resp = DnsPacketBuilder.buildNoDataResponse(q);

        assertEquals(12 + q.questionSection().length + OPT_LEN, resp.length);
        assertEquals(0x1111, u16(resp, 0));
        int flags = u16(resp, 2);
        assertTrue((flags & 0x8000) != 0, "QR should be 1");
        assertEquals(0, flags & 0x000F, "RCODE should be 0");
        assertEquals(1, u16(resp, 4), "QDCOUNT should be 1");
        assertEquals(0, u16(resp, 6), "ANCOUNT should be 0");
        assertEquals(0, u16(resp, 8), "NSCOUNT should be 0");
    }

    @Test
    void buildResponseChoosesSinkholeOrNodata() throws Exception {
        DnsQuery a = query(0x2222, "tiktok.com", 1);
        assertEquals(1, u16(DnsPacketBuilder.buildResponse(a, DnsResolver.Decision.BLOCK), 6),
                "BLOCK has 1 answer (sinkhole)");
        DnsQuery mx = query(0x2223, "tiktok.com", 15);
        assertEquals(0, u16(DnsPacketBuilder.buildResponse(mx, DnsResolver.Decision.BLOCK_NODATA), 6),
                "BLOCK_NODATA has 0 answers");
        assertThrows(IllegalArgumentException.class,
                () -> DnsPacketBuilder.buildResponse(a, DnsResolver.Decision.FORWARD));
    }

    @Test
    void blockResponseHasAaBit() throws Exception {
        DnsQuery q = query(0xBEEF, "www.tiktok.com", 1);
        byte[] resp = DnsPacketBuilder.buildBlockedResponse(q);
        int flags = u16(resp, 2);
        assertTrue((flags & 0x0400) != 0, "AA should be 1: the server is authoritative for the blocked name");
        assertEquals(0x8580, flags, "flags should be QR|AA|RD|RA");
    }

    @Test
    void nodataResponseHasAaBit() throws Exception {
        DnsQuery q = query(0x1111, "youtube.com", 15);
        byte[] resp = DnsPacketBuilder.buildNoDataResponse(q);
        assertTrue((u16(resp, 2) & 0x0400) != 0, "AA should be 1 in NODATA for a blocked name");
    }

    @Test
    void errorResponseHasNoAaBit() throws Exception {
        DnsQuery q = query(0x4321, "example.com", 1);
        byte[] resp = DnsPacketBuilder.buildErrorResponse(q, 2);
        assertEquals(0, u16(resp, 2) & 0x0400,
                "AA should be 0 in SERVFAIL: we are not authoritative for the name");
    }

    @Test
    void publicQuestionSectionReturnsCopy() throws Exception {
        DnsQuery q = query(0x1, "www.tiktok.com", 1);
        byte[] first = q.questionSection();
        byte[] second = q.questionSection();
        assertNotSame(first, second, "the public accessor should return a copy");
        first[0] = 0x7F; // changing the copy must not change the query
        assertNotEquals(0x7F, q.questionSection()[0] & 0xFF);
    }

    @Test
    void internalQuestionSectionDoesNotClone() throws Exception {
        DnsQuery q = query(0x2, "www.tiktok.com", 1);
        assertSame(q.questionSectionInternal(), q.questionSectionInternal(),
                "the internal accessor should not clone: the response uses the array directly");
    }

    @Test
    void responseIncludesMinimalEdnsOpt() throws Exception {
        DnsQuery q = query(0x7777, "youtube.com", 15);
        byte[] resp = DnsPacketBuilder.buildNoDataResponse(q);

        assertEquals(1, u16(resp, 10), "ARCOUNT should be 1 (EDNS OPT)");
        int opt = resp.length - 11;
        assertEquals(0x00, resp[opt] & 0xFF, "the OPT NAME should be the root");
        assertEquals(41, u16(resp, opt + 1), "the OPT TYPE should be 41");
        assertEquals(4096, u16(resp, opt + 3), "CLASS should be the default payload size");
        assertEquals(0L, u32(resp, opt + 5), "the OPT TTL should be 0");
        assertEquals(0, u16(resp, opt + 9), "the OPT RDLEN should be 0");
    }

    @Test
    void responseEchoesClientPayloadSize() throws Exception {
        byte[] raw = DnsPacketParserTest.queryBytesWithOpt(0x7778, "youtube.com", 15, 1232);
        DnsQuery q = DnsPacketParser.parse(raw, raw.length);
        assertEquals(1232, q.ednsPayloadSize());

        byte[] resp = DnsPacketBuilder.buildBlockedResponse(q);
        int opt = resp.length - 11;
        assertEquals(1232, u16(resp, opt + 3), "the OPT CLASS should echo the client payload size");
    }

    @Test
    void servfailHasNoAnswersAndRcode2() throws Exception {
        DnsQuery q = query(0x4321, "example.com", 1);
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
