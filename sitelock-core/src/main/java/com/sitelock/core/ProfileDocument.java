package com.sitelock.core;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;
import java.util.TreeMap;

/**
 * Root of {@code profiles.json}: the format version and the profiles by name.
 *
 * <p>The {@code version} exists for the same reason as the {@code version} in
 * any state file that will evolve: when the format changes, the old
 * reader needs to know it is looking at a different format.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ProfileDocument {

    public static final int CURRENT_VERSION = 1;

    private int version = CURRENT_VERSION;
    private Map<String, Profile> profiles = new TreeMap<>();

    /** Constructor for Jackson. */
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