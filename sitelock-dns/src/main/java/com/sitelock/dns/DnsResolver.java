package com.sitelock.dns;

import com.sitelock.core.BlockEntry;
import com.sitelock.core.BlockStore;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;

/**
 * Decides, per query, between answering with the local sinkhole and forwarding upstream.
 *
 * <p>The decision is pure and isolated from the network ({@link #decide(String, int, Map, Instant)}
 * is static and testable without sockets); transport lives in {@link #forwardRaw} and
 * {@link DnsServer}. Rules:
 * <ul>
 *   <li>Name equal to — or a subdomain of — an active block ({@code tiktok.com} also
 *       covers {@code www.tiktok.com} and {@code m.tiktok.com}): type A or AAAA gets
 *       {@link Decision#BLOCK} (sinkhole 0.0.0.0 / ::), and any other type gets
 *       {@link Decision#BLOCK_NODATA} (the name exists but has no data of that type).</li>
 *   <li>Everything else (unlisted domains, expired entries, root): {@link Decision#FORWARD}.</li>
 * </ul>
 *
 * <p>A blocked name never goes upstream: forwarding the bytes to the public DNS would
 * deliver the blocked domain's REAL data to the client (TXT, MX, CNAME, HTTPS…) and
 * reveal to the provider which name was queried. For types other than A/AAAA the
 * response is NODATA (RCODE 0, ANCOUNT 0) — the "the name exists but has no data of
 * this type" semantics, the same as Pi-hole/AdGuard.
 *
 * <p>CNAME aliases: the decision looks only at the queried name — the alias appears
 * only in the upstream <i>response</i>, so the query is sent anyway. The server
 * closes the gap through {@link #blockedNameInChain}.
 *
 * <p>No in-memory cache: every query rereads {@code blocks.json} from disk through
 * {@link BlockStore#load()} (atomic snapshot), so a {@code block}/{@code unblock}
 * done in another terminal applies on the next query. The cost is reading a small
 * file per query — irrelevant next to UDP RTT.
 *
 * <p>Unreadable state becomes {@link Decision#FORWARD} (fail-open) instead of taking
 * every device offline: network availability wins over blocking.
 */
public class DnsResolver {

    /**
     * What to do with the query: answer locally or forward upstream.
     *
     * <p>Contract for consumers (the {@link DnsServer}): a blocked name NEVER
     * results in {@link Decision#FORWARD}. Use {@link #isBlocked()} to decide
     * "answer locally or forward?" without listing the blocked cases.
     */
    public enum Decision {
        /** Answer locally with the sinkhole (0.0.0.0 / ::). */
        BLOCK,
        /**
         * Answer locally with NODATA (RCODE 0, ANCOUNT 0): the name exists but has
         * no data of the requested type. This is the blocked-name response for types
         * other than A/AAAA — never forwarding upstream.
         */
        BLOCK_NODATA,
        /** Pass the bytes to the upstream DNS and relay the response. */
        FORWARD;

        /**
         * True when the response is built locally (BLOCK or BLOCK_NODATA).
         *
         * <p>WARNING: use this method instead of comparing with {@link #BLOCK} — a
         * blocked name queried with a type other than A/AAAA is {@link #BLOCK_NODATA}.
         */
        public boolean isBlocked() {
            return this != FORWARD;
        }
    }

    /** Upstream DNS port (fixed: the standard DNS service). */
    public static final int UPSTREAM_PORT = 53;

    // WHY: the client ID travels in cleartext on the LAN; using it upstream exposes
    // the transaction. A dedicated ID, randomized per query, closes the door on
    // anyone trying to forge a response.
    private static final SecureRandom RANDOM = new SecureRandom();

    // Draws a 16-bit ID for the upstream transaction.
    static int randomId() {
        return RANDOM.nextInt(0x10000);
    }

    private final BlockStore store;
    private final Clock clock;

