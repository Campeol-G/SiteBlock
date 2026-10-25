package com.sitelock.core;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * How long the daemon sleeps between sweeps.
 *
 * <p>The ceiling ({@code --interval}, 60s by default) ensures a block created in
 * another terminal is noticed within one minute at most, with no file watcher.
 * The 1s floor keeps the daemon from spinning while waiting for a deadline that is
 * 500ms away. And the idle interval is what keeps a daemon from spending the night
 * re-reading the disk every second with nothing expiring.
 */
class SweeperScheduleTest {

    private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");
    private static final Duration MAX = Duration.ofSeconds(60);
    private static final Duration IDLE = Duration.ofMinutes(5);

    private static Duration sleep(Map<String, BlockEntry> state) {
        return SweeperSchedule.nextSleep(state, NOW, MAX, IDLE);
    }

    // State with a temporary block expiring in the given number of seconds.
    private static Map<String, BlockEntry> expiringIn(long seconds) {
        return expiringInMillis(seconds * 1000L);
    }

    // State with a temporary block expiring in the given number of milliseconds.
    private static Map<String, BlockEntry> expiringInMillis(long millis) {
        Map<String, BlockEntry> state = new TreeMap<>();
        state.put("tiktok.com", new BlockEntry("tiktok.com", NOW.minusSeconds(60),
                NOW.plusMillis(millis)));
        return state;
    }

    @Test
    void noTemporariesSleepsTheIdleInterval() {
        assertEquals(IDLE, sleep(new TreeMap<>()));
    }

    @Test
    void permanentOnlySleepsTheIdleInterval() {
        Map<String, BlockEntry> state = new TreeMap<>();
        state.put("tiktok.com", new BlockEntry("tiktok.com", NOW, null));
        assertEquals(IDLE, sleep(state));
    }

    @Test
    void expiringSoonSleepsUntilThen() {
        assertEquals(Duration.ofSeconds(30), sleep(expiringIn(30)));
    }

    @Test
    void farExpiryCapsAtMaxInterval() {
        assertEquals(MAX, sleep(expiringIn(600)));
    }

    @Test
    void nearExpiryFloorsAtOneSecond() {
        assertEquals(Duration.ofSeconds(1), sleep(expiringInMillis(500)),
                "a 500ms sleep would round down and the daemon would wake up early");
    }

    @Test
    void expiryExactlyNowCountsAsExpired() {
        // isExpired(now) is true when the deadline equals now: this iteration's
        // sweep already resolved it, so there is no active temporary block.
        assertEquals(IDLE, sleep(expiringInMillis(0)));
    }

    @Test
    void aFiredDeadlineNeverProducesZeroOrNegativeSleep() {
        assertEquals(IDLE, sleep(expiringInMillis(-5000)));
    }

    @Test
    void exactMaxIntervalIsUnchanged() {
        assertEquals(MAX, sleep(expiringIn(60)));
    }

    @Test
    void picksTheEarliestOfSeveral() {
        Map<String, BlockEntry> state = expiringIn(45);
        state.put("youtube.com", new BlockEntry("youtube.com", NOW, NOW.plusSeconds(20)));
        assertEquals(Duration.ofSeconds(20), sleep(state));
    }

    @Test
    void alreadyExpiredEntriesDoNotShortenTheSleep() {
        Map<String, BlockEntry> state = expiringIn(600);
        state.put("expired.com", new BlockEntry("expired.com", NOW.minusSeconds(7200),
                NOW.minusSeconds(3600)));
        assertEquals(MAX, sleep(state),
                "the sweep runs first and removes the expired ones; none of them may pull the sleep to 1s");
    }

    @Test
    void customIdleIntervalIsHonoured() {
        assertEquals(Duration.ofMinutes(30),
                SweeperSchedule.nextSleep(new TreeMap<>(), NOW, MAX, Duration.ofMinutes(30)));
    }
}