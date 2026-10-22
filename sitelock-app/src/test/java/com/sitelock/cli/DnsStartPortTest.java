package com.sitelock.cli;

import com.sitelock.core.OsType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Porta privilegiada no Windows não é o mesmo caso de sudo no Unix. */
class DnsStartPortTest {

    @Test
    void windowsNaoExigeRootParaPortaBaixa() {
        assertFalse(DnsStartCommand.requiresElevationForPort(53, OsType.WINDOWS));
        assertFalse(DnsStartCommand.requiresElevationForPort(1, OsType.WINDOWS));
    }

    @Test
    void unixExigeElevacaoParaPortaAbaixoDe1024() {
        assertTrue(DnsStartCommand.requiresElevationForPort(53, OsType.LINUX));
        assertTrue(DnsStartCommand.requiresElevationForPort(53, OsType.MACOS));
        assertTrue(DnsStartCommand.requiresElevationForPort(53, OsType.UNKNOWN));
    }

    @Test
    void portaAltaNaoExigeElevacaoEmNenhumSo() {
        for (OsType os : OsType.values()) {
            assertFalse(DnsStartCommand.requiresElevationForPort(5300, os), os.name());
        }
    }
}
