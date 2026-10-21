package com.sitelock.dns;

/**
 * Montagem manual de respostas DNS (RFC 1035 §4.1.1), sem bibliotecas de alto nível.
 *
 * <p>Layout da resposta de bloqueio:
 * <pre>
 *   header (12 bytes, ID ecoado) + question (eco da pergunta) + 1 answer:
 *     NAME = ponteiro 0xC00C (offset 12, início do QNAME), TYPE, CLASS=IN,
 *     TTL, RDLENGTH, RDATA (sinkhole)
 *   + OPT do EDNS (11 bytes, RFC 6891)
 * </pre>
 *
 * <h2>0.0.0.0 vs 127.0.0.1 — por que 0.0.0.0</h2>
 * <ul>
 *   <li><b>0.0.0.0</b> é o endereço "unspecified" (RFC 1122 §3.2.1.3). Quando um app tenta
 *       {@code connect()} para 0.0.0.0, o SO falha na hora (EADDRNOTAVAIL/EINVAL) sem emitir
 *       nenhum pacote na rede. O bloqueio é instantâneo e não toca em nada na máquina local.
 *       É o padrão usado por listas como StevenBlack e pelo Pi-hole.</li>
 *   <li><b>127.0.0.1</b> aponta para o loopback: o cliente tenta abrir conexão <i>consigo
 *       mesmo</i>. Se houver algum serviço ouvindo naquela porta, o app pode receber resposta
 *       inesperada; se não houver, ele espera até o timeout de conexão (lento, drena bateria
 *       no celular) e gera logs confusos de "conexão recusada pelo localhost".</li>
 * </ul>
 * <p>Por isso o sinkhole aqui é <b>0.0.0.0</b> para A e <b>::</b> (16 bytes zero) para AAAA.
 */
public final class DnsPacketBuilder {

    /** TTL curto para o sinkhole: bloqueio some rápido do cache ao desbloquear. */
    public static final int TTL_BLOCKED_SECONDS = 60;

    /**
     * Payload size EDNS usado quando o cliente não mandou OPT (RFC 6891 §6.2.3).
     */
    public static final int DEFAULT_EDNS_PAYLOAD_SIZE = 4096;

    /** Tamanho do pseudo-registro OPT: nome raiz (1) + TYPE (2) + CLASS (2) + TTL (4) + RDLEN (2). */
    private static final int OPT_LEN = 11;

    private static final byte[] SINKHOLE_V4 = {0, 0, 0, 0};
    private static final byte[] SINKHOLE_V6 = new byte[16];

    private DnsPacketBuilder() {
    }

    /**
     * Monta a resposta de bloqueio para a consulta: sinkhole (0.0.0.0 / ::) nos tipos
     * A/AAAA e NODATA nos demais tipos.
     *
     * <p>Um nome bloqueado nunca é encaminhado ao upstream: a resposta real do domínio
     * bloqueado chegaria ao cliente (e o provedor de DNS veria a consulta).
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
     * Monta a resposta NODATA: RCODE 0, ANCOUNT 0 e nenhuma answer — "o nome existe,
     * mas não tem dados desse tipo".
     *
     * <p>É a resposta de um nome bloqueado para tipos diferentes de A/AAAA (MX, TXT,
     * CNAME, ANY, SVCB, HTTPS…), a mesma semântica do Pi-hole/AdGuard.
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
     * Monta a resposta local correspondente à decisão (sinkhole em BLOCK, NODATA em
     * BLOCK_NODATA). Atalho para o servidor não errar o tipo de resposta.
     *
     * @throws IllegalArgumentException se a decisão for FORWARD (não gera resposta local)
     */
    public static byte[] buildResponse(DnsQuery query, DnsResolver.Decision decision) {
        return switch (decision) {
            case BLOCK -> buildBlockedResponse(query);
            case BLOCK_NODATA -> buildNoDataResponse(query);
            case FORWARD -> throw new IllegalArgumentException(
                    "decisão FORWARD não gera resposta local");
        };
    }

    /**
     * Monta resposta de erro com a pergunta ecoada e zero answers.
     *
     * @param rcode código de erro (ex.: 2 = SERVFAIL quando o upstream não responde)
     */
    public static byte[] buildErrorResponse(DnsQuery query, int rcode) {
        if (rcode < 0 || rcode > 15) {
            throw new IllegalArgumentException("rcode fora de 0-15: " + rcode);
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
        // 12 (header) + question + 12 (answer fixo: ptr+type+class+ttl+rdlen) + rdata + OPT
        byte[] out = new byte[12 + question.length + 12 + rdata.length + OPT_LEN];
        writeHeader(out, query, 0, 1, true);
        int off = 12;
        System.arraycopy(question, 0, out, off, question.length);
        off += question.length;
        // NAME como ponteiro para o QNAME (offset 12 = 0xC00C).
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

    /**
     * Escreve o pseudo-registro OPT do EDNS (RFC 6891): nome raiz, TYPE=41,
     * CLASS = payload size (o do cliente, quando conhecido), TTL=0 e RDLEN=0.
     *
     * <p>Alguns resolvers e firewalls descartam a resposta sem OPT — e o cliente
     * EDNS espera um na resposta.
     */
    private static void writeOpt(byte[] out, int off, DnsQuery query) {
        int size = query.ednsPayloadSize() > 0
                ? query.ednsPayloadSize()
                : DEFAULT_EDNS_PAYLOAD_SIZE;
        out[off] = 0x00; // NAME: raiz
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

    /**
     * Header de resposta: ID ecoado, QR=1, OPCODE=0, RD copiado da pergunta,
     * RA=1 (aceitamos recursão via upstream), RCODE informado.
     *
     * <p>AA=1 nas respostas locais de bloqueio/NODATA: para o nome bloqueado o
     * servidor é a autoridade. AA=0 nas respostas de erro (SERVFAIL): lá não
     * há answer e não somos autoridade do nome.
     */
    private static void writeHeader(byte[] out, DnsQuery query, int rcode,
                                    int anCount, boolean authoritative) {
        int id = query.transactionId();
        out[0] = (byte) (id >>> 8);
        out[1] = (byte) id;
        int flags = 0x8000 // QR = resposta
                | (query.requestFlags() & 0x0100) // RD: copia o pedido do cliente
                | (authoritative ? 0x0400 : 0) // AA = autoridade para o nome
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
        out[11] = 0x01; // ARCOUNT = 1 (o OPT do EDNS, RFC 6891)
    }
}
