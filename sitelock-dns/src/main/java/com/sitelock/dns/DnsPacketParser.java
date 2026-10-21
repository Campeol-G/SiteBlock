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
 * Parsing manual de pacotes DNS (RFC 1035 §4.1), sem bibliotecas de alto nível.
 *
 * <p>Suporta consultas padrão (OPCODE 0, QR 0) com exatamente uma pergunta (QDCOUNT 1).
 * Ponteiros de compressão no QNAME são rejeitados: consultas bem formadas enviadas
 * por stubs (celulares, PCs) nunca comprimem a pergunta, então um ponteiro aqui
 * indica pacote malformado ou malicioso.
 *
 * <p>Seções adicionais (ex.: OPT do EDNS, ARCOUNT &gt; 0) são toleradas: o OPT é
 * lido (payload size do cliente) e o resto é ignorado — o encaminhamento ao
 * upstream retransmite os bytes originais intactos, então nada se perde.
 * Respostas de bloqueio ecoam um OPT mínimo (RFC 6891).
 */
public final class DnsPacketParser {

    /** Tamanho do header DNS fixo (ID + flags + 4 contadores). */
    static final int HEADER_LEN = 12;

    /** QTYPE do pseudo-registro OPT do EDNS (RFC 6891 §6.1.1). */
    static final int TYPE_OPT = 41;

    /** QTYPE do registro CNAME (RFC 1035 §3.2.2). */
    static final int TYPE_CNAME = 5;

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

        int ednsPayloadSize = readEdnsPayloadSize(data, questionEnd, length, u16(data, 10));

