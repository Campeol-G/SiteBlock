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
class PermissionAdvisorTest {

    @ParameterizedTest
    @ValueSource(strings = {"Linux", "linux", "Ubuntu"})
    void linuxHintsSudo(String osName) {
        String hint = new PermissionAdvisor(osName)
                .elevationHint(Path.of("/etc/hosts"), "block tiktok.com");
        assertTrue(hint.toLowerCase().contains("sudo"), "esperava 'sudo': " + hint);
        assertTrue(hint.contains("/etc/hosts"), hint);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Mac OS X", "Darwin", "macOS"})
    void macHintsSudo(String osName) {
        String hint = new PermissionAdvisor(osName)
                .elevationHint(Path.of("/etc/hosts"), "block tiktok.com");
        assertTrue(hint.toLowerCase().contains("sudo"), "esperava 'sudo': " + hint);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Windows 10", "Windows 11", "WINDOWS"})
    void windowsHintsRunAsAdministrator(String osName) {
        Path winHosts = Path.of("C:\\Windows", "System32", "drivers", "etc", "hosts");
        String hint = new PermissionAdvisor(osName).elevationHint(winHosts, "block tiktok.com");
        assertTrue(hint.contains("Administrador"), "esperava 'Administrador': " + hint);
        assertTrue(hint.contains("Executar como administrador"), hint);
        assertFalse(hint.toLowerCase().contains("sudo"), "Windows não deve sugerir sudo: " + hint);
    }

    @Test
    void privilegedPortHintReusesElevationVocabulary() {
        String linux = new PermissionAdvisor("Linux").privilegedPortHint(53, "dns start");
        assertTrue(linux.contains("sudo"), linux);
        assertTrue(linux.contains("5300"), linux);

        String mac = new PermissionAdvisor("Mac OS X").privilegedPortHint(53, "dns start");
        assertTrue(mac.contains("sudo"), mac);

        String win = new PermissionAdvisor("Windows 11").privilegedPortHint(53, "dns start");
        assertTrue(win.contains("Administrador"), win);
        assertFalse(win.toLowerCase().contains("sudo"), win);
    }

    @Test
    void dnsFlushWarningIsStable() {
        String w = PermissionAdvisor.dnsFlushFailureWarning();
        assertTrue(w.contains("bloqueio salvo"), w);
        assertTrue(w.contains("reiniciar o navegador"), w);
    }
}
