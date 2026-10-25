package com.sitelock.dns;

import com.sitelock.core.BlockStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// When the serving loop fails midway, the old pool must not be orphaned: the
// watchdog creates a new pool on top while the old threads stay alive (daemon,
// 60s keepalive) consuming memory and a port.
class DnsPoolOrphanTest {

    @TempDir
    Path tmp;

    @Test
    void oldPoolIsShutDownWhenTheLoopFails() throws Exception {
        Path state = DnsTestSupport.blocksFile(tmp, "youtube.com");
        int port = DnsTestSupport.freePort();
        AtomicInteger calls = new AtomicInteger();
        // The bind happens on the server thread; without waiting, the query arrives
        // before the socket is ready and UDP drops it (Windows is slower).
        CountDownLatch listening = new CountDownLatch(1);
        int before = countPoolThreads();

        DnsServer server = new DnsServer(port, InetAddress.getLoopbackAddress(), 100,
                new BlockStore(state), Clock.systemUTC()) {
            @Override
            void awaitAndDispatch(DatagramSocket ds) throws IOException {
                if (calls.incrementAndGet() == 1) {
                    listening.countDown();
                    super.awaitAndDispatch(ds); // receive a real query
                    return;
                }
                // give the pool thread time to handle the query before the failure
                try {
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                throw new IllegalStateException("simulated failure");
            }
        };

        Thread t = new Thread(() -> {
            try {
                server.serveForever();
            } catch (RuntimeException e) {
                // expected: the simulated failure
            }
        }, "falling-server");
        t.setDaemon(true);
        t.start();

        // A real query: creates the pool thread.
        assertTrue(listening.await(5, TimeUnit.SECONDS), "the server never started listening");
        DnsTestSupport.query(port, DnsWire.query(1, "youtube.com", 1), 3000);
        t.join(5000);
        assertFalse(t.isAlive(), "the server should have crashed");

        long deadline = System.currentTimeMillis() + 5000;
        int remaining = countPoolThreads() - before;
        while (remaining > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
            remaining = countPoolThreads() - before;
        }
        assertEquals(0, remaining,
                "the old pool was orphaned: " + remaining + " live thread(s)");
    }

    // UDP pool threads (the TCP pool has another prefix).
    private static int countPoolThreads() {
        int total = 0;
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.getName().matches("sitelock-dns-\\d+")) {
                total++;
            }
        }
        return total;
    }
}
