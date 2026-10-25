package com.sitelock.cli;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Sudo hints use the real path of the binary. */
class CliSupportTest {

    @TempDir
    Path tmp;

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

    // WARNING: sending "sudo siteblock daemon enable" on Windows sends a command
    // that does not exist on the machine.
    /**
     * There is no sudo on Windows: the terminal elevates when opened as
     * Administrator.
     */
    @Test
    void daemonWarningOnWindowsDoesNotUseSudo() {
        System.setProperty("os.name", "Windows 11");

        String output = capture(() -> CliSupport.warnIfNoDaemon(
                tmp.resolve("state/blocks.json").toString()));

        assertFalse(output.contains("sudo"), output);
        assertTrue(output.contains("Administrator"), output);
        assertTrue(output.contains("daemon enable"), output);
    }

    private static String capture(Runnable r) {
        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        try {
            r.run();
        } finally {
            System.setOut(original);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }
}
