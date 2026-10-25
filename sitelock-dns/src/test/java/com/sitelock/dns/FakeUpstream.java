package com.sitelock.dns;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

// Fake upstream: a local UDP socket that responds as if it were the public DNS.
// It exists so tests never depend on the internet: the address is loopback and
// the port is ephemeral. The response mode reproduces transport defects.
final class FakeUpstream implements AutoCloseable {

    // How the fake upstream responds to the query.
    enum Mode {
        // Normal response: echoes the ID and returns an answer.
        NORMAL,
        // Responds with another ID (a response from another transaction).
        WRONG_ID,
        // Responds with QR=0 (not a response).
        NO_QR,
        // Responds with nothing (silent upstream).
        SILENCE,
        // Responds without an answer section (NXDOMAIN).
        NO_ANSWER
    }

    private final DatagramSocket socket;
    private final List<byte[]> received = Collections.synchronizedList(new ArrayList<>());
    private final List<Integer> sourcePorts = Collections.synchronizedList(new ArrayList<>());
    private volatile Mode mode = Mode.NORMAL;
    private volatile Function<byte[], byte[]> customResponse;
    private volatile boolean closed;

    FakeUpstream() throws SocketException {
        socket = new DatagramSocket(0, InetAddress.getLoopbackAddress());
        socket.setSoTimeout(100);
    }

    int port() {
        return socket.getLocalPort();
    }

    InetAddress address() {
        return socket.getLocalAddress();
    }

    List<byte[]> receivedQueries() {
        synchronized (received) {
            return List.copyOf(received);
        }
    }

    // Source ports of the received queries — that is, how many different sockets
    // the server opened to talk to the upstream.
    Set<Integer> sourcePorts() {
        synchronized (sourcePorts) {
            return new LinkedHashSet<>(sourcePorts);
        }
    }

    void mode(Mode newMode) {
        this.mode = newMode;
    }

    // Custom response: the lambda receives the received query and returns the
    // response (e.g. a hand-built CNAME chain). It takes precedence over Mode:
    // the mode only applies while there is no custom response.
    void respondWith(Function<byte[], byte[]> response) {
        this.customResponse = response;
    }

    void start() {
        Thread t = new Thread(this::loop, "fake-upstream");
        t.setDaemon(true);
        t.start();
    }

    private void loop() {
        byte[] buf = new byte[4096];
        while (!closed) {
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            try {
                socket.receive(p);
            } catch (SocketTimeoutException e) {
                continue;
            } catch (IOException e) {
                return;
            }
            byte[] query = Arrays.copyOf(p.getData(), p.getLength());
            received.add(query);
            sourcePorts.add(p.getPort());
            Mode m = mode;
            if (m == Mode.SILENCE) {
                continue;
            }
            Function<byte[], byte[]> custom = customResponse;
            byte[] response = custom != null ? custom.apply(query) : respond(query, m);
            if (response == null) {
                continue;
            }
            try {
                socket.send(new DatagramPacket(response, response.length,
                        p.getAddress(), p.getPort()));
            } catch (IOException ignored) {
                // fake upstream: send errors do not matter
            }
        }
    }

    // Response with a real answer (to prove that real data leaked).
    private static byte[] respond(byte[] query, Mode mode) {
        int id = DnsWire.u16(query, 0);
        if (mode == Mode.WRONG_ID) {
            id = (id + 1) & 0xFFFF;
        }
        int questionEnd = DnsWire.questionEnd(query);
        byte[] question = Arrays.copyOfRange(query, 12, questionEnd);
        int qtype = DnsWire.u16(query, questionEnd - 4);
        byte[] rdata = rdataFor(qtype);

        if (mode == Mode.NO_ANSWER) {
            // NXDOMAIN: the question is echoed, but the answer section is empty.
            byte[] out = new byte[12 + question.length];
            DnsWire.put16(out, 0, id);
            DnsWire.put16(out, 2, 0x8183); // QR=1, RD, RA, RCODE=3 (NXDOMAIN)
            DnsWire.put16(out, 4, 1);  // QDCOUNT
            DnsWire.put16(out, 6, 0);  // ANCOUNT
            System.arraycopy(question, 0, out, 12, question.length);
            return out;
        }

        byte[] out = new byte[12 + question.length + 12 + rdata.length];
        DnsWire.put16(out, 0, id);
        DnsWire.put16(out, 2, mode == Mode.NO_QR ? 0x0000 : 0x8180); // QR=1, RD, RA
        DnsWire.put16(out, 4, 1);  // QDCOUNT
        DnsWire.put16(out, 6, 1);  // ANCOUNT
        System.arraycopy(question, 0, out, 12, question.length);
        int off = 12 + question.length;
        out[off++] = (byte) 0xC0;
        out[off++] = 0x0C;
        DnsWire.put16(out, off, qtype);
        DnsWire.put16(out, off + 2, 1); // CLASS IN
        DnsWire.put16(out, off + 4, 60); // TTL
        DnsWire.put16(out, off + 8, rdata.length);
        System.arraycopy(rdata, 0, out, off + 10, rdata.length);
        return out;
    }

    private static byte[] rdataFor(int qtype) {
        if (qtype == DnsQuery.TYPE_A) {
            return new byte[]{(byte) 93, (byte) 184, (byte) 216, (byte) 34};
        }
        if (qtype == DnsQuery.TYPE_AAAA) {
            return new byte[16];
        }
        return "real-upstream-response".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        closed = true;
        socket.close();
    }
}
