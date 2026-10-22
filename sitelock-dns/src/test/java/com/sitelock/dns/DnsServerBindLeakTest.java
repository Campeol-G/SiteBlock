package com.sitelock.dns;

import com.sitelock.core.BlockStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Se o bind TCP falhar, o UDP já aberto não pode ficar escutando nem segurando
 * a porta: o próximo start diria "porta em uso" sem haver servidor atendendo.
 */
class DnsServerBindLeakTest {

    @TempDir
    Path tmp;

    @Test
    void falhaNoBindTcpFechaOSocketUdp() throws Exception {
        int porta = DnsTestSupport.portaLivre();
        Path estado = DnsTestSupport.arquivoDeBloqueios(tmp, "bloqueado.com");

        try (ServerSocket ocupandoTcp = new ServerSocket(porta)) {
            DnsServer server = new DnsServer(porta, InetAddress.getLoopbackAddress(), 500,
                    new BlockStore(estado), Clock.systemUTC(), List.of());

            assertThrows(DnsServerException.class, server::serveForever);

            try (DatagramSocket udp = new DatagramSocket(porta)) {
                assertTrue(udp.isBound(),
                        "o UDP do servidor que falhou tinha que ter sido fechado");
            }
        }
    }
}
