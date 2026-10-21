package com.sitelock.dns;

import com.sitelock.core.BlockStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * O servidor DNS de verdade, em socket de verdade (loopback, porta alta).
 *
 * <p>O módulo não tinha nenhum teste de socket: a decisão era testada, o
 * transporte não. Estes testes usam um upstream no escuro (127.0.0.1:53, onde
 * não escuta ninguém) ou um upstream falso local — nunca a internet.
 */
class DnsServerTest {

    @TempDir
    Path tmp;

    /**
     * O upstream fica no escuro: 127.0.0.1:53 não tem ninguém escutando, então
     * um encaminhamento acaba em SERVFAIL — que é exatamente o sintoma do
     * defeito (nome bloqueado que vaza para o upstream).
     */
    private DnsServer subirServidor(String... bloqueados) throws Exception {
        Path estado = DnsTestSupport.arquivoDeBloqueios(tmp, bloqueados);
        int porta = DnsTestSupport.portaLivre();
        DnsServer server = new DnsServer(porta, InetAddress.getLoopbackAddress(), 500,
                new BlockStore(estado), Clock.systemUTC());
        Thread t = new Thread(server::serveForever, "servidor-de-teste");
        t.setDaemon(true);
        t.start();
        DnsTestSupport.esperaEscuta(porta, bloqueados[0], 5000);
        return server;
    }

    @Test
    void nomeBloqueadoComTipoNaoAaaaRespondeNodataSemConsultarUpstream() throws Exception {
        DnsServer server = subirServidor("youtube.com");
        try {
            byte[] bruto = DnsTestSupport.consultar(server.getPort(),
                    DnsWire.query(0x1234, "youtube.com", 16 /*TXT*/), 3000);
            assertNotNull(bruto, "o servidor não respondeu");
            DnsWire.Resposta r = DnsWire.parse(bruto);
            assertEquals(0, r.rcode(),
                    "nome bloqueado não pode dar SERVFAIL: foi encaminhado ao upstream");
            assertEquals(0, r.anCount(),
                    "NODATA: nenhum answer para tipo diferente de A/AAAA");
            assertEquals(0x1234, r.id(), "a resposta precisa ecoar o ID do cliente");
        } finally {
            server.stop();
        }
    }

    @Test
    void nomeBloqueadoTipoARespondeSinkhole() throws Exception {
        DnsServer server = subirServidor("youtube.com");
        try {
            byte[] bruto = DnsTestSupport.consultar(server.getPort(),
                    DnsWire.query(0x2222, "youtube.com", 1 /*A*/), 3000);
            assertNotNull(bruto, "o servidor não respondeu");
            DnsWire.Resposta r = DnsWire.parse(bruto);
            assertEquals(0, r.rcode());
            assertEquals(1, r.anCount(), "A/AAAA bloqueado leva o sinkhole");
            assertArrayEquals(new byte[]{0, 0, 0, 0}, r.primeiroRdata(),
                    "sinkhole IPv4 é 0.0.0.0");
        } finally {
            server.stop();
        }
    }

