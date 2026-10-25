package com.sitelock.core;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * A named set of domains with a duration.
 *
 * <p>It stores both dates because {@code updatedAt} answers "when did I last
 * change this recipe" and {@code createdAt} answers "when did I create this
 * recipe" — they are different things after the second save.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Profile {

    private Instant createdAt;
    private Instant updatedAt;
    private Map<String, ProfileEntry> entries;

    /** Constructor for Jackson. */
    @JsonCreator
    public Profile(
            @JsonProperty(value = "createdAt", required = true) Instant createdAt,
            @JsonProperty("updatedAt") Instant updatedAt,
            @JsonProperty("entries") Map<String, ProfileEntry> entries) {
        this.createdAt = createdAt;
        this.updatedAt = updatedAt != null ? updatedAt : createdAt;
        // TreeMap so the JSON comes out deterministic: a profile's list must not
        // change order between a save and a show just because the map changed
        // implementation.
        this.entries = entries != null ? new TreeMap<>(entries) : new TreeMap<>();
    }

    public Profile() {
        this.entries = new TreeMap<>();
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Map<String, ProfileEntry> getEntries() {
        return entries;
    }

    public void setEntries(Map<String, ProfileEntry> entries) {
        this.entries = entries != null ? new TreeMap<>(entries) : new TreeMap<>();
    }

    public int size() {
        return entries.size();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Profile other)) {
            return false;
        }
        return Objects.equals(createdAt, other.createdAt)
                && Objects.equals(updatedAt, other.updatedAt)
                && Objects.equals(entries, other.entries);
    }

    @Override
    public int hashCode() {
        return Objects.hash(createdAt, updatedAt, entries);
    }

    @Override
    public String toString() {
        return "Profile{createdAt=" + createdAt + ", updatedAt=" + updatedAt
                + ", entries=" + entries.size() + '}';
    }
}