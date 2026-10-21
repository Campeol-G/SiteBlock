package com.sitelock.dns;

import com.sitelock.core.BlockEntry;
import com.sitelock.core.BlockStore;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;

/**
 * Decide, por consulta, entre responder o sinkhole local ou encaminhar ao upstream.
 *
 * <p>A decisão é pura e isolada da rede ({@link #decide(String, int, Map, Instant)} é
 * estática e testável sem sockets); o transporte está em {@link #forwardRaw} e no
 * {@link DnsServer}. Regras:
 * <ul>
 *   <li>Nome igual — ou subdomínio — de um bloqueio ativo:
 *       <ul>
 *         <li>tipo A ou AAAA: {@link Decision#BLOCK} (sinkhole 0.0.0.0 / ::);</li>
 *         <li>qualquer outro tipo: {@link Decision#BLOCK_NODATA} (NODATA: o nome
 *             existe, mas não tem dados desse tipo).</li>
 *       </ul>
 *       Ex.: bloquear {@code tiktok.com} também bloqueia {@code www.tiktok.com}
 *       e {@code m.tiktok.com} (sufixo ".tiktok.com").</li>
 *   <li>Todo o resto (domínios não listados, expirados, raiz): {@link Decision#FORWARD}.</li>
 * </ul>
 *
 * <h2>Nome bloqueado nunca vai ao upstream</h2>
 * <p>Um nome bloqueado só pode ser respondido localmente ({@link Decision#isBlocked()}):
 * encaminhar os bytes ao DNS público entregaria ao cliente os dados REAIS do domínio
 * bloqueado (TXT, MX, CNAME, HTTPS…) e revelaria ao provedor qual nome foi consultado.
 * Para os tipos que não são A/AAAA a resposta é NODATA (RCODE 0, ANCOUNT 0), que é a
 * semântica correta de "o nome existe, mas não há dados desse tipo" — a mesma do
 * Pi-hole/AdGuard.
 *
 * <p><b>Alias por CNAME:</b> a decisão olha só o nome consultado — o alias só
 * aparece na <i>resposta</i> do upstream, então a consulta sai mesmo assim. Quem
 * fecha o buraco é o servidor: ele inspeciona a section answer da resposta
 * ({@link #nomeBloqueadoNaCadeia}) e, se algum nome da cadeia for bloqueado,
 * descarta a resposta do upstream e entrega o sinkhole local ao cliente.
 *
 * <h2>Concorrência e tempo real (sem observer, sem cache)</h2>
 * <p>Cada consulta relê o {@code blocks.json} do disco via {@link BlockStore#load()},
 * que lê um snapshot atômico (escrita atômica + lock entre processos no core). Não há
 * cache em memória compartilhado, portanto não há condição de corrida nem necessidade
 * de {@code ConcurrentHashMap} ou de um mecanismo de "observer": um
 * {@code block}/{@code unblock} feito em outro terminal vale já na próxima consulta.
 * O custo é uma leitura de arquivo pequeno por query — irrelevante frente ao RTT do UDP.
 *
 * <h2>Falha na leitura do estado: fail-open</h2>
 * <p>Se o estado estiver ilegível/corrompido, a decisão é {@link Decision#FORWARD}
 * (deixa passar) em vez de derrubar a internet de todos os dispositivos. Disponibilidade
 * da rede prevalece sobre o bloqueio; a CLI já trata corrupção com erro explícito.
 *
 * <p><b>Ajuste em sitelock-core avaliado e dispensado:</b> nenhuma mudança foi necessária.
 * O reuso de {@link BlockStore#load()} + {@link BlockEntry#isExpired(Instant)} cobre o
 * tempo real sem nova API.
 */
public class DnsResolver {

