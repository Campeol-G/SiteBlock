package com.sitelock.cli;

import com.sitelock.blocker.SiteBlocker;
import com.sitelock.core.HostsFileManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * profile apply — merge in memory, one save, one blockAll, rollback if the hosts
 * file fails. The per-domain path died halfway through a 40-domain profile and
 * left partial state; the batch exists so the command is all or nothing.
 */
class ProfileApplyTest {

    @TempDir
    Path tmp;

    // Create once: rewriting would erase the blocks the test just made.
    private Path hosts() throws Exception {
        if (hosts == null) {
            hosts = tmp.resolve("hosts");
            Files.writeString(hosts, "127.0.0.1 localhost\n", StandardCharsets.UTF_8);
        }
        return hosts;
    }

    private Path hosts;

    private String state() {
        return tmp.resolve("state/blocks.json").toString();
    }

    private void block(String... domains) throws Exception {
        assertEquals(0, new BlockCommand(List.of(domains), "4h", hosts().toString(), state()).call());
    }

    private int save(String name) throws Exception {
        return new ProfileSaveCommand(name, false, hosts().toString(), state()).call();
    }

    private int apply(String name) throws Exception {
        return new ProfileApplyCommand(name, hosts().toString(), state(), null).call();
    }

    private String hostsContent() throws Exception {
        return Files.readString(hosts(), StandardCharsets.UTF_8);
    }

    private String blocksJson() throws Exception {
        return Files.readString(Path.of(state()), StandardCharsets.UTF_8);
    }

    // Reads a domain's expiresAt straight from the JSON, without going through the CLI.
    private Instant expiresAt(String domain) throws Exception {
        String json = blocksJson();
        int domainIndex = json.indexOf("\"" + domain + "\"");
        assertTrue(domainIndex >= 0, "domain missing from the state: " + json);
        int expiresKey = json.indexOf("\"expiresAt\"", domainIndex);
        assertTrue(expiresKey > 0, "expiresAt missing: " + json);
        int colon = json.indexOf(':', expiresKey);
        int end = json.indexOf('\n', colon);
        String value = json.substring(colon + 1, end < 0 ? json.length() : end)
                .trim().replace(",", "").replace("\"", "").trim();
        return Instant.parse(value);
    }

    @Test
    void applyBlocksEveryDomainOfTheProfile() throws Exception {
        block("tiktok.com", "youtube.com");
        assertEquals(0, save("work"));
        assertEquals(0, apply("work"));

        String h = hostsContent();
        assertTrue(h.contains("127.0.0.1 tiktok.com"), h);
        assertTrue(h.contains("127.0.0.1 youtube.com"), h);
        assertTrue(blocksJson().contains("tiktok.com"), blocksJson());
    }

    @Test
    void applyIsIdempotent() throws Exception {
        block("tiktok.com", "youtube.com");
        assertEquals(0, save("work"));
        assertEquals(0, apply("work"));
        String before = hostsContent();

        String output = capture(() -> apply("work"));

        assertEquals(before, hostsContent(), "the second apply must not touch the hosts file");
        assertFalse(output.contains("(new)"), "nothing may be created again: " + output);
    }

    @Test
    void applyRestoresHostsWhenEveryDecisionIsKept() throws Exception {
        block("tiktok.com");
        assertEquals(0, save("work"));
        assertEquals(0, apply("work"));

        // Simulates a previous apply that saved the state and died before the hosts file.
        String withoutLine = HostsFileManager.removeDomain(hostsContent(), "tiktok.com");
        Files.writeString(hosts(), withoutLine, StandardCharsets.UTF_8);
        assertFalse(hostsContent().contains("127.0.0.1 tiktok.com"), hostsContent());

        assertEquals(0, apply("work"));

        String after = hostsContent();
        assertTrue(after.contains("127.0.0.1 tiktok.com"),
                "KEPT also needs to repair the hosts file: " + after);
        assertTrue(after.contains("::1 tiktok.com"), after);
    }