    public DnsResolver(BlockStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    /**
     * The decision and the state snapshot that produced it.
     *
     * @param state the blocks used in the decision (snapshot from disk)
     */
    public record Result(Decision decision, Map<String, BlockEntry> state) {
    }

    /** Reads state from disk and decides (fail-open on a read error). */
    public Decision decide(String domain, int qtype) {
        return decideWithState(domain, qtype).decision();
    }

    /**
     * Decides and returns the state snapshot used in the decision.
     *
     * <p>WHY: the CNAME chain check (on the FORWARD path) needs the SAME blocklist
     * as the decision — without the snapshot, the server would reread blocks.json
     * in a second disk read per query.
     */
    public Result decideWithState(String domain, int qtype) {
        final Map<String, BlockEntry> state;
        try {
            state = store.load();
        } catch (IOException e) {
            return new Result(Decision.FORWARD, Map.of());
        }
        Decision decision = decide(domain, qtype, state, Instant.now(clock));
        if (decision == Decision.BLOCK_NODATA) {
            // WHY: the operator needs to know that a client asked a blocked name for
            // a type other than A/AAAA — a sign of an app trying to bypass the block.
            log("BLOCKED (nodata) " + domain + " type=" + qtype);
        }
        return new Result(decision, state);
    }

    /**
     * A blocked name in the alias chain of the upstream response.
     *
     * <p>This is the gap left by DNS blocking: the queried name is free, but the
     * upstream responds with the CNAME chain <b>and</b> the target's real IP in the
     * same response. If any name in the chain is blocked, the upstream response
     * counts as the IP of a blocked domain — the caller discards the response and
     * delivers the local sinkhole instead.
     *
     * @param response upstream response bytes
     * @param state    blocklist snapshot (the same one used for the decision)
     * @return the blocked name found, or null if the chain is clean
     */
    public String blockedNameInChain(byte[] response, Map<String, BlockEntry> state) {
        if (response == null || state == null || state.isEmpty()) {
            return null;
        }
        Instant now = Instant.now(clock);
        for (String name : DnsPacketParser.answerSectionNames(response, response.length)) {
            if (name.isEmpty()) {
                continue;
            }
            if (decide(name, DnsQuery.TYPE_A, state, now) != Decision.FORWARD) {
                return name;
            }
        }
        return null;
    }

    /**
     * Local response decision for a blocked name (directly or through an alias chain).
     *
     * <p>WHY: the response must match a blocked name — sinkhole (0.0.0.0 / ::) for
     * A/AAAA and NODATA for every other type.
     */
    public static Decision blockingDecision(int qtype) {
        return (qtype == DnsQuery.TYPE_A || qtype == DnsQuery.TYPE_AAAA)
                ? Decision.BLOCK : Decision.BLOCK_NODATA;
    }

    private static void log(String message) {
        System.out.println("[sitelock-dns] " + message);
    }

    /**
     * Pure decision, with no I/O — used by the server and by the unit tests.
     *
     * <p>Contract: a blocked name never becomes {@link Decision#FORWARD}. Type
     * A/AAAA gets the sinkhole ({@link Decision#BLOCK}); any other type gets NODATA
     * ({@link Decision#BLOCK_NODATA}) — forwarding upstream would deliver the
     * blocked domain's real data to the client.
     *
     * @param domain queried name (any case; trailing dot optional)
     * @param qtype  DNS type (1 = A, 28 = AAAA)
     * @param state  blocklist snapshot (key = normalized domain)
     * @param now    reference instant for expiration
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
        return blockingDecision(qtype);
    }

    // True if the name equals an active block or is a subdomain of one
    // (".domain" suffix — "eviltiktok.com" does NOT match "tiktok.com").
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

    // Canonical name form for comparing against the blocklist: lowercase, no
    // trailing dot, and punycode — so a Unicode name and a punycode name match the
    // same entry ("пример.рф" and "xn--e1afmkfd.xn--p1ai" are the same name).
    static String canonicalName(String raw) {
        String norm = normalizeQueryName(raw);
        if (norm.isEmpty()) {
            return "";
        }
        try {
            return DnsPacketParser.toAsciiName(norm);
        } catch (IllegalArgumentException e) {
            // A name invalid for IDNA matches no blocklist entry.
            return norm;
        }
    }

    /** Lowercase + remove one trailing dot (FQDN with trailing dot). Does not remove "www.". */
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
     * Forwards the query bytes to the upstream and waits for the response.
     *
     * <p><b>Transport:</b> the socket is <b>connected</b> to the upstream, so the
     * kernel drops anything arriving from another address — without that, any UDP
     * packet that hit the ephemeral port would enter as if it were the response.
     *
     * <p><b>ID:</b> the upstream receives its <b>own</b> ID, randomized per query
     * (the client ID travels in cleartext on the LAN), and the response comes back
     * with the client ID rewritten — the client keeps seeing the ID it sent.
     *
     * <p><b>Validation:</b> the response is relayed only if it really is a response
     * (QR=1), has the ID that was sent, and matches the question that was asked.
     * Anything else becomes a failure (SERVFAIL to the client), never fake data.
     *
     * @param request      original client packet
     * @param requestLen   valid bytes in {@code request}
     * @param upstream     public DNS address (e.g. 1.1.1.1)
     * @param upstreamPort upstream port (53 for public DNS)
     * @param timeoutMs    wait limit (e.g. 2000); throws
     *                     {@link java.net.SocketTimeoutException} instead of hanging the thread
     * @return exact upstream response bytes, with the client ID
     */
    public static byte[] forwardRaw(byte[] request, int requestLen,
                                    InetAddress upstream, int upstreamPort, int timeoutMs)
            throws IOException {
        try (UpstreamClient client = new UpstreamClient(upstream, upstreamPort, timeoutMs, 1)) {
            return client.forward(request, requestLen);
        }
    }

    /**
     * Forwards to the default upstream (port 53).
     *
     * @see #forwardRaw(byte[], int, InetAddress, int, int)
     */
    public static byte[] forwardRaw(byte[] request, int requestLen,
                                    InetAddress upstream, int timeoutMs) throws IOException {
        return forwardRaw(request, requestLen, upstream, UPSTREAM_PORT, timeoutMs);
    }

    // Checks that the upstream response really answers our query: swapped ID,
    // packet that is not a response, or a different question become IOException.
    static void validateResponse(byte[] response, int expectedId,
                                 byte[] query, int queryLen) throws IOException {
        if (response == null || response.length < 12) {
            throw new IOException("upstream response too short: "
                    + (response == null ? 0 : response.length) + " bytes");
        }
        int id = u16(response, 0);
        if (id != expectedId) {
            throw new IOException("upstream ID mismatch (" + id + " != "
                    + expectedId + "): response discarded");
        }
        int flags = u16(response, 2);
        if ((flags & 0x8000) == 0) {
            throw new IOException("upstream packet is not a response (QR=0): discarded");
        }
        final DnsQuery parsed;
        try {
            parsed = DnsPacketParser.parse(query, queryLen);
        } catch (DnsParseException e) {
            // A query that does not parse never gets here (the server already dropped it).
            return;
        }
        byte[] question = parsed.questionSection();
        if (response.length < 12 + question.length) {
            throw new IOException("upstream response is missing the question: discarded");
        }
        for (int i = 0; i < question.length; i++) {
            if (response[12 + i] != question[i]) {
                throw new IOException(
                        "upstream question is not the one that was sent: response discarded");
            }
        }
    }

    private static int u16(byte[] data, int off) {
        return ((data[off] & 0xFF) << 8) | (data[off + 1] & 0xFF);
    }
}

// WHY: a new socket per query exhausts the OS ephemeral ports. The sockets here
// are reused by every query, one per request under a lock: send/receive must
// belong to the same transaction, tied together by the randomized ID.
final class UpstreamClient implements AutoCloseable {

