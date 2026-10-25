package com.sitelock.core;

import java.util.Locale;

/**
 * Operating system normalized from {@code os.name}.
 *
 * <p>Detection is centralized so that {@link HostsFileLocator},
 * {@link PermissionAdvisor} and {@link DnsCacheFlusher} decide behavior
 * per OS in a single place, instead of spreading {@code System.getProperty("os.name")}
 * across the code.
 *
 * <p>Case-insensitive detection, covering common variations:
 * "Windows 10", "Windows 11", "Mac OS X", "Darwin", "Linux".
 */
public enum OsType {
    LINUX,
    MACOS,
    WINDOWS,
    UNKNOWN;

    /** Detects from the JVM's real {@code os.name}. */
    public static OsType detect() {
        return detect(System.getProperty("os.name", ""));
    }

    // WARNING: "darwin" contains "win" as a substring, so mac/darwin is tested
    // before win.
    /**
     * Detects from an injected {@code os.name} value.
     * Exists to allow unit tests of the three branches on any machine.
     */
    public static OsType detect(String osName) {
        String n = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (n.contains("mac") || n.contains("darwin")) {
            return MACOS;
        }
        if (n.contains("win")) {
            return WINDOWS;
        }
        if (n.contains("linux") || n.contains("nix") || n.contains("nux")) {
            return LINUX;
        }
        return UNKNOWN;
    }

    public boolean isWindows() {
        return this == WINDOWS;
    }

    public boolean isMac() {
        return this == MACOS;
    }

    /** Linux + macOS + UNKNOWN (Unix-like behavior by default). */
    public boolean isUnixLike() {
        return this == LINUX || this == MACOS || this == UNKNOWN;
    }
}
