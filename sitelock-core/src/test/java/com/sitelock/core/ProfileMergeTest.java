package com.sitelock.core;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The profile rule: it can only <b>raise</b> the deadline, never shorten it.
 *
 * <p>That is what makes "applying the same profile 40 times a day" safe. Without this
 * rule, the second application would turn a 10h block into "10h from
 * now" — shortening it on every repetition, the opposite of what the person
 * asked for when typing --for 10h.
 */
class ProfileMergeTest {

    private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");
    private static final Instant CREATED = Instant.parse("2026-10-15T08:00:00Z");

    private static long hours(long h) {
        return h * 3600_000L;
    }

    // State with one domain, temporary (remainingMillis left) or permanent.
    private static Map<String, BlockEntry> current(String domain, Long remainingMillis) {
        Map<String, BlockEntry> state = new TreeMap<>();
        if (remainingMillis == null) {
            state.put(domain, new BlockEntry(domain, CREATED, null));
        } else {
            state.put(domain, new BlockEntry(domain, CREATED, NOW.plusMillis(remainingMillis)));
        }
        return state;
    }

    // Profile offering offerMillis for the domain, or permanent when null.
    private static Profile offering(String domain, Long offerMillis) {
        Map<String, ProfileEntry> entries = new TreeMap<>();
        entries.put(domain, new ProfileEntry(offerMillis));
        return new Profile(CREATED, CREATED, entries);
    }

    @Test
    void absentPlusTemporaryIsAdded() {
        Map<String, ProfileMerge.Decision> out =
                ProfileMerge.merge(new TreeMap<>(), offering("tiktok.com", hours(8)), NOW);
        assertEquals(ProfileMerge.Action.ADDED, out.get("tiktok.com").action());
        assertEquals(NOW.plusMillis(hours(8)), out.get("tiktok.com").entry().getExpiresAt());
    }

    @Test
    void absentPlusPermanentIsAdded() {
        Map<String, ProfileMerge.Decision> out =
                ProfileMerge.merge(new TreeMap<>(), offering("tiktok.com", null), NOW);
        assertEquals(ProfileMerge.Action.ADDED, out.get("tiktok.com").action());
        assertNull(out.get("tiktok.com").entry().getExpiresAt());
    }

    @Test
    void temporaryRaised() {
        Map<String, ProfileMerge.Decision> out = ProfileMerge.merge(
                current("tiktok.com", 30 * 60_000L), offering("tiktok.com", hours(8)), NOW);
        assertEquals(ProfileMerge.Action.RAISED, out.get("tiktok.com").action());
        assertEquals(NOW.plusMillis(hours(8)), out.get("tiktok.com").entry().getExpiresAt());
    }

    @Test
    void temporaryLongerIsKept() {
        Map<String, ProfileMerge.Decision> out = ProfileMerge.merge(
                current("tiktok.com", hours(10)), offering("tiktok.com", hours(8)), NOW);
        assertEquals(ProfileMerge.Action.KEPT, out.get("tiktok.com").action());
    }

    @Test
    void temporaryEqualIsKept() {
        Map<String, ProfileMerge.Decision> out = ProfileMerge.merge(
                current("tiktok.com", hours(8)), offering("tiktok.com", hours(8)), NOW);
        assertEquals(ProfileMerge.Action.KEPT, out.get("tiktok.com").action());
    }

    @Test
    void temporaryRaisedToPermanent() {
        Map<String, ProfileMerge.Decision> out = ProfileMerge.merge(
                current("tiktok.com", 30 * 60_000L), offering("tiktok.com", null), NOW);
        assertEquals(ProfileMerge.Action.RAISED, out.get("tiktok.com").action());
        assertNull(out.get("tiktok.com").entry().getExpiresAt());
    }

    @Test
    void permanentStaysPermanentWhenProfileOffersTime() {
        Map<String, ProfileMerge.Decision> out = ProfileMerge.merge(
                current("tiktok.com", null), offering("tiktok.com", hours(8)), NOW);
        assertEquals(ProfileMerge.Action.KEPT, out.get("tiktok.com").action());
    }

