package com.sitelock.dns;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Random;

// Parser fuzzing: no packet may take the server down. Query parsing was already
// proven with 400 thousand random packets (zero AIOOBE); the upstream response is
// not trustworthy.
class DnsPacketParserFuzzTest {

    // Packets per round (400 thousand).
    private static final int PACKETS = 400_000;

    // Fixed seed: a failure here must be reproducible.
    private static final long SEED = 20261021L;

    @Test
    void randomPacketNeverBreaksTheQueryParser() {
        Random r = new Random(SEED);
        for (int i = 0; i < PACKETS; i++) {
            byte[] packet = new byte[1 + r.nextInt(600)];
            r.nextBytes(packet);
            consume(packet);
        }
    }

    // Valid response (CNAME chain, compressed names) with swapped bytes and cuts:
    // this is the path the answer-section reader really takes — it follows
    // compression pointers, which query parsing rejects on purpose.
    @Test
    void mutatedResponseNeverBreaksTheAnswerReader() {
        Random r = new Random(SEED + 1);
        byte[] base = validResponse();
        for (int i = 0; i < PACKETS; i++) {
            byte[] packet = base.clone();
            int mutations = 1 + r.nextInt(4);
            for (int m = 0; m < mutations; m++) {
                int pos = r.nextInt(packet.length);
                if (r.nextInt(3) == 0) {
                    // compression pointer aimed at nothing
                    packet[pos] = (byte) 0xC0;
                    if (pos + 1 < packet.length) {
                        packet[pos + 1] = (byte) r.nextInt(256);
                    }
                } else {
                    packet[pos] = (byte) r.nextInt(256);
                }
            }
            if (r.nextInt(4) == 0) {
                packet = Arrays.copyOf(packet, r.nextInt(packet.length + 1));
            }
            consume(packet);
        }
    }

    // Runs the packet through both readers: the query parser and the answer-section
    // reader. Neither may throw a runtime exception (AIOOBE, NPE, loop).
    private static void consume(byte[] packet) {
        try {
            DnsPacketParser.parse(packet, packet.length);
        } catch (DnsParseException e) {
            // malformed packet: expected result
        } catch (RuntimeException e) {
            throw new AssertionError("the query parser blew up: " + e, e);
        }
        try {
            DnsPacketParser.answerSectionNames(packet, packet.length);
        } catch (RuntimeException e) {
            throw new AssertionError("the answer-section reader blew up: " + e, e);
        }
    }

    // Response with a CNAME chain, built with compressed names.
    private static byte[] validResponse() {
        byte[] query = DnsWire.query(0x1234, "alias.test", DnsQuery.TYPE_A);
        return DnsWire.chainedResponse(query,
                new String[]{"alias.test", "middle.test", "target.test"},
                DnsQuery.TYPE_A, new byte[]{1, 2, 3, 4});
    }
}
