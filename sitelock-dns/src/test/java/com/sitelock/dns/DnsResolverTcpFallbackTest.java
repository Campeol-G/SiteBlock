package com.sitelock.dns;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Upstream que devolve TC=1 no UDP obriga a refazer a consulta por TCP; sem
 * isso, a resposta truncada chegava ao cliente e a resolução podia falhar.
 */
class DnsResolverTcpFallbackTest {

    @Test
    void respostaTruncadaEhRefeitaPorTcp() throws Exception {
        try (UpstreamTruncado upstream = new UpstreamTruncado()) {
            byte[] consulta = DnsWire.query(0x7A7A, "exemplo.com", DnsQuery.TYPE_A);

            byte[] resposta = DnsResolver.forwardRaw(consulta, consulta.length,
                    InetAddress.getLoopbackAddress(), upstream.porta(), 2000);

            DnsWire.Resposta r = DnsWire.parse(resposta);
            assertEquals(0x7A7A, r.id(), "o cliente tem que receber o próprio ID");
            assertEquals(1, r.anCount(), "a resposta completa veio do TCP");
            assertArrayEquals(new byte[]{(byte) 93, (byte) 184, (byte) 216, (byte) 34},
                    r.primeiroRdata(), "o dado real do upstream tem que chegar inteiro");
            assertTrue(upstream.tcpRecebeu(), "o upstream tem que receber a consulta por TCP");
            assertNotEquals(0x7A7A, DnsWire.u16(upstream.consultaTcp(), 0),
                    "o ID próprio do SiteBlock tem que ir no TCP também");
        }
    }

    @Test
    void bufferCobreAMensagemMaximaDeDns() {
        assertEquals(65535, DnsServer.MAX_PACKET_BYTES,
                "resposta EDNS grande não pode ser truncada pelo buffer do cliente UDP");
    }

    // Upstream com UDP e TCP na mesma porta: UDP responde TC=1, TCP responde
    // a mesma pergunta com a answer completa.
    private static final class UpstreamTruncado implements AutoCloseable {

        private final InetAddress loopback = InetAddress.getLoopbackAddress();
        private final DatagramSocket udp;
        private final ServerSocket tcp;
        private final Thread udpThread;
        private final Thread tcpThread;
        private volatile boolean fechado;
        private volatile byte[] consultaTcp;

        UpstreamTruncado() throws IOException {
            int porta = DnsTestSupport.portaLivre();
            udp = new DatagramSocket(porta, loopback);
            udp.setSoTimeout(100);
            tcp = new ServerSocket(porta, 10, loopback);
            tcp.setSoTimeout(100);
            udpThread = new Thread(this::loopUdp, "upstream-udp-truncado");
            tcpThread = new Thread(this::loopTcp, "upstream-tcp-truncado");
            udpThread.setDaemon(true);
            tcpThread.setDaemon(true);
            udpThread.start();
            tcpThread.start();
        }

        int porta() {
            return udp.getLocalPort();
        }

        boolean tcpRecebeu() {
            return consultaTcp != null;
        }

        byte[] consultaTcp() {
            return consultaTcp;
        }

        private void loopUdp() {
            byte[] buf = new byte[4096];
            while (!fechado) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    udp.receive(p);
                } catch (SocketTimeoutException e) {
                    continue;
                } catch (IOException e) {
                    return;
                }
                byte[] consulta = Arrays.copyOf(p.getData(), p.getLength());
                int fimPergunta = DnsWire.fimDaPergunta(consulta);
                byte[] pergunta = Arrays.copyOfRange(consulta, 12, fimPergunta);
                byte[] truncada = new byte[12 + pergunta.length];
                DnsWire.put16(truncada, 0, DnsWire.u16(consulta, 0));
                DnsWire.put16(truncada, 2, 0x8380); // QR=1, TC=1, RD, RA
                DnsWire.put16(truncada, 4, 1);
                DnsWire.put16(truncada, 6, 0);
                System.arraycopy(pergunta, 0, truncada, 12, pergunta.length);
                try {
                    udp.send(new DatagramPacket(truncada, truncada.length,
                            p.getAddress(), p.getPort()));
                } catch (IOException ignored) {
                    // teste local
                }
            }
        }

        private void loopTcp() {
            while (!fechado) {
                try (Socket socket = tcp.accept()) {
                    socket.setSoTimeout(2000);
                    InputStream in = socket.getInputStream();
                    byte[] tamanho = ler(in, 2);
                    if (tamanho == null) {
                        continue;
                    }
                    int length = ((tamanho[0] & 0xFF) << 8) | (tamanho[1] & 0xFF);
                    byte[] consulta = ler(in, length);
                    if (consulta == null) {
                        continue;
                    }
                    consultaTcp = consulta;
                    byte[] resposta = DnsWire.respostaEmCadeia(consulta,
                            new String[]{"exemplo.com"}, DnsQuery.TYPE_A,
                            new byte[]{(byte) 93, (byte) 184, (byte) 216, (byte) 34});
                    OutputStream out = socket.getOutputStream();
                    out.write((resposta.length >>> 8) & 0xFF);
                    out.write(resposta.length & 0xFF);
                    out.write(resposta);
                    out.flush();
                } catch (SocketTimeoutException e) {
                    continue;
                } catch (IOException e) {
                    if (!fechado) {
                        return;
                    }
                }
            }
        }

        private static byte[] ler(InputStream in, int n) throws IOException {
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

        @Override
        public void close() throws IOException {
            fechado = true;
            udp.close();
            tcp.close();
        }
    }
}
