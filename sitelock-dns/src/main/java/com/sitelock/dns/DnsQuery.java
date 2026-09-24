package com.sitelock.dns;

import java.util.Arrays;
import java.util.Objects;

/**
 * Uma consulta DNS (seção question) já parseada.
 *
 * @param transactionId ID de 16 bits do header (0–65535); a resposta precisa ecoá-lo.
 * @param name          domínio perguntado, normalizado (minúsculas, sem ponto final).
 *                      Nome vazio ("") representa a raiz ".".
 * @param type          QTYPE (ex.: 1 = A, 28 = AAAA).
 * @param clazz         QCLASS (ex.: 1 = IN).
 * @param questionSection bytes da seção question (do início do QNAME até QCLASS),
 *                      copiados do pacote original para serem ecoados na resposta.
 * @param requestFlags  palavra de flags do header original (usada para copiar o bit RD).
 */
public record DnsQuery(
        int transactionId,
        String name,
        int type,
        int clazz,
        byte[] questionSection,
        int requestFlags) {

    /** Tipos e classes (RFC 1035 §3.2.2, §3.2.4). */
    public static final int TYPE_A = 1;
    public static final int TYPE_AAAA = 28;
    public static final int CLASS_IN = 1;

    public DnsQuery {
        if (transactionId < 0 || transactionId > 0xFFFF) {
            throw new IllegalArgumentException("transactionId fora de 0-65535: " + transactionId);
        }
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(questionSection, "questionSection");
        questionSection = questionSection.clone();
    }

    @Override
    public byte[] questionSection() {
        return questionSection.clone();
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