    @Test
    void permanentStaysPermanentWithPermanentProfile() {
        Map<String, ProfileMerge.Decision> out = ProfileMerge.merge(
                current("tiktok.com", null), offering("tiktok.com", null), NOW);
        assertEquals(ProfileMerge.Action.KEPT, out.get("tiktok.com").action());
    }

    @Test
    void keptIsTheSameInstance() {
        Map<String, BlockEntry> state = current("tiktok.com", hours(10));
        BlockEntry original = state.get("tiktok.com");

        Map<String, ProfileMerge.Decision> out =
                ProfileMerge.merge(state, offering("tiktok.com", hours(8)), NOW);

        assertSame(original, out.get("tiktok.com").entry(),
                "a KEPT decision cannot rewrite the BlockEntry: created/expires would change the deadline");
        assertEquals(original.getCreatedAt(), out.get("tiktok.com").entry().getCreatedAt());
        assertEquals(original.getExpiresAt(), out.get("tiktok.com").entry().getExpiresAt());
    }

    @Test
    void raisedKeepsOriginalCreatedAt() {
        Map<String, BlockEntry> state = current("tiktok.com", 30 * 60_000L);

        Map<String, ProfileMerge.Decision> out =
                ProfileMerge.merge(state, offering("tiktok.com", hours(8)), NOW);

        assertEquals(CREATED, out.get("tiktok.com").entry().getCreatedAt(),
                "the block started at 08:00 and still started at 08:00");
    }

    @Test
    void expiredEntryCountsAsAbsent() {
        // The sweep runs before the apply and already removed the expired ones; if any
        // survived (no permission on the hosts file), it is treated as absent —
        // otherwise the profile would revive a deadline that already expired.
        Map<String, BlockEntry> state = current("tiktok.com", -3600_000L);

        Map<String, ProfileMerge.Decision> out =
                ProfileMerge.merge(state, offering("tiktok.com", hours(8)), NOW);

        assertEquals(ProfileMerge.Action.ADDED, out.get("tiktok.com").action());
        assertEquals(NOW.plusMillis(hours(8)), out.get("tiktok.com").entry().getExpiresAt());
    }

    @Test
    void applyingTwiceIsIdempotent() {
        Profile profile = offering("tiktok.com", hours(8));
        Map<String, BlockEntry> state = new TreeMap<>();

        Map<String, ProfileMerge.Decision> first = ProfileMerge.merge(state, profile, NOW);
        state = apply(state, first);

        Map<String, ProfileMerge.Decision> second = ProfileMerge.merge(state, profile, NOW);
        state = apply(state, second);

        assertEquals(ProfileMerge.Action.KEPT, second.get("tiktok.com").action());
        assertEquals(first.get("tiktok.com").entry(), second.get("tiktok.com").entry(),
                "the second application must not change the deadline again");
    }

    @Test
    void decisionsCoverEveryDomainOfTheProfile() {
        Map<String, ProfileEntry> entries = new TreeMap<>();
        entries.put("a.com", new ProfileEntry(hours(1)));
        entries.put("b.com", new ProfileEntry(null));
        Profile profile = new Profile(CREATED, CREATED, entries);

        Map<String, BlockEntry> state = current("b.com", 5 * 3600_000L);

        Map<String, ProfileMerge.Decision> out = ProfileMerge.merge(state, profile, NOW);

        assertEquals(2, out.size(), "KEPT also comes back in the map — the caller decides what goes to the hosts");
        assertEquals(ProfileMerge.Action.ADDED, out.get("a.com").action());
        assertEquals(ProfileMerge.Action.RAISED, out.get("b.com").action());
    }

    // Simulates what the command does: applies the decisions to the state map.
    private static Map<String, BlockEntry> apply(Map<String, BlockEntry> state,
            Map<String, ProfileMerge.Decision> decisions) {
        Map<String, BlockEntry> next = new HashMap<>(state);
        decisions.forEach((domain, d) -> next.put(domain, d.entry()));
        return next;
    }
}