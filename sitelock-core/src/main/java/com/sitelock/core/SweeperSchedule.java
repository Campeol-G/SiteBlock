package com.sitelock.core;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * How long the expiration daemon sleeps between sweeps.
 *
 * <p>Three rules fight each other, and the design is the balance between them:
 *
 * <ul>
 *   <li><b>Floor of 1s</b> — a deadline that expires in 500ms can never become
 *       a 500ms sleep: the OS rounds down and the daemon would wake up early.
 *       It also never produces a zero or negative sleep.</li>
 *   <li><b>Ceiling ({@code --interval}, 60s)</b> — this is what ensures a block
 *       created in another terminal is noticed within one minute at most, without
 *       a file watcher.</li>
 *   <li><b>Idle interval</b> — with no active temporary block, sleeping 1s means
 *       re-reading the disk every second all night long just to find nothing.</li>
 * </ul>
 *
 * <p>Pure: no I/O and no {@code Clock}. The reference instant goes into the
 * method.
 */
public final class SweeperSchedule {

    /** Sleep floor: never less than this, to avoid spinning. */
    public static final Duration MIN_SLEEP = Duration.ofSeconds(1);

    private SweeperSchedule() {
    }

    /**
     * @param state       current block state (not modified)
     * @param now         reference instant
     * @param maxInterval sleep ceiling ({@code --interval})
     * @param idleInterval sleep when there is no active temporary block
     * @return how long to sleep before the next sweep
     */
    public static Duration nextSleep(Map<String, BlockEntry> state, Instant now,
            Duration maxInterval, Duration idleInterval) {
        Instant next = nextActiveExpiry(state, now);
        if (next == null) {
            return idleInterval;
        }
        Duration remaining = Duration.between(now, next);
        if (remaining.compareTo(MIN_SLEEP) < 0) {
            return MIN_SLEEP;
        }
        if (remaining.compareTo(maxInterval) > 0) {
            return maxInterval;
        }
        return remaining;
    }

    // The earliest expiresAt still in the future.
    // WHY: expired ones are ignored on purpose — sweepExpired() runs before the
    // sleep, so one that is left means the hosts file could not be written; without the
    // filter it would pull the sleep down to the 1s floor.
    static Instant nextActiveExpiry(Map<String, BlockEntry> state, Instant now) {
        if (state == null || state.isEmpty()) {
            return null;
        }
        Instant best = null;
        for (BlockEntry entry : state.values()) {
            if (entry == null || entry.getExpiresAt() == null || entry.isExpired(now)) {
                continue;
            }
            if (best == null || entry.getExpiresAt().isBefore(best)) {
                best = entry.getExpiresAt();
            }
        }
        return best;
    }
}