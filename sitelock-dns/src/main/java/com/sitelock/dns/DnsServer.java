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
 * DNS server (RFC 1035) for the local network: listens on UDP and TCP on the same
 * port, dispatches each query to a pool thread, and answers with the sinkhole
 * (blocked) or forwards upstream (allowed).
 */
public class DnsServer {

    /** Default wait for the upstream before answering SERVFAIL. */
    public static final int DEFAULT_UPSTREAM_TIMEOUT_MS = 2000;

    // Buffer per datagram and per upstream response. 65535 is the ceiling of a
    // DNS message (2-byte length on TCP); on UDP the largest payload is 65507.
    // 8192 truncated large EDNS responses before checking TC.
    public static final int MAX_PACKET_BYTES = 65535;

    /** Maximum size of a DNS message over TCP (2-byte length, RFC 1035 §4.2.2). */
    public static final int MAX_TCP_MESSAGE_BYTES = 65535;

    // How long a TCP connection may stay open without sending the query.
    // WHY: without this, a connection that opens and stays silent occupies a
    // pool thread forever — SiteLock's own client would become the vector.
    static final int TCP_READ_TIMEOUT_MS = 5000;

    // Threads handling UDP queries at the same time.
    // WHY: a "cached" pool would create thousands of threads in a burst (an app
    // with aggressive retries, someone on the LAN); the excess is dropped by the queue.
    static final int UDP_POOL_SIZE = 16;

    // Queue of UDP queries waiting for a thread.
    static final int UDP_QUEUE_SIZE = 128;

    // Sockets connected to the upstream, reused across queries.
    static final int UPSTREAM_SOCKETS = 8;

    // Threads handling TCP connections at the same time.
    static final int TCP_POOL_SIZE = 8;

    // Queue of TCP connections waiting for a thread.
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

    // Last time each refused source was logged (so the log is not flooded).
    private final Map<String, Long> lastRefusalAt = new ConcurrentHashMap<>();

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
     * @param allowFrom sources allowed in addition to local ones (address or CIDR,
     *                  comma-separated)
     */
    public DnsServer(int port, InetAddress upstream, BlockStore store, Clock clock,
                     List<String> allowFrom) {
        this(port, upstream, DnsResolver.UPSTREAM_PORT, DEFAULT_UPSTREAM_TIMEOUT_MS,
                store, clock, allowFrom);
    }

    /**
     * @param allowFrom sources allowed in addition to local ones (address or CIDR,
     *                  comma-separated)
     */
    public DnsServer(int port, InetAddress upstream, int upstreamTimeoutMs,
                     BlockStore store, Clock clock, List<String> allowFrom) {
        this(port, upstream, DnsResolver.UPSTREAM_PORT, upstreamTimeoutMs,
                store, clock, allowFrom);
    }

    /**
     * @param upstreamPort upstream port (53 for public DNS)
     * @param allowFrom    sources allowed in addition to local ones (address or CIDR)
     */
    public DnsServer(int port, InetAddress upstream, int upstreamPort, int upstreamTimeoutMs,
                     BlockStore store, Clock clock, List<String> allowFrom) {
        this(port, upstream, upstreamPort, upstreamTimeoutMs, store, clock,
                SourcePolicy.defaultPolicy(allowFrom));
    }

    // Constructor with the source policy already built (used in tests).
    DnsServer(int port, InetAddress upstream, int upstreamTimeoutMs,
              BlockStore store, Clock clock, SourcePolicy policy) {
        this(port, upstream, DnsResolver.UPSTREAM_PORT, upstreamTimeoutMs,
                store, clock, policy);
    }

