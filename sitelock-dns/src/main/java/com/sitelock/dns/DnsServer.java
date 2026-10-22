package com.sitelock.dns;

import com.sitelock.core.BlockStore;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.BindException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Servidor DNS (RFC 1035) para a rede local: escuta UDP e TCP na mesma porta,
 * despacha cada consulta para uma thread do pool e responde sinkhole
 * (bloqueado) ou encaminha ao upstream (liberado).
 */
public class DnsServer {

    /** Timeout padrão de espera pelo upstream antes de responder SERVFAIL. */
    public static final int DEFAULT_UPSTREAM_TIMEOUT_MS = 2000;

    /** Buffer por datagrama; cobre DNS clássico (512) e EDNS (até 4096) com folga. */
    public static final int MAX_PACKET_BYTES = 8192;

    /** Tamanho máximo de uma mensagem DNS sobre TCP (2 bytes de tamanho, RFC 1035 §4.2.2). */
    public static final int MAX_TCP_MESSAGE_BYTES = 65535;

    // Tempo que uma conexão TCP pode ficar aberta sem enviar a consulta.
    // POR QUE: sem isso, uma conexão que abre e fica muda ocupa uma thread do
    // pool para sempre — o próprio cliente do SiteLock viraria o vetor.
    static final int TCP_READ_TIMEOUT_MS = 5000;

    // Threads que atendem consultas UDP ao mesmo tempo.
    // POR QUE: pool "cached" criaria milhares de threads numa rajada (app com
    // retry agressivo, alguém na LAN); o excedente é descartado pela fila.
    static final int UDP_POOL_SIZE = 16;

    // Fila de consultas UDP à espera de thread.
    static final int UDP_QUEUE_SIZE = 128;

    // Sockets conectados ao upstream, reaproveitados entre consultas.
    static final int UPSTREAM_SOCKETS = 8;

    // Threads que atendem conexões TCP ao mesmo tempo.
    static final int TCP_POOL_SIZE = 8;

    // Fila de conexões TCP à espera de thread.
    static final int TCP_QUEUE_SIZE = 64;

    private final int port;
    private final InetAddress upstream;
    private final int upstreamPort;
    private final int upstreamTimeoutMs;
    private final BlockStore store;
    private final Clock clock;
    private final DnsResolver resolver;
    private final SourcePolicy policy;

    private volatile boolean stopRequested = false;
    private volatile DatagramSocket socket;
    private volatile ExecutorService pool;
    private volatile ServerSocket tcpServer;
    private volatile ExecutorService tcpPool;
    private volatile UpstreamClient upstreamClient;

    // Última vez que cada origem recusada foi registrada (para não inundar o log).
    private final Map<String, Long> ultimaRecusa = new ConcurrentHashMap<>();

    public DnsServer(int port, InetAddress upstream, BlockStore store, Clock clock) {
        this(port, upstream, DnsResolver.UPSTREAM_PORT, DEFAULT_UPSTREAM_TIMEOUT_MS,
                store, clock, List.of());
    }

    public DnsServer(int port, InetAddress upstream, int upstreamTimeoutMs,
                     BlockStore store, Clock clock) {
        this(port, upstream, DnsResolver.UPSTREAM_PORT, upstreamTimeoutMs,
                store, clock, List.of());
    }

    /**
     * @param allowFrom origens liberadas além das locais (endereço ou CIDR,
     *                  separados por vírgula)
     */
    public DnsServer(int port, InetAddress upstream, BlockStore store, Clock clock,
                     List<String> allowFrom) {
        this(port, upstream, DnsResolver.UPSTREAM_PORT, DEFAULT_UPSTREAM_TIMEOUT_MS,
                store, clock, allowFrom);
    }

    /**
     * @param allowFrom origens liberadas além das locais (endereço ou CIDR,
     *                  separados por vírgula)
     */
    public DnsServer(int port, InetAddress upstream, int upstreamTimeoutMs,
                     BlockStore store, Clock clock, List<String> allowFrom) {
        this(port, upstream, DnsResolver.UPSTREAM_PORT, upstreamTimeoutMs,
                store, clock, allowFrom);
    }

