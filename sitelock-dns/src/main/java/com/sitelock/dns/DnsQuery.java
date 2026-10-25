package com.sitelock.dns;

import java.util.Arrays;
import java.util.Objects;

/**
 * An already parsed DNS query (question section).
 *
 * @param transactionId  16-bit header ID (0–65535); the response must echo it.
 * @param name           queried domain, normalized (lowercase, no trailing dot,
 *                       in canonical ASCII/punycode form).
 *                       An empty name ("") represents the root ".".
 * @param type           QTYPE (e.g. 1 = A, 28 = AAAA).
 * @param clazz          QCLASS (e.g. 1 = IN).
 * @param questionSection question-section bytes (from the start of QNAME through QCLASS),
 *                        copied from the original packet so they can be echoed in the response.
 * @param requestFlags   flag word from the original header (used to copy the RD bit).
 * @param ednsPayloadSize UDP payload advertised by the client in the EDNS OPT (RFC 6891);
 *                        0 = the client sent no OPT.
 */
public record DnsQuery(
        int transactionId,
        String name,
        int type,
        int clazz,
        byte[] questionSection,
        int requestFlags,
        int ednsPayloadSize) {

    /** Types and classes (RFC 1035 §3.2.2, §3.2.4). */
    public static final int TYPE_A = 1;
    public static final int TYPE_AAAA = 28;
    public static final int CLASS_IN = 1;

    public DnsQuery {
        if (transactionId < 0 || transactionId > 0xFFFF) {
            throw new IllegalArgumentException("transactionId outside 0-65535: " + transactionId);
        }
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(questionSection, "questionSection");
        questionSection = questionSection.clone();
    }

    /** Constructor without EDNS (client sent no OPT). */
    public DnsQuery(int transactionId, String name, int type, int clazz,
                    byte[] questionSection, int requestFlags) {
        this(transactionId, name, type, clazz, questionSection, requestFlags, 0);
    }

    @Override
    public byte[] questionSection() {
        return questionSection.clone();
    }

    // Question-section array without cloning — internal use within the package.
    // WHY: the builder was copying the array twice (once per accessor); the public
    // accessor still returns a copy, so mutability does not escape the package.
    byte[] questionSectionInternal() {
        return questionSection;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DnsQuery other)) {
            return false;
        }
        return transactionId == other.transactionId
                && type == other.type
                && clazz == other.clazz
                && requestFlags == other.requestFlags
                && name.equals(other.name)
                && Arrays.equals(questionSection, other.questionSection);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(transactionId, name, type, clazz, requestFlags);
        return 31 * result + Arrays.hashCode(questionSection);
    }

    @Override
    public String toString() {
        return "DnsQuery{id=" + transactionId + ", name='" + name + "'"
                + ", type=" + type + ", class=" + clazz + "}";
    }
}
