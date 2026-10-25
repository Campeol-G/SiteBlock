package com.sitelock.core;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/**
 * A domain inside a profile.
 *
 * <p>{@code durationMillis} is a {@code Long} because {@code null} means
 * "permanent" — the same contract as {@link BlockEntry#expiresAt}. Storing an
 * ISO duration string would not help: {@code Duration} serializes in Jackson
 * as decimal seconds ("28800.000000000"), which is ambiguous to read.
 *
 * <p>It is the <b>remaining duration at save time</b>, never the original
 * {@code expiresAt}: a profile is a recipe, not a history. Storing the instant
 * would turn a 6-month profile into "already expired".
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ProfileEntry {

    private Long durationMillis;

    /** Constructor for Jackson. */
    @JsonCreator
    public ProfileEntry(@JsonProperty("durationMillis") Long durationMillis) {
        this.durationMillis = durationMillis;
    }

    public ProfileEntry() {
    }

    /** {@code null} = permanent block. */
    public Long getDurationMillis() {
        return durationMillis;
    }

    public void setDurationMillis(Long durationMillis) {
        this.durationMillis = durationMillis;
    }

    /**
     * True if the entry has a deadline. Equivalent to {@link BlockEntry#isTemporaryAndActive}
     * without the expiry check: the recipe stores a duration, not a point in time.
     */
    public boolean isTemporary() {
        return durationMillis != null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ProfileEntry other)) {
            return false;
        }
        return Objects.equals(durationMillis, other.durationMillis);
    }

    @Override
    public int hashCode() {
        return Objects.hash(durationMillis);
    }

    @Override
    public String toString() {
        return "ProfileEntry{durationMillis=" + durationMillis + '}';
    }
}