package com.sitelock.dns;

import com.sitelock.core.BlockStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// The real DNS server, on real sockets (loopback, high port). Previously the
// decision was tested but the transport was not: these tests use a dark upstream
// (127.0.0.1:53) or a local fake upstream — never the internet.
class DnsServerTest {

    @TempDir
    Path tmp;

    // Starts the server. With a null upstream, the upstream stays dark
    // (127.0.0.1:53, where nobody listens) — a forward ends in SERVFAIL, which is
    // the symptom of the blocking defect.
    private DnsServer startServer(String... blocked) throws Exception {
        return startServer(null, blocked);
    }

    private DnsServer startServer(FakeUpstream upstream, String... blocked) throws Exception {
        return startServer(upstream, 2000, blocked);
    }

    private DnsServer startServer(FakeUpstream upstream, int upstreamTimeoutMs,
                                  String... blocked) throws Exception {
        Path state = DnsTestSupport.blocksFile(tmp, blocked);
        int port = DnsTestSupport.freePort();
        InetAddress upstreamAddress = upstream == null
                ? InetAddress.getLoopbackAddress() : upstream.address();
        int upstreamPort = upstream == null ? 53 : upstream.port();
        DnsServer server = new DnsServer(port, upstreamAddress, upstreamPort, upstreamTimeoutMs,
                new BlockStore(state), Clock.systemUTC(), List.of());
        Thread t = new Thread(server::serveForever, "test-server");
        t.setDaemon(true);
        t.start();
        DnsTestSupport.awaitListening(port, blocked[0], 5000);
        return server;
    }

    @Test
    void blockedNameWithNonAaaaTypeAnswersNodataWithoutQueryingUpstream() throws Exception {
        DnsServer server = startServer("youtube.com");
        try {
            byte[] raw = DnsTestSupport.query(server.getPort(),
                    DnsWire.query(0x1234, "youtube.com", 16 /*TXT*/), 3000);
            assertNotNull(raw, "the server did not respond");
            DnsWire.Response r = DnsWire.parse(raw);
            assertEquals(0, r.rcode(),
                    "a blocked name must not produce SERVFAIL: it was forwarded upstream");
            assertEquals(0, r.anCount(),
                    "NODATA: no answer for a type other than A/AAAA");
            assertEquals(0x1234, r.id(), "the response must echo the client ID");
        } finally {
            server.stop();
        }
    }

    @Test
    void blockedNameTypeAAnswersSinkhole() throws Exception {
        DnsServer server = startServer("youtube.com");
        try {
            byte[] raw = DnsTestSupport.query(server.getPort(),
                    DnsWire.query(0x2222, "youtube.com", 1 /*A*/), 3000);
            assertNotNull(raw, "the server did not respond");
            DnsWire.Response r = DnsWire.parse(raw);
            assertEquals(0, r.rcode());
            assertEquals(1, r.anCount(), "a blocked A/AAAA gets the sinkhole");
            assertArrayEquals(new byte[]{0, 0, 0, 0}, r.firstRdata(),
                    "the IPv4 sinkhole is 0.0.0.0");
        } finally {
            server.stop();
        }
    }

