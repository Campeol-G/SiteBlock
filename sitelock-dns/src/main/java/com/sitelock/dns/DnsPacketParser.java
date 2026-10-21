package com.sitelock.dns;

import java.net.IDN;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
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

    /**
     * Decoder UTF-8 estrito (rejeita entrada inválida em vez de trocar por U+FFFD).
     * Por thread: o decoder do JDK não é thread-safe.
     */
    private static final ThreadLocal<CharsetDecoder> UTF8_STRICT = ThreadLocal.withInitial(() ->
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT));

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
        int wireLen = 0;
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
            String label = decodeLabel(data, off, labelLen).toLowerCase(Locale.ROOT);
            if (!firstLabel) {
                name.append('.');
                wireLen += 1; // o ponto do nome também ocupa 1 byte no fio
            }
            name.append(label);
            firstLabel = false;
            off += labelLen;
            wireLen += labelLen;
            // O limite do RFC 1035 §3.1 é sobre o nome no fio (bytes), não sobre os
            // chars decodificados: um rótulo UTF-8 ocupa mais de um byte.
            if (wireLen > 253) {
                throw new DnsParseException("nome com mais de 253 caracteres");
            }
        }
        if (!terminated) {
            throw new DnsParseException("QNAME sem terminador (pacote malformado ou malicioso)");
        }

        String canonical;
        try {
            canonical = toAsciiName(name.toString());
        } catch (IllegalArgumentException e) {
            // Nome inválido para IDNA: pacote descartado (nada de derrubar o servidor).
            throw new DnsParseException("nome inválido para IDNA: " + e.getMessage());
        }
        if (canonical.length() > 253) {
            throw new DnsParseException("nome com mais de 253 caracteres");
        }

        if (off + 4 > length) {
            throw new DnsParseException("faltam QTYPE/QCLASS após o QNAME");
        }
        int qtype = u16(data, off);
        int qclass = u16(data, off + 2);
        int questionEnd = off + 4;

        byte[] questionSection = Arrays.copyOfRange(data, HEADER_LEN, questionEnd);
        return new DnsQuery(transactionId, canonical, qtype, qclass, questionSection, flags);
    }

    /**
     * Decodifica um rótulo sem perder byte nenhum: 1 byte vira 1 char.
     *
     * <p>UTF-8 quando o rótulo é UTF-8 válido (cliente que manda IDN em vez de
     * punycode); senão ISO-8859-1, que mapeia cada byte 0x00-0xFF em um char. O
     * decoder ASCII trocaria todo byte &ge; 0x80 por U+FFFD e o nome deixaria de
     * casar com a lista de bloqueio.
     */
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
     * Converte um nome para a forma ASCII canônica (IDNA/punycode), em minúsculas.
     *
     * <p>É o que faz o nome em unicode e o nome em punycode casarem com a mesma
     * entrada da lista de bloqueio ("пример.рф" e "xn--e1afmkfd.xn--p1ai").
     *
     * @throws IllegalArgumentException se o nome não for válido para IDNA
     */
    public static String toAsciiName(String name) {
        String s = name == null ? "" : name.trim();
        while (s.endsWith(".") && s.length() > 1) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.isEmpty()) {
            return "";
        }
        // IDN.toASCII não minúscula o ASCII: normalizamos aqui.
        return IDN.toASCII(s).toLowerCase(Locale.ROOT);
    }

    private static int u16(byte[] data, int off) {
        return ((data[off] & 0xFF) << 8) | (data[off + 1] & 0xFF);
    }
}
