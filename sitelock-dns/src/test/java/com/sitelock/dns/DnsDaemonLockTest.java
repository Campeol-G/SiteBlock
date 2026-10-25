package com.sitelock.dns;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two simultaneous {@code dns start} runs must not bring up two daemons: the lock
 * is exclusive while the first reads state and starts the child, and it applies
 * again as soon as that finishes.
 */
class DnsDaemonLockTest {

    @TempDir
    Path tmp;

    @Test
    void secondAcquisitionFailsWhileFirstIsHeld() throws Exception {
        try (DnsDaemonLock first = DnsDaemonLock.tryAcquire(tmp).orElseThrow()) {
            Optional<DnsDaemonLock> second = DnsDaemonLock.tryAcquire(tmp);

            assertTrue(second.isEmpty(),
                    "the second dns start must be refused without waiting");
        }
    }

    @Test
    void lockIsReleasedOnClose() throws Exception {
        try (DnsDaemonLock ignored = DnsDaemonLock.tryAcquire(tmp).orElseThrow()) {
            // only verifies the acquisition
        }

        try (DnsDaemonLock second = DnsDaemonLock.tryAcquire(tmp).orElseThrow()) {
            assertTrue(Files.exists(DnsDaemonLock.lockPath(tmp)));
        }
        assertFalse(Files.exists(tmp.resolve("dns-server.json")),
                "the lock must not touch the daemon state");
    }
}