    /**
     * O que fazer com a consulta: responder localmente ou encaminhar ao upstream.
     *
     * <p>Contrato para quem consome (o {@link DnsServer}): um nome bloqueado NUNCA
     * resulta em {@link Decision#FORWARD}.
     * <ul>
     *   <li>{@link Decision#BLOCK}: nome bloqueado, consulta A/AAAA — responder com o
     *       sinkhole (0.0.0.0 / ::).</li>
     *   <li>{@link Decision#BLOCK_NODATA}: nome bloqueado, consulta de outro tipo —
     *       responder NODATA (RCODE 0, ANCOUNT 0, sem section answer).</li>
     *   <li>{@link Decision#FORWARD}: nome não bloqueado — repassar os bytes ao
     *       upstream e retransmitir a resposta.</li>
     * </ul>
     * <p>Use {@link #isBlocked()} para decidir "respondo local ou encaminho?" sem
     * listar os casos bloqueados. No servidor:
     * <pre>
     *   if (decision.isBlocked()) {
     *       byte[] resposta = DnsPacketBuilder.buildResponse(query, decision);
     *       enviar(resposta);
     *       return;                 // nome bloqueado nunca vai ao upstream
     *   }
     *   // só um nome não bloqueado chega aqui
     * </pre>
     */
    public enum Decision {
        /** Responder localmente com o sinkhole (0.0.0.0 / ::). */
        BLOCK,
        /**
         * Responder localmente com NODATA (RCODE 0, ANCOUNT 0): o nome existe, mas não
         * tem dados do tipo pedido. É a resposta de um nome bloqueado para tipos
         * diferentes de A/AAAA — nunca o encaminhamento ao upstream.
         */
        BLOCK_NODATA,
        /** Repassar os bytes ao DNS upstream e retransmitir a resposta. */
        FORWARD;

        /**
         * true quando a resposta é montada localmente (BLOCK ou BLOCK_NODATA).
         *
         * <p>ATENÇÃO: usar este método em vez de comparar com {@link #BLOCK} — um nome
         * bloqueado consultado com tipo ≠ A/AAAA é {@link #BLOCK_NODATA}, não
         * {@link #BLOCK}.
         */
        public boolean isBlocked() {
            return this != FORWARD;
        }
    }

    /** Porta do DNS upstream (fixa: serviço DNS padrão). */
    public static final int UPSTREAM_PORT = 53;

    /**
     * Sorteio do ID enviado ao upstream.
     *
     * <p>POR QUE: o ID do cliente trafega em claro na LAN; usá-lo no upstream
     * expõe a transação. Um ID próprio, sorteado por consulta, fecha a porta
     * para quem tenta forjar resposta.
     */
    private static final SecureRandom SORTEIO = new SecureRandom();

    /** Sorteia um ID de 16 bits para a transação com o upstream. */
    static int sorteiaId() {
        return SORTEIO.nextInt(0x10000);
    }

    private final BlockStore store;
    private final Clock clock;

