package com.sitelock.core;

import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;

/**
 * Merge rule for applying a profile over the current state.
 *
 * <p>Formally, treating "permanent" as infinity, the result is
 * {@code max(what remains now, what the profile offers)}. A profile can only
 * <b>raise</b> the deadline.
 *
 * <pre>
 * | current state        | profile offers | action  |
 * |-----------------------|----------------|---------|
 * | absent                | 8h             | ADDED   |
 * | absent                | permanent      | ADDED   |
 * | temporary, 30min      | 8h             | RAISED  |
 * | temporary, 10h        | 8h             | KEPT    |
 * | temporary, 8h         | 8h             | KEPT    |
 * | temporary, 30min      | permanent      | RAISED  |
 * | permanent             | 8h             | KEPT    |
 * | permanent             | permanent      | KEPT    |
 * </pre>
 *
 * <p>Repeating the operation is safe: applying the same profile N times only produces {@code KEPT},
 * and a weaker profile over a stronger setup does no damage. And a
 * {@code KEPT} decision is <b>not</b> an internal no-op — it returns the same {@link BlockEntry}
 * instance, with the same {@code createdAt} and the same {@code expiresAt}.
 * Rewriting the map from scratch would turn a 10h block into "10h from now".
 *
 * <p>Pure: no I/O and no {@code Clock}. The reference instant goes into the method.
 */
public final class ProfileMerge {

    /** What happened to a domain. */
    public enum Action {
        /** Was not blocked; the profile created the block. */
        ADDED,
        /** Was blocked with a shorter deadline; the profile extended it. */
        RAISED,
        /** The current state was already stronger; nothing changes. */
        KEPT
    }

    /** One decision per profile domain. */
    public record Decision(String domain, Action action, BlockEntry entry) {
    }

    private ProfileMerge() {
    }

    /**
     * Applies the profile over the current block state.
     *
     * @param state current block state; not modified
     * @return one decision per profile domain, including the {@code KEPT} ones, in
     *         alphabetical order
     */
    public static Map<String, Decision> merge(Map<String, BlockEntry> state,
            Profile profile, Instant now) {
        Map<String, Decision> decisions = new TreeMap<>();
        if (profile == null || profile.getEntries() == null) {
            return decisions;
        }
        for (Map.Entry<String, ProfileEntry> pe : profile.getEntries().entrySet()) {
            String domain = pe.getKey();
            Long offeredMillis = pe.getValue() == null ? null : pe.getValue().getDurationMillis();
            BlockEntry current = state == null ? null : state.get(domain);

            // A leftover expired entry (the sweep runs first, but without permission
            // on the hosts file it cleans nothing) counts as absent: re-blocking from a
            // dead deadline does not revive it; it creates a new one.
            if (current != null && current.isExpired(now)) {
                current = null;
            }

            if (current == null) {
                decisions.put(domain, new Decision(domain, Action.ADDED,
                        new BlockEntry(domain, now, offeredMillis == null ? null : now.plusMillis(offeredMillis))));
                continue;
            }
            if (current.getExpiresAt() == null) {
                // Already permanent: nothing is stronger than infinity.
                decisions.put(domain, new Decision(domain, Action.KEPT, current));
                continue;
            }
            long remaining = current.getExpiresAt().toEpochMilli() - now.toEpochMilli();
            if (offeredMillis == null) {
                decisions.put(domain, new Decision(domain, Action.RAISED,
                        new BlockEntry(domain, current.getCreatedAt(), null)));
            } else if (offeredMillis > remaining) {
                decisions.put(domain, new Decision(domain, Action.RAISED,
                        new BlockEntry(domain, current.getCreatedAt(), now.plusMillis(offeredMillis))));
            } else {
                decisions.put(domain, new Decision(domain, Action.KEPT, current));
            }
        }
        return decisions;
    }
}