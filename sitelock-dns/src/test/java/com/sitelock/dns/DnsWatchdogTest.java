package com.sitelock.dns;

import com.sitelock.core.BlockStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.nio.file.Path;
import java.time.Clock;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// The server watchdog: the first failure must take the process down. Previously
// the watchdog swallowed the RuntimeException, slept 1s, and retried forever: the
// process lived without listening and dns start printed "DNS server started".
class DnsWatchdogTest {

    @TempDir
    Path tmp;

    @Test
    @Timeout(20)
    void firstFailureTakesDownTheServer() throws Exception {
        Path state = DnsTestSupport.blocksFile(tmp, "youtube.com");
        DnsServer server = new DnsServer(15377, InetAddress.getLoopbackAddress(), 100,
                new BlockStore(state), Clock.systemUTC()) {
            @Override
            public void serveForever() {
                throw new IllegalStateException("simulated failure");
            }
        };

        DnsServerException failure = assertThrows(DnsServerException.class, server::runWithWatchdog);
        assertTrue(failure.getMessage().contains("simulated failure"),
                "the message must say what went wrong: " + failure.getMessage());
    }

    @Test
    @Timeout(20)
    void bindFailureAlsoTakesDownTheServer() {
        DnsServer server = new DnsServer(15378, InetAddress.getLoopbackAddress(), 100,
                new BlockStore(tmp.resolve("missing.json")), Clock.systemUTC()) {
            @Override
            public void serveForever() {
                throw new DnsServerException("port 53 is already in use");
            }
        };

        DnsServerException failure = assertThrows(DnsServerException.class, server::runWithWatchdog);
        assertTrue(failure.getMessage().contains("port 53 is already in use"),
                "the message must say what went wrong: " + failure.getMessage());
    }
}
