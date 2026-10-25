package com.sitelock.dns;

import com.sitelock.core.BlockEntry;
import com.sitelock.core.BlockStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// CNAME aliases: the queried name is free but points at a blocked name.
// The defect: the upstream returned the CNAME chain and the target's real IP in
// the same response, and the server relayed it all — the client connected to the
// blocked IP.
class DnsAliasCnameTest {

    // IP the upstream hands out for the blocked target (it must not leak).
    private static final byte[] REAL_IP = {1, 2, 3, 4};

    @TempDir
    Path tmp;

    // Starts the server pointed at the fake upstream, with the given blocks.
    private DnsServer startServer(FakeUpstream upstream, String... blocked) throws Exception {
        Path state = DnsTestSupport.blocksFile(tmp, blocked);
        return startServer(upstream, new BlockStore(state), blocked[0]);
    }

    // Starts the server with its own BlockStore (to count reads).
    private DnsServer startServer(FakeUpstream upstream, BlockStore store, String blocked)
            throws Exception {
        int port = DnsTestSupport.freePort();
        DnsServer server = new DnsServer(port, upstream.address(), upstream.port(), 2000,
                store, Clock.systemUTC(), List.of());
        Thread t = new Thread(server::serveForever, "test-server");
        t.setDaemon(true);
        t.start();
        DnsTestSupport.awaitListening(port, blocked, 5000);
        return server;
    }

    // Upstream that answers with the given alias chain.
    private static FakeUpstream upstreamWithChain(String[] chain, int finalType,
                                                  byte[] finalRdata) throws Exception {
        FakeUpstream upstream = new FakeUpstream();
        upstream.respondWith(query ->
                DnsWire.chainedResponse(query, chain, finalType, finalRdata));
        upstream.start();
        return upstream;
    }

    // True if the IP appears in any byte of the response.
    private static boolean containsIp(byte[] response, byte[] ip) {
        for (int i = 0; i + ip.length <= response.length; i++) {
            boolean equal = true;
            for (int j = 0; j < ip.length; j++) {
                if (response[i + j] != ip[j]) {
                    equal = false;
                    break;
                }
            }
            if (equal) {
                return true;
            }
        }
        return false;
    }

    // The original defect: "www.microsoft.com" is free, with a CNAME to the blocked
    // "www.microsoft.com-c-3.edgekey.net". The client received the real IP.
    @Test
    void blockedAliasGetsSinkhole() throws Exception {
        FakeUpstream upstream = upstreamWithChain(
                new String[]{"alias.test", "target.test"}, DnsQuery.TYPE_A, REAL_IP);
        DnsServer server = startServer(upstream, "target.test");
        try {
            byte[] raw = DnsTestSupport.query(server.getPort(),
                    DnsWire.query(0x1234, "alias.test", DnsQuery.TYPE_A), 3000);
            assertNotNull(raw, "the server did not respond");
            DnsWire.Response r = DnsWire.parse(raw);
            assertEquals(0, r.rcode(), "a blocked alias must not produce SERVFAIL");
            assertEquals(1, r.anCount(), "a blocked alias gets the sinkhole");
            assertArrayEquals(new byte[]{0, 0, 0, 0}, r.firstRdata(),
                    "the IPv4 sinkhole is 0.0.0.0");
            assertFalse(containsIp(raw, REAL_IP),
                    "the blocked name's real IP must not reach the client");
        } finally {
            server.stop();
            upstream.close();
        }
    }

    // A free name without an alias is still forwarded: the alias fix must not
    // break the normal path.
    @Test
    void freeNameWithoutCnameIsStillForwarded() throws Exception {
        FakeUpstream upstream = new FakeUpstream();
        upstream.start();
        DnsServer server = startServer(upstream, "target.test");
        try {
            byte[] raw = DnsTestSupport.query(server.getPort(),
                    DnsWire.query(0x2222, "free.test", DnsQuery.TYPE_A), 3000);
            assertNotNull(raw, "the server did not respond");
            DnsWire.Response r = DnsWire.parse(raw);
            assertEquals(0x2222, r.id(), "the response must echo the client ID");
            assertEquals(0, r.rcode());
            assertEquals(1, r.anCount(), "the upstream response must be relayed");
            assertArrayEquals(new byte[]{(byte) 93, (byte) 184, (byte) 216, (byte) 34},
                    r.firstRdata(), "the upstream response must arrive intact");
        } finally {
            server.stop();
            upstream.close();
        }
    }

