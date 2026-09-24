package com.sitelock.dns;

import com.sitelock.core.BlockEntry;
import com.sitelock.core.BlockStore;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
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
 *   <li>Tipo A ou AAAA cujo nome é igual — ou subdomínio — de um bloqueio ativo:
 *       {@link Decision#BLOCK}. Ex.: bloquear {@code tiktok.com} também bloqueia
 *       {@code www.tiktok.com} e {@code m.tiktok.com} (sufixo ".tiktok.com").</li>
 *   <li>Todo o resto (outros tipos como MX/TXT/CNAME, domínios não listados, raiz):
 *       {@link Decision#FORWARD}.</li>
 *   <li>Bloqueios expirados são ignorados (como se não existissem).</li>
 * </ul>
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

    public enum Decision {
        /** Responder localmente com o sinkhole (0.0.0.0 / ::). */
        BLOCK,
        /** Repassar os bytes ao DNS upstream e retransmitir a resposta. */
        FORWARD
    }

    /** Porta do DNS upstream (fixa: serviço DNS padrão). */
    public static final int UPSTREAM_PORT = 53;

    private final BlockStore store;
    private final Clock clock;

    public DnsResolver(BlockStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    /** Lê o estado do disco e decide (fail-open em caso de erro de leitura). */
    public Decision decide(String domain, int qtype) {
        final Map<String, BlockEntry> state;
        try {
            state = store.load();
        } catch (IOException e) {
            return Decision.FORWARD;
        }
        return decide(domain, qtype, state, Instant.now(clock));
    }

    /**
     * Decisão pura, sem I/O — usada pelo servidor e pelos testes unitários.
     *
     * @param domain nome perguntado (qualquer caixa; ponto final opcional)
     * @param qtype  tipo DNS (1 = A, 28 = AAAA; demais sempre encaminham)
     * @param state  snapshot dos bloqueios (chave = domínio normalizado)
     * @param now    instante de referência para expiração
     */
    public static Decision decide(String domain, int qtype,
                                  Map<String, BlockEntry> state, Instant now) {
        if (qtype != DnsQuery.TYPE_A && qtype != DnsQuery.TYPE_AAAA) {
            return Decision.FORWARD;
        }
        String q = normalizeQueryName(domain);
        if (q.isEmpty() || state == null || state.isEmpty()) {
            return Decision.FORWARD;
        }
        for (BlockEntry entry : state.values()) {
            if (entry == null || entry.getDomain() == null) {
                continue;
            }
            if (entry.isExpired(now)) {
                continue;
            }
            String blocked = entry.getDomain().toLowerCase(Locale.ROOT);
            if (q.equals(blocked) || q.endsWith("." + blocked)) {
                return Decision.BLOCK;
            }
        }
        return Decision.FORWARD;
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
     * <p>Os bytes são enviados <b>intactos</b> (mesmo Transaction ID do cliente), então a
     * resposta do upstream já carrega o ID original e pode ser retransmitida as-is ao
     * dispositivo — o que cumpre o requisito de preservar o Transaction ID sem reescrita.
     * Cada chamada usa seu próprio socket efêmero, logo consultas concorrentes com o mesmo
     * ID não colidem entre si.
     *
     * @param request   pacote original do cliente
     * @param requestLen bytes válidos em {@code request}
     * @param upstream  endereço do DNS público (ex.: 1.1.1.1)
     * @param timeoutMs limite de espera (ex.: 2000); estoura
     *                  {@link java.net.SocketTimeoutException} em vez de travar a thread
     * @return bytes exatos da resposta do upstream
     */
    public static byte[] forwardRaw(byte[] request, int requestLen,
                                    InetAddress upstream, int timeoutMs) throws IOException {
        try (DatagramSocket sock = new DatagramSocket()) {
            sock.setSoTimeout(timeoutMs);
            DatagramPacket out = new DatagramPacket(request, requestLen, upstream, UPSTREAM_PORT);
            sock.send(out);
            byte[] buf = new byte[DnsServer.MAX_PACKET_BYTES];
            DatagramPacket in = new DatagramPacket(buf, buf.length);
            sock.receive(in);
            return Arrays.copyOf(in.getData(), in.getLength());
        }
    }
}
