package com.sitelock.dns;

import com.sitelock.core.BlockStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.nio.file.Path;
import java.time.Clock;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * O watchdog do servidor: a primeira falha tem que derrubar o processo.
 *
 * <p>Antes, só a {@link DnsServerException} era repassada; qualquer outra
 * {@link RuntimeException} caía no catch genérico, o watchdog dormia 1s e
 * tentava de novo — para sempre. O processo ficava vivo sem nunca escutar, e
 * o {@code dns start} (que só olhava se o filho estava vivo) imprimia
 * "Servidor DNS iniciado".
 */
class DnsWatchdogTest {

    @TempDir
    Path tmp;

    @Test
    @Timeout(20)
    void primeiraFalhaDerrubaOServidor() throws Exception {
        Path estado = DnsTestSupport.arquivoDeBloqueios(tmp, "youtube.com");
        DnsServer server = new DnsServer(15377, InetAddress.getLoopbackAddress(), 100,
                new BlockStore(estado), Clock.systemUTC()) {
            @Override
            public void serveForever() {
                throw new IllegalStateException("falha simulada");
            }
        };

        DnsServerException erro = assertThrows(DnsServerException.class, server::runWithWatchdog);
        assertTrue(erro.getMessage().contains("falha simulada"),
                "a mensagem tem que dizer o que deu errado: " + erro.getMessage());
    }

    @Test
    @Timeout(20)
    void falhaNoBindAlsoDerrubaOServidor() {
        DnsServer server = new DnsServer(15378, InetAddress.getLoopbackAddress(), 100,
                new BlockStore(tmp.resolve("inexistente.json")), Clock.systemUTC()) {
            @Override
            public void serveForever() {
                throw new DnsServerException("porta 53 já está em uso");
            }
        };

        DnsServerException erro = assertThrows(DnsServerException.class, server::runWithWatchdog);
        assertTrue(erro.getMessage().contains("porta 53 já está em uso"),
                "a mensagem tem que dizer o que deu errado: " + erro.getMessage());
    }
}