    /**
     * @param upstreamPort porta do upstream (53 no DNS público)
     * @param allowFrom    origens liberadas além das locais (endereço ou CIDR)
     */
    public DnsServer(int port, InetAddress upstream, int upstreamPort, int upstreamTimeoutMs,
                     BlockStore store, Clock clock, List<String> allowFrom) {
        this(port, upstream, upstreamPort, upstreamTimeoutMs, store, clock,
                SourcePolicy.padrao(allowFrom));
    }

    // Construtor com a política de origem já pronta (usado nos testes).
    DnsServer(int port, InetAddress upstream, int upstreamTimeoutMs,
              BlockStore store, Clock clock, SourcePolicy policy) {
        this(port, upstream, DnsResolver.UPSTREAM_PORT, upstreamTimeoutMs,
                store, clock, policy);
    }

    // Construtor com a política de origem e a porta do upstream (usados nos testes).
    DnsServer(int port, InetAddress upstream, int upstreamPort, int upstreamTimeoutMs,
              BlockStore store, Clock clock, SourcePolicy policy) {
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
        this.upstreamPort = upstreamPort;
        this.upstreamTimeoutMs = upstreamTimeoutMs;
        this.store = store;
        this.clock = clock;
        this.resolver = new DnsResolver(store, clock);
        this.policy = policy;
    }

    public int getPort() {
        return port;
    }

    public InetAddress getUpstream() {
        return upstream;
    }

    // ATENÇÃO: a validação acontece na CLI, antes de forkar — um erro de
    // digitação no endereço não pode derrubar o daemon depois de subir.
    /**
     * Valida a lista de origens do {@code --allow-from} (separada por vírgula).
     *
     * @throws IllegalArgumentException se alguma entrada não for IP ou CIDR válido
     */
    public static List<String> parseAllowFrom(String texto) {
        List<String> origens = new ArrayList<>();
        if (texto == null || texto.isBlank()) {
            return origens;
        }
        for (String parte : texto.split(",")) {
            String limpa = parte.trim();
            if (limpa.isEmpty()) {
                continue;
            }
            Cidr.parse(limpa); // valida: lança com mensagem clara
            origens.add(limpa);
        }
        return origens;
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
        ServerSocket tcp = bindTcp();
        this.tcpServer = tcp;
        final UpstreamClient cliente;
        try {
            cliente = new UpstreamClient(upstream, upstreamPort, upstreamTimeoutMs, UPSTREAM_SOCKETS);
        } catch (IOException e) {
            closeQuietly(ds);
            closeQuietly(tcp);
            throw new DnsServerException("Falha ao abrir o socket do upstream "
                    + upstream.getHostAddress() + ":" + upstreamPort + ": " + e.getMessage(), e);
        }
        this.upstreamClient = cliente;
        this.pool = newPool();
        this.tcpPool = newTcpPool();
        startTcpAcceptor(tcp);
        log("ouvindo em 0.0.0.0:" + port + " (upstream " + upstream.getHostAddress()
                + ") — aceitando origens locais"
                + (policy.extras().isEmpty() ? ""
                : " + extras " + String.join(", ", policy.extras())));
        System.out.println("SITELOCK_DNS_LISTENING port=" + port);
        try {
            while (!stopRequested) {
                try {
                    aguardaEDespacha(ds);
                } catch (SocketException e) {
                    if (stopRequested || ds.isClosed()) {
                        break; // stop() fechou o socket para destravar o receive
                    }
                    log("erro no socket de escuta: " + e.getMessage() + " (continuando)");
                } catch (IOException e) {
                    log("erro de I/O na escuta: " + e.getMessage() + " (continuando)");
                }
            }
        } finally {
            closeQuietly(ds);
            closeQuietly(tcp);
            cliente.close();
            // POR QUE: derruba o pool no finally — sem isso, uma queda no meio do
            // loop deixaria o pool antigo órfão (threads daemon com keepalive de
            // 60s) e o watchdog criaria outro pool por cima.
            shutdown(pool);
            shutdown(tcpPool);
        }
    }

