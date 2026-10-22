package com.sitelock.dns;

import com.sitelock.core.BlockStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Quando o loop de atendimento cai no meio, o pool antigo não pode ficar
// órfão: o watchdog cria um pool novo por cima e as threads do velho
// continuam vivas (daemon, keepalive de 60s) consumindo memória e porta.
class DnsPoolOrfaoTest {

    @TempDir
    Path tmp;

    @Test
    void poolAntigoEhEncerradoQuandoOLoopCai() throws Exception {
        Path estado = DnsTestSupport.arquivoDeBloqueios(tmp, "youtube.com");
        int porta = DnsTestSupport.portaLivre();
        AtomicInteger chamadas = new AtomicInteger();
        // O bind acontece na thread do servidor; sem esperar, a consulta chega
        // antes do socket estar pronto e o UDP a descarta (o Windows é mais lento).
        CountDownLatch escutando = new CountDownLatch(1);
        int antes = contaThreadsDoPool();

        DnsServer server = new DnsServer(porta, InetAddress.getLoopbackAddress(), 100,
                new BlockStore(estado), Clock.systemUTC()) {
            @Override
            void aguardaEDespacha(DatagramSocket ds) throws IOException {
                if (chamadas.incrementAndGet() == 1) {
                    escutando.countDown();
                    super.aguardaEDespacha(ds); // recebe uma consulta de verdade
                    return;
                }
                // dá tempo de a thread do pool atender a consulta antes da queda
                try {
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                throw new IllegalStateException("queda simulada");
            }
        };

        Thread t = new Thread(() -> {
            try {
                server.serveForever();
            } catch (RuntimeException e) {
                // esperado: a queda simulada
            }
        }, "servidor-que-cai");
        t.setDaemon(true);
        t.start();

        // Uma consulta de verdade: cria a thread do pool.
        assertTrue(escutando.await(5, TimeUnit.SECONDS), "o servidor não chegou a escutar");
        DnsTestSupport.consultar(porta, DnsWire.query(1, "youtube.com", 1), 3000);
        t.join(5000);
        assertFalse(t.isAlive(), "o servidor deveria ter caído");

        long limite = System.currentTimeMillis() + 5000;
        int restantes = contaThreadsDoPool() - antes;
        while (restantes > 0 && System.currentTimeMillis() < limite) {
            Thread.sleep(50);
            restantes = contaThreadsDoPool() - antes;
        }
        assertEquals(0, restantes,
                "o pool antigo ficou órfão: " + restantes + " thread(s) viva(s)");
    }

    // Threads do pool de UDP (o de TCP tem outro prefixo).
    private static int contaThreadsDoPool() {
        int total = 0;
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.getName().matches("sitelock-dns-\\d+")) {
                total++;
            }
        }
        return total;
    }
}
