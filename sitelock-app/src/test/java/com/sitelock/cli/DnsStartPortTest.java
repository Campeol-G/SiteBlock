package com.sitelock.cli;

import com.sitelock.core.OsType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A privileged port on Windows is not the same case as sudo on Unix. */
class DnsStartPortTest {

    @Test
    void windowsDoesNotRequireRootForALowPort() {
        assertFalse(DnsStartCommand.requiresElevationForPort(53, OsType.WINDOWS));
        assertFalse(DnsStartCommand.requiresElevationForPort(1, OsType.WINDOWS));
    }

    @Test
    void unixRequiresElevationForAPortBelow1024() {
        assertTrue(DnsStartCommand.requiresElevationForPort(53, OsType.LINUX));
        assertTrue(DnsStartCommand.requiresElevationForPort(53, OsType.MACOS));
        assertTrue(DnsStartCommand.requiresElevationForPort(53, OsType.UNKNOWN));
    }

    @Test
    void highPortRequiresNoElevationOnAnyOs() {
        for (OsType os : OsType.values()) {
            assertFalse(DnsStartCommand.requiresElevationForPort(5300, os), os.name());
        }
    }
}
