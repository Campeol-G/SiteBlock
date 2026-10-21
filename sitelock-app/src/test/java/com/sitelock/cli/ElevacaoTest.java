package com.sitelock.cli;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * O comando de elevação no vocabulário de cada SO.
 *
 * <p>No Unix, sudo com o caminho real do binário: o {@code ~/.local/bin}
 * costuma ficar fora do {@code secure_path} do sudo. No Windows, sudo não
 * existe — quem eleva é o terminal, aberto como Administrador.
 */
class ElevacaoTest {

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
    void noWindowsOComandoDeElevacaoNaoMandaSudo() {
        System.setProperty("os.name", "Windows 11");
        String comando = CliSupport.elevatedCommand("daemon enable");

        assertFalse(comando.contains("sudo"), comando);
        assertTrue(comando.contains("Administrador"), comando);
        assertTrue(comando.contains("siteblock daemon enable"), comando);
    }

    @Test
    void noUnixOComandoDeElevacaoUsaSudoComOCaminhoReal() {
        System.setProperty("os.name", "Linux");
        System.setProperty("siteblock.bin", "/home/chico/.local/bin/siteblock");

        assertEquals("sudo /home/chico/.local/bin/siteblock daemon enable",
                CliSupport.elevatedCommand("daemon enable"));
    }
}
