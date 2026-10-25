package com.sitelock.dns;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

// Shared support for the socket tests: a free port, a UDP query, waiting for the
// server to be up, and a temporary blocks file. Nothing here touches the internet
// or user files: everything lives in a temporary directory and on loopback.
final class DnsTestSupport {

    private static final int BASE_PORT = 15300;

    private DnsTestSupport() {
    }

    // A port free in both protocol spaces (UDP and TCP). The server uses the same
    // port for both, so it must be free on both sides at the same time.
    static int freePort() {
        for (int i = 0; i < 200; i++) {
            int port = BASE_PORT + (i * 7) % 2000;
            try (DatagramSocket udp = new DatagramSocket(port);
                 ServerSocket tcp = new ServerSocket(port)) {
                return port;
            } catch (IOException e) {
                // in use: try the next one
            }
        }
        throw new IllegalStateException("could not find a free port for the test");
    }

    // Sends a UDP query to the server and returns the response (or null).
    static byte[] query(int port, byte[] query, int timeoutMs) throws IOException {
        try (DatagramSocket s = new DatagramSocket()) {
            s.setSoTimeout(timeoutMs);
            s.send(new DatagramPacket(query, query.length,
                    InetAddress.getLoopbackAddress(), port));
            byte[] buf = new byte[8192];
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            s.receive(p);
            return Arrays.copyOf(p.getData(), p.getLength());
        }
    }

    // Waits for the server to respond (uses a blocked name: local, fast response).
    static void awaitListening(int port, String blockedName, int timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        byte[] probe = DnsWire.query(0x4242, blockedName, DnsQuery.TYPE_A);
        while (System.currentTimeMillis() < deadline) {
            try {
                if (query(port, probe, 500) != null) {
                    return;
                }
            } catch (IOException ignored) {
                // not up yet
            }
            Thread.sleep(50);
        }
        throw new IllegalStateException("the server did not respond on port " + port);
    }

    // Waits for the server log to show the requested marker.
    static void awaitMarker(ByteArrayOutputStream log, String marker, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (log.toString(StandardCharsets.UTF_8).contains(marker)) {
                return;
            }
            Thread.sleep(25);
        }
        throw new IllegalStateException("marker '" + marker + "' did not appear in the log");
    }

    // Creates a blocks.json with the given domains.
    static Path blocksFile(Path dir, String... domains) throws IOException {
        Files.createDirectories(dir);
        Path file = dir.resolve("blocks.json");
        StringBuilder json = new StringBuilder("{");
        Instant created = Instant.parse("2026-01-01T00:00:00Z");
        for (int i = 0; i < domains.length; i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append('"').append(domains[i]).append("\":{\"domain\":\"")
                    .append(domains[i]).append("\",\"createdAt\":\"")
                    .append(created).append("\",\"expiresAt\":null}");
        }
        json.append('}');
        Files.writeString(file, json.toString(), StandardCharsets.UTF_8);
        return file;
    }

    // Temporary file names used in tests.
    static List<String> names(List<String> base, String... extras) {
        List<String> all = new ArrayList<>(base);
        all.addAll(Arrays.asList(extras));
        return all;
    }
}
