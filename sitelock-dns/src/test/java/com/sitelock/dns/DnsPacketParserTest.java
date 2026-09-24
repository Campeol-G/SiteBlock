package com.sitelock.dns;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** Monta consultas DNS brutas e verifica a extração do domínio perguntado. */
class DnsPacketParserTest {

    /** Consulta padrão: ID 0x1234, RD=1, QDCOUNT=1, QNAME www.tiktok.com, A/IN. */
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
            out.write(0x00); // fim do QNAME
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
    void extraiDominioTipoClasseEId() throws Exception {
        byte[] raw = queryBytes(0x1234, "www.tiktok.com", 1, 1);
        DnsQuery q = DnsPacketParser.parse(raw, raw.length);
        assertEquals(0x1234, q.transactionId());
        assertEquals("www.tiktok.com", q.name());
        assertEquals(1, q.type());
        assertEquals(1, q.clazz());
    }

    @Test
    void normalizaCaixaAltaParaMinusculas() throws Exception {
        byte[] raw = queryBytes(0x0001, "WWW.TikTok.COM", 28, 1);
        DnsQuery q = DnsPacketParser.parse(raw, raw.length);
        assertEquals("www.tiktok.com", q.name());
        assertEquals(28, q.type());
    }

    @Test
    void toleraSecaoAdicionalEdns() throws Exception {
        byte[] base = queryBytes(0x00AA, "exemplo.com", 1, 1);
        // OPT pseudo-record mínimo (11 bytes) + ARCOUNT=1: parser deve ignorar.
        byte[] raw = new byte[base.length + 11];
        System.arraycopy(base, 0, raw, 0, base.length);
        raw[10] = 0x00;
        raw[11] = 0x01; // ARCOUNT = 1
        DnsQuery q = DnsPacketParser.parse(raw, raw.length);
        assertEquals("exemplo.com", q.name());
    }

    @Test
    void rejeitaPacoteCurto() {
        assertThrows(DnsParseException.class,
                () -> DnsPacketParser.parse(new byte[11], 11));
    }

    @Test
    void rejeitaRespostaComQrLigado() {
        byte[] raw = queryBytes(0x1234, "a.com", 1, 1);
        raw[2] = (byte) 0x81; // QR=1
        raw[3] = (byte) 0x80;
        assertThrows(DnsParseException.class,
                () -> DnsPacketParser.parse(raw, raw.length));
    }

    @Test
    void rejeitaQdcountDiferenteDeUm() {
        byte[] raw = queryBytes(0x1234, "a.com", 1, 1);
        raw[5] = 0x00; // QDCOUNT = 0
        assertThrows(DnsParseException.class,
                () -> DnsPacketParser.parse(raw, raw.length));
    }

    @Test
    void rejeitaCompressaoNoQname() {
        byte[] raw = queryBytes(0x1234, "a.com", 1, 1);
        raw[12] = (byte) 0xC0; // ponteiro onde deveria haver tamanho de rótulo
        assertThrows(DnsParseException.class,
                () -> DnsPacketParser.parse(raw, raw.length));
    }

    @Test
    void rejeitaQtypeTruncado() {
        byte[] raw = queryBytes(0x1234, "a.com", 1, 1);
        byte[] truncated = new byte[raw.length - 2];
        System.arraycopy(raw, 0, truncated, 0, truncated.length);
        assertThrows(DnsParseException.class,
                () -> DnsPacketParser.parse(truncated, truncated.length));
    }
}
