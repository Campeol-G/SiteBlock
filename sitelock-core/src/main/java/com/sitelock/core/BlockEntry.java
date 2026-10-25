package com.sitelock.core;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Objects;

/**
 * A block persisted in blocks.json.
 * A null expiresAt means a permanent block.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class BlockEntry {

    private String domain;
    private Instant createdAt;
    private Instant expiresAt;

    /** Constructor for Jackson. */
    @JsonCreator
    public BlockEntry(
            @JsonProperty(value = "domain", required = true) String domain,
            @JsonProperty("createdAt") Instant createdAt,
            @JsonProperty("expiresAt") Instant expiresAt) {
        this.domain = Objects.requireNonNull(domain, "domain");
        // WHY: null createdAt is "unknown" (file written by hand or
        // by an old version). Inventing "now" would change the semantics with no
        // visible error — and the displayed date would lie.
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public BlockEntry() {
    }

    public String getDomain() {
        return domain;
    }

    public void setDomain(String domain) {
        this.domain = domain;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    /** True if past the expiration. */
    public boolean isExpired(Instant now) {
        return expiresAt != null && !now.isBefore(expiresAt);
    }

    /** True if temporary and still within its deadline. */
    public boolean isTemporaryAndActive(Instant now) {
        return expiresAt != null && now.isBefore(expiresAt);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof BlockEntry other)) {
            return false;
        }
        return Objects.equals(domain, other.domain)
                && Objects.equals(createdAt, other.createdAt)
                && Objects.equals(expiresAt, other.expiresAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(domain, createdAt, expiresAt);
    }

    @Override
    public String toString() {
        return "BlockEntry{domain='" + domain + "', createdAt=" + createdAt
                + ", expiresAt=" + expiresAt + '}';
    }
}
