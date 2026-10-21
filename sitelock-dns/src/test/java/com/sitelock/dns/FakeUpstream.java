package com.sitelock.dns;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Upstream falso: um socket UDP local que responde como se fosse o DNS público.
 *
 * <p>Existe para que os testes do servidor nunca dependam da internet real: o
 * endereço é loopback, a porta é efêmera e o servidor sob teste aponta para
 * cá. O modo de resposta permite reproduzir os defeitos do transporte
 * (ID trocado, pacote que não é resposta, upstream que não responde).
 */
final class FakeUpstream implements AutoCloseable {

    /** Como o upstream falso responde à consulta. */
    enum Modo {
        /** Resposta normal: ecoa o ID e devolve uma answer. */
        NORMAL,
        /** Responde com outro ID (resposta de outra transação). */
        ID_ERRADO,
        /** Responde com QR=0 (não é resposta). */
        SEM_QR,
        /** Não responde nada (upstream mudo). */
        SILENCIO
    }

    private final DatagramSocket socket;
    private final List<byte[]> recebidas = Collections.synchronizedList(new ArrayList<>());
    private final List<Integer> portasDeOrigem = Collections.synchronizedList(new ArrayList<>());
    private volatile Modo modo = Modo.NORMAL;
    private volatile boolean fechado;

    FakeUpstream() throws SocketException {
        socket = new DatagramSocket(0, InetAddress.getLoopbackAddress());
        socket.setSoTimeout(100);
    }

    int porta() {
        return socket.getLocalPort();
    }

    InetAddress endereco() {
        return socket.getLocalAddress();
    }

    List<byte[]> consultasRecebidas() {
        synchronized (recebidas) {
            return List.copyOf(recebidas);
        }
    }

    /**
     * Portas de origem das consultas recebidas — ou seja, quantos sockets
     * diferentes o servidor abriu para falar com o upstream.
     */
    Set<Integer> portasDeOrigem() {
        synchronized (portasDeOrigem) {
            return new LinkedHashSet<>(portasDeOrigem);
        }
    }

    void modo(Modo novo) {
        this.modo = novo;
    }

    void start() {
        Thread t = new Thread(this::loop, "fake-upstream");
        t.setDaemon(true);
        t.start();
    }

    private void loop() {
        byte[] buf = new byte[4096];
        while (!fechado) {
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            try {
                socket.receive(p);
            } catch (SocketTimeoutException e) {
                continue;
            } catch (IOException e) {
                return;
            }
            byte[] consulta = Arrays.copyOf(p.getData(), p.getLength());
            recebidas.add(consulta);
            portasDeOrigem.add(p.getPort());
            Modo m = modo;
            if (m == Modo.SILENCIO) {
                continue;
            }
            byte[] resposta = responder(consulta, m);
            try {
                socket.send(new DatagramPacket(resposta, resposta.length,
                        p.getAddress(), p.getPort()));
            } catch (IOException ignored) {
                // upstream falso: erro de envio não interessa
            }
        }
    }

    /** Resposta com uma answer de verdade (para provar que o dado real vazou). */
    private static byte[] responder(byte[] consulta, Modo modo) {
        int id = DnsWire.u16(consulta, 0);
        if (modo == Modo.ID_ERRADO) {
            id = (id + 1) & 0xFFFF;
        }
        int fimPergunta = DnsWire.fimDaPergunta(consulta);
        byte[] pergunta = Arrays.copyOfRange(consulta, 12, fimPergunta);
        int qtype = DnsWire.u16(consulta, fimPergunta - 4);
        byte[] rdata = rdataPara(qtype);

        byte[] out = new byte[12 + pergunta.length + 12 + rdata.length];
        DnsWire.put16(out, 0, id);
        DnsWire.put16(out, 2, modo == Modo.SEM_QR ? 0x0000 : 0x8180); // QR=1, RD, RA
        DnsWire.put16(out, 4, 1);  // QDCOUNT
        DnsWire.put16(out, 6, 1);  // ANCOUNT
        System.arraycopy(pergunta, 0, out, 12, pergunta.length);
        int off = 12 + pergunta.length;
        out[off++] = (byte) 0xC0;
        out[off++] = 0x0C;
        DnsWire.put16(out, off, qtype);
        DnsWire.put16(out, off + 2, 1); // CLASS IN
        DnsWire.put16(out, off + 4, 60); // TTL
        DnsWire.put16(out, off + 8, rdata.length);
        System.arraycopy(rdata, 0, out, off + 10, rdata.length);
        return out;
    }

    private static byte[] rdataPara(int qtype) {
        if (qtype == DnsQuery.TYPE_A) {
            return new byte[]{(byte) 93, (byte) 184, (byte) 216, (byte) 34};
        }
        if (qtype == DnsQuery.TYPE_AAAA) {
            return new byte[16];
        }
        return "resposta-real-do-upstream".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        fechado = true;
        socket.close();
    }
}
