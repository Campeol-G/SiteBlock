package com.sitelock.dns;

import com.sitelock.core.BlockStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * If the TCP bind fails, the UDP socket that is already open must not keep
 * listening or holding the port: the next start would say "port in use" with no
 * server actually serving.
 */
class DnsServerBindLeakTest {

    @TempDir
    Path tmp;

    @Test
    void tcpBindFailureClosesTheUdpSocket() throws Exception {
        int port = DnsTestSupport.freePort();
        Path state = DnsTestSupport.blocksFile(tmp, "blocked.com");

        try (ServerSocket tcpOccupier = new ServerSocket(port)) {
            DnsServer server = new DnsServer(port, InetAddress.getLoopbackAddress(), 500,
                    new BlockStore(state), Clock.systemUTC(), List.of());

            assertThrows(DnsServerException.class, server::serveForever);

            try (DatagramSocket udp = new DatagramSocket(port)) {
                assertTrue(udp.isBound(),
                        "the failed server's UDP socket should have been closed");
            }
        }
    }
}