    @Test
    void applyNeverShortens() throws Exception {
        assertEquals(0, new BlockCommand(List.of("tiktok.com"), "10h", hosts().toString(), state()).call());
        assertEquals(0, save("short"));
        Instant before = expiresAt("tiktok.com");

        assertEquals(0, apply("short"));
        Instant after = expiresAt("tiktok.com");

        // It must not shorten. It may even stretch a little: the profile stores what
        // was left at save time, and the milliseconds between save and apply push
        // the deadline forward. The rule is max(), not "equal".
        assertFalse(after.isBefore(before),
                "a weaker profile must not shorten a stronger deadline: " + before + " -> " + after);
    }

    @Test
    void applyRaisesShortBlock() throws Exception {
        // The recipe offers 4h; the state it lands in has only 30min left.
        // This is the "temporary, 30min left | 8h | RAISED" table row.
        assertEquals(0, new BlockCommand(List.of("tiktok.com"), "4h", hosts().toString(), state()).call());
        assertEquals(0, save("long"));
        assertEquals(0, new BlockCommand(List.of("tiktok.com"), "30m", hosts().toString(), state()).call());
        Instant before = expiresAt("tiktok.com");

        assertEquals(0, apply("long"));

        Instant after = expiresAt("tiktok.com");
        assertTrue(after.isAfter(before.plusSeconds(3 * 3600)),
                "30min must become the profile's ~4h: " + before + " -> " + after);
    }

    @Test
    void rollbackWhenBlockAllFails() throws Exception {
        // The recipe must offer MORE than the current state, otherwise the decision is
        // KEPT and blockAll is never called — and there is no rollback to prove. It is the
        // same scenario as applyRaisesShortBlock: save with 4h, shrink to 30m.
        assertEquals(0, new BlockCommand(List.of("tiktok.com"), "4h", hosts().toString(), state()).call());
        assertEquals(0, save("work"));
        assertEquals(0, new BlockCommand(List.of("tiktok.com"), "30m", hosts().toString(), state()).call());
        String before = blocksJson();

        SiteBlocker failingBlocker = new SiteBlocker() {
            @Override
            public void block(String domain) {
            }

            @Override
            public void unblock(String domain) {
            }

            @Override
            public void blockAll(List<String> domains) throws IOException {
                throw new IOException("hosts file is read-only");
            }

            @Override
            public void unblockAll(List<String> domains) {
            }
        };

        int exit = new ProfileApplyCommand("work", hosts().toString(), state(), failingBlocker).call();

        assertEquals(1, exit);
        assertEquals(before, blocksJson(),
                "if the hosts file refused, the state must return — otherwise the JSON says blocked and the hosts file lacks the line");
    }

    /**
     * The missing-daemon warning depends on the FINAL STATE, not on the change.
     *
     * <p>With a recipe weaker than the state, the merge returns KEPT — nothing
     * changed. But the time-bound block is still active, and without a daemon it
     * only expires when someone runs another command. Whoever typed the deadline
     * needs to know that, and a hidden warning makes the person believe the
     * deadline enforces itself.
     */
    @Test
    void applyWarnsWhenTheTemporaryBlockWasAlreadyInTheState() throws Exception {
        assertEquals(0, new BlockCommand(List.of("tiktok.com"), "1h",
                hosts().toString(), state()).call());
        assertEquals(0, save("work"));
        // The state becomes stronger than the recipe: the merge will return KEPT.
        assertEquals(0, new BlockCommand(List.of("tiktok.com"), "10h",
                hosts().toString(), state()).call());

        String output = capture(() -> apply("work"));

        assertTrue(output.contains("WARNING:"),
                "an active temporary block without a daemon must warn, even with no change: " + output);
        assertTrue(output.contains("daemon enable"), output);
    }

    @Test
    void applyUnknownProfileFails() throws Exception {
        assertEquals(1, apply("missing"));
    }

    @Test
    void applyWritesSortedJson() throws Exception {
        block("alpha.com", "beta.com", "mike.com", "zeta.com");
        assertEquals(0, save("work"));

        assertEquals(0, apply("work"));

        String json = blocksJson();
        int alpha = json.indexOf("alpha.com");
        int beta = json.indexOf("beta.com");
        int mike = json.indexOf("mike.com");
        int zeta = json.indexOf("zeta.com");
        assertTrue(alpha >= 0 && alpha < beta && beta < mike && mike < zeta,
                "the JSON must always come out sorted: " + json);
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static String capture(ThrowingRunnable r) throws Exception {
        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        try {
            r.run();
        } finally {
            System.setOut(original);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }
}