    // Chain with two links and the block on the second link: the real IP must not leak.
    @Test
    void twoLinkChainWithBlockOnSecondLinkGetsSinkhole() throws Exception {
        FakeUpstream upstream = upstreamWithChain(
                new String[]{"alias.test", "middle.test", "target.test"}, DnsQuery.TYPE_A, REAL_IP);
        DnsServer server = startServer(upstream, "target.test");
        try {
            byte[] raw = DnsTestSupport.query(server.getPort(),
                    DnsWire.query(0x3456, "alias.test", DnsQuery.TYPE_A), 3000);
            assertNotNull(raw, "the server did not respond");
            DnsWire.Response r = DnsWire.parse(raw);
            assertEquals(0, r.rcode());
            assertEquals(1, r.anCount(), "a chain with a blocked link gets the sinkhole");
            assertArrayEquals(new byte[]{0, 0, 0, 0}, r.firstRdata(),
                    "the IPv4 sinkhole is 0.0.0.0");
            assertFalse(containsIp(raw, REAL_IP),
                    "the blocked name's real IP must not reach the client");
        } finally {
            server.stop();
            upstream.close();
        }
    }

    // Chain with two links, none blocked: the response passes through.
    @Test
    void twoLinkChainWithNoBlockPassesThrough() throws Exception {
        FakeUpstream upstream = upstreamWithChain(
                new String[]{"alias.test", "middle.test", "target.test"}, DnsQuery.TYPE_A, REAL_IP);
        DnsServer server = startServer(upstream, "other.test");
        try {
            byte[] raw = DnsTestSupport.query(server.getPort(),
                    DnsWire.query(0x4321, "alias.test", DnsQuery.TYPE_A), 3000);
            assertNotNull(raw, "the server did not respond");
            DnsWire.Response r = DnsWire.parse(raw);
            assertEquals(0, r.rcode());
            assertEquals(3, r.anCount(), "the whole chain must be relayed");
            assertArrayEquals(REAL_IP, r.answers().get(2).rdata(),
                    "the final record must arrive intact");
        } finally {
            server.stop();
            upstream.close();
        }
    }

    // Response without an answer section (NXDOMAIN): nothing to inspect, relay it.
    @Test
    void responseWithoutAnswerSectionIsRelayed() throws Exception {
        FakeUpstream upstream = new FakeUpstream();
        upstream.mode(FakeUpstream.Mode.NO_ANSWER);
        upstream.start();
        DnsServer server = startServer(upstream, "target.test");
        try {
            byte[] raw = DnsTestSupport.query(server.getPort(),
                    DnsWire.query(0x3333, "free.test", DnsQuery.TYPE_A), 3000);
            assertNotNull(raw, "the server did not respond");
            DnsWire.Response r = DnsWire.parse(raw);
            assertEquals(0x3333, r.id(), "the response must echo the client ID");
            assertEquals(3, r.rcode(), "the upstream NXDOMAIN must be relayed");
            assertEquals(0, r.anCount());
        } finally {
            server.stop();
            upstream.close();
        }
    }

    // The actual block still applies: a blocked name never goes upstream.
    @Test
    void upstreamDoesNotReceiveQueriesForBlockedNames() throws Exception {
        FakeUpstream upstream = new FakeUpstream();
        upstream.start();
        DnsServer server = startServer(upstream, "target.test");
        try {
            byte[] raw = DnsTestSupport.query(server.getPort(),
                    DnsWire.query(0x4444, "target.test", DnsQuery.TYPE_A), 3000);
            assertNotNull(raw, "the server did not respond");
            DnsWire.Response r = DnsWire.parse(raw);
            assertEquals(1, r.anCount(), "the blocked name gets the sinkhole");
            assertArrayEquals(new byte[]{0, 0, 0, 0}, r.firstRdata(),
                    "the IPv4 sinkhole is 0.0.0.0");
            Thread.sleep(300); // give the upstream time to receive it, if the server sends it
            assertTrue(upstream.receivedQueries().isEmpty(),
                    "a blocked name must not be queried upstream");
        } finally {
            server.stop();
            upstream.close();
        }
    }

