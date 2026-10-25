package com.sitelock.dns;

/**
 * Hand-rolled DNS response construction (RFC 1035 §4.1.1), with no high-level libraries.
 *
 * <p>Layout of the block response:
 * <pre>
 *   header (12 bytes, echoed ID) + question (echo of the query) + 1 answer:
 *     NAME = pointer 0xC00C (offset 12, start of QNAME), TYPE, CLASS=IN,
 *     TTL, RDLENGTH, RDATA (sinkhole)
 *   + EDNS OPT (11 bytes, RFC 6891)
 * </pre>
 */
public final class DnsPacketBuilder {

    /** Short TTL for the sinkhole: a block disappears from caches quickly after unblock. */
    public static final int TTL_BLOCKED_SECONDS = 60;

    /**
     * EDNS payload size used when the client sent no OPT (RFC 6891 §6.2.3).
     */
    public static final int DEFAULT_EDNS_PAYLOAD_SIZE = 4096;

    // Size of the OPT pseudo-record: root name (1) + TYPE (2) + CLASS (2) + TTL (4) + RDLEN (2).
    private static final int OPT_LEN = 11;

    // WHY: 0.0.0.0 (the "unspecified" address, RFC 1122 §3.2.1.3) rather than 127.0.0.1:
    // against loopback the app may get an unexpected response or wait for the connection
    // timeout; against 0.0.0.0, connect() fails immediately (EADDRNOTAVAIL/EINVAL).
    // This is what Pi-hole does.
    private static final byte[] SINKHOLE_V4 = {0, 0, 0, 0};
    private static final byte[] SINKHOLE_V6 = new byte[16];

    private DnsPacketBuilder() {
    }

    /**
     * Builds the block response for the query: sinkhole (0.0.0.0 / ::) for A/AAAA
     * and NODATA for every other type.
     *
     * <p>A blocked name is never forwarded upstream: the target's real answer would
     * reach the client and the DNS provider would see the query.
     */
    public static byte[] buildBlockedResponse(DnsQuery query) {
        if (query.type() == DnsQuery.TYPE_A) {
            return buildAnswer(query, DnsQuery.TYPE_A, SINKHOLE_V4);
        }
        if (query.type() == DnsQuery.TYPE_AAAA) {
            return buildAnswer(query, DnsQuery.TYPE_AAAA, SINKHOLE_V6);
        }
        return buildNoDataResponse(query);
    }

    /**
     * Builds the NODATA response: RCODE 0, ANCOUNT 0, and no answer — "the name exists
     * but has no data of this type".
     *
     * <p>This is the blocked-name response for types other than A/AAAA (MX, TXT,
     * CNAME, ANY, SVCB, HTTPS…), matching Pi-hole/AdGuard semantics.
     */
    public static byte[] buildNoDataResponse(DnsQuery query) {
        byte[] question = query.questionSectionInternal();
        byte[] out = new byte[12 + question.length + OPT_LEN];
        writeHeader(out, query, 0, 0, true);
        System.arraycopy(question, 0, out, 12, question.length);
        writeOpt(out, 12 + question.length, query);
        return out;
    }

    /**
     * Builds the local response for the decision (sinkhole for BLOCK, NODATA for
     * BLOCK_NODATA). A shortcut so the server cannot pick the wrong response type.
     *
     * @throws IllegalArgumentException if the decision is FORWARD (no local response)
     */
    public static byte[] buildResponse(DnsQuery query, DnsResolver.Decision decision) {
        return switch (decision) {
            case BLOCK -> buildBlockedResponse(query);
            case BLOCK_NODATA -> buildNoDataResponse(query);
            case FORWARD -> throw new IllegalArgumentException(
                    "FORWARD decision does not produce a local response");
        };
    }

