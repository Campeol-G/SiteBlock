package com.sitelock.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * profile unblock — two layers of resistance, and they are not the same thing.
 *
 * <p>The outer one is {@code --force}: without it, any active domain in the
 * profile makes the command refuse everything. The inner one is the typed
 * confirmation, a single one, before touching any domain — 40 "YES" prompts are
 * exactly the friction the feature exists to remove.
 *
 * <p>Permanent blocks enter the confirmation because a permanent block is the
 * strongest commitment the person made, not the weakest. Releasing it silently
 * would be the shortest path to discarding an unlimited lock without typing a
 * key.
 */
class ProfileUnblockTest {

    @TempDir
    Path tmp;

    private Path hosts;

    private Path hosts() throws Exception {
        if (hosts == null) {
            hosts = tmp.resolve("hosts");
            Files.writeString(hosts, "127.0.0.1 localhost\n", StandardCharsets.UTF_8);
        }
        return hosts;
    }

    private String state() {
        return tmp.resolve("state/blocks.json").toString();
    }

    private int block(String duration, String... domains) throws Exception {
        return new BlockCommand(List.of(domains), duration, hosts().toString(), state()).call();
    }

    private int save(String name) throws Exception {
        return new ProfileSaveCommand(name, false, hosts().toString(), state()).call();
    }

    private int unblock(String name, boolean force, String answer) throws Exception {
        return new ProfileUnblockCommand(name, force, hosts().toString(), state(), answer).call();
    }

    private String blocks() throws Exception {
        return Files.readString(Path.of(state()), StandardCharsets.UTF_8);
    }

    private String hostsContent() throws Exception {
        return Files.readString(hosts(), StandardCharsets.UTF_8);
    }

    @Test
    void refusesWithoutForceWhenTemporaryIsActive() throws Exception {
        assertEquals(0, block("4h", "tiktok.com"));
        assertEquals(0, save("work"));
        String before = blocks();

        assertEquals(3, unblock("work", false, "YES"));
        assertEquals(before, blocks());
    }

    @Test
    void refusesWithoutForceWhenOnlyPermanentsAreBlocked() throws Exception {
        assertEquals(0, block(null, "tiktok.com", "youtube.com"));
        assertEquals(0, save("permanent"));
        String before = blocks();

        assertEquals(3, unblock("permanent", false, "YES"));

        assertEquals(before, blocks(),
                "without --force, a profile of unlimited locks does not leave — not even with YES in hand");
    }

    @Test
    void wrongConfirmationUnblocksNothing() throws Exception {
        assertEquals(0, block("4h", "tiktok.com"));
        assertEquals(0, save("work"));
        String before = blocks();

        assertEquals(3, unblock("work", true, ""));

        assertEquals(before, blocks());
        assertTrue(hostsContent().contains("tiktok.com"), "the hosts file must not be touched");
    }

    @Test
    void confirmationUnblocksEveryDomain() throws Exception {
        assertEquals(0, block("4h", "tiktok.com", "youtube.com"));
        assertEquals(0, save("work"));

        assertEquals(0, unblock("work", true, "YES"));

        assertFalse(blocks().contains("tiktok.com"), blocks());
        assertFalse(blocks().contains("youtube.com"), blocks());
        assertFalse(hostsContent().contains("tiktok.com"), hostsContent());
    }

    @Test
    void permanentsAreListedInTheConfirmation() throws Exception {
        assertEquals(0, block(null, "tiktok.com"));
        assertEquals(0, save("permanent"));

        String output = capture(() -> unblock("permanent", true, "YES"));

        assertTrue(output.contains("tiktok.com"), output);
        assertTrue(output.contains("permanent"), output);
    }

    @Test
    void onlyOnePromptForManyDomains() throws Exception {
        String[] many = new String[12];
        for (int i = 0; i < many.length; i++) {
            many[i] = "site" + i + ".com";
        }
        assertEquals(0, block("4h", many));
        assertEquals(0, save("huge"));

        String output = capture(() -> unblock("huge", true, "YES"));

        assertEquals(1, count(output, "type YES"),
                "one question, not one per domain:\n" + output);
    }

