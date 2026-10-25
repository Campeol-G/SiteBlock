package com.sitelock.core;

import java.nio.file.Path;

/**
 * Per-OS privilege elevation messages, in a single place.
 *
 * <p>Before, {@code CliSupport.sudoHint} always said "run with sudo", which is
 * wrong on Windows (no reliable native sudo). Now the CLI commands ask for the
 * text here, passing the real hosts path and a subcommand example —
 * no permission strings scattered through the code.
 *
 * <ul>
 *   <li>Linux/macOS: instruct to run again with {@code sudo}.</li>
 *   <li>Windows: instruct to reopen the terminal as Administrator.</li>
 * </ul>
 */
public final class PermissionAdvisor {

    private final OsType osType;

    /** Uses the JVM's real OS. */
    public PermissionAdvisor() {
        this(OsType.detect());
    }

    /** Injects the OS (for tests and internal reuse). */
    public PermissionAdvisor(OsType osType) {
        this.osType = osType == null ? OsType.UNKNOWN : osType;
    }

    /** Injects the {@code os.name} value (for tests of the three branches). */
    public PermissionAdvisor(String osName) {
        this(OsType.detect(osName));
    }

    public OsType getOsType() {
        return osType;
    }

    /**
     * Elevation hint when write permission on the hosts file is missing.
     *
     * @param hostsPath real hosts path (already resolved per OS).
     * @param subcommandExample e.g. "block tiktok.com --for 1h" (without the binary).
     */
    public String elevationHint(Path hostsPath, String subcommandExample) {
        String args = subcommandExample == null || subcommandExample.isBlank()
                ? ""
                : " " + subcommandExample.strip();
        if (osType.isWindows()) {
            return "No write permission on " + hostsPath
                    + ". Close the current terminal and reopen it as Administrator "
                    + "(Start → cmd/PowerShell → \"Run as administrator\") "
                    + "and run again: siteblock" + args + ".";
        }
        // Linux, macOS and UNKNOWN: sudo.
        return "No write permission on " + hostsPath
                + ". Run again with sudo, e.g.: sudo siteblock" + args + ".";
    }

    /**
     * Hint for privileged ports (&lt; 1024 require elevation on all three OSes).
     * Reuses the same elevation vocabulary as {@link #elevationHint}.
     *
     * @param retryExample retry example, e.g. "dns start" or "dns start --for 2h".
     */
    public String privilegedPortHint(int port, String retryExample) {
        String args = retryExample == null || retryExample.isBlank() ? "" : " " + retryExample.strip();
        if (osType.isWindows()) {
            return "Port " + port + " is privileged on Windows (ports < 1024 require "
                    + "Administrator privileges). Close the terminal and reopen it as Administrator "
                    + "(Start → cmd/PowerShell → \"Run as administrator\") "
                    + "and run again: siteblock" + args + ". "
                    + "To test without elevation, use a high port: siteblock dns start --port 5300.";
        }
        String osLabel = osType.isMac() ? "macOS" : "Linux";
        return "Port " + port + " is privileged on " + osLabel + " (only root opens "
                + "ports < 1024). Run with sudo, e.g.: sudo siteblock" + args + ". "
                + "To test without sudo, use a high port: siteblock dns start --port 5300.";
    }

    /**
     * Best-effort warning when the DNS flush fails: the block was already saved,
     * only the cache may be stale.
     */
    public static String dnsFlushFailureWarning() {
        return "block saved, but the DNS cache could not be cleared automatically; "
                + "consider restarting your browser.";
    }
}