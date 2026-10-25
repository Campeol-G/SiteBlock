package com.sitelock.dns;

import java.net.IDN;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Hand-rolled DNS packet parsing (RFC 1035 §4.1), with no high-level libraries.
 *
 * <p>Supports standard queries (OPCODE 0, QR 0) with exactly one question (QDCOUNT 1).
 * Compression pointers in the QNAME are rejected: well-formed queries sent by stubs
 * (phones, PCs) never compress the question, so a pointer here means a malformed or
 * malicious packet.
 *
 * <p>Additional sections (e.g. the EDNS OPT, ARCOUNT &gt; 0) are tolerated: the OPT is
 * read (client payload size) and the rest is ignored — forwarding to the upstream
 * retransmits the original bytes untouched, so nothing is lost.
 * Block responses echo a minimal OPT (RFC 6891).
 */
public final class DnsPacketParser {

    /** Size of the fixed DNS header (ID + flags + 4 counters). */
    static final int HEADER_LEN = 12;

    /** QTYPE of the EDNS OPT pseudo-record (RFC 6891 §6.1.1). */
    static final int TYPE_OPT = 41;

    /** QTYPE of the CNAME record (RFC 1035 §3.2.2). */
    static final int TYPE_CNAME = 5;

    // Strict UTF-8 decoder (rejects invalid input instead of substituting U+FFFD).
    // Per thread: the JDK decoder is not thread-safe.
    private static final ThreadLocal<CharsetDecoder> UTF8_STRICT = ThreadLocal.withInitial(() ->
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT));

    private DnsPacketParser() {
    }

    /**
     * @param data   bytes received over UDP
     * @param length number of valid bytes in {@code data}
     * @return parsed query
     * @throws DnsParseException if the packet is not a well-formed standard query
     */
    public static DnsQuery parse(byte[] data, int length) throws DnsParseException {
        if (data == null) {
            throw new DnsParseException("null packet");
        }
        if (length < HEADER_LEN) {
            throw new DnsParseException(
                    "short packet: " + length + " bytes (header requires " + HEADER_LEN + ")");
        }
        if (length > data.length) {
            throw new DnsParseException("length (" + length + ") exceeds buffer (" + data.length + ")");
        }

        int transactionId = u16(data, 0);
        int flags = u16(data, 2);
        int qdCount = u16(data, 4);

        boolean isResponse = (flags & 0x8000) != 0;
        if (isResponse) {
            throw new DnsParseException("QR=1 bit set: packet is a response, not a query");
        }
        int opcode = (flags >>> 11) & 0x0F;
        if (opcode != 0) {
            throw new DnsParseException("OPCODE=" + opcode + " not supported (standard query only)");
        }
        if (qdCount != 1) {
            throw new DnsParseException("QDCOUNT=" + qdCount + " (expected exactly 1)");
        }

        int off = HEADER_LEN;
        StringBuilder name = new StringBuilder();
        boolean firstLabel = true;
        boolean terminated = false;
        int wireLen = 0;
        // Safety guard against a malicious packet with no terminator: at most
        // 128 labels, and the name never exceeds 253 chars (RFC 1035 §3.1).
        for (int labels = 0; labels < 128; labels++) {
            if (off >= length) {
                throw new DnsParseException("QNAME extends past end of packet");
            }
            int labelLen = data[off] & 0xFF;
            if ((labelLen & 0xC0) == 0xC0) {
                throw new DnsParseException("compression pointer in QNAME (not expected in a query)");
            }
            if (labelLen > 63) {
                throw new DnsParseException("label with invalid length: " + labelLen);
            }
            if (labelLen == 0) {
                off++;
                terminated = true;
                break;
            }
            off++;
            if (off + labelLen > length) {
                throw new DnsParseException("label extends past end of packet");
            }
            String label = decodeLabel(data, off, labelLen).toLowerCase(Locale.ROOT);
            if (!firstLabel) {
                name.append('.');
                wireLen += 1; // the dot in the name also takes one byte on the wire
            }
            name.append(label);
            firstLabel = false;
            off += labelLen;
            wireLen += labelLen;
            // The RFC 1035 §3.1 limit applies to the name on the wire (bytes), not to
            // decoded chars: a UTF-8 label takes more than one byte.
            if (wireLen > 253) {
                throw new DnsParseException("name longer than 253 characters");
            }
        }
        if (!terminated) {
            throw new DnsParseException("QNAME without terminator (malformed or malicious packet)");
        }

        String canonical;
        try {
            canonical = toAsciiName(name.toString());
        } catch (IllegalArgumentException e) {
            // Name invalid for IDNA: drop the packet (never bring the server down).
            throw new DnsParseException("invalid IDNA name: " + e.getMessage());
        }
        if (canonical.length() > 253) {
            throw new DnsParseException("name longer than 253 characters");
        }

        if (off + 4 > length) {
            throw new DnsParseException("missing QTYPE/QCLASS after QNAME");
        }
        int qtype = u16(data, off);
        int qclass = u16(data, off + 2);
        int questionEnd = off + 4;

        int ednsPayloadSize = readEdnsPayloadSize(data, questionEnd, length, u16(data, 10));

        byte[] questionSection = Arrays.copyOfRange(data, HEADER_LEN, questionEnd);
        return new DnsQuery(transactionId, canonical, qtype, qclass, questionSection, flags,
                ednsPayloadSize);
    }

    /**
     * Names in the answer section of a RESPONSE (QR=1).
     *
     * <p>WHY: an alias chain only shows up in the upstream response — the queried
     * name is free, but the CNAME points at a blocked name. Seeing the chain
     * requires reading the answer section, where names come <b>compressed</b>
     * (0xC0xx pointers), unlike the query QNAME.
     *
     * <p>Returns the owner of each record and the target of each CNAME: those are
     * the names in the chain. A malformed record ends parsing (the rest of the
     * section is not trustworthy), and nothing is read outside the packet — the
     * list comes back incomplete, but the packet never takes the server down.
     *
     * @param data   response bytes
     * @param length number of valid bytes in {@code data}
     */
    public static List<String> answerSectionNames(byte[] data, int length) {
        List<String> names = new ArrayList<>();
        if (data == null || length < HEADER_LEN || length > data.length) {
            return names;
        }
        int anCount = u16(data, 6);
        if (anCount == 0) {
            return names; // NXDOMAIN/NODATA: there is no chain to inspect
        }
        // The question is skipped with the same reader: in a response its name can
        // also be compressed.
        WireName question = readName(data, HEADER_LEN, length, length);
        if (question == null || question.end() + 4 > length) {
            return names;
        }
        int off = question.end() + 4; // past QTYPE/QCLASS
        for (int i = 0; i < anCount; i++) {
            if (off >= length) {
                break;
            }
            WireName owner = readName(data, off, length, length);
            if (owner == null) {
                break;
            }
            off = owner.end();
            if (off + 10 > length) {
                break; // truncated record
            }
            int type = u16(data, off);
            int rdlen = u16(data, off + 8);
            int rdataOffset = off + 10;
            if (rdataOffset + rdlen > length) {
                break; // truncated RDATA
            }
            if (!owner.text().isEmpty()) {
                names.add(owner.text());
            }
            if (type == TYPE_CNAME) {
                // The CNAME target is the next link in the chain: the RDATA is a name.
                WireName target = readName(data, rdataOffset, rdataOffset + rdlen, length);
                if (target != null && !target.text().isEmpty()) {
                    names.add(target.text());
                }
            }
            off = rdataOffset + rdlen;
        }
        return names;
    }

    // A name read from the wire: its text and the offset just past it.
    private record WireName(String text, int end) {
    }

    // Reads a name from the wire, following compression pointers (RFC 1035 §4.1.4).
    // "limit" is where labels may be read directly (the RDATA); "packetEnd" is the
    // end of the packet, where pointers may point. Null if it does not fit.
    private static WireName readName(byte[] data, int off, int limit, int packetEnd) {
        if (off < 0 || off >= limit || limit > packetEnd) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        int p = off;
        int end = -1;
        int currentLimit = limit;
        boolean complete = false;
        // Safety guard: a name with too many labels or a pointer cycle.
        for (int steps = 0; steps < 128; steps++) {
            if (p < 0 || p >= packetEnd) {
                return null;
            }
            int b = data[p] & 0xFF;
            if ((b & 0xC0) == 0xC0) {
                if (p + 1 >= packetEnd) {
                    return null;
                }
                int destination = ((b & 0x3F) << 8) | (data[p + 1] & 0xFF);
                if (destination >= packetEnd) {
                    return null;
                }
                if (end < 0) {
                    end = p + 2;
                }
                // NOTE: after the jump the labels come from the packet, not from
                // the RDATA — that is why the limit becomes the end of the packet.
                currentLimit = packetEnd;
                p = destination;
                continue;
            }
            if ((b & 0xC0) != 0) {
                return null; // reserved bits (0x40/0x80) are not valid in a name
            }
            if (b > 63) {
                return null;
            }
            if (b == 0) {
                p++;
                if (end < 0) {
                    end = p;
                }
                complete = true;
                break;
            }
            if (p + 1 + b > currentLimit) {
                return null; // label extends past the allowed region
            }
            p++;
            if (sb.length() > 0) {
                sb.append('.');
            }
            sb.append(decodeLabel(data, p, b).toLowerCase(Locale.ROOT));
            p += b;
            if (sb.length() > 253) {
                return null; // name longer than the RFC 1035 §3.1 limit
            }
        }
        if (!complete) {
            return null; // name without terminator or with too many labels
        }
        return new WireName(sb.toString(), end);
    }

    // Reads the UDP payload advertised by the client in the EDNS OPT (RFC 6891).
    // The additional section is tolerated: if it is malformed or truncated, the OPT
    // is ignored (0 = no EDNS). Never read outside the buffer.
    private static int readEdnsPayloadSize(byte[] data, int off, int length, int arCount) {
        int p = off;
        for (int i = 0; i < arCount; i++) {
            int next = skipName(data, p, length);
            if (next < 0) {
                return 0;
            }
            p = next;
            if (p + 10 > length) {
                return 0; // truncated record
            }
            int type = u16(data, p);
            int cls = u16(data, p + 2);
            int rdlen = u16(data, p + 8);
            p += 10;
            if (p + rdlen > length) {
                return 0; // truncated RDATA
            }
            if (type == TYPE_OPT) {
                return cls; // OPT CLASS = client payload size
            }
            p += rdlen;
        }
        return 0;
    }

    // Returns the offset just past the name (labels or compression pointer),
    // or -1 if the name does not fit in the packet.
    private static int skipName(byte[] data, int off, int length) {
        int p = off;
        for (int labels = 0; labels < 128; labels++) {
            if (p >= length) {
                return -1;
            }
            int l = data[p] & 0xFF;
            if ((l & 0xC0) == 0xC0) {
                return p + 2 <= length ? p + 2 : -1;
            }
            if (l > 63) {
                return -1;
            }
            p += 1 + l;
            if (p > length) {
                return -1;
            }
            if (l == 0) {
                return p; // terminated name
            }
        }
        return -1;
    }

    // Decodes a label without losing a single byte: one byte becomes one char. UTF-8
    // when the label is valid (IDN instead of punycode), otherwise ISO-8859-1 — the
    // ASCII decoder would replace every byte >= 0x80 with U+FFFD and the name would
    // never match.
    private static String decodeLabel(byte[] data, int off, int len) {
        boolean ascii = true;
        for (int i = off; i < off + len; i++) {
            if ((data[i] & 0x80) != 0) {
                ascii = false;
                break;
            }
        }
        if (ascii) {
            return new String(data, off, len, StandardCharsets.US_ASCII);
        }
        try {
            return UTF8_STRICT.get().reset().decode(ByteBuffer.wrap(data, off, len)).toString();
        } catch (CharacterCodingException e) {
            return new String(data, off, len, StandardCharsets.ISO_8859_1);
        }
    }

    /**
     * Converts a name to its canonical lowercase ASCII form (IDNA/punycode).
     *
     * <p>This is what makes a Unicode name and a punycode name match the same
     * blocklist entry ("пример.рф" and "xn--e1afmkfd.xn--p1ai").
     *
     * @throws IllegalArgumentException if the name is not valid for IDNA
     */
    public static String toAsciiName(String name) {
        String s = name == null ? "" : name.trim();
        while (s.endsWith(".") && s.length() > 1) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.isEmpty()) {
            return "";
        }
        // IDN.toASCII does not lowercase ASCII: normalize it here.
        return IDN.toASCII(s).toLowerCase(Locale.ROOT);
    }

    private static int u16(byte[] data, int off) {
        return ((data[off] & 0xFF) << 8) | (data[off + 1] & 0xFF);
    }
}
