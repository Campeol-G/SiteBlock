package com.sitelock.dns;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

/**
 * Parsing manual de pacotes DNS (RFC 1035 §4.1), sem bibliotecas de alto nível.
 *
 * <p>Suporta consultas padrão (OPCODE 0, QR 0) com exatamente uma pergunta (QDCOUNT 1).
 * Ponteiros de compressão no QNAME são rejeitados: consultas bem formadas enviadas
 * por stubs (celulares, PCs) nunca comprimem a pergunta, então um ponteiro aqui
 * indica pacote malformado ou malicioso.
 *
 * <p>Seções adicionais (ex.: OPT do EDNS, ARCOUNT &gt; 0) são toleradas e ignoradas
 * no parsing — o encaminhamento ao upstream retransmite os bytes originais intactos,
 * então nada se perde. Respostas de bloqueio não ecoam o OPT (resposta mínima válida).
 */
public final class DnsPacketParser {

    /** Tamanho do header DNS fixo (ID + flags + 4 contadores). */
    static final int HEADER_LEN = 12;

    private DnsPacketParser() {
    }

    /**
     * @param data   bytes recebidos via UDP
     * @param length quantidade de bytes válidos em {@code data}
     * @return consulta parseada
     * @throws DnsParseException se o pacote não for uma consulta padrão bem formada
     */
    public static DnsQuery parse(byte[] data, int length) throws DnsParseException {
        if (data == null) {
            throw new DnsParseException("pacote nulo");
        }
        if (length < HEADER_LEN) {
            throw new DnsParseException(
                    "pacote curto: " + length + " bytes (header exige " + HEADER_LEN + ")");
        }
        if (length > data.length) {
            throw new DnsParseException("length (" + length + ") maior que o buffer (" + data.length + ")");
        }

        int transactionId = u16(data, 0);
        int flags = u16(data, 2);
        int qdCount = u16(data, 4);

        boolean isResponse = (flags & 0x8000) != 0;
        if (isResponse) {
            throw new DnsParseException("bit QR=1: pacote é resposta, não consulta");
        }
        int opcode = (flags >>> 11) & 0x0F;
        if (opcode != 0) {
            throw new DnsParseException("OPCODE=" + opcode + " não suportado (só consulta padrão)");
        }
        if (qdCount != 1) {
            throw new DnsParseException("QDCOUNT=" + qdCount + " (esperado exatamente 1)");
        }

        int off = HEADER_LEN;
        StringBuilder name = new StringBuilder();
        boolean firstLabel = true;
        boolean terminated = false;
        // Trava de segurança contra pacote malicioso sem terminador: no máximo
        // 128 rótulos e o nome nunca passa de 253 chars (RFC 1035 §3.1).
        for (int labels = 0; labels < 128; labels++) {
            if (off >= length) {
                throw new DnsParseException("QNAME ultrapassa o fim do pacote");
            }
            int labelLen = data[off] & 0xFF;
            if ((labelLen & 0xC0) == 0xC0) {
                throw new DnsParseException("ponteiro de compressão no QNAME (não esperado em consulta)");
            }
            if (labelLen > 63) {
                throw new DnsParseException("rótulo com tamanho inválido: " + labelLen);
            }
            if (labelLen == 0) {
                off++;
                terminated = true;
                break;
            }
            off++;
            if (off + labelLen > length) {
                throw new DnsParseException("rótulo ultrapassa o fim do pacote");
            }
            String label = new String(data, off, labelLen, StandardCharsets.US_ASCII)
                    .toLowerCase(Locale.ROOT);
            if (!firstLabel) {
                name.append('.');
            }
            name.append(label);
            firstLabel = false;
            off += labelLen;
            if (name.length() > 253) {
                throw new DnsParseException("nome com mais de 253 caracteres");
            }
        }
        if (!terminated) {
            throw new DnsParseException("QNAME sem terminador (pacote malformado ou malicioso)");
        }

        if (off + 4 > length) {
            throw new DnsParseException("faltam QTYPE/QCLASS após o QNAME");
        }
        int qtype = u16(data, off);
        int qclass = u16(data, off + 2);
        int questionEnd = off + 4;

        byte[] questionSection = Arrays.copyOfRange(data, HEADER_LEN, questionEnd);
        return new DnsQuery(transactionId, name.toString(), qtype, qclass, questionSection, flags);
    }

    private static int u16(byte[] data, int off) {
        return ((data[off] & 0xFF) << 8) | (data[off + 1] & 0xFF);
    }
}