        byte[] questionSection = Arrays.copyOfRange(data, HEADER_LEN, questionEnd);
        return new DnsQuery(transactionId, canonical, qtype, qclass, questionSection, flags,
                ednsPayloadSize);
    }

    /**
     * Nomes da section answer de uma RESPOSTA (QR=1).
     *
     * <p>POR QUE: a cadeia de aliases só aparece na resposta do upstream — o nome
     * consultado é livre, mas o CNAME aponta para um nome bloqueado. Para ver a
     * cadeia é preciso ler os nomes da section answer, e lá eles vêm
     * <b>comprimidos</b> (ponteiro 0xC0xx), ao contrário do QNAME da consulta.
     *
     * <p>Devolve o owner de cada registro e o alvo de cada CNAME: são os nomes
     * da cadeia. Um registro malformado encerra a leitura (o resto da seção não
     * é confiável) e nada é lido fora do pacote — a lista volta incompleta, o
     * pacote nunca quebra o servidor.
     *
     * @param data   bytes da resposta
     * @param length quantidade de bytes válidos em {@code data}
     */
    public static List<String> nomesDaAnswer(byte[] data, int length) {
        List<String> nomes = new ArrayList<>();
        if (data == null || length < HEADER_LEN || length > data.length) {
            return nomes;
        }
        int anCount = u16(data, 6);
        if (anCount == 0) {
            return nomes; // NXDOMAIN/NODATA: não há cadeia para inspecionar
        }
        // A question é pulada com o mesmo leitor: numa resposta o nome dela
        // também pode vir comprimido.
        Nome pergunta = lerNome(data, HEADER_LEN, length, length);
        if (pergunta == null || pergunta.fim() + 4 > length) {
            return nomes;
        }
        int off = pergunta.fim() + 4; // depois do QTYPE/QCLASS
        for (int i = 0; i < anCount; i++) {
            if (off >= length) {
                break;
            }
            Nome owner = lerNome(data, off, length, length);
            if (owner == null) {
                break;
            }
            off = owner.fim();
            if (off + 10 > length) {
                break; // registro truncado
            }
            int type = u16(data, off);
            int rdlen = u16(data, off + 8);
            int rdata = off + 10;
            if (rdata + rdlen > length) {
                break; // RDATA truncada
            }
            if (!owner.texto().isEmpty()) {
                nomes.add(owner.texto());
            }
            if (type == TYPE_CNAME) {
                // O alvo do CNAME é o próximo elo da cadeia: a RDATA é um nome.
                Nome alvo = lerNome(data, rdata, rdata + rdlen, length);
                if (alvo != null && !alvo.texto().isEmpty()) {
                    nomes.add(alvo.texto());
                }
            }
            off = rdata + rdlen;
        }
        return nomes;
    }

    /** Um nome lido do fio: o texto e o offset logo depois dele. */
    private record Nome(String texto, int fim) {
    }

    /**
     * Lê um nome no fio, seguindo ponteiros de compressão (RFC 1035 §4.1.4).
     *
     * @param data      pacote
     * @param off       offset onde o nome começa
     * @param limite    fim da região onde os rótulos podem ser lidos direto
     *                  (a RDATA, quando o nome está dentro dela)
     * @param fimPacote fim do pacote (onde os ponteiros podem apontar)
     * @return o nome lido, ou null se o nome não couber no pacote
     */
    private static Nome lerNome(byte[] data, int off, int limite, int fimPacote) {
        if (off < 0 || off >= limite || limite > fimPacote) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        int p = off;
        int fim = -1;
        int limiteAtual = limite;
        boolean completo = false;
        // Trava de segurança: nome com rótulos demais ou ponteiro em ciclo.
        for (int passos = 0; passos < 128; passos++) {
            if (p < 0 || p >= fimPacote) {
                return null;
            }
            int b = data[p] & 0xFF;
            if ((b & 0xC0) == 0xC0) {
                if (p + 1 >= fimPacote) {
                    return null;
                }
                int destino = ((b & 0x3F) << 8) | (data[p + 1] & 0xFF);
                if (destino >= fimPacote) {
                    return null;
                }
                if (fim < 0) {
                    fim = p + 2;
                }
                // ATENÇÃO: depois do salto os rótulos vêm do pacote, não da
                // RDATA — por isso o limite passa a ser o fim do pacote.
                limiteAtual = fimPacote;
                p = destino;
                continue;
            }
            if ((b & 0xC0) != 0) {
                return null; // bits reservados (0x40/0x80) não valem num nome
            }
            if (b > 63) {
                return null;
            }
            if (b == 0) {
                p++;
                if (fim < 0) {
                    fim = p;
                }
                completo = true;
                break;
            }
            if (p + 1 + b > limiteAtual) {
                return null; // rótulo ultrapassa a região permitida
            }
            p++;
            if (sb.length() > 0) {
                sb.append('.');
            }
            sb.append(decodeLabel(data, p, b).toLowerCase(Locale.ROOT));
            p += b;
            if (sb.length() > 253) {
                return null; // nome maior que o limite do RFC 1035 §3.1
            }
        }
        if (!completo) {
            return null; // nome sem terminador ou com rótulos demais
        }
        return new Nome(sb.toString(), fim);
    }

    /**
     * Lê o payload UDP anunciado pelo cliente no OPT do EDNS (RFC 6891).
     *
     * <p>A section additional é tolerada: se estiver malformada ou truncada, o OPT
     * é ignorado (0 = sem EDNS). Nunca ler fora do buffer.
     */
    private static int readEdnsPayloadSize(byte[] data, int off, int length, int arCount) {
        int p = off;
        for (int i = 0; i < arCount; i++) {
            int next = skipName(data, p, length);
            if (next < 0) {
                return 0;
            }
            p = next;
            if (p + 10 > length) {
                return 0; // registro truncado
            }
            int type = u16(data, p);
            int cls = u16(data, p + 2);
            int rdlen = u16(data, p + 8);
            p += 10;
            if (p + rdlen > length) {
                return 0; // RDATA truncada
            }
            if (type == TYPE_OPT) {
                return cls; // CLASS do OPT = payload size do cliente
            }
            p += rdlen;
        }
        return 0;
    }

    /**
     * Devolve o offset logo depois do nome (rótulos ou ponteiro de compressão),
     * ou -1 se o nome não couber no pacote.
     */
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
                return p; // nome terminado
            }
        }
        return -1;
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