    private final InetAddress upstream;
    private final int port;
    private final int timeoutMs;
    private final DatagramSocket[] sockets;
    private final Object[] locks;
    private final java.util.concurrent.atomic.AtomicInteger next =
            new java.util.concurrent.atomic.AtomicInteger();

    // "count" is how many sockets to connect (1 = a single one, in series).
    UpstreamClient(InetAddress upstream, int port, int timeoutMs, int count)
            throws IOException {
        if (count < 1) {
            throw new IllegalArgumentException("invalid socket count: " + count);
        }
        this.upstream = upstream;
        this.port = port;
        this.timeoutMs = timeoutMs;
        this.sockets = new DatagramSocket[count];
        this.locks = new Object[count];
        try {
            for (int i = 0; i < count; i++) {
                DatagramSocket sock = new DatagramSocket();
                // NOTE: connecting is what makes the kernel filter by source.
                sock.connect(upstream, port);
                sock.setSoTimeout(timeoutMs);
                sockets[i] = sock;
                locks[i] = new Object();
            }
        } catch (IOException e) {
            close();
            throw e;
        }
    }

    // Forwards the query and returns the response with the client ID.
    byte[] forward(byte[] request, int requestLen) throws IOException {
        if (request == null || requestLen < 2 || requestLen > request.length) {
            throw new IOException("invalid query to forward");
        }
        int index = Math.floorMod(next.getAndIncrement(), sockets.length);
        DatagramSocket sock = sockets[index];
        synchronized (locks[index]) {
            int clientId = ((request[0] & 0xFF) << 8) | (request[1] & 0xFF);
            int upstreamId = DnsResolver.randomId();
            byte[] sent = Arrays.copyOf(request, requestLen);
            sent[0] = (byte) (upstreamId >>> 8);
            sent[1] = (byte) upstreamId;
            sock.send(new DatagramPacket(sent, requestLen));
            byte[] buf = new byte[DnsServer.MAX_PACKET_BYTES];
            DatagramPacket in = new DatagramPacket(buf, buf.length);
            sock.receive(in);
            byte[] response = Arrays.copyOf(in.getData(), in.getLength());
            DnsResolver.validateResponse(response, upstreamId, request, requestLen);
            // TC=1 means the upstream response did not fit in UDP. Retrying the same
            // query over TCP is the path the client would take on its own; without
            // it, the client got the truncated response and resolution could fail.
            int flags = ((response[2] & 0xFF) << 8) | (response[3] & 0xFF);
            if ((flags & 0x0200) != 0) {
                response = forwardTcp(sent, requestLen, upstreamId, request);
            }
            // The client must see its own ID: the upstream ID is ours, not theirs.
            response[0] = (byte) (clientId >>> 8);
            response[1] = (byte) clientId;
            return response;
        }
    }

    private byte[] forwardTcp(byte[] sent, int requestLen, int upstreamId,
                              byte[] originalQuery) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(upstream, port), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            OutputStream out = socket.getOutputStream();
            out.write((requestLen >>> 8) & 0xFF);
            out.write(requestLen & 0xFF);
            out.write(sent, 0, requestLen);
            out.flush();

            InputStream in = socket.getInputStream();
            byte[] size = readExactly(in, 2);
            if (size == null) {
                throw new IOException("upstream closed TCP before the response size");
            }
            int length = ((size[0] & 0xFF) << 8) | (size[1] & 0xFF);
            if (length <= 0) {
                throw new IOException("upstream TCP response with invalid size: " + length);
            }
            byte[] response = readExactly(in, length);
            if (response == null) {
                throw new IOException("truncated upstream TCP response");
            }
            DnsResolver.validateResponse(response, upstreamId, originalQuery, requestLen);
            return response;
        }
    }

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

    @Override
    public void close() {
        for (DatagramSocket sock : sockets) {
            if (sock != null) {
                try {
                    sock.close();
                } catch (RuntimeException ignored) {
                    // already closing
                }
            }
        }
    }
}