    // Espera um pacote e despacha o atendimento para o pool.
    // POR QUE: package-private para o teste poder simular a queda do loop.
    void aguardaEDespacha(DatagramSocket ds) throws IOException {
        byte[] buf = new byte[MAX_PACKET_BYTES];
        DatagramPacket packet = new DatagramPacket(buf, buf.length);
        ds.receive(packet);
        byte[] data = Arrays.copyOf(packet.getData(), packet.getLength());
        InetAddress client = packet.getAddress();
        int clientPort = packet.getPort();
        try {
            pool.execute(() -> handlePacket(data, client, clientPort));
        } catch (RejectedExecutionException e) {
            // Pool cheio ou encerrando: descarta a consulta (o cliente retransmite).
        }
    }

    // POR QUE: a primeira falha derruba o processo — antes o watchdog genérico
    // dormia 1s e tentava de novo para sempre, e o `dns start` (que só olhava
    // se o filho estava vivo) imprimia "Servidor DNS iniciado" sem escutar nada.
    /**
     * Watchdog: se o loop de atendimento morrer por exceção inesperada, espera 1s
     * e tenta de novo até {@link #stop()}. A primeira falha derruba o processo.
     * Não cobre {@code kill -9}: o watchdog morre junto (ver README).
     */
    public void runWithWatchdog() {
        boolean primeiraTentativa = true;
        while (!stopRequested) {
            try {
                serveForever();
            } catch (DnsServerException e) {
                if (stopRequested) {
                    break;
                }
                if (primeiraTentativa) {
                    throw e;
                }
                log("queda inesperada (" + e.getMessage() + "); reiniciando em 1s...");
            } catch (RuntimeException e) {
                if (stopRequested) {
                    break;
                }
                if (primeiraTentativa) {
                    throw new DnsServerException("Falha inesperada no servidor DNS: "
                            + e.getMessage(), e);
                }
                log("erro inesperado (" + e.getMessage() + "); reiniciando em 1s...");
            }
            primeiraTentativa = false;
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

    /** Encerra: destrava o receive, fecha os sockets e desliga os pools. */
    public void stop() {
        stopRequested = true;
        DatagramSocket ds = socket;
        if (ds != null && !ds.isClosed()) {
            ds.close();
        }
        ServerSocket tcp = tcpServer;
        if (tcp != null && !tcp.isClosed()) {
            closeQuietly(tcp);
        }
        UpstreamClient cliente = upstreamClient;
        if (cliente != null) {
            cliente.close();
        }
        shutdown(pool);
        shutdown(tcpPool);
    }
    // Desliga um pool, esperando as threads terminarem.
    private static void shutdown(ExecutorService p) {
        if (p == null) {
            return;
        }
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

    private void handlePacket(byte[] data, InetAddress client, int clientPort) {
        if (!policy.isAllowed(client)) {
            registrarOrigemRecusada(client);
            return; // silêncio: responder seria justamente amplificar
        }
        byte[] response = montaResposta(data, client);
        if (response != null) {
            send(response, client, clientPort);
        }
    }

    // Decide e monta a resposta de uma consulta — o mesmo caminho para UDP e TCP.
    // Devolve null quando a consulta foi ignorada.
    private byte[] montaResposta(byte[] data, InetAddress client) {
        final DnsQuery query;
        try {
            query = DnsPacketParser.parse(data, data.length);
        } catch (DnsParseException e) {
            // ATENÇÃO: pacote com OPCODE/QDCOUNT inesperado é malformado, e fica
            // sem resposta de propósito — responder erro a lixo facilita
            // reflexão/amplificação, e o cliente retransmite por conta própria.
            log("ignorado pacote malformado de " + client.getHostAddress() + ": " + e.getMessage());
            return null;
        }

        // Cinturão e suspensórios: nenhum erro de uma consulta pode matar a worker
        // thread sem resposta (o cliente ficaria esperando até o timeout). Erro aqui
        // vira SERVFAIL; pacote malformado continua sendo ignorado (ver acima).
        final DnsResolver.Resultado resultado;
        try {
            resultado = resolver.decideComEstado(query.name(), query.type());
        } catch (RuntimeException e) {
            log("erro interno ao decidir " + query.name() + ": " + e + " (SERVFAIL)");
            return DnsPacketBuilder.buildErrorResponse(query, 2);
        }
        DnsResolver.Decision decision = resultado.decisao();
        // POR QUE: isBlocked() cobre BLOCK e BLOCK_NODATA. Comparar só com BLOCK
        // deixa o tipo ≠ A/AAAA cair no encaminhamento — o cliente receberia os
        // dados REAIS do nome bloqueado e o provedor de DNS veria a consulta.
        if (decision.isBlocked()) {
            log("BLOQUEADO " + query.name() + " (tipo " + query.type() + ") <- "
                    + client.getHostAddress());
            return DnsPacketBuilder.buildResponse(query, decision);
        }

        final byte[] resposta;
        try {
            UpstreamClient cliente = upstreamClient;
            if (cliente == null) {
                resposta = DnsResolver.forwardRaw(data, data.length, upstream, upstreamPort, upstreamTimeoutMs);
            } else {
                resposta = cliente.encaminhar(data, data.length);
            }
        } catch (SocketTimeoutException e) {
            // Timeout vira SERVFAIL (RCODE 2): nunca espera o upstream indefinidamente.
            log("timeout do upstream " + upstream.getHostAddress() + " para " + query.name()
                    + " (SERVFAIL)");
            return DnsPacketBuilder.buildErrorResponse(query, 2);
        } catch (IOException e) {
            log("falha no upstream para " + query.name() + ": " + e.getMessage() + " (SERVFAIL)");
            return DnsPacketBuilder.buildErrorResponse(query, 2);
        }

        // Alias por CNAME: o nome consultado é livre, mas a cadeia devolvida pelo
        // upstream pode apontar para um nome bloqueado — e vir com o IP real dele.
        // Aí a resposta é descartada e o cliente recebe o sinkhole local.
        final String aliasBloqueado;
        try {
            aliasBloqueado = resolver.nomeBloqueadoNaCadeia(resposta, resultado.estado());
        } catch (RuntimeException e) {
            // Falha na checagem não pode derrubar a resposta nem o servidor:
            // a rede do cliente continua funcionando (fail-open, como na leitura
            // do estado). O log registra o problema.
            log("erro ao checar a cadeia de alias de " + query.name() + ": " + e
                    + " (resposta repassada)");
            return resposta;
        }
        if (aliasBloqueado != null) {
            log("BLOQUEADO (alias) " + query.name() + " -> " + aliasBloqueado
                    + " (tipo " + query.type() + ") <- " + client.getHostAddress());
            return DnsPacketBuilder.buildResponse(query,
                    DnsResolver.decisaoDeBloqueio(query.type()));
        }
        return resposta;
    }

    // Atende uma consulta vinda por TCP (RFC 1035 §4.2.2): o formato é o mesmo
    // da consulta, precedido do tamanho em 2 bytes. A mesma política de origem
    // e a mesma decisão do UDP valem aqui.
    private void handleTcp(Socket socket) {
        InetAddress client = socket.getInetAddress();
        try {
            if (!policy.isAllowed(client)) {
                registrarOrigemRecusada(client);
                return; // silêncio: responder seria justamente amplificar
            }
            socket.setSoTimeout(TCP_READ_TIMEOUT_MS);
            InputStream in = socket.getInputStream();
            byte[] tamanho = leExatamente(in, 2);
            if (tamanho == null) {
                return; // conexão fechada sem consulta
            }
            int length = ((tamanho[0] & 0xFF) << 8) | (tamanho[1] & 0xFF);
            if (length <= 0 || length > MAX_TCP_MESSAGE_BYTES) {
                log("consulta TCP com tamanho inválido (" + length + ") de "
                        + client.getHostAddress());
                return;
            }
            byte[] data = leExatamente(in, length);
            if (data == null) {
                return; // conexão fechada no meio da consulta
            }
            byte[] response = montaResposta(data, client);
            if (response == null) {
                return;
            }
            // ATENÇÃO: tamanho e corpo num buffer só. Escrever o tamanho em
            // separado deixa o cliente receber a mensagem em duas partes, e
            // quem lê "os 2 bytes do tamanho" num recv() só pega o primeiro.
            byte[] mensagem = new byte[2 + response.length];
            mensagem[0] = (byte) (response.length >>> 8);
            mensagem[1] = (byte) response.length;
            System.arraycopy(response, 0, mensagem, 2, response.length);
            OutputStream out = socket.getOutputStream();
            out.write(mensagem);
            out.flush();
        } catch (SocketTimeoutException e) {
            log("conexão TCP sem consulta completa de " + client.getHostAddress() + " (timeout)");
        } catch (IOException e) {
            log("falha na conexão TCP de " + client.getHostAddress() + ": " + e.getMessage());
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
                // já fechando
            }
        }
    }

    // Lê exatamente n bytes; devolve null se o fluxo acabou antes.
    private static byte[] leExatamente(InputStream in, int n) throws IOException {
        byte[] buffer = new byte[n];
        int lidos = 0;
        while (lidos < n) {
            int r = in.read(buffer, lidos, n - lidos);
            if (r < 0) {
                return null;
            }
            lidos += r;
        }
        return buffer;
    }

    // POR QUE: as workers respondem pelo mesmo socket de escuta —
    // DatagramSocket#send é thread-safe, então não precisa de lock.
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

    // Abre o listener TCP na MESMA porta do UDP: os dois protocolos não
    // conflitam. Sem o listener TCP, uma resposta grande (TC=1) faz o cliente
    // retransmitir por TCP (RFC 7766 §8) e a resolução falha com erro de DNS.
    private ServerSocket bindTcp() {
        final ServerSocket server;
        try {
            server = new ServerSocket();
        } catch (IOException e) {
            throw new DnsServerException(
                    "Falha ao criar o socket TCP na porta " + port + ": " + e.getMessage(), e);
        }
        try {
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(port));
            server.setSoTimeout(1000); // o accept não pode prender o servidor para sempre
        } catch (IOException e) {
            closeQuietly(server);
            throw new DnsServerException(
                    "Falha ao abrir a porta TCP " + port + ": " + e.getMessage(), e);
        }
        return server;
    }

    // Thread que aceita conexões TCP e entrega cada uma ao pool.
    private void startTcpAcceptor(ServerSocket tcp) {
        Thread acceptor = new Thread(() -> {
            while (!stopRequested) {
                Socket socket;
                try {
                    socket = tcp.accept();
                } catch (SocketTimeoutException e) {
                    continue; // só para reverificar a flag de parada
                } catch (IOException e) {
                    if (stopRequested || tcp.isClosed()) {
                        break;
                    }
                    log("erro ao aceitar conexão TCP: " + e.getMessage());
                    continue;
                }
                ExecutorService p = tcpPool;
                if (p == null) {
                    closeQuietly(socket);
                    break;
                }
                try {
                    p.execute(() -> handleTcp(socket));
                } catch (RejectedExecutionException e) {
                    // Pool cheio: fecha a conexão (o cliente retransmite por conta própria).
                    closeQuietly(socket);
                }
            }
        }, "sitelock-dns-tcp");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    // Troca exceções cruas de bind por mensagens acionáveis.
    // A porta 53 é privilegiada no Linux: só root faz bind abaixo de 1024.
    // Para testes locais sem sudo existe a flag --port (ex.: --port 5300).
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
        return newPool(UDP_POOL_SIZE, UDP_QUEUE_SIZE, "sitelock-dns-");
    }

    private static ExecutorService newTcpPool() {
        return newPool(TCP_POOL_SIZE, TCP_QUEUE_SIZE, "sitelock-dns-tcp-");
    }

    // Pool limitado: número fixo de threads, fila com teto e rejeição quando
    // enche (o pacote é descartado — o cliente retransmite). É o que impede que
    // uma consulta lenta no upstream trave as demais.
    private static ExecutorService newPool(int threads, int fila, String nome) {
        ThreadFactory factory = new ThreadFactory() {
            private final AtomicInteger seq = new AtomicInteger();

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, nome + seq.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        };
        return new ThreadPoolExecutor(threads, threads, 60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(fila), factory, new ThreadPoolExecutor.AbortPolicy());
    }

    // Registra a origem recusada, sem repetir a cada pacote.
    // POR QUE: um log por pacote enche o disco numa varredura de porta. O nome
    // consultado não aparece de propósito: o log não vira canal de consulta.
    private void registrarOrigemRecusada(InetAddress client) {
        String ip = client.getHostAddress();
        long agora = System.currentTimeMillis();
        if (ultimaRecusa.size() > 1000) {
            ultimaRecusa.clear();
        }
        Long anterior = ultimaRecusa.put(ip, agora);
        if (anterior == null || agora - anterior > 10_000) {
            log("origem recusada " + ip + " (não é local e não está em --allow-from)");
        }
    }

    // POR QUE: escutar em 0.0.0.0 sem olhar a origem transformaria a máquina
    // num resolvedor aberto — qualquer um da internet a usaria como reflexo de
    // DDoS (a resposta do upstream sai com o IP dela) e para ver o que a rede consulta.
    /**
     * Quem pode consultar este servidor. O padrão aceita só origem local
     * (loopback, link-local, RFC1918, IPv6 ULA e os próprios endereços da
     * máquina) e recusa o resto <strong>em silêncio</strong>.
     */
    public static final class SourcePolicy {

        // Redes liberadas por padrão: loopback, link-local, RFC1918 e ULA.
        private static final List<String> REDES_PADRAO = List.of(
                "127.0.0.0/8",     // loopback IPv4
                "::1/128",         // loopback IPv6
                "169.254.0.0/16",  // link-local IPv4 (hotspot, PPPoE)
                "fe80::/10",       // link-local IPv6
                "10.0.0.0/8",      // RFC1918
                "172.16.0.0/12",   // RFC1918
                "192.168.0.0/16",  // RFC1918
                "fc00::/7");       // ULA IPv6 (equivalente do RFC1918)

        private final List<Cidr> redes;
        private final Set<InetAddress> exatos;
        private final List<String> extras;

        private SourcePolicy(List<Cidr> redes, Set<InetAddress> exatos, List<String> extras) {
            this.redes = List.copyOf(redes);
            this.exatos = Set.copyOf(exatos);
            this.extras = List.copyOf(extras);
        }

        /** Política padrão: redes locais + endereços da máquina + --allow-from. */
        public static SourcePolicy padrao(List<String> allowFrom) {
            List<Cidr> redes = new ArrayList<>();
            for (String rede : REDES_PADRAO) {
                redes.add(Cidr.parse(rede));
            }
            List<String> extras = new ArrayList<>();
            for (String extra : allowFrom) {
                for (String parte : extra.split(",")) {
                    if (!parte.isBlank()) {
                        redes.add(Cidr.parse(parte));
                        extras.add(parte.trim());
                    }
                }
            }
            return new SourcePolicy(redes, Set.copyOf(NetworkUtils.localAddresses()), extras);
        }

        // Política que não aceita ninguém (usado nos testes).
        static SourcePolicy nenhuma() {
            return new SourcePolicy(List.of(), Set.of(), List.of());
        }

        /** Origens liberadas além das locais (para o log). */
        public List<String> extras() {
            return extras;
        }

        /** true se a origem pode consultar este servidor. */
        public boolean isAllowed(InetAddress origem) {
            if (origem == null) {
                return false;
            }
            if (exatos.contains(origem)) {
                return true; // endereço da própria máquina
            }
            for (Cidr rede : redes) {
                if (rede.matches(origem)) {
                    return true;
                }
            }
            return false;
        }
    }

    // Uma rede em notação CIDR (ou um endereço único, tratado como /32 ou /128).
    static final class Cidr {

        private final byte[] rede;
        private final int bits;

        private Cidr(byte[] rede, int bits) {
            this.rede = rede;
            this.bits = bits;
        }

        // Lança IllegalArgumentException se o texto não for um IP ou CIDR válido.
        static Cidr parse(String texto) {
            String limpo = texto == null ? "" : texto.trim();
            if (limpo.isEmpty()) {
                throw new IllegalArgumentException("endereço vazio em --allow-from");
            }
            int barra = limpo.indexOf('/');
            String parteEndereco = barra < 0 ? limpo : limpo.substring(0, barra);
            String parteBits = barra < 0 ? null : limpo.substring(barra + 1);
            InetAddress endereco = enderecoLiteral(parteEndereco);
            byte[] bytes = endereco.getAddress();
            int maximo = bytes.length * 8;
            int bits = maximo;
            if (parteBits != null) {
                try {
                    bits = Integer.parseInt(parteBits.trim());
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("máscara inválida em --allow-from: " + texto);
                }
                if (bits < 0 || bits > maximo) {
                    throw new IllegalArgumentException("máscara fora de faixa em --allow-from: " + texto);
                }
            }
            byte[] base = new byte[bytes.length];
            System.arraycopy(bytes, 0, base, 0, bytes.length);
            for (int i = 0; i < base.length; i++) {
                int validos = Math.max(0, Math.min(8, bits - i * 8));
                int mascara = validos == 0 ? 0xFF : (0xFF << (8 - validos)) & 0xFF;
                base[i] = (byte) (base[i] & mascara);
            }
            return new Cidr(base, bits);
        }

        boolean matches(InetAddress endereco) {
            byte[] bytes = endereco.getAddress();
            if (bytes.length != rede.length) {
                return false; // IPv4 não casa com rede IPv6 (e vice-versa)
            }
            int bytesCheios = bits / 8;
            int bitsSoltos = bits % 8;
            for (int i = 0; i < bytesCheios; i++) {
                if (bytes[i] != rede[i]) {
                    return false;
                }
            }
            if (bitsSoltos > 0) {
                int mascara = (0xFF << (8 - bitsSoltos)) & 0xFF;
                if ((bytes[bytesCheios] & mascara) != (rede[bytesCheios] & mascara)) {
                    return false;
                }
            }
            return true;
        }

        // Converte o texto em endereço, sem nunca consultar o DNS.
        // POR QUE: getByName resolve nomes, e um hostname esquecido travaria o
        // servidor na primeira query (o DNS público pode estar bloqueado).
        private static InetAddress enderecoLiteral(String texto) {
            String t = texto.trim();
            if (t.indexOf(':') >= 0) {
                try {
                    return InetAddress.getByName(t);
                } catch (UnknownHostException e) {
                    throw new IllegalArgumentException("endereço IPv6 inválido: " + texto);
                }
            }
            String[] partes = t.split("\\.");
            if (partes.length != 4) {
                throw new IllegalArgumentException(
                        "endereço inválido em --allow-from (use IP ou CIDR, sem nome): " + texto);
            }
            byte[] bytes = new byte[4];
            for (int i = 0; i < 4; i++) {
                int valor;
                try {
                    valor = Integer.parseInt(partes[i]);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("endereço inválido em --allow-from: " + texto);
                }
                if (valor < 0 || valor > 255) {
                    throw new IllegalArgumentException("endereço inválido em --allow-from: " + texto);
                }
                bytes[i] = (byte) valor;
            }
            try {
                return InetAddress.getByAddress(bytes);
            } catch (UnknownHostException e) {
                throw new IllegalArgumentException("endereço inválido em --allow-from: " + texto);
            }
        }
    }

    private static void closeQuietly(DatagramSocket ds) {
        try {
            ds.close();
        } catch (RuntimeException ignored) {
        }
    }

    private static void closeQuietly(ServerSocket server) {
        try {
            server.close();
        } catch (IOException ignored) {
            // já fechando
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // já fechando
        }
    }

    private static void log(String message) {
        System.out.println("[sitelock-dns] " + message);
    }
}
