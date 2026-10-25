package com.sitelock.dns;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

// Builds raw DNS queries and verifies extraction of the queried domain.
class DnsPacketParserTest {

    // Standard query: RD=1, QDCOUNT=1; ID, name, type, and class come as parameters.
    static byte[] queryBytes(int id, String dottedName, int qtype, int qclass) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write((id >>> 8) & 0xFF);
            out.write(id & 0xFF);
            out.write(0x01); // flags: RD
            out.write(0x00);
            out.write(0x00);
            out.write(0x01); // QDCOUNT = 1
            out.write(0x00);
            out.write(0x00); // ANCOUNT
            out.write(0x00);
            out.write(0x00); // NSCOUNT
            out.write(0x00);
            out.write(0x00); // ARCOUNT
            if (!dottedName.isEmpty()) {
                for (String label : dottedName.split("\\.")) {
                    byte[] lb = label.getBytes(StandardCharsets.US_ASCII);
                    out.write(lb.length);
                    out.write(lb, 0, lb.length);
                }
            }
            out.write(0x00); // end of QNAME
            out.write((qtype >>> 8) & 0xFF);
            out.write(qtype & 0xFF);
            out.write((qclass >>> 8) & 0xFF);
            out.write(qclass & 0xFF);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void extractsDomainTypeClassAndId() throws Exception {
        byte[] raw = queryBytes(0x1234, "www.tiktok.com", 1, 1);
        DnsQuery q = DnsPacketParser.parse(raw, raw.length);
        assertEquals(0x1234, q.transactionId());
        assertEquals("www.tiktok.com", q.name());
        assertEquals(1, q.type());
        assertEquals(1, q.clazz());
    }

    @Test
    void lowercasesUppercase() throws Exception {
        byte[] raw = queryBytes(0x0001, "WWW.TikTok.COM", 28, 1);
        DnsQuery q = DnsPacketParser.parse(raw, raw.length);
        assertEquals("www.tiktok.com", q.name());
        assertEquals(28, q.type());
    }

    @Test
    void toleratesAdditionalEdnsSection() throws Exception {
        byte[] base = queryBytes(0x00AA, "example.com", 1, 1);
        // Minimal OPT pseudo-record (11 bytes) + ARCOUNT=1: the parser should ignore it.
        byte[] raw = new byte[base.length + 11];
        System.arraycopy(base, 0, raw, 0, base.length);
        raw[10] = 0x00;
        raw[11] = 0x01; // ARCOUNT = 1
        DnsQuery q = DnsPacketParser.parse(raw, raw.length);
        assertEquals("example.com", q.name());
    }

    // Query with an EDNS OPT (ARCOUNT=1, TYPE=41, CLASS=payload size).
    static byte[] queryBytesWithOpt(int id, String dottedName, int qtype, int payloadSize) {
        byte[] base = queryBytes(id, dottedName, qtype, 1);
        byte[] raw = new byte[base.length + 11];
        System.arraycopy(base, 0, raw, 0, base.length);
        raw[10] = 0x00;
        raw[11] = 0x01; // ARCOUNT = 1
        int p = base.length;
        raw[p] = 0x00; // NAME: root
        raw[p + 1] = 0x00; // TYPE = 41 (OPT)
        raw[p + 2] = 0x29;
        raw[p + 3] = (byte) (payloadSize >>> 8); // CLASS = client payload size
        raw[p + 4] = (byte) payloadSize;
        raw[p + 5] = 0x00; // TTL = 0
        raw[p + 6] = 0x00;
        raw[p + 7] = 0x00;
        raw[p + 8] = 0x00;
        raw[p + 9] = 0x00; // RDLEN = 0
        raw[p + 10] = 0x00;
        return raw;
    }

    @Test
    void clientOptIsRead() throws Exception {
        byte[] raw = queryBytesWithOpt(0x00AB, "example.com", 1, 1232);
        DnsQuery q = DnsPacketParser.parse(raw, raw.length);
        assertEquals("example.com", q.name());
        assertEquals(1232, q.ednsPayloadSize());
    }

    @Test
    void queryWithoutOptHasZeroPayloadSize() throws Exception {
        byte[] raw = queryBytes(0x00AC, "example.com", 1, 1);
        DnsQuery q = DnsPacketParser.parse(raw, raw.length);
        assertEquals(0, q.ednsPayloadSize());
    }

    @Test
    void rejectsShortPacket() {
        assertThrows(DnsParseException.class,
                () -> DnsPacketParser.parse(new byte[11], 11));
    }

    @Test
    void rejectsResponseWithQrSet() {
        byte[] raw = queryBytes(0x1234, "a.com", 1, 1);
        raw[2] = (byte) 0x81; // QR=1
        raw[3] = (byte) 0x80;
        assertThrows(DnsParseException.class,
                () -> DnsPacketParser.parse(raw, raw.length));
    }