    /**
     * O mesmo servidor precisa atender por TCP: quando a resposta não cabe no
     * UDP, o cliente retransmite por TCP (RFC 7766 §8) e, sem listener, a
     * resolução falha com erro de DNS.
     */
    @Test
    void tcpRespondeConsultaBloqueada() throws Exception {
        DnsServer server = subirServidor("youtube.com");
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), server.getPort())) {
            socket.setSoTimeout(5000);
            byte[] consulta = DnsWire.query(0x4444, "youtube.com", 1 /*A*/);
            OutputStream out = socket.getOutputStream();
            out.write((consulta.length >>> 8) & 0xFF);
            out.write(consulta.length & 0xFF);
            out.write(consulta);
            out.flush();

            byte[] tamanho = lerExatamente(socket.getInputStream(), 2);
            assertNotNull(tamanho, "o servidor não respondeu no TCP");
            int length = ((tamanho[0] & 0xFF) << 8) | (tamanho[1] & 0xFF);
            byte[] resposta = lerExatamente(socket.getInputStream(), length);
            assertNotNull(resposta, "resposta TCP truncada");

            DnsWire.Resposta r = DnsWire.parse(resposta);
            assertEquals(0x4444, r.id(), "a resposta TCP precisa ecoar o ID do cliente");
            assertEquals(0, r.rcode());
            assertEquals(1, r.anCount(), "A/AAAA bloqueado leva o sinkhole");
            assertArrayEquals(new byte[]{0, 0, 0, 0}, r.primeiroRdata(),
                    "sinkhole IPv4 é 0.0.0.0");
        } finally {
            server.stop();
        }
    }

    @Test
    void tcpRespondeNodataParaTipoNaoAaaa() throws Exception {
        DnsServer server = subirServidor("youtube.com");
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), server.getPort())) {
            socket.setSoTimeout(5000);
            byte[] consulta = DnsWire.query(0x5555, "youtube.com", 16 /*TXT*/);
            OutputStream out = socket.getOutputStream();
            out.write((consulta.length >>> 8) & 0xFF);
            out.write(consulta.length & 0xFF);
            out.write(consulta);
            out.flush();

            byte[] tamanho = lerExatamente(socket.getInputStream(), 2);
            assertNotNull(tamanho, "o servidor não respondeu no TCP");
            int length = ((tamanho[0] & 0xFF) << 8) | (tamanho[1] & 0xFF);
            byte[] resposta = lerExatamente(socket.getInputStream(), length);
            assertNotNull(resposta, "resposta TCP truncada");

            DnsWire.Resposta r = DnsWire.parse(resposta);
            assertEquals(0, r.rcode());
            assertEquals(0, r.anCount(), "NODATA no TCP também");
        } finally {
            server.stop();
        }
    }

    /** Conexão que abre e não manda nada não pode prender o servidor. */
    @Test
    void conexaoTcpOciosaNaoPrendeOServidor() throws Exception {
        DnsServer server = subirServidor("youtube.com");
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), server.getPort())) {
            socket.setSoTimeout(15000);
            assertEquals(-1, socket.getInputStream().read(),
                    "o servidor precisa fechar a conexão que não enviou consulta");
        } finally {
            server.stop();
        }
    }

    private static byte[] lerExatamente(InputStream in, int n) throws IOException {
        byte[] buffer = new byte[n];
        int lidos = 0;
        while (lidos < n) {
            int r = in.read(buffer, lidos, n - lidos);
            if (r < 0) {
                return null;
            }
            lidos += r;
        }
        return buffer;
    }

    /**
     * Origem de fora não recebe nada: responder seria amplificar a consulta
     * para a vítima e revelaria o que a rede consulta.
     */    @Test
    void origemRecusadaNaoRecebeResposta() throws Exception {
        Path estado = DnsTestSupport.arquivoDeBloqueios(tmp, "youtube.com");
        int porta = DnsTestSupport.portaLivre();
        DnsServer server = new DnsServer(porta, InetAddress.getLoopbackAddress(), 500,
                new BlockStore(estado), Clock.systemUTC(), DnsServer.SourcePolicy.nenhuma());

        ByteArrayOutputStream log = new ByteArrayOutputStream();
        PrintStream original = System.out;
        System.setOut(new PrintStream(log, true, StandardCharsets.UTF_8));
        try {
            Thread t = new Thread(server::serveForever, "servidor-de-teste");
            t.setDaemon(true);
            t.start();
            DnsTestSupport.esperaMarca(log, "SITELOCK_DNS_LISTENING", 5000);

            assertThrows(SocketTimeoutException.class, () -> DnsTestSupport.consultar(porta,
                    DnsWire.query(0x3333, "youtube.com", 1), 1000),
                    "origem recusada não pode receber resposta");

            String texto = log.toString(StandardCharsets.UTF_8);
            assertTrue(texto.contains("origem recusada"),
                    "o log precisa registrar a recusa: " + texto);
            assertFalse(texto.contains("BLOQUEADO"),
                    "consulta recusada não pode ser atendida: " + texto);
        } finally {
            System.setOut(original);
            server.stop();
        }
    }
}
