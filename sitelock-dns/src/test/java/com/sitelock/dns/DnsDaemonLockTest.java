package com.sitelock.dns;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Dois {@code dns start} simultâneos não podem subir dois daemons: a trava é
 * exclusiva enquanto o primeiro lê o estado e sobe o filho, e volta a valer
 * assim que ele termina.
 */
class DnsDaemonLockTest {

    @TempDir
    Path tmp;

    @Test
    void segundaAquisicaoFalhaEnquantoAPrimeiraEstaRetida() throws Exception {
        try (DnsDaemonLock primeira = DnsDaemonLock.tryAcquire(tmp).orElseThrow()) {
            Optional<DnsDaemonLock> segunda = DnsDaemonLock.tryAcquire(tmp);

            assertTrue(segunda.isEmpty(),
                    "o segundo dns start tem que ser recusado sem esperar");
        }
    }

    @Test
    void travaEhLiberadaAoFechar() throws Exception {
        try (DnsDaemonLock ignored = DnsDaemonLock.tryAcquire(tmp).orElseThrow()) {
            // só valida a aquisição
        }

        try (DnsDaemonLock segunda = DnsDaemonLock.tryAcquire(tmp).orElseThrow()) {
            assertTrue(Files.exists(DnsDaemonLock.lockPath(tmp)));
        }
        assertFalse(Files.exists(tmp.resolve("dns-server.json")),
                "a trava não pode mexer no estado do daemon");
    }
}