    public DnsResolver(BlockStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    /**
     * A decisão e o snapshot do estado que a produziu.
     *
     * @param decisao o que fazer com a consulta
     * @param estado  os bloqueios usados na decisão (snapshot do disco)
     */
    public record Resultado(Decision decisao, Map<String, BlockEntry> estado) {
    }

    /** Lê o estado do disco e decide (fail-open em caso de erro de leitura). */
    public Decision decide(String domain, int qtype) {
        return decideComEstado(domain, qtype).decisao();
    }

    /**
     * Decide e devolve junto o snapshot do estado usado na decisão.
     *
     * <p>POR QUE: a checagem da cadeia de CNAMEs (no caminho FORWARD) precisa da
     * MESMA lista de bloqueios da decisão. Sem devolver o snapshot, o servidor
     * teria que reler o blocks.json — uma segunda leitura de disco por consulta.
     */
    public Resultado decideComEstado(String domain, int qtype) {
        final Map<String, BlockEntry> state;
        try {
            state = store.load();
        } catch (IOException e) {
            return new Resultado(Decision.FORWARD, Map.of());
        }
        Decision decision = decide(domain, qtype, state, Instant.now(clock));
        if (decision == Decision.BLOCK_NODATA) {
            // POR QUE: o operador precisa saber que o cliente pediu um tipo ≠ A/AAAA
            // de um nome bloqueado — é o indício de um app contornando o bloqueio.
            log("BLOQUEADO (nodata) " + domain + " tipo=" + qtype);
        }
        return new Resultado(decision, state);
    }

    /**
     * Nome bloqueado na cadeia de aliases da resposta do upstream.
     *
     * <p>É o buraco que sobrou no bloqueio por DNS: o nome consultado é livre, mas
     * o upstream responde com a cadeia de CNAMEs <b>e</b> o IP real do alvo na
     * mesma resposta. Se algum nome da cadeia for bloqueado, a resposta do
     * upstream vale como o IP de um domínio bloqueado.
     *
     * @param resposta bytes da resposta do upstream
     * @param estado   snapshot dos bloqueios (o mesmo da decisão)
     * @return o nome bloqueado encontrado, ou null se a cadeia é limpa
     */
    public String nomeBloqueadoNaCadeia(byte[] resposta, Map<String, BlockEntry> estado) {
        if (resposta == null || estado == null || estado.isEmpty()) {
            return null;
        }
        Instant now = Instant.now(clock);
        for (String nome : DnsPacketParser.nomesDaAnswer(resposta, resposta.length)) {
            if (nome.isEmpty()) {
                continue;
            }
            if (decide(nome, DnsQuery.TYPE_A, estado, now) != Decision.FORWARD) {
                return nome;
            }
        }
        return null;
    }

    /**
     * Decisão de resposta local para um nome cuja cadeia de alias é bloqueada.
     *
     * <p>POR QUE: a resposta tem que ser a mesma de um nome bloqueado — sinkhole
     * (0.0.0.0 / ::) em A/AAAA e NODATA nos demais tipos.
     */
    public static Decision decisaoDeBloqueio(int qtype) {
        return (qtype == DnsQuery.TYPE_A || qtype == DnsQuery.TYPE_AAAA)
                ? Decision.BLOCK : Decision.BLOCK_NODATA;
    }

    private static void log(String message) {
        System.out.println("[sitelock-dns] " + message);
    }

    /**
     * Decisão pura, sem I/O — usada pelo servidor e pelos testes unitários.
     *
     * <p>Contrato: nome bloqueado nunca vira {@link Decision#FORWARD}. Tipo A/AAAA
     * recebe o sinkhole ({@link Decision#BLOCK}); qualquer outro tipo recebe NODATA
     * ({@link Decision#BLOCK_NODATA}) — encaminhar ao upstream entregaria ao cliente
     * os dados reais do domínio bloqueado.
     *
     * @param domain nome perguntado (qualquer caixa; ponto final opcional)
     * @param qtype  tipo DNS (1 = A, 28 = AAAA; os demais tipos de um nome bloqueado
     *               resultam em NODATA, nunca em encaminhamento)
     * @param state  snapshot dos bloqueios (chave = domínio normalizado)
     * @param now    instante de referência para expiração
     */
    public static Decision decide(String domain, int qtype,
                                  Map<String, BlockEntry> state, Instant now) {
        String q = canonicalName(domain);
        if (q.isEmpty() || state == null || state.isEmpty()) {
            return Decision.FORWARD;
        }
        if (!isBlockedName(q, state, now)) {
            return Decision.FORWARD;
        }
        return decisaoDeBloqueio(qtype);
    }

    /**
     * true se o nome é igual a um bloqueio ativo ou é subdomínio dele
     * (sufixo ".dominio" — "eviltiktok.com" NÃO casa com "tiktok.com").
     */
    private static boolean isBlockedName(String q, Map<String, BlockEntry> state, Instant now) {
        for (BlockEntry entry : state.values()) {
            if (entry == null || entry.getDomain() == null) {
                continue;
            }
            if (entry.isExpired(now)) {
                continue;
            }
            String blocked = canonicalName(entry.getDomain());
            if (blocked.isEmpty()) {
                continue;
            }
            if (q.equals(blocked) || q.endsWith("." + blocked)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Forma canônica do nome para comparar com a lista de bloqueio: minúsculas, sem
     * ponto final e em punycode.
     *
     * <p>Assim o nome em unicode e o nome em punycode casam com a mesma entrada da
     * lista ("пример.рф" e "xn--e1afmkfd.xn--p1ai" são o mesmo nome).
     */
    static String canonicalName(String raw) {
        String norm = normalizeQueryName(raw);
        if (norm.isEmpty()) {
            return "";
        }
        try {
            return DnsPacketParser.toAsciiName(norm);
        } catch (IllegalArgumentException e) {
            // Nome inválido para IDNA não casa com nenhuma entrada da lista.
            return norm;
        }
    }

    /** Minúsculas + remove um ponto final (FQDN com trailing dot). Não remove "www.". */
    public static String normalizeQueryName(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        while (s.endsWith(".") && s.length() > 1) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.equals(".")) {
            return "";
        }
        return s;
    }

    /**
     * Encaminha os bytes da consulta ao upstream e aguarda a resposta.
     *
     * <p><b>Transporte:</b> o socket é <b>conectado</b> ao upstream, então o
     * kernel descarta o que vier de outro endereço — sem isso, qualquer pacote
     * UDP que acerte a porta efêmera entrava como se fosse a resposta.
     *
     * <p><b>ID:</b> o upstream recebe um ID <b>próprio</b>, sorteado a cada
     * consulta. O ID do cliente trafega em claro na LAN; repassá-lo ao upstream
     * expõe a transação a quem escute o caminho. A resposta volta com o ID do
     * cliente, reescrito — o cliente continua vendo o ID que mandou.
     *
     * <p><b>Validação:</b> a resposta só é repassada se for mesmo uma resposta
     * (QR=1), tiver o ID que foi enviado e a pergunta que foi perguntada.
     * Qualquer outra coisa vira falha (SERVFAIL ao cliente), não dado falso.
     *
     * @param request      pacote original do cliente
     * @param requestLen   bytes válidos em {@code request}
     * @param upstream     endereço do DNS público (ex.: 1.1.1.1)
     * @param upstreamPort porta do upstream (53 no DNS público)
     * @param timeoutMs    limite de espera (ex.: 2000); estoura
     *                     {@link java.net.SocketTimeoutException} em vez de travar a thread
     * @return bytes exatos da resposta do upstream, com o ID do cliente
     */
    public static byte[] forwardRaw(byte[] request, int requestLen,
                                    InetAddress upstream, int upstreamPort, int timeoutMs)
            throws IOException {
        try (UpstreamClient client = new UpstreamClient(upstream, upstreamPort, timeoutMs, 1)) {
            return client.encaminhar(request, requestLen);
        }
    }

    /**
     * Encaminha para o upstream padrão (porta 53).
     *
     * @see #forwardRaw(byte[], int, InetAddress, int, int)
     */
    public static byte[] forwardRaw(byte[] request, int requestLen,
                                    InetAddress upstream, int timeoutMs) throws IOException {
        return forwardRaw(request, requestLen, upstream, UPSTREAM_PORT, timeoutMs);
    }

    /**
     * Confere que a resposta do upstream é mesmo a resposta da nossa consulta.
     *
     * @throws IOException se a resposta não serve (ID trocado, pacote que não é
     *                     resposta ou pergunta diferente)
     */
    static void validarResposta(byte[] resposta, int idEsperado,
                                 byte[] consulta, int consultaLen) throws IOException {
        if (resposta == null || resposta.length < 12) {
            throw new IOException("resposta do upstream curta demais: "
                    + (resposta == null ? 0 : resposta.length) + " bytes");
        }
        int id = u16(resposta, 0);
        if (id != idEsperado) {
            throw new IOException("ID do upstream não confere (" + id + " != "
                    + idEsperado + "): resposta descartada");
        }
        int flags = u16(resposta, 2);
        if ((flags & 0x8000) == 0) {
            throw new IOException("pacote do upstream não é resposta (QR=0): descartado");
        }
        final DnsQuery query;
        try {
            query = DnsPacketParser.parse(consulta, consultaLen);
        } catch (DnsParseException e) {
            // Consulta que não parseia não chega aqui (o servidor já a descartou).
            return;
        }
        byte[] pergunta = query.questionSection();
        if (resposta.length < 12 + pergunta.length) {
            throw new IOException("resposta do upstream sem a pergunta: descartada");
        }
        for (int i = 0; i < pergunta.length; i++) {
            if (resposta[12 + i] != pergunta[i]) {
                throw new IOException(
                        "a pergunta do upstream não é a que foi enviada: resposta descartada");
            }
        }
    }

    private static int u16(byte[] data, int off) {
        return ((data[off] & 0xFF) << 8) | (data[off + 1] & 0xFF);
    }
}

/**
 * Cliente do upstream: um punhado de sockets conectados, reaproveitados.
 *
 * <p>POR QUE: um socket novo por consulta esgota as portas efêmeras do SO
 * (uma rajada de algumas centenas de consultas já estoura o intervalo) e
 * multiplica os sockets abertos. Os sockets aqui são <b>conectados</b> — é o
 * que faz o kernel descartar resposta de origem errada — e são reaproveitados
 * por todas as consultas.
 *
 * <p>Cada socket é usado por uma requisição por vez (um lock por socket): o
 * par send/receive tem que ser da mesma transação, e o ID sorteado por
 * consulta é o que amarra a resposta à consulta.
 */
final class UpstreamClient implements AutoCloseable {

    private final InetAddress upstream;
    private final int porta;
    private final int timeoutMs;
    private final DatagramSocket[] sockets;
    private final Object[] locks;
    private final java.util.concurrent.atomic.AtomicInteger proximo =
            new java.util.concurrent.atomic.AtomicInteger();

    /**
     * @param quantidade quantos sockets conectar (1 = um só, em série)
     */
    UpstreamClient(InetAddress upstream, int porta, int timeoutMs, int quantidade)
            throws IOException {
        if (quantidade < 1) {
            throw new IllegalArgumentException("quantidade de sockets inválida: " + quantidade);
        }
        this.upstream = upstream;
        this.porta = porta;
        this.timeoutMs = timeoutMs;
        this.sockets = new DatagramSocket[quantidade];
        this.locks = new Object[quantidade];
        try {
            for (int i = 0; i < quantidade; i++) {
                DatagramSocket sock = new DatagramSocket();
                // ATENÇÃO: conectar é o que faz o kernel filtrar por origem.
                sock.connect(upstream, porta);
                sock.setSoTimeout(timeoutMs);
                sockets[i] = sock;
                locks[i] = new Object();
            }
        } catch (IOException e) {
            close();
            throw e;
        }
    }

    /** Encaminha a consulta e devolve a resposta com o ID do cliente. */
    byte[] encaminhar(byte[] request, int requestLen) throws IOException {
        if (request == null || requestLen < 2 || requestLen > request.length) {
            throw new IOException("consulta inválida para encaminhar");
        }
        int indice = Math.floorMod(proximo.getAndIncrement(), sockets.length);
        DatagramSocket sock = sockets[indice];
        synchronized (locks[indice]) {
            int idCliente = ((request[0] & 0xFF) << 8) | (request[1] & 0xFF);
            int idUpstream = DnsResolver.sorteiaId();
            byte[] enviado = Arrays.copyOf(request, requestLen);
            enviado[0] = (byte) (idUpstream >>> 8);
            enviado[1] = (byte) idUpstream;
            sock.send(new DatagramPacket(enviado, requestLen));
            byte[] buf = new byte[DnsServer.MAX_PACKET_BYTES];
            DatagramPacket in = new DatagramPacket(buf, buf.length);
            sock.receive(in);
            byte[] resposta = Arrays.copyOf(in.getData(), in.getLength());
            DnsResolver.validarResposta(resposta, idUpstream, request, requestLen);
            // O cliente precisa ver o ID dele: o ID do upstream é nosso, não dele.
            resposta[0] = (byte) (idCliente >>> 8);
            resposta[1] = (byte) idCliente;
            return resposta;
        }
    }

    @Override
    public void close() {
        for (DatagramSocket sock : sockets) {
            if (sock != null) {
                try {
                    sock.close();
                } catch (RuntimeException ignored) {
                    // já fechando
                }
            }
        }
    }
}