    // The alias sinkhole must respect the requested type (AAAA → ::).
    @Test
    void blockedAliasWithAaaaGetsIpv6Sinkhole() throws Exception {
        FakeUpstream upstream = upstreamWithChain(
                new String[]{"alias.test", "target.test"}, DnsQuery.TYPE_AAAA, new byte[16]);
        DnsServer server = startServer(upstream, "target.test");
        try {
            byte[] raw = DnsTestSupport.query(server.getPort(),
                    DnsWire.query(0x5555, "alias.test", DnsQuery.TYPE_AAAA), 3000);
            assertNotNull(raw, "the server did not respond");
            DnsWire.Response r = DnsWire.parse(raw);
            assertEquals(0, r.rcode());
            assertEquals(1, r.anCount());
            assertArrayEquals(new byte[16], r.firstRdata(), "the IPv6 sinkhole is ::");
        } finally {
            server.stop();
            upstream.close();
        }
    }

    // Type other than A/AAAA on a blocked alias: NODATA, as for a blocked name.
    @Test
    void blockedAliasWithNonAaaaTypeAnswersNodata() throws Exception {
        FakeUpstream upstream = upstreamWithChain(
                new String[]{"alias.test", "target.test"}, 0, new byte[0]);
        DnsServer server = startServer(upstream, "target.test");
        try {
            byte[] raw = DnsTestSupport.query(server.getPort(),
                    DnsWire.query(0x6666, "alias.test", 16 /*TXT*/), 3000);
            assertNotNull(raw, "the server did not respond");
            DnsWire.Response r = DnsWire.parse(raw);
            assertEquals(0, r.rcode(), "NODATA is not SERVFAIL");
            assertEquals(0, r.anCount(), "NODATA: no answer for a type other than A/AAAA");
        } finally {
            server.stop();
            upstream.close();
        }
    }

    // Subdomain of the blocked name in the chain: block it too (dotted suffix).
    @Test
    void aliasToSubdomainOfBlockedNameGetsSinkhole() throws Exception {
        FakeUpstream upstream = upstreamWithChain(
                new String[]{"alias.test", "cdn.target.test"}, DnsQuery.TYPE_A, REAL_IP);
        DnsServer server = startServer(upstream, "target.test");
        try {
            byte[] raw = DnsTestSupport.query(server.getPort(),
                    DnsWire.query(0x7777, "alias.test", DnsQuery.TYPE_A), 3000);
            assertNotNull(raw, "the server did not respond");
            DnsWire.Response r = DnsWire.parse(raw);
            assertEquals(1, r.anCount(), "the subdomain of the blocked name gets the sinkhole");
            assertArrayEquals(new byte[]{0, 0, 0, 0}, r.firstRdata());
            assertFalse(containsIp(raw, REAL_IP),
                    "the blocked name's real IP must not reach the client");
        } finally {
            server.stop();
            upstream.close();
        }
    }

    // The alias check must not reread blocks.json: the query already read the
    // blocklist, and the chain is checked against it.
    @Test
    void aliasCheckDoesNotRereadBlocksJson() throws Exception {
        FakeUpstream upstream = upstreamWithChain(
                new String[]{"alias.test", "target.test"}, DnsQuery.TYPE_A, REAL_IP);
        Path state = DnsTestSupport.blocksFile(tmp, "target.test");
        CountingBlockStore store = new CountingBlockStore(state);
        DnsServer server = startServer(upstream, store, "target.test");
        try {
            store.reset();
            byte[] raw = DnsTestSupport.query(server.getPort(),
                    DnsWire.query(0x9999, "alias.test", DnsQuery.TYPE_A), 3000);
            assertNotNull(raw, "the server did not respond");
            assertEquals(1, store.reads(),
                    "blocks.json may be read once per query — the alias check must use "
                            + "the same list");
        } finally {
            server.stop();
            upstream.close();
        }
    }

    // BlockStore that counts reads of the state file.
    private static final class CountingBlockStore extends BlockStore {

        private final AtomicInteger reads = new AtomicInteger();

        CountingBlockStore(Path file) {
            super(file);
        }

        @Override
        public Map<String, BlockEntry> load() throws IOException {
            reads.incrementAndGet();
            return super.load();
        }

        int reads() {
            return reads.get();
        }

        void reset() {
            reads.set(0);
        }
    }
}
