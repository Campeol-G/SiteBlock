package com.sitelock.core;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;

/**
 * Um conjunto nomeado de domínios com duração.
 *
 * <p>Guarda as duas datas porque {@code updatedAt} responde "quando foi a
 * última vez que eu mudei esta receita" e {@code createdAt} responde "desde
 * quando eu tenho esta receita" — são coisas diferentes depois do segundo save.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Profile {

    private Instant createdAt;
    private Instant updatedAt;
    private Map<String, ProfileEntry> entries;

    /** Construtor para o Jackson. */
    @JsonCreator
    public Profile(
            @JsonProperty(value = "createdAt", required = true) Instant createdAt,
            @JsonProperty("updatedAt") Instant updatedAt,
            @JsonProperty("entries") Map<String, ProfileEntry> entries) {
        this.createdAt = createdAt;
        this.updatedAt = updatedAt != null ? updatedAt : createdAt;
        // TreeMap para o JSON sair determinístico: a lista de um perfil não pode
        // mudar de ordem entre um save e um show só porque o mapa mudou de
        // implementação.
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
    public String toString() {
        return "Profile{createdAt=" + createdAt + ", updatedAt=" + updatedAt
                + ", entries=" + entries.size() + '}';
    }
}