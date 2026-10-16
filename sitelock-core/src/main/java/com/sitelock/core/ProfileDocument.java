package com.sitelock.core;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;
import java.util.TreeMap;

/**
 * Raiz do {@code profiles.json}: a versão do formato e os perfis por nome.
 *
 * <p>O {@code version} existe desde já pelo mesmo motivo do {@code version} em
 * qualquer arquivo de estado que vai evoluir: quando o formato mudar, o leitor
 * antigo precisa saber que está olhando outra coisa.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ProfileDocument {

    public static final int CURRENT_VERSION = 1;

    private int version = CURRENT_VERSION;
    private Map<String, Profile> profiles = new TreeMap<>();

    /** Construtor para o Jackson. */
    @JsonCreator
    public ProfileDocument(@JsonProperty("version") int version,
            @JsonProperty("profiles") Map<String, Profile> profiles) {
        this.version = version != 0 ? version : CURRENT_VERSION;
        this.profiles = profiles != null ? new TreeMap<>(profiles) : new TreeMap<>();
    }

    public ProfileDocument() {
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public Map<String, Profile> getProfiles() {
        return profiles;
    }

    public void setProfiles(Map<String, Profile> profiles) {
        this.profiles = profiles != null ? new TreeMap<>(profiles) : new TreeMap<>();
    }
}