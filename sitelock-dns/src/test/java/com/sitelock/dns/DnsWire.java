package com.sitelock.dns;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

// DNS packet building and reading for the socket tests. It does not use the
// internet: queries are built by hand and responses are read straight from the
// buffer, because the production parser only knows how to read queries.
final class DnsWire {

    private DnsWire() {
    }

    // Reads a big-endian 16-bit integer.
    static int u16(byte[] data, int off) {
        return ((data[off] & 0xFF) << 8) | (data[off + 1] & 0xFF);
    }

    // Writes a big-endian 16-bit integer.
    static void put16(byte[] data, int off, int value) {
        data[off] = (byte) (value >>> 8);
        data[off + 1] = (byte) value;
    }

    // Encodes a name into DNS labels ("example.com" -> 7"example"3"com"0).
    static byte[] encodeName(String name) {
        String[] labels = name.split("\\.");
        int size = 1;
        for (String l : labels) {
            size += 1 + l.length();
        }
        byte[] out = new byte[size];
        int off = 0;
        for (String l : labels) {
            byte[] bytes = l.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            out[off++] = (byte) bytes.length;
            System.arraycopy(bytes, 0, out, off, bytes.length);
            off += bytes.length;
        }
        out[off] = 0;
        return out;
    }

    // Builds a standard query (QR=0, RD=1, one question).
    static byte[] query(int id, String name, int type) {
        byte[] qname = encodeName(name);
        byte[] out = new byte[12 + qname.length + 4];
        put16(out, 0, id);
        put16(out, 2, 0x0100); // RD
        put16(out, 4, 1);      // QDCOUNT
        System.arraycopy(qname, 0, out, 12, qname.length);
        int off = 12 + qname.length;
        put16(out, off, type);
        put16(out, off + 2, 1); // CLASS IN
        return out;
    }

    // Response with an alias chain, the way a real upstream builds it: each link
    // is a CNAME to the next, the last one gets the final record (A/AAAA), and
    // the question is echoed byte for byte, as the server requires before relaying.

    // WARNING: names in the answer section come compressed (0xC0xx pointer) — that
    // is how the real upstream saves bytes, and what the name reader follows.
    static byte[] chainedResponse(byte[] query, String[] chain,
                                  int finalType, byte[] finalRdata) {
        int id = u16(query, 0);
        int questionEnd = questionEnd(query);
        byte[] question = Arrays.copyOfRange(query, 12, questionEnd);
        int links = Math.max(0, chain.length - 1);
        int anCount = links + (finalType > 0 ? 1 : 0);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(id >>> 8);
        out.write(id & 0xFF);
        out.write(0x81); // QR=1, RD
        out.write(0x80); // RA
        out.write(0);
        out.write(1); // QDCOUNT
        out.write(0);
        out.write(anCount); // ANCOUNT
        out.write(0);
        out.write(0); // NSCOUNT
        out.write(0);
        out.write(0); // ARCOUNT
        out.writeBytes(question);

        // Offset of each name in the chain: the first is the echoed QNAME (12), the
        // others are written inside the RDATA of the CNAME that points to them.
        int[] offsets = new int[chain.length];
        offsets[0] = 12;
        for (int i = 0; i < links; i++) {
            writePointer(out, offsets[i]); // owner = previous name
            out.write(0);
            out.write(5); // TYPE CNAME
            out.write(0);
            out.write(1); // CLASS IN
            out.write(0);
            out.write(0);
            out.write(0);
            out.write(60); // TTL
            byte[] target = encodeName(chain[i + 1]);
            out.write(0);
            out.write(target.length); // RDLEN
            offsets[i + 1] = out.size();
            out.writeBytes(target);
        }
        if (finalType > 0) {
            writePointer(out, offsets[chain.length - 1]);
            out.write(finalType >>> 8);
            out.write(finalType & 0xFF);
            out.write(0);
            out.write(1); // CLASS IN
            out.write(0);
            out.write(0);
            out.write(0);
            out.write(60); // TTL
            out.write(finalRdata.length >>> 8);
            out.write(finalRdata.length & 0xFF);
            out.writeBytes(finalRdata);
        }
        return out.toByteArray();
    }

    // Writes a compression pointer (0xC0xx) to the given offset.
    private static void writePointer(ByteArrayOutputStream out, int offset) {
        out.write(0xC0 | ((offset >>> 8) & 0x3F));
        out.write(offset & 0xFF);
    }

    // Offset of the end of the question section (after QTYPE/QCLASS).
    static int questionEnd(byte[] data) {
        int off = 12;
        while (off < data.length) {
            int len = data[off] & 0xFF;
            if ((len & 0xC0) == 0xC0) {
                off += 2;
                break;
            }
            off += 1 + len;
            if (len == 0) {
                break;
            }
        }
        return off + 4;
    }

    // Skips a name (including a compression pointer) and returns the next offset.
    static int skipName(byte[] data, int off) {
        while (off < data.length) {
            int len = data[off] & 0xFF;
            if ((len & 0xC0) == 0xC0) {
                return off + 2;
            }
            off += 1 + len;
            if (len == 0) {
                return off;
            }
        }
        return off;
    }

    // Response read from the buffer.
    record Response(int id, int flags, int rcode, int qdCount, int anCount, List<Answer> answers) {

        boolean isResponse() {
            return (flags & 0x8000) != 0;
        }

        // First RDATA in the response, or null.
        byte[] firstRdata() {
            return answers.isEmpty() ? null : answers.get(0).rdata();
        }
    }

    // An answer-section record.
    record Answer(int type, byte[] rdata) {
    }

    // Reads a response (header + question + answers).
    static Response parse(byte[] data) {
        int id = u16(data, 0);
        int flags = u16(data, 2);
        int qdCount = u16(data, 4);
        int anCount = u16(data, 6);
        int off = questionEnd(data);
        List<Answer> answers = new ArrayList<>();
        for (int i = 0; i < anCount; i++) {
            off = skipName(data, off);
            int type = u16(data, off);
            off += 2; // TYPE
            off += 2; // CLASS
            off += 4; // TTL
            int rdlen = u16(data, off);
            off += 2;
            byte[] rdata = Arrays.copyOfRange(data, off, off + rdlen);
            off += rdlen;
            answers.add(new Answer(type, rdata));
        }
        return new Response(id, flags, flags & 0x0F, qdCount, anCount, answers);
    }
}
