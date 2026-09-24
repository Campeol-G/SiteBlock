package com.sitelock.dns;

import com.sitelock.core.BlockStore;

import java.io.IOException;
import java.net.BindException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.time.Clock;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Servidor DNS UDP (RFC 1035): loop principal que recebe consultas de qualquer
 * dispositivo da rede, despacha cada pacote para uma thread do pool e responde
 * sinkhole (bloqueado) ou retransmite a resposta do upstream (liberado).
 *
 * <ul>
 *   <li>Binding em {@code 0.0.0.0:porta} para alcançar toda a rede local.</li>
 *   <li>Thread pool (cached, threads daemon): múltiplos dispositivos simultâneos
 *       sem travar; uma consulta lenta (upstream) nunca bloqueia as demais.</li>
 *   <li>{@link DatagramSocket#send} é thread-safe, então as workers compartilham
 *       o mesmo socket de escuta para responder.</li>
 *   <li>Pacotes malformados ou com OPCODE/QDCOUNT inesperados são <b>ignorados</b>
 *       (sem resposta, sem derrubar o loop) — responder erro a lixo facilita
 *       reflexão/amplificação; o cliente retransmite por conta própria.</li>
 *   <li>Timeout no upstream vira SERVFAIL (RCODE 2) ao cliente, nunca espera infinita.</li>
 * </ul>
 */
public class DnsServer {

    /** Timeout padrão de espera pelo upstream antes de responder SERVFAIL. */
    public static final int DEFAULT_UPSTREAM_TIMEOUT_MS = 2000;

    /** Buffer por datagrama; cobre DNS clássico (512) e EDNS (até 4096) com folga. */
    public static final int MAX_PACKET_BYTES = 8192;

    private final int port;
    private final InetAddress upstream;
    private final int upstreamTimeoutMs;
    private final BlockStore store;
    private final Clock clock;
    private final DnsResolver resolver;

    private volatile boolean stopRequested = false;
    private volatile DatagramSocket socket;
    private volatile ExecutorService pool;

    public DnsServer(int port, InetAddress upstream, BlockStore store, Clock clock) {
        this(port, upstream, DEFAULT_UPSTREAM_TIMEOUT_MS, store, clock);
    }

    public DnsServer(int port, InetAddress upstream, int upstreamTimeoutMs,
                     BlockStore store, Clock clock) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("porta inválida: " + port);
        }
        if (upstream == null) {
            throw new IllegalArgumentException("upstream nulo");
        }
        if (store == null || clock == null) {
            throw new IllegalArgumentException("store/clock nulos");
        }
        this.port = port;
        this.upstream = upstream;
        this.upstreamTimeoutMs = upstreamTimeoutMs;
        this.store = store;
        this.clock = clock;
        this.resolver = new DnsResolver(store, clock);
    }

    public int getPort() {
        return port;
    }

    public InetAddress getUpstream() {
        return upstream;
    }

    /** Registra hook de encerramento controlado (fecha socket + pool no SIGTERM/Ctrl+C). */
    public void installShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(this::stop, "sitelock-dns-shutdown"));
    }

    /**
     * Loop de atendimento; retorna apenas quando {@link #stop()} for chamado.
     *
     * @throws DnsServerException com mensagem amigável se a porta não puder ser aberta
     */
    public void serveForever() {
        DatagramSocket ds = bind();
        this.socket = ds;
        this.pool = newPool();
        log("ouvindo em 0.0.0.0:" + port + " (upstream " + upstream.getHostAddress() + ")");
        System.out.println("SITELOCK_DNS_LISTENING port=" + port);
        try {
            while (!stopRequested) {
                byte[] buf = new byte[MAX_PACKET_BYTES];
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                try {
                    ds.receive(packet);
                } catch (SocketException e) {
                    if (stopRequested || ds.isClosed()) {
                        break; // stop() fechou o socket para destravar o receive
                    }
                    log("erro no socket de escuta: " + e.getMessage() + " (continuando)");
                    continue;
                } catch (IOException e) {
                    log("erro de I/O na escuta: " + e.getMessage() + " (continuando)");
                    continue;
                }
                byte[] data = Arrays.copyOf(packet.getData(), packet.getLength());
                InetAddress client = packet.getAddress();
                int clientPort = packet.getPort();
                try {
                    pool.execute(() -> handlePacket(data, client, clientPort));
                } catch (RejectedExecutionException e) {
                    // Pool encerrando: ignora o pacote (cliente retransmite).
                }
            }
        } finally {
            closeQuietly(ds);
        }
    }

    /**
     * Watchdog simples: se o loop de atendimento morrer por exceção inesperada
     * (ex.: socket invalidado), espera 1s e tenta de novo até {@link #stop()}.
     * A <b>primeira</b> falha de bind (porta em uso, sem permissão) é lançada em vez
     * de reiniciada, para que o {@code dns start} detecte o filho morto e mostre o
     * motivo — em vez de fingir sucesso com um daemon em loop de erro.
     * Não cobre {@code kill -9} do processo — nesse caso o watchdog morre junto
     * (ver README: risco de perda de internet e como reverter).
     */
    public void runWithWatchdog() {
        boolean firstAttempt = true;
        while (!stopRequested) {
            try {
                serveForever();
            } catch (DnsServerException e) {
                if (stopRequested) {
                    break;
                }
                if (firstAttempt) {
                    throw e;
                }
                log("queda inesperada (" + e.getMessage() + "); reiniciando em 1s...");
            } catch (RuntimeException e) {
                if (stopRequested) {
                    break;
                }
                log("erro inesperado (" + e.getMessage() + "); reiniciando em 1s...");
            }
            firstAttempt = false;
            if (!stopRequested) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    /** Encerra: destrava o receive, fecha o socket e desliga o pool. */
    public void stop() {
        stopRequested = true;
        DatagramSocket ds = socket;
        if (ds != null && !ds.isClosed()) {
            ds.close();
        }
        ExecutorService p = pool;
        if (p != null) {
            p.shutdown();
            try {
                if (!p.awaitTermination(5, TimeUnit.SECONDS)) {
                    p.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                p.shutdownNow();
            }
        }
    }

    private void handlePacket(byte[] data, InetAddress client, int clientPort) {
        final DnsQuery query;
        try {
            query = DnsPacketParser.parse(data, data.length);
        } catch (DnsParseException e) {
            log("ignorado pacote malformado de " + client.getHostAddress() + ": " + e.getMessage());
            return;
        }

        // Cinturão e suspensórios: nenhum erro de uma consulta pode matar a worker
        // thread sem resposta (o cliente ficaria esperando até o timeout). Erro aqui
        // vira SERVFAIL; pacote malformado continua sendo ignorado (ver acima).
        final DnsResolver.Decision decision;
        try {
            decision = resolver.decide(query.name(), query.type());
        } catch (RuntimeException e) {
            log("erro interno ao decidir " + query.name() + ": " + e + " (SERVFAIL)");
            send(DnsPacketBuilder.buildErrorResponse(query, 2), client, clientPort);
            return;
        }
        if (decision == DnsResolver.Decision.BLOCK) {
            byte[] response = DnsPacketBuilder.buildBlockedResponse(query);
            send(response, client, clientPort);
            log("BLOQUEADO " + query.name() + " (tipo " + query.type() + ") <- "
                    + client.getHostAddress());
            return;
        }

        try {
            byte[] upstreamResponse = DnsResolver.forwardRaw(data, data.length, upstream, upstreamTimeoutMs);
            send(upstreamResponse, client, clientPort);
        } catch (SocketTimeoutException e) {
            log("timeout do upstream " + upstream.getHostAddress() + " para " + query.name()
                    + " (SERVFAIL)");
            send(DnsPacketBuilder.buildErrorResponse(query, 2), client, clientPort);
        } catch (IOException e) {
            log("falha no upstream para " + query.name() + ": " + e.getMessage() + " (SERVFAIL)");
            send(DnsPacketBuilder.buildErrorResponse(query, 2), client, clientPort);
        }
    }

    private void send(byte[] response, InetAddress client, int clientPort) {
        DatagramSocket ds = socket;
        if (ds == null || ds.isClosed()) {
            return;
        }
        try {
            ds.send(new DatagramPacket(response, response.length, client, clientPort));
        } catch (IOException e) {
            log("falha ao responder " + client.getHostAddress() + ": " + e.getMessage());
        }
    }

    private DatagramSocket bind() {
        final DatagramSocket ds;
        try {
            ds = new DatagramSocket(null);
        } catch (SocketException e) {
            throw new DnsServerException(
                    "Falha ao criar o socket UDP na porta " + port + ": " + e.getMessage(), e);
        }
        try {
            ds.setReuseAddress(true);
            ds.bind(new InetSocketAddress(port));
        } catch (SocketException e) {
            closeQuietly(ds);
            throw friendlyBindError(e);
        }
        return ds;
    }

    /**
     * Troca exceções cruas de bind por mensagens acionáveis.
     * A porta 53 é privilegiada no Linux: só root faz bind abaixo de 1024.
     * Para testes locais sem sudo existe a flag --port (ex.: --port 5300).
     */
    DnsServerException friendlyBindError(SocketException cause) {
        String msg = String.valueOf(cause.getMessage()).toLowerCase();
        if (cause instanceof BindException || msg.contains("already in use")
                || msg.contains("address already in use")) {
            return new DnsServerException(
                    "Porta UDP " + port + " já está em uso. Outro servidor DNS está rodando? "
                            + "(Em muitas distros o systemd-resolved ocupa a 53 em 127.0.0.53.) "
                            + "Libere a porta ou use outra: --port 5300.", cause);
        }
        if (port < 1024 && (msg.contains("permission") || msg.contains("denied")
                || msg.contains("access") || msg.contains("permiss"))) {
            return new DnsServerException(
                    "Sem permissão para abrir a porta UDP " + port + ". Portas abaixo de 1024 são "
                            + "privilegiadas no Linux e exigem root: rode com sudo "
                            + "(ex.: sudo siteblock dns start). Para testar sem sudo, use uma "
                            + "porta alta: siteblock dns start --port 5300.", cause);
        }
        return new DnsServerException(
                "Falha ao abrir a porta UDP " + port + ": " + cause.getMessage(), cause);
    }

    private static ExecutorService newPool() {
        ThreadFactory factory = new ThreadFactory() {
            private final AtomicInteger seq = new AtomicInteger();

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "sitelock-dns-" + seq.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        };
        return Executors.newCachedThreadPool(factory);
    }

    private static void closeQuietly(DatagramSocket ds) {
        try {
            ds.close();
        } catch (RuntimeException ignored) {
        }
    }

    private static void log(String message) {
        System.out.println("[sitelock-dns] " + message);
    }
}