    @Test
    void rejectsQdcountOtherThanOne() {
        byte[] raw = queryBytes(0x1234, "a.com", 1, 1);
        raw[5] = 0x00; // QDCOUNT = 0
        assertThrows(DnsParseException.class,
                () -> DnsPacketParser.parse(raw, raw.length));
    }

    @Test
    void rejectsCompressionInQname() {
        byte[] raw = queryBytes(0x1234, "a.com", 1, 1);
        raw[12] = (byte) 0xC0; // pointer where a label length should be
        assertThrows(DnsParseException.class,
                () -> DnsPacketParser.parse(raw, raw.length));
    }

    @Test
    void rejectsTruncatedQtype() {
        byte[] raw = queryBytes(0x1234, "a.com", 1, 1);
        byte[] truncated = new byte[raw.length - 2];
        System.arraycopy(raw, 0, truncated, 0, truncated.length);
        assertThrows(DnsParseException.class,
                () -> DnsPacketParser.parse(truncated, truncated.length));
    }

    // Query with labels in raw bytes (allows bytes >= 0x80 and UTF-8).
    static byte[] queryBytesLabels(int id, byte[][] labels, int qtype, int qclass) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write((id >>> 8) & 0xFF);
            out.write(id & 0xFF);
            out.write(0x01); // flags: RD
            out.write(0x00);
            out.write(0x00);
            out.write(0x01); // QDCOUNT = 1
            out.write(0x00);
            out.write(0x00); // ANCOUNT
            out.write(0x00);
            out.write(0x00); // NSCOUNT
            out.write(0x00);
            out.write(0x00); // ARCOUNT
            for (byte[] lb : labels) {
                out.write(lb.length);
                out.write(lb, 0, lb.length);
            }
            out.write(0x00); // end of QNAME
            out.write((qtype >>> 8) & 0xFF);
            out.write(qtype & 0xFF);
            out.write((qclass >>> 8) & 0xFF);
            out.write(qclass & 0xFF);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // UTF-8 labels (what a client sends when it uses IDN instead of punycode).
    private static byte[][] utf8Labels(String... labels) {
        byte[][] out = new byte[labels.length][];
        for (int i = 0; i < labels.length; i++) {
            out[i] = labels[i].getBytes(StandardCharsets.UTF_8);
        }
        return out;
    }

    @Test
    void highByteNameDoesNotBecomeReplacementChar() throws Exception {
        byte[] raw = queryBytesLabels(0x1, utf8Labels("exämple", "com"), 1, 1);
        DnsQuery q = DnsPacketParser.parse(raw, raw.length);
        assertFalse(q.name().contains("�"), "the name must not contain U+FFFD: " + q.name());
        assertEquals("xn--exmple-cua.com", q.name());
    }

    @Test
    void punycodeNameIsUnchanged() throws Exception {
        byte[] raw = queryBytes(0x2, "xn--e1afmkfd.xn--p1ai", 1, 1);
        DnsQuery q = DnsPacketParser.parse(raw, raw.length);
        assertEquals("xn--e1afmkfd.xn--p1ai", q.name());
    }

    @Test
    void rawUtf8NameBecomesCanonicalPunycode() throws Exception {
        byte[] raw = queryBytesLabels(0x3, utf8Labels("пример", "рф"), 1, 1);
        DnsQuery q = DnsPacketParser.parse(raw, raw.length);
        assertFalse(q.name().contains("�"), "the name must not contain U+FFFD: " + q.name());
        assertEquals("xn--e1afmkfd.xn--p1ai", q.name());
    }

    @Test
    void forbiddenByteInLabelIsRejectedWithoutTakingDownServer() {
        byte[] raw = queryBytesLabels(0x4, new byte[][]{{(byte) 0x80}}, 1, 1);
        assertThrows(DnsParseException.class,
                () -> DnsPacketParser.parse(raw, raw.length));
    }

    @Test
    void unassignedCodePointInLabelIsRejected() {
        // U+0378 is unassigned in Unicode: IDN.toASCII throws and the packet is dropped.
        byte[] raw = queryBytesLabels(0x5, utf8Labels("͸", "com"), 1, 1);
        assertThrows(DnsParseException.class,
                () -> DnsPacketParser.parse(raw, raw.length));
    }

    @Test
    void nameOver253CharactersIsRejected() {
        // 4 labels of 63 bytes + 3 dots = 255 > 253 (RFC 1035 §3.1).
        String label = "a".repeat(63);
        byte[] raw = queryBytesLabels(0x6, utf8Labels(label, label, label, label), 1, 1);
        assertThrows(DnsParseException.class,
                () -> DnsPacketParser.parse(raw, raw.length));
    }
}