    /**
     * Builds an error response with the echoed question and zero answers.
     *
     * @param rcode error code (e.g. 2 = SERVFAIL when the upstream does not answer)
     */
    public static byte[] buildErrorResponse(DnsQuery query, int rcode) {
        if (rcode < 0 || rcode > 15) {
            throw new IllegalArgumentException("rcode outside 0-15: " + rcode);
        }
        byte[] question = query.questionSectionInternal();
        byte[] out = new byte[12 + question.length + OPT_LEN];
        writeHeader(out, query, rcode, 0, false);
        System.arraycopy(question, 0, out, 12, question.length);
        writeOpt(out, 12 + question.length, query);
        return out;
    }

    private static byte[] buildAnswer(DnsQuery query, int type, byte[] rdata) {
        byte[] question = query.questionSectionInternal();
        // 12 (header) + question + 12 (fixed answer: ptr+type+class+ttl+rdlen) + rdata + OPT
        byte[] out = new byte[12 + question.length + 12 + rdata.length + OPT_LEN];
        writeHeader(out, query, 0, 1, true);
        int off = 12;
        System.arraycopy(question, 0, out, off, question.length);
        off += question.length;
        // NAME as a pointer to the QNAME (offset 12 = 0xC00C).
        out[off++] = (byte) 0xC0;
        out[off++] = 0x0C;
        out[off++] = (byte) (type >>> 8);
        out[off++] = (byte) type;
        out[off++] = 0x00;
        out[off++] = (byte) DnsQuery.CLASS_IN;
        int ttl = TTL_BLOCKED_SECONDS;
        out[off++] = (byte) (ttl >>> 24);
        out[off++] = (byte) (ttl >>> 16);
        out[off++] = (byte) (ttl >>> 8);
        out[off++] = (byte) ttl;
        out[off++] = (byte) (rdata.length >>> 8);
        out[off++] = (byte) rdata.length;
        System.arraycopy(rdata, 0, out, off, rdata.length);
        off += rdata.length;
        writeOpt(out, off, query);
        return out;
    }

    // Writes the EDNS OPT pseudo-record (RFC 6891): root name, TYPE=41, CLASS =
    // payload size (the client's when known), TTL=0, and RDLEN=0. Some resolvers
    // and firewalls drop a response without OPT, and EDNS clients expect one.
    private static void writeOpt(byte[] out, int off, DnsQuery query) {
        int size = query.ednsPayloadSize() > 0
                ? query.ednsPayloadSize()
                : DEFAULT_EDNS_PAYLOAD_SIZE;
        out[off] = 0x00; // NAME: root
        out[off + 1] = 0x00; // TYPE = 41 (OPT)
        out[off + 2] = 0x29;
        out[off + 3] = (byte) (size >>> 8); // CLASS = payload size
        out[off + 4] = (byte) size;
        out[off + 5] = 0x00; // TTL = 0
        out[off + 6] = 0x00;
        out[off + 7] = 0x00;
        out[off + 8] = 0x00;
        out[off + 9] = 0x00; // RDLEN = 0
        out[off + 10] = 0x00;
    }

    // Response header: echoed ID, QR=1, OPCODE=0, RD copied from the query,
    // RA=1 (we accept recursion through the upstream), and the given RCODE. AA=1
    // on local block/NODATA responses (the server is authoritative for the name)
    // and AA=0 on error responses.
    private static void writeHeader(byte[] out, DnsQuery query, int rcode,
                                    int anCount, boolean authoritative) {
        int id = query.transactionId();
        out[0] = (byte) (id >>> 8);
        out[1] = (byte) id;
        int flags = 0x8000 // QR = response
                | (query.requestFlags() & 0x0100) // RD: copy the client's request
                | (authoritative ? 0x0400 : 0) // AA = authoritative for the name
                | 0x0080 // RA = recursion available
                | (rcode & 0x0F);
        out[2] = (byte) (flags >>> 8);
        out[3] = (byte) flags;
        out[4] = 0x00;
        out[5] = 0x01; // QDCOUNT = 1
        out[6] = (byte) (anCount >>> 8);
        out[7] = (byte) anCount;
        out[8] = 0x00;
        out[9] = 0x00; // NSCOUNT = 0
        out[10] = 0x00;
        out[11] = 0x01; // ARCOUNT = 1 (the EDNS OPT, RFC 6891)
    }
}
