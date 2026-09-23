package com.sitelock.cli;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Formatação das dicas de sudo ({@link CliSupport#sudoCommand(String)}):
 * o comando sugerido precisa funcionar sob o {@code secure_path} do sudo,
 * que ignora {@code ~/.local/bin}.
 */
class CliSupportTest {

    private String savedBin;

    @BeforeEach
    void saveProperty() {
        savedBin = System.getProperty("siteblock.bin");
    }

    @AfterEach
    void restoreProperty() {
        if (savedBin == null) {
            System.clearProperty("siteblock.bin");
        } else {
            System.setProperty("siteblock.bin", savedBin);
        }
    }

    @Test
    void sudoCommandDefaultsToBareName() {
        System.clearProperty("siteblock.bin");
        assertEquals("sudo siteblock status", CliSupport.sudoCommand("status"));
    }

    @Test
    void sudoCommandUsesAbsoluteInvokerPath() {
        System.setProperty("siteblock.bin", "/home/chico/.local/bin/siteblock");
        assertEquals("sudo /home/chico/.local/bin/siteblock block tiktok.com",
                CliSupport.sudoCommand("block tiktok.com"));
    }

    @Test
    void sudoCommandShortensSystemInstall() {
        System.setProperty("siteblock.bin", "/usr/local/bin/siteblock");
        assertEquals("sudo siteblock block tiktok.com",
                CliSupport.sudoCommand("block tiktok.com"));
    }

    @Test
    void sudoCommandQuotesPathsWithSpaces() {
        System.setProperty("siteblock.bin", "/home/chico/My Dir/siteblock");
        assertEquals("sudo \"/home/chico/My Dir/siteblock\" status",
                CliSupport.sudoCommand("status"));
    }
}
