package com.sitelock.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the three OS branches in a simulated way (injecting {@code os.name}),
 * without depending on actually running on the OS in question.
 */
class PermissionAdvisorTest {

    @ParameterizedTest
    @ValueSource(strings = {"Linux", "linux", "Ubuntu"})
    void linuxHintsSudo(String osName) {
        Path hosts = Path.of("/etc/hosts");
        String hint = new PermissionAdvisor(osName)
                .elevationHint(hosts, "block tiktok.com");
        assertTrue(hint.toLowerCase().contains("sudo"), "expected 'sudo': " + hint);
        assertTrue(hint.contains(hosts.toString()), hint);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Mac OS X", "Darwin", "macOS"})
    void macHintsSudo(String osName) {
        String hint = new PermissionAdvisor(osName)
                .elevationHint(Path.of("/etc/hosts"), "block tiktok.com");
        assertTrue(hint.toLowerCase().contains("sudo"), "expected 'sudo': " + hint);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Windows 10", "Windows 11", "WINDOWS"})
    void windowsHintsRunAsAdministrator(String osName) {
        Path winHosts = Path.of("C:\\Windows", "System32", "drivers", "etc", "hosts");
        String hint = new PermissionAdvisor(osName).elevationHint(winHosts, "block tiktok.com");
        assertTrue(hint.contains("Administrator"), "expected 'Administrator': " + hint);
        assertTrue(hint.contains("Run as administrator"), hint);
        assertFalse(hint.toLowerCase().contains("sudo"), "Windows should not suggest sudo: " + hint);
    }

    @Test
    void privilegedPortHintReusesElevationVocabulary() {
        String linux = new PermissionAdvisor("Linux").privilegedPortHint(53, "dns start");
        assertTrue(linux.contains("sudo"), linux);
        assertTrue(linux.contains("5300"), linux);

        String mac = new PermissionAdvisor("Mac OS X").privilegedPortHint(53, "dns start");
        assertTrue(mac.contains("sudo"), mac);

        String win = new PermissionAdvisor("Windows 11").privilegedPortHint(53, "dns start");
        assertTrue(win.contains("Administrator"), win);
        assertFalse(win.toLowerCase().contains("sudo"), win);
    }

    @Test
    void dnsFlushWarningIsStable() {
        String w = PermissionAdvisor.dnsFlushFailureWarning();
        assertTrue(w.contains("block saved"), w);
        assertTrue(w.contains("restarting your browser"), w);
    }
}