    @Test
    void expiredDomainsPassWithoutPrompt() throws Exception {
        // A temporary block that has already expired: the sweep removes it at the
        // start of the command, so it does not even appear in the confirmation.
        assertEquals(0, block("4h", "tiktok.com"));
        assertEquals(0, save("work"));
        poisonExpiration("tiktok.com");

        String output = capture(() -> unblock("work", true, "YES"));

        assertFalse(output.contains("tiktok.com"), "an expired domain does not enter the list: " + output);
        assertFalse(blocks().contains("tiktok.com"));
    }

    @Test
    void emptyTargetListDoesNotAskAnything() throws Exception {
        // A profile with nothing blocked does not exist: save refuses.
        assertEquals(1, save("empty"),
                "with no active block there is nothing to save: save must fail");
        assertEquals(0, block(null, "tiktok.com"));
        assertEquals(0, save("only-tiktok"));

        // The profile only has tiktok, which has already been released: the list is empty.
        assertEquals(0, unblock("only-tiktok", true, "YES"));
        String output = capture(() -> unblock("only-tiktok", false, null));

        assertFalse(output.contains("type YES"), "an empty list asks nothing: " + output);
        assertTrue(output.contains("Nothing to do"),
                "an empty list must say there is nothing to do: " + output);
        assertEquals(0, unblock("only-tiktok", false, null),
                "and without --force either, because there is nothing to lose");
    }

    @Test
    void refusesWhenHostsIsNotWritable() throws Exception {
        // Without this gate the worst outcome is silent: the state is saved, the
        // hosts write fails, and the domain stays stuck with no record anywhere —
        // every command then says "not blocked".
        assertEquals(0, block(null, "tiktok.com"));
        assertEquals(0, save("work"));
        String before = blocks();

        Path unwritable = tmp.resolve("no-parent/hosts");
        int exit = new ProfileUnblockCommand("work", true, unwritable.toString(),
                state(), "YES").call();

        assertEquals(1, exit);
        assertEquals(before, blocks(), "the state must not be deleted if the hosts file refused");
    }

    @Test
    void rollbackWhenUnblockAllFails() throws Exception {
        assertEquals(0, block("4h", "tiktok.com"));
        assertEquals(0, save("work"));
        String before = blocks();

        com.sitelock.blocker.SiteBlocker failingBlocker = new com.sitelock.blocker.SiteBlocker() {
            @Override
            public void block(String domain) {
            }

            @Override
            public void unblock(String domain) {
            }

            @Override
            public void blockAll(List<String> domains) {
            }

            @Override
            public void unblockAll(List<String> domains) throws java.io.IOException {
                throw new java.io.IOException("hosts file is read-only");
            }
        };

        int exit = new ProfileUnblockCommand("work", true, hosts().toString(),
                state(), "YES", failingBlocker).call();

        assertEquals(1, exit);
        assertEquals(before, blocks(),
                "if the hosts file refused, the state must return — otherwise the domain stays stuck");
    }

    @Test
    void unknownProfileFails() throws Exception {
        String output = capture(() -> unblock("missing", true, "YES"));

        assertEquals(1, unblock("missing", true, "YES"));
        assertTrue(output.contains("does not exist"),
                "the refusal must say that the profile does not exist: " + output);
    }

    // Replaces the domain's expiresAt with an instant that has already passed.
    private void poisonExpiration(String domain) throws Exception {
        String json = blocks();
        int domainKey = json.indexOf("\"" + domain + "\"");
        int expires = json.indexOf("\"expiresAt\"", domainKey);
        int colon = json.indexOf(':', expires);
        int end = json.indexOf('\n', colon);
        json = json.substring(0, colon) + ": \"2020-01-01T00:00:00Z\""
                + json.substring(end < 0 ? json.length() : end);
        Files.writeString(Path.of(state()), json, StandardCharsets.UTF_8);
    }

    private static int count(String s, String needle) {
        int n = 0;
        int i = 0;
        while ((i = s.indexOf(needle, i)) >= 0) {
            n++;
            i += needle.length();
        }
        return n;
    }

    // Captures stdout and stderr: the command's refusals go to stderr.
    private static String capture(ThrowingRunnable r) throws Exception {
        PrintStream original = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        try {
            r.run();
        } finally {
            System.setOut(original);
            System.setErr(originalErr);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
