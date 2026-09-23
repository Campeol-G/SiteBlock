package com.sitelock.core;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Objects;

/**
 * Um bloqueio persistido em {@code blocks.json}.
 *
 * <p>{@code expiresAt == null} significa bloqueio permanente (até
 * {@code unblock} manual).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class BlockEntry {

    private String domain;
    private Instant createdAt;
    private Instant expiresAt;

    /** Construtor para o Jackson. */
    @JsonCreator
    public BlockEntry(
            @JsonProperty(value = "domain", required = true) String domain,
            @JsonProperty("createdAt") Instant createdAt,
            @JsonProperty("expiresAt") Instant expiresAt) {
        this.domain = Objects.requireNonNull(domain, "domain");
        this.createdAt = createdAt != null ? createdAt : Instant.now();
        this.expiresAt = expiresAt;
    }

    public BlockEntry() {
        // Necessário para desserialização em alguns cenários.
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

    /** true se o bloqueio já passou da expiração em {@code now}. */
    public boolean isExpired(Instant now) {
        return expiresAt != null && !now.isBefore(expiresAt);
    }

    /** true se ainda está dentro do prazo (tem expiração futura). */
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
