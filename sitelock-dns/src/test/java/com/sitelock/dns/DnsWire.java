package com.sitelock.dns;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Monte e leitura de pacotes DNS para os testes de socket.
 *
 * <p>Não usa a internet: as consultas são montadas na mão e as respostas são
 * lidas direto do buffer, porque o parser de produção só sabe ler consultas.
 */
final class DnsWire {

    private DnsWire() {
    }

    /** Lê um inteiro de 16 bits big-endian. */
    static int u16(byte[] data, int off) {
        return ((data[off] & 0xFF) << 8) | (data[off + 1] & 0xFF);
    }

    /** Escreve um inteiro de 16 bits big-endian. */
    static void put16(byte[] data, int off, int value) {
        data[off] = (byte) (value >>> 8);
        data[off + 1] = (byte) value;
    }

    /** Codifica um nome em rótulos DNS ("exemplo.com" -> 7"exemplo"3"com"0). */
    static byte[] encodeName(String name) {
        String[] labels = name.split("\\.");
        int tamanho = 1;
        for (String l : labels) {
            tamanho += 1 + l.length();
        }
        byte[] out = new byte[tamanho];
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

    /** Monta uma consulta padrão (QR=0, RD=1, uma pergunta). */
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

    /** Offset do fim da seção question (após QTYPE/QCLASS). */
    static int fimDaPergunta(byte[] data) {
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

    /** Pula um nome (com ponteiro de compressão) e devolve o offset seguinte. */
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

    /** Resposta lida do buffer. */
    record Resposta(int id, int flags, int rcode, int qdCount, int anCount, List<Answer> answers) {

        boolean isResposta() {
            return (flags & 0x8000) != 0;
        }

        /** Primeiro RDATA da resposta, ou null. */
        byte[] primeiroRdata() {
            return answers.isEmpty() ? null : answers.get(0).rdata();
        }
    }

    /** Um registro da seção answer. */
    record Answer(int type, byte[] rdata) {
    }

    /** Lê uma resposta (header + question + answers). */
    static Resposta parse(byte[] data) {
        int id = u16(data, 0);
        int flags = u16(data, 2);
        int qdCount = u16(data, 4);
        int anCount = u16(data, 6);
        int off = fimDaPergunta(data);
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
        return new Resposta(id, flags, flags & 0x0F, qdCount, anCount, answers);
    }
}