    // Constructor with the source policy and the upstream port (used in tests).
    DnsServer(int port, InetAddress upstream, int upstreamPort, int upstreamTimeoutMs,
              BlockStore store, Clock clock, SourcePolicy policy) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("invalid port: " + port);
        }
        if (upstream == null) {
            throw new IllegalArgumentException("upstream is null");
        }
        if (store == null || clock == null) {
            throw new IllegalArgumentException("store/clock are null");
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

    // NOTE: validation happens in the CLI, before forking — a typo in the address
    // must not bring the daemon down after it starts.
    /**
     * Validates the {@code --allow-from} source list (comma-separated).
     *
     * @throws IllegalArgumentException if any entry is not a valid IP or CIDR
     */
    public static List<String> parseAllowFrom(String text) {
        List<String> sources = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return sources;
        }
        for (String part : text.split(",")) {
            String clean = part.trim();
            if (clean.isEmpty()) {
                continue;
            }
            Cidr.parse(clean); // validates the entry; throws with a clear message
            sources.add(clean);
        }
        return sources;
    }

    /** Registers a graceful shutdown hook (closes socket + pool on SIGTERM/Ctrl+C). */
    public void installShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(this::stop, "sitelock-dns-shutdown"));
    }

    /**
     * Serving loop; returns only when {@link #stop()} is called.
     *
     * @throws DnsServerException with a friendly message if the port cannot be opened
     */
    public void serveForever() {
        DatagramSocket ds = bind();
        this.socket = ds;
        ServerSocket tcp = null;
        UpstreamClient client = null;
        // NOTE: bindTcp and the upstream client creation are also inside the try:
        // if either fails, the finally closes the UDP — previously a TCP failure
        // leaked the UDP socket and the next start said the port was in use.
        try {
            tcp = bindTcp();
            this.tcpServer = tcp;
            try {
                client = new UpstreamClient(upstream, upstreamPort, upstreamTimeoutMs,
                        UPSTREAM_SOCKETS);
            } catch (IOException e) {
                throw new DnsServerException("Failed to open the upstream socket "
                        + upstream.getHostAddress() + ":" + upstreamPort + ": "
                        + e.getMessage(), e);
            }
            this.upstreamClient = client;
            this.pool = newPool();
            this.tcpPool = newTcpPool();
            startTcpAcceptor(tcp);
            log("listening on 0.0.0.0:" + port + " (upstream " + upstream.getHostAddress()
                    + ") — accepting local sources"
                    + (policy.extras().isEmpty() ? ""
                    : " + extras " + String.join(", ", policy.extras())));
            System.out.println("SITELOCK_DNS_LISTENING port=" + port);
            while (!stopRequested) {
                try {
                    awaitAndDispatch(ds);
                } catch (SocketException e) {
                    if (stopRequested || ds.isClosed()) {
                        break; // stop() closed the socket to unblock receive
                    }
                    log("socket error on listener: " + e.getMessage() + " (continuing)");
                } catch (IOException e) {
                    log("I/O error on listener: " + e.getMessage() + " (continuing)");
                }
            }
        } finally {
            closeQuietly(ds);
            if (tcp != null) {
                closeQuietly(tcp);
            }
            if (client != null) {
                client.close();
            }
            // WHY: tear down the pool in the finally — without this, a crash in the
            // middle of the loop would orphan the old pool (daemon threads with a 60s
            // keepalive) and the watchdog would create another pool on top.
            shutdown(pool);
            shutdown(tcpPool);
        }
    }

    // Waits for a packet and dispatches handling to the pool.
    // WHY: package-private so the test can simulate the loop crashing.
    void awaitAndDispatch(DatagramSocket ds) throws IOException {
        byte[] buf = new byte[MAX_PACKET_BYTES];
        DatagramPacket packet = new DatagramPacket(buf, buf.length);
        ds.receive(packet);
        byte[] data = Arrays.copyOf(packet.getData(), packet.getLength());
        InetAddress client = packet.getAddress();
        int clientPort = packet.getPort();
        try {
            pool.execute(() -> handlePacket(data, client, clientPort));
        } catch (RejectedExecutionException e) {
            // Pool full or shutting down: drop the query (the client retransmits).
        }
    }

    // WHY: the first failure takes the process down — previously the generic
    // watchdog slept 1s and retried forever, and `dns start` (which only checked
    // whether the child was alive) printed "DNS server started" while listening to nothing.
    /**
     * Watchdog: if the serving loop dies from an unexpected exception, waits 1s and
     * retries until {@link #stop()}. The first failure takes the process down.
     * It does not cover {@code kill -9}: the watchdog dies with it (see README).
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
                log("unexpected crash (" + e.getMessage() + "); restarting in 1s...");
            } catch (RuntimeException e) {
                if (stopRequested) {
                    break;
                }
                if (firstAttempt) {
                    throw new DnsServerException("Unexpected DNS server failure: "
                            + e.getMessage(), e);
                }
                log("unexpected error (" + e.getMessage() + "); restarting in 1s...");
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

    /** Stops the server: unblocks receive, closes sockets, and shuts down the pools. */
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
        UpstreamClient client = upstreamClient;
        if (client != null) {
            client.close();
        }
        shutdown(pool);
        shutdown(tcpPool);
    }
    // Shuts down a pool, waiting for the threads to finish.
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
            recordRefusedSource(client);
            return; // silence: answering would be exactly the amplification
        }
        byte[] response = answerQuery(data, client);
        if (response != null) {
            send(response, client, clientPort);
        }
    }

    // Decides and builds the response for a query — the same path for UDP and TCP.
    // Returns null when the query was ignored.
    private byte[] answerQuery(byte[] data, InetAddress client) {
        final DnsQuery query;
        try {
            query = DnsPacketParser.parse(data, data.length);
        } catch (DnsParseException e) {
            // NOTE: a packet with an unexpected OPCODE/QDCOUNT is malformed and is
            // intentionally left unanswered — answering garbage with an error makes
            // reflection/amplification easier, and the client retransmits on its own.
            log("ignored malformed packet from " + client.getHostAddress() + ": " + e.getMessage());
            return null;
        }

        // Belt and suspenders: no query error may kill the worker thread without a
        // response (the client would wait until the timeout). An error here becomes
        // SERVFAIL; a malformed packet is still ignored (see above).
        final DnsResolver.Result result;
        try {
            result = resolver.decideWithState(query.name(), query.type());
        } catch (RuntimeException e) {
            log("internal error deciding " + query.name() + ": " + e + " (SERVFAIL)");
            return DnsPacketBuilder.buildErrorResponse(query, 2);
        }
        DnsResolver.Decision decision = result.decision();
        // WHY: isBlocked() covers BLOCK and BLOCK_NODATA. Comparing only with BLOCK
        // lets types other than A/AAAA fall through to forwarding — the client would
        // receive the blocked name's REAL data and the DNS provider would see the query.
        if (decision.isBlocked()) {
            log("BLOCKED " + query.name() + " (type " + query.type() + ") <- "
                    + client.getHostAddress());
            return DnsPacketBuilder.buildResponse(query, decision);
        }

        final byte[] response;
        try {
            UpstreamClient client2 = upstreamClient;
            if (client2 == null) {
                response = DnsResolver.forwardRaw(data, data.length, upstream, upstreamPort, upstreamTimeoutMs);
            } else {
                response = client2.forward(data, data.length);
            }
        } catch (SocketTimeoutException e) {
            // Timeout becomes SERVFAIL (RCODE 2): never wait for the upstream forever.
            log("upstream timeout " + upstream.getHostAddress() + " for " + query.name()
                    + " (SERVFAIL)");
            return DnsPacketBuilder.buildErrorResponse(query, 2);
        } catch (IOException e) {
            log("upstream failure for " + query.name() + ": " + e.getMessage() + " (SERVFAIL)");
            return DnsPacketBuilder.buildErrorResponse(query, 2);
        }

        // CNAME alias: the queried name is free, but the chain returned by the
        // upstream may point at a blocked name — and carry its real IP.
        // In that case the response is discarded and the client gets the local sinkhole.
        final String blockedAlias;
        try {
            blockedAlias = resolver.blockedNameInChain(response, result.state());
        } catch (RuntimeException e) {
            // A failed check must not take down the response or the server: the
            // client's network keeps working (fail-open, as when reading state).
            // The log records the problem.
            log("error checking the alias chain for " + query.name() + ": " + e
                    + " (response relayed)");
            return response;
        }
        if (blockedAlias != null) {
            log("BLOCKED (alias) " + query.name() + " -> " + blockedAlias
                    + " (type " + query.type() + ") <- " + client.getHostAddress());
            return DnsPacketBuilder.buildResponse(query,
                    DnsResolver.blockingDecision(query.type()));
        }
        return response;
    }

    // Handles a query arriving over TCP (RFC 1035 §4.2.2): the format is the same
    // as the query, preceded by its 2-byte length. The same source policy and the
    // same decision as UDP apply here.
    private void handleTcp(Socket socket) {
        InetAddress client = socket.getInetAddress();
        try {
            if (!policy.isAllowed(client)) {
                recordRefusedSource(client);
                return; // silence: answering would be exactly the amplification
            }
            socket.setSoTimeout(TCP_READ_TIMEOUT_MS);
            InputStream in = socket.getInputStream();
            byte[] size = readExactly(in, 2);
            if (size == null) {
                return; // connection closed without a query
            }
            int length = ((size[0] & 0xFF) << 8) | (size[1] & 0xFF);
            if (length <= 0 || length > MAX_TCP_MESSAGE_BYTES) {
                log("TCP query with invalid size (" + length + ") from "
                        + client.getHostAddress());
                return;
            }
            byte[] data = readExactly(in, length);
            if (data == null) {
                return; // connection closed in the middle of the query
            }
            byte[] response = answerQuery(data, client);
            if (response == null) {
                return;
            }
            // NOTE: size and body in a single buffer. Writing the size separately
            // lets the client receive the message in two parts, and a reader that
            // asks for "the 2 size bytes" in one recv() gets only the first.
            byte[] message = new byte[2 + response.length];
            message[0] = (byte) (response.length >>> 8);
            message[1] = (byte) response.length;
            System.arraycopy(response, 0, message, 2, response.length);
            OutputStream out = socket.getOutputStream();
            out.write(message);
            out.flush();
        } catch (SocketTimeoutException e) {
            log("incomplete TCP query from " + client.getHostAddress() + " (timeout)");
        } catch (IOException e) {
            log("TCP connection failure from " + client.getHostAddress() + ": " + e.getMessage());
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
                // already closing
            }
        }
    }

    // Reads exactly n bytes; returns null if the stream ended first.
    private static byte[] readExactly(InputStream in, int n) throws IOException {
        byte[] buffer = new byte[n];
        int read = 0;
        while (read < n) {
            int r = in.read(buffer, read, n - read);
            if (r < 0) {
                return null;
            }
            read += r;
        }
        return buffer;
    }

    // WHY: workers respond through the same listening socket —
    // DatagramSocket#send is thread-safe, so no lock is needed.
    private void send(byte[] response, InetAddress client, int clientPort) {
        DatagramSocket ds = socket;
        if (ds == null || ds.isClosed()) {
            return;
        }
        try {
            ds.send(new DatagramPacket(response, response.length, client, clientPort));
        } catch (IOException e) {
            log("failed to respond to " + client.getHostAddress() + ": " + e.getMessage());
        }
    }

    private DatagramSocket bind() {
        final DatagramSocket ds;
        try {
            ds = new DatagramSocket(null);
        } catch (SocketException e) {
            throw new DnsServerException(
                    "Failed to create the UDP socket on port " + port + ": " + e.getMessage(), e);
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

    // Opens the TCP listener on the SAME port as UDP: the two protocols do not
    // conflict. Without a TCP listener, a large response (TC=1) makes the client
    // retry over TCP (RFC 7766 §8) and resolution fails with a DNS error.
    private ServerSocket bindTcp() {
        final ServerSocket server;
        try {
            server = new ServerSocket();
        } catch (IOException e) {
            throw new DnsServerException(
                    "Failed to create the TCP socket on port " + port + ": " + e.getMessage(), e);
        }
        try {
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(port));
            server.setSoTimeout(1000); // accept must not hold the server forever
        } catch (IOException e) {
            closeQuietly(server);
            throw new DnsServerException(
                    "Failed to open TCP port " + port + ": " + e.getMessage(), e);
        }
        return server;
    }

    // Thread that accepts TCP connections and hands each one to the pool.
    private void startTcpAcceptor(ServerSocket tcp) {
        Thread acceptor = new Thread(() -> {
            while (!stopRequested) {
                Socket socket;
                try {
                    socket = tcp.accept();
                } catch (SocketTimeoutException e) {
                    continue; // just to recheck the stop flag
                } catch (IOException e) {
                    if (stopRequested || tcp.isClosed()) {
                        break;
                    }
                    log("error accepting TCP connection: " + e.getMessage());
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
                    // Pool full: close the connection (the client retries on its own).
                    closeQuietly(socket);
                }
            }
        }, "sitelock-dns-tcp");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    // Turns raw bind exceptions into actionable messages.
    // Port 53 is privileged on Linux: only root can bind below 1024.
    // For local tests without sudo there is the --port flag (e.g. --port 5300).
    DnsServerException friendlyBindError(SocketException cause) {
        String msg = String.valueOf(cause.getMessage()).toLowerCase();
        if (cause instanceof BindException || msg.contains("already in use")
                || msg.contains("address already in use")) {
            return new DnsServerException(
                    "UDP port " + port + " is already in use. Is another DNS server running? "
                            + "(On many distributions systemd-resolved occupies 53 on 127.0.0.53.) "
                            + "Free the port or use another one: --port 5300.", cause);
        }
        if (port < 1024 && (msg.contains("permission") || msg.contains("denied")
                || msg.contains("access") || msg.contains("permiss"))) {
            return new DnsServerException(
                    "No permission to open UDP port " + port + ". Ports below 1024 are "
                            + "privileged on Linux and require root: run with sudo "
                            + "(e.g. sudo siteblock dns start). To test without sudo, use a "
                            + "high port: siteblock dns start --port 5300.", cause);
        }
        return new DnsServerException(
                "Failed to open UDP port " + port + ": " + cause.getMessage(), cause);
    }

    private static ExecutorService newPool() {
        return newPool(UDP_POOL_SIZE, UDP_QUEUE_SIZE, "sitelock-dns-");
    }

    private static ExecutorService newTcpPool() {
        return newPool(TCP_POOL_SIZE, TCP_QUEUE_SIZE, "sitelock-dns-tcp-");
    }

    // Bounded pool: fixed number of threads, capped queue, and rejection when it
    // fills (the packet is dropped — the client retransmits). This is what stops a
    // slow upstream query from blocking the others.
    private static ExecutorService newPool(int threads, int queueSize, String namePrefix) {
        ThreadFactory factory = new ThreadFactory() {
            private final AtomicInteger seq = new AtomicInteger();

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, namePrefix + seq.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        };
        return new ThreadPoolExecutor(threads, threads, 60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueSize), factory, new ThreadPoolExecutor.AbortPolicy());
    }

    // Records the refused source without repeating for every packet.
    // WHY: one log line per packet fills the disk during a port scan. The queried
    // name is intentionally absent: the log must not become a query channel.
    private void recordRefusedSource(InetAddress client) {
        String ip = client.getHostAddress();
        long now = System.currentTimeMillis();
        if (lastRefusalAt.size() > 1000) {
            lastRefusalAt.clear();
        }
        Long previous = lastRefusalAt.put(ip, now);
        if (previous == null || now - previous > 10_000) {
            log("refused source " + ip + " (not local and not in --allow-from)");
        }
    }

    // WHY: listening on 0.0.0.0 without checking the source would turn the machine
    // into an open resolver — anyone on the internet would use it as a DDoS
    // reflector (the upstream response leaves with its IP) and to see what the
    // network queries.
    /**
     * Who may query this server. The default accepts only local sources
     * (loopback, link-local, RFC1918, IPv6 ULA, and the machine's own addresses)
     * and refuses the rest <strong>silently</strong>.
     */
    public static final class SourcePolicy {

        // Networks allowed by default: loopback, link-local, RFC1918, and ULA.
        private static final List<String> DEFAULT_NETWORKS = List.of(
                "127.0.0.0/8",     // IPv4 loopback
                "::1/128",         // IPv6 loopback
                "169.254.0.0/16",  // IPv4 link-local (hotspot, PPPoE)
                "fe80::/10",       // IPv6 link-local
                "10.0.0.0/8",      // RFC1918
                "172.16.0.0/12",   // RFC1918
                "192.168.0.0/16",  // RFC1918
                "fc00::/7");       // IPv6 ULA (the RFC1918 equivalent)

        private final List<Cidr> networks;
        private final Set<InetAddress> exactAddresses;
        private final List<String> extras;

        private SourcePolicy(List<Cidr> networks, Set<InetAddress> exactAddresses,
                             List<String> extras) {
            this.networks = List.copyOf(networks);
            this.exactAddresses = Set.copyOf(exactAddresses);
            this.extras = List.copyOf(extras);
        }

        /** Default policy: local networks + machine addresses + --allow-from. */
        public static SourcePolicy defaultPolicy(List<String> allowFrom) {
            List<Cidr> networks = new ArrayList<>();
            for (String network : DEFAULT_NETWORKS) {
                networks.add(Cidr.parse(network));
            }
            List<String> extras = new ArrayList<>();
            for (String extra : allowFrom) {
                for (String part : extra.split(",")) {
                    if (!part.isBlank()) {
                        networks.add(Cidr.parse(part));
                        extras.add(part.trim());
                    }
                }
            }
            return new SourcePolicy(networks, Set.copyOf(NetworkUtils.localAddresses()), extras);
        }

        // Policy that accepts nobody (used in tests).
        static SourcePolicy none() {
            return new SourcePolicy(List.of(), Set.of(), List.of());
        }

        /** Sources allowed in addition to local ones (for the log). */
        public List<String> extras() {
            return extras;
        }

        /** True if the source may query this server. */
        public boolean isAllowed(InetAddress source) {
            if (source == null) {
                return false;
            }
            if (exactAddresses.contains(source)) {
                return true; // the machine's own address
            }
            for (Cidr network : networks) {
                if (network.matches(source)) {
                    return true;
                }
            }
            return false;
        }
    }

    // A network in CIDR notation (or a single address, treated as /32 or /128).
    static final class Cidr {

        private final byte[] network;
        private final int bits;

        private Cidr(byte[] network, int bits) {
            this.network = network;
            this.bits = bits;
        }

        // Throws IllegalArgumentException if the text is not a valid IP or CIDR.
        static Cidr parse(String text) {
            String clean = text == null ? "" : text.trim();
            if (clean.isEmpty()) {
                throw new IllegalArgumentException("empty address in --allow-from");
            }
            int slash = clean.indexOf('/');
            String addressPart = slash < 0 ? clean : clean.substring(0, slash);
            String bitsPart = slash < 0 ? null : clean.substring(slash + 1);
            InetAddress address = literalAddress(addressPart);
            byte[] bytes = address.getAddress();
            int maximum = bytes.length * 8;
            int bits = maximum;
            if (bitsPart != null) {
                try {
                    bits = Integer.parseInt(bitsPart.trim());
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("invalid mask in --allow-from: " + text);
                }
                if (bits < 0 || bits > maximum) {
                    throw new IllegalArgumentException("mask out of range in --allow-from: " + text);
                }
            }
            byte[] base = new byte[bytes.length];
            System.arraycopy(bytes, 0, base, 0, bytes.length);
            for (int i = 0; i < base.length; i++) {
                int validBits = Math.max(0, Math.min(8, bits - i * 8));
                int mask = validBits == 0 ? 0xFF : (0xFF << (8 - validBits)) & 0xFF;
                base[i] = (byte) (base[i] & mask);
            }
            return new Cidr(base, bits);
        }

        boolean matches(InetAddress address) {
            byte[] bytes = address.getAddress();
            if (bytes.length != network.length) {
                return false; // IPv4 does not match an IPv6 network (or vice versa)
            }
            int fullBytes = bits / 8;
            int remainingBits = bits % 8;
            for (int i = 0; i < fullBytes; i++) {
                if (bytes[i] != network[i]) {
                    return false;
                }
            }
            if (remainingBits > 0) {
                int mask = (0xFF << (8 - remainingBits)) & 0xFF;
                if ((bytes[fullBytes] & mask) != (network[fullBytes] & mask)) {
                    return false;
                }
            }
            return true;
        }

        // Converts the text to an address without ever querying DNS.
        // WHY: getByName resolves names, and a forgotten hostname would hang the
        // server on the first query (the public DNS may be blocked).
        private static InetAddress literalAddress(String text) {
            String t = text.trim();
            if (t.indexOf(':') >= 0) {
                try {
                    return InetAddress.getByName(t);
                } catch (UnknownHostException e) {
                    throw new IllegalArgumentException("invalid IPv6 address: " + text);
                }
            }
            String[] parts = t.split("\\.");
            if (parts.length != 4) {
                throw new IllegalArgumentException(
                        "invalid address in --allow-from (use an IP or CIDR, not a name): " + text);
            }
            byte[] bytes = new byte[4];
            for (int i = 0; i < 4; i++) {
                int value;
                try {
                    value = Integer.parseInt(parts[i]);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("invalid address in --allow-from: " + text);
                }
                if (value < 0 || value > 255) {
                    throw new IllegalArgumentException("invalid address in --allow-from: " + text);
                }
                bytes[i] = (byte) value;
            }
            try {
                return InetAddress.getByAddress(bytes);
            } catch (UnknownHostException e) {
                throw new IllegalArgumentException("invalid address in --allow-from: " + text);
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
            // already closing
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // already closing
        }
    }

    private static void log(String message) {
        System.out.println("[sitelock-dns] " + message);
    }
}
