package com.sitelock.core;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

/**
 * The profile is compared by value, like the {@link BlockEntry}.
 *
 * <p>The {@code ProfileStore} writes a delta over what is on disk, and the delta
 * needs to know what changed. By identity, only a new {@code Profile} looked
 * changed — which works by accident, not by design: any path that
 * returned an equivalent profile (or that changed a profile in place)
 * wrote a wrong delta.
 */
class ProfileEqualsTest {

    private static final Instant CREATED = Instant.parse("2026-10-15T12:00:00Z");
    private static final Instant UPDATED = Instant.parse("2026-10-15T18:30:00Z");

    private static Profile profile(String... pairs) {
        Map<String, ProfileEntry> entries = new TreeMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            entries.put(pairs[i], new ProfileEntry(Long.valueOf(pairs[i + 1])));
        }
        return new Profile(CREATED, UPDATED, entries);
    }

    @Test
    void twoProfilesWithTheSameContentAreEqual() {
        Profile a = profile("tiktok.com", "3600000");
        Profile b = profile("tiktok.com", "3600000");

        assertNotSame(a, b, "the test only counts if they are two different instances");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void profileWithAnExtraEntryIsNotEqual() {
        Profile a = profile("tiktok.com", "3600000");
        Profile b = profile("tiktok.com", "3600000", "youtube.com", "0");

        assertNotEquals(a, b);
    }

    @Test
    void profileWithADifferentDurationIsNotEqual() {
        Profile a = profile("tiktok.com", "3600000");
        Profile b = profile("tiktok.com", "7200000");

        assertNotEquals(a, b);
    }

    @Test
    void profileUpdatedLaterIsNotEqual() {
        Profile a = profile("tiktok.com", "3600000");
        Profile b = new Profile(CREATED, UPDATED.plusSeconds(60),
                profile("tiktok.com", "3600000").getEntries());

        assertNotEquals(a, b);
    }

    @Test
    void profileCreatedAtAnotherMomentIsNotEqual() {
        Profile a = profile("tiktok.com", "3600000");
        Profile b = new Profile(CREATED.minusSeconds(60), UPDATED,
                profile("tiktok.com", "3600000").getEntries());

        assertNotEquals(a, b);
    }

    @Test
    void profileEntriesAreComparedByValue() {
        ProfileEntry a = new ProfileEntry(3600000L);
        ProfileEntry b = new ProfileEntry(3600000L);

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, new ProfileEntry(7200000L));
    }

    @Test
    void permanentIsNotEqualToTemporary() {
        assertNotEquals(new ProfileEntry(null), new ProfileEntry(0L));
        assertEquals(new ProfileEntry(null), new ProfileEntry(null));
    }
}
