package com.sitelock.cli;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The elevation command in each OS's vocabulary.
 *
 * <p>On Unix, sudo with the real binary path: {@code ~/.local/bin} is often
 * outside sudo's {@code secure_path}. On Windows there is no sudo — the
 * terminal elevates when opened as Administrator.
 */
class ElevationTest {

    private String savedBin;
    private String savedOsName;

    @BeforeEach
    void saveProperty() {
        savedBin = System.getProperty("siteblock.bin");
        savedOsName = System.getProperty("os.name");
    }

    @AfterEach
    void restoreProperty() {
        if (savedBin == null) {
            System.clearProperty("siteblock.bin");
        } else {
            System.setProperty("siteblock.bin", savedBin);
        }
        System.setProperty("os.name", savedOsName);
    }

    @Test
    void onWindowsElevationCommandDoesNotUseSudo() {
        System.setProperty("os.name", "Windows 11");
        String command = CliSupport.elevatedCommand("daemon enable");

        assertFalse(command.contains("sudo"), command);
        assertTrue(command.contains("Administrator"), command);
        assertTrue(command.contains("siteblock daemon enable"), command);
    }

    @Test
    void onUnixElevationCommandUsesSudoWithRealPath() {
        System.setProperty("os.name", "Linux");
        System.setProperty("siteblock.bin", "/home/chico/.local/bin/siteblock");

        assertEquals("sudo /home/chico/.local/bin/siteblock daemon enable",
                CliSupport.elevatedCommand("daemon enable"));
    }
}
