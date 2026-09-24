package com.sitelock.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cobre os três ramos de SO de forma simulada (injetando {@code os.name}),
 * sem depender de rodar de fato no SO em questão.
 */
class HostsFileLocatorTest {

    @ParameterizedTest
    @ValueSource(strings = {"Linux", "LINUX", "linux", "Ubuntu Linux"})
    void linuxUsesEtcHosts(String osName) {
        Path p = new DefaultHostsFileLocator(osName).getHostsPath();
        assertEquals(Path.of("/etc/hosts"), p);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Mac OS X", "mac os x", "MAC OS X", "Darwin", "macOS"})
    void macUsesEtcHosts(String osName) {
        Path p = new DefaultHostsFileLocator(osName).getHostsPath();
        assertEquals(Path.of("/etc/hosts"), p);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Windows 10", "Windows 11", "WINDOWS 10", "windows 7"})
    void windowsUsesSystemRoot(String osName) {
        DefaultHostsFileLocator loc =
                new DefaultHostsFileLocator(OsType.detect(osName), () -> "C:\\Windows");
        assertEquals(Path.of("C:\\Windows", "System32", "drivers", "etc", "hosts"),
                loc.getHostsPath());
    }

    @Test
    void windowsRespectsCustomSystemRoot() {
        DefaultHostsFileLocator loc =
                new DefaultHostsFileLocator(OsType.WINDOWS, () -> "D:\\Win");
        assertEquals(Path.of("D:\\Win", "System32", "drivers", "etc", "hosts"),
                loc.getHostsPath());
    }

    @Test
    void windowsFallsBackToCFallbackWhenSystemRootMissing() {
        // Limitação documentada: sem %SystemRoot%, assume C:\Windows.
        DefaultHostsFileLocator loc =
                new DefaultHostsFileLocator(OsType.WINDOWS, () -> null);
        assertEquals(Path.of("C:\\Windows", "System32", "drivers", "etc", "hosts"),
                loc.getHostsPath());
    }

    @Test
    void windowsFallsBackWhenSystemRootBlank() {
        DefaultHostsFileLocator loc =
                new DefaultHostsFileLocator(OsType.WINDOWS, () -> "  ");
        assertEquals(Path.of("C:\\Windows", "System32", "drivers", "etc", "hosts"),
                loc.getHostsPath());
    }

    @Test
    void osTypeDetectionIsCaseInsensitive() {
        assertEquals(OsType.WINDOWS, OsType.detect("Windows 10"));
        assertEquals(OsType.WINDOWS, OsType.detect("WINDOWS 11"));
        assertEquals(OsType.MACOS, OsType.detect("Mac OS X"));
        assertEquals(OsType.LINUX, OsType.detect("Linux"));
        assertEquals(OsType.UNKNOWN, OsType.detect("Plan9"));
    }

    @Test
    void interfaceContractHolds() {
        HostsFileLocator locator = new DefaultHostsFileLocator("Linux");
        assertNotNull(locator.getHostsPath());
    }
}
