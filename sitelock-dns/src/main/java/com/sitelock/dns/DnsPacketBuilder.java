package com.sitelock.dns;

/**
 * Montagem manual de respostas DNS (RFC 1035 §4.1.1), sem bibliotecas de alto nível.
 *
 * <p>Layout da resposta de bloqueio:
 * <pre>
 *   header (12 bytes, ID ecoado) + question (eco da pergunta) + 1 answer:
 *     NAME = ponteiro 0xC00C (offset 12, início do QNAME), TYPE, CLASS=IN,
 *     TTL, RDLENGTH, RDATA (sinkhole)
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

    private static final byte[] SINKHOLE_V4 = {0, 0, 0, 0};
    private static final byte[] SINKHOLE_V6 = new byte[16];

    private DnsPacketBuilder() {
    }

    /**
     * Monta a resposta de bloqueio para a consulta (só A e AAAA são bloqueáveis).
     *
     * @throws IllegalArgumentException se o tipo não for A/AAAA (esses devem ser encaminhados)
     */
    public static byte[] buildBlockedResponse(DnsQuery query) {
        if (query.type() == DnsQuery.TYPE_A) {
            return buildAnswer(query, DnsQuery.TYPE_A, SINKHOLE_V4);
        }
        if (query.type() == DnsQuery.TYPE_AAAA) {
            return buildAnswer(query, DnsQuery.TYPE_AAAA, SINKHOLE_V6);
        }
        throw new IllegalArgumentException(
                "tipo " + query.type() + " não é bloqueável (deve ser encaminhado ao upstream)");
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
        byte[] question = query.questionSection();
        byte[] out = new byte[12 + question.length];
        writeHeader(out, query, rcode, 0);
        System.arraycopy(question, 0, out, 12, question.length);
        return out;
    }

    private static byte[] buildAnswer(DnsQuery query, int type, byte[] rdata) {
        byte[] question = query.questionSection();
        // 12 (header) + question + 12 (answer fixo: ptr+type+class+ttl+rdlen) + rdata
        byte[] out = new byte[12 + question.length + 12 + rdata.length];
        writeHeader(out, query, 0, 1);
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
        return out;
    }

    /**
     * Header de resposta: ID ecoado, QR=1, OPCODE=0, RD copiado da pergunta,
     * RA=1 (aceitamos recursão via upstream), RCODE informado.
     */
    private static void writeHeader(byte[] out, DnsQuery query, int rcode, int anCount) {
        int id = query.transactionId();
        out[0] = (byte) (id >>> 8);
        out[1] = (byte) id;
        int flags = 0x8000 // QR = resposta
                | (query.requestFlags() & 0x0100) // RD: copia o pedido do cliente
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
        out[11] = 0x00; // ARCOUNT = 0 (não ecoamos OPT do EDNS; resposta mínima válida)
    }
}