    // The same server must answer over TCP: when the response does not fit in UDP,
    // the client retries over TCP (RFC 7766 §8) and, without a listener, resolution
    // fails with a DNS error.
    @Test
    void tcpAnswersBlockedQuery() throws Exception {
        DnsServer server = startServer("youtube.com");
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), server.getPort())) {
            socket.setSoTimeout(5000);
            byte[] query = DnsWire.query(0x4444, "youtube.com", 1 /*A*/);
            OutputStream out = socket.getOutputStream();
            out.write((query.length >>> 8) & 0xFF);
            out.write(query.length & 0xFF);
            out.write(query);
            out.flush();

            byte[] size = readExactly(socket.getInputStream(), 2);
            assertNotNull(size, "the server did not respond over TCP");
            int length = ((size[0] & 0xFF) << 8) | (size[1] & 0xFF);
            byte[] response = readExactly(socket.getInputStream(), length);
            assertNotNull(response, "truncated TCP response");

            DnsWire.Response r = DnsWire.parse(response);
            assertEquals(0x4444, r.id(), "the TCP response must echo the client ID");
            assertEquals(0, r.rcode());
            assertEquals(1, r.anCount(), "a blocked A/AAAA gets the sinkhole");
            assertArrayEquals(new byte[]{0, 0, 0, 0}, r.firstRdata(),
                    "the IPv4 sinkhole is 0.0.0.0");
        } finally {
            server.stop();
        }
    }

    @Test
    void tcpAnswersNodataForNonAaaaType() throws Exception {
        DnsServer server = startServer("youtube.com");
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), server.getPort())) {
            socket.setSoTimeout(5000);
            byte[] query = DnsWire.query(0x5555, "youtube.com", 16 /*TXT*/);
            OutputStream out = socket.getOutputStream();
            out.write((query.length >>> 8) & 0xFF);
            out.write(query.length & 0xFF);
            out.write(query);
            out.flush();

            byte[] size = readExactly(socket.getInputStream(), 2);
            assertNotNull(size, "the server did not respond over TCP");
            int length = ((size[0] & 0xFF) << 8) | (size[1] & 0xFF);
            byte[] response = readExactly(socket.getInputStream(), length);
            assertNotNull(response, "truncated TCP response");

            DnsWire.Response r = DnsWire.parse(response);
            assertEquals(0, r.rcode());
            assertEquals(0, r.anCount(), "NODATA over TCP too");
        } finally {
            server.stop();
        }
    }

    // A connection that opens and sends nothing must not hold the server.
    @Test
    void idleTcpConnectionDoesNotHoldTheServer() throws Exception {
        DnsServer server = startServer("youtube.com");
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), server.getPort())) {
            socket.setSoTimeout(15000);
            assertEquals(-1, socket.getInputStream().read(),
                    "the server must close a connection that sent no query");
        } finally {
            server.stop();
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

    // The client ID must not travel in cleartext to the upstream: anyone listening
    // on the path could then forge the response. The upstream receives its own ID,
    // and the client receives the response with its own ID.
    @Test
    void upstreamReceivesIdDifferentFromClient() throws Exception {
        FakeUpstream upstream = new FakeUpstream();
        upstream.start();
        DnsServer server = startServer(upstream, "youtube.com");
        try {
            byte[] raw = DnsTestSupport.query(server.getPort(),
                    DnsWire.query(0x6789, "example.com", 1), 3000);
            assertNotNull(raw, "the server did not respond");
            DnsWire.Response r = DnsWire.parse(raw);
            assertEquals(0x6789, r.id(), "the client must see its own ID");
            assertEquals(1, r.anCount(), "the upstream response must be relayed");

            List<byte[]> seen = upstream.receivedQueries();
            assertEquals(1, seen.size(), "the upstream should receive one query");
            assertNotEquals(0x6789, DnsWire.u16(seen.get(0), 0),
                    "the client ID must not go to the upstream in cleartext");
        } finally {
            server.stop();
            upstream.close();
        }
    }

    // A response with a swapped ID belongs to another transaction: it becomes
    // SERVFAIL, never fake data.
    @Test
    void responseWithWrongIdDoesNotReachClient() throws Exception {
        FakeUpstream upstream = new FakeUpstream();
        upstream.mode(FakeUpstream.Mode.WRONG_ID);
        upstream.start();
        DnsServer server = startServer(upstream, "youtube.com");
        try {
            byte[] raw = DnsTestSupport.query(server.getPort(),
                    DnsWire.query(0x1111, "example.com", 1), 3000);
            assertNotNull(raw, "the server did not respond");
            DnsWire.Response r = DnsWire.parse(raw);
            assertEquals(2, r.rcode(), "an invalid response must become SERVFAIL");
            assertEquals(0, r.anCount(), "no data from an invalid response may leak");
        } finally {
            server.stop();
            upstream.close();
        }
    }

    // A packet that is not a response (QR=0) is also discarded.
    @Test
    void packetThatIsNotAResponseDoesNotReachClient() throws Exception {
        FakeUpstream upstream = new FakeUpstream();
        upstream.mode(FakeUpstream.Mode.NO_QR);
        upstream.start();
        DnsServer server = startServer(upstream, "youtube.com");
        try {
            byte[] raw = DnsTestSupport.query(server.getPort(),
                    DnsWire.query(0x2222, "example.com", 1), 3000);
            assertNotNull(raw, "the server did not respond");
            DnsWire.Response r = DnsWire.parse(raw);
            assertEquals(2, r.rcode(), "a packet that is not a response must become SERVFAIL");
            assertEquals(0, r.anCount());
        } finally {
            server.stop();
            upstream.close();
        }
    }

    // A burst of queries must not create one thread per query or one socket per
    // query: the old ("cached") pool grew without a cap and each forward opened a
    // new socket — a burst exhausts the OS ephemeral ports.
    @Test
    void burstDoesNotCreateOneThreadOrSocketPerQuery() throws Exception {
        FakeUpstream upstream = new FakeUpstream();
        upstream.mode(FakeUpstream.Mode.SILENCE); // holds each query until the timeout
        upstream.start();
        DnsServer server = startServer(upstream, 300, "youtube.com");
        try {
            int before = countPoolThreads();
            byte[] query = DnsWire.query(0x9999, "example.com", 1);
            try (DatagramSocket client = new DatagramSocket()) {
                for (int i = 0; i < 40; i++) {
                    client.send(new DatagramPacket(query, query.length,
                            InetAddress.getLoopbackAddress(), server.getPort()));
                }
            }
            Thread.sleep(1000);

            int newThreads = countPoolThreads() - before;
            assertTrue(newThreads <= 16,
                    "the pool must be bounded (16 threads), but it created " + newThreads);

            Set<Integer> ports = upstream.sourcePorts();
            assertTrue(ports.size() <= 8,
                    "the upstream socket must be reused (8 sockets), but there were "
                            + ports.size() + " different ports");
        } finally {
            server.stop();
            upstream.close();
        }
    }

    // UDP pool threads (the TCP pool has another prefix).
    private static int countPoolThreads() {
        int total = 0;
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.getName().matches("sitelock-dns-\\d+")) {
                total++;
            }
        }
        return total;
    }

    // An outside source gets nothing: answering would amplify the query toward the
    // victim and reveal what the network queries.
    @Test
    void refusedSourceGetsNoResponse() throws Exception {
        Path state = DnsTestSupport.blocksFile(tmp, "youtube.com");
        int port = DnsTestSupport.freePort();
        DnsServer server = new DnsServer(port, InetAddress.getLoopbackAddress(), 500,
                new BlockStore(state), Clock.systemUTC(), DnsServer.SourcePolicy.none());

        ByteArrayOutputStream log = new ByteArrayOutputStream();
        PrintStream original = System.out;
        System.setOut(new PrintStream(log, true, StandardCharsets.UTF_8));
        try {
            Thread t = new Thread(server::serveForever, "test-server");
            t.setDaemon(true);
            t.start();
            DnsTestSupport.awaitMarker(log, "SITELOCK_DNS_LISTENING", 5000);

            assertThrows(SocketTimeoutException.class, () -> DnsTestSupport.query(port,
                    DnsWire.query(0x3333, "youtube.com", 1), 1000),
                    "a refused source must not receive a response");

            String text = log.toString(StandardCharsets.UTF_8);
            assertTrue(text.contains("refused source"),
                    "the log must record the refusal: " + text);
            assertFalse(text.contains("BLOCKED"),
                    "a refused query must not be served: " + text);
        } finally {
            System.setOut(original);
            server.stop();
        }
    }
}
