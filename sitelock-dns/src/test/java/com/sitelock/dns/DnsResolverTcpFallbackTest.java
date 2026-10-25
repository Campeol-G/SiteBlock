package com.sitelock.dns;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An upstream that returns TC=1 over UDP forces the query to be retried over TCP;
 * without that, the truncated response reached the client and resolution could fail.
 */
class DnsResolverTcpFallbackTest {

    @Test
    void truncatedResponseIsRetriedOverTcp() throws Exception {
        try (TruncatedUpstream upstream = new TruncatedUpstream()) {
            byte[] query = DnsWire.query(0x7A7A, "example.com", DnsQuery.TYPE_A);

            byte[] response = DnsResolver.forwardRaw(query, query.length,
                    InetAddress.getLoopbackAddress(), upstream.port(), 2000);

            DnsWire.Response r = DnsWire.parse(response);
            assertEquals(0x7A7A, r.id(), "the client must receive its own ID");
            assertEquals(1, r.anCount(), "the complete response came from TCP");
            assertArrayEquals(new byte[]{(byte) 93, (byte) 184, (byte) 216, (byte) 34},
                    r.firstRdata(), "the upstream's real data must arrive intact");
            assertTrue(upstream.tcpReceived(), "the upstream must receive the query over TCP");
            assertNotEquals(0x7A7A, DnsWire.u16(upstream.tcpQuery(), 0),
                    "SiteLock's own ID must go over TCP as well");
        }
    }

    @Test
    void largeUdpResponseWithoutTcIsNotTruncated() throws Exception {
        try (FakeUpstream upstream = new FakeUpstream()) {
            upstream.respondWith(query -> largeResponse(query, 9000));
            upstream.start();
            byte[] query = DnsWire.query(0x4242, "large.com", 16); // TXT

            byte[] response = DnsResolver.forwardRaw(query, query.length,
                    upstream.address(), upstream.port(), 2000);

            assertTrue(response.length > 8192,
                    "the old 8192 buffer truncated this response: " + response.length);
            assertEquals(0, DnsWire.u16(response, 2) & 0x0200, "TC must remain zero");
            assertEquals(0x4242, DnsWire.u16(response, 0), "the client ID must come back");
            assertEquals(1, DnsWire.u16(response, 6), "the large answer must arrive");
        }
    }

    @Test
    void bufferCoversMaximumDnsMessage() {
        assertEquals(65535, DnsServer.MAX_PACKET_BYTES,
                "a large EDNS response must not be truncated by the UDP client buffer");
    }

    // TXT response with rdataLen bytes of RDATA and no TC: it fits in UDP, so the
    // resolver must not cut it in the buffer.
    private static byte[] largeResponse(byte[] query, int rdataLen) {
        int questionEnd = DnsWire.questionEnd(query);
        byte[] question = Arrays.copyOfRange(query, 12, questionEnd);
        byte[] out = new byte[12 + question.length + 12 + rdataLen];
        DnsWire.put16(out, 0, DnsWire.u16(query, 0));
        DnsWire.put16(out, 2, 0x8180); // QR=1, RD, RA, TC=0
        DnsWire.put16(out, 4, 1);      // QDCOUNT
        DnsWire.put16(out, 6, 1);      // ANCOUNT
        System.arraycopy(question, 0, out, 12, question.length);
        int off = 12 + question.length;
        out[off++] = (byte) 0xC0;      // name: pointer to the question
        out[off++] = 0x0C;
        DnsWire.put16(out, off, 16);   // TXT
        DnsWire.put16(out, off + 2, 1);
        DnsWire.put16(out, off + 4, 60);
        DnsWire.put16(out, off + 8, rdataLen);
        Arrays.fill(out, off + 10, off + 10 + rdataLen, (byte) 'x');
        return out;
    }

    // Upstream with UDP and TCP on the same port: UDP answers TC=1, TCP answers
    // the same question with the complete answer.
    private static final class TruncatedUpstream implements AutoCloseable {

        private final InetAddress loopback = InetAddress.getLoopbackAddress();
        private final DatagramSocket udp;
        private final ServerSocket tcp;
        private final Thread udpThread;
        private final Thread tcpThread;
        private volatile boolean closed;
        private volatile byte[] tcpQuery;

        TruncatedUpstream() throws IOException {
            int port = DnsTestSupport.freePort();
            udp = new DatagramSocket(port, loopback);
            udp.setSoTimeout(100);
            tcp = new ServerSocket(port, 10, loopback);
            tcp.setSoTimeout(100);
            udpThread = new Thread(this::udpLoop, "truncated-upstream-udp");
            tcpThread = new Thread(this::tcpLoop, "truncated-upstream-tcp");
            udpThread.setDaemon(true);
            tcpThread.setDaemon(true);
            udpThread.start();
            tcpThread.start();
        }

        int port() {
            return udp.getLocalPort();
        }

        boolean tcpReceived() {
            return tcpQuery != null;
        }

        byte[] tcpQuery() {
            return tcpQuery;
        }

        private void udpLoop() {
            byte[] buf = new byte[4096];
            while (!closed) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    udp.receive(p);
                } catch (SocketTimeoutException e) {
                    continue;
                } catch (IOException e) {
                    return;
                }
                byte[] query = Arrays.copyOf(p.getData(), p.getLength());
                int questionEnd = DnsWire.questionEnd(query);
                byte[] question = Arrays.copyOfRange(query, 12, questionEnd);
                byte[] truncated = new byte[12 + question.length];
                DnsWire.put16(truncated, 0, DnsWire.u16(query, 0));
                DnsWire.put16(truncated, 2, 0x8380); // QR=1, TC=1, RD, RA
                DnsWire.put16(truncated, 4, 1);
                DnsWire.put16(truncated, 6, 0);
                System.arraycopy(question, 0, truncated, 12, question.length);
                try {
                    udp.send(new DatagramPacket(truncated, truncated.length,
                            p.getAddress(), p.getPort()));
                } catch (IOException ignored) {
                    // local test
                }
            }
        }

        private void tcpLoop() {
            while (!closed) {
                try (Socket socket = tcp.accept()) {
                    socket.setSoTimeout(2000);
                    InputStream in = socket.getInputStream();
                    byte[] size = readExactly(in, 2);
                    if (size == null) {
                        continue;
                    }
                    int length = ((size[0] & 0xFF) << 8) | (size[1] & 0xFF);
                    byte[] query = readExactly(in, length);
                    if (query == null) {
                        continue;
                    }
                    tcpQuery = query;
                    byte[] response = DnsWire.chainedResponse(query,
                            new String[]{"example.com"}, DnsQuery.TYPE_A,
                            new byte[]{(byte) 93, (byte) 184, (byte) 216, (byte) 34});
                    OutputStream out = socket.getOutputStream();
                    out.write((response.length >>> 8) & 0xFF);
                    out.write(response.length & 0xFF);
                    out.write(response);
                    out.flush();
                } catch (SocketTimeoutException e) {
                    continue;
                } catch (IOException e) {
                    if (!closed) {
                        return;
                    }
                }
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
        public void close() throws IOException {
            closed = true;
            udp.close();
            tcp.close();
        }
    }
}
