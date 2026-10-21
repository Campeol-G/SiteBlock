package com.sitelock.dns;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Apoio comum aos testes de socket: porta livre, consulta UDP, espera pelo
 * servidor no ar e arquivo de bloqueios temporário.
 *
 * <p>Nada aqui toca na internet nem em arquivo do usuário: tudo vive em
 * diretório temporário e em loopback.
 */
final class DnsTestSupport {

    private static final int PORTA_BASE = 15300;

    private DnsTestSupport() {
    }

    /**
     * Porta livre nos dois espaços de protocolo (UDP e TCP).
     *
     * <p>O servidor usa a mesma porta nos dois: por isso a porta precisa estar
     * livre nos dois lados ao mesmo tempo.
     */
    static int portaLivre() {
        for (int i = 0; i < 200; i++) {
            int porta = PORTA_BASE + (i * 7) % 2000;
            try (DatagramSocket udp = new DatagramSocket(porta);
                 ServerSocket tcp = new ServerSocket(porta)) {
                return porta;
            } catch (IOException e) {
                // ocupada: tenta a próxima
            }
        }
        throw new IllegalStateException("não achou porta livre para o teste");
    }

    /** Envia uma consulta UDP ao servidor e devolve a resposta (ou null). */
    static byte[] consultar(int porta, byte[] consulta, int timeoutMs) throws IOException {
        try (DatagramSocket s = new DatagramSocket()) {
            s.setSoTimeout(timeoutMs);
            s.send(new DatagramPacket(consulta, consulta.length,
                    InetAddress.getLoopbackAddress(), porta));
            byte[] buf = new byte[8192];
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            s.receive(p);
            return Arrays.copyOf(p.getData(), p.getLength());
        }
    }

    /** Espera o servidor responder (usa um nome bloqueado: resposta local e rápida). */
    static void esperaEscuta(int porta, String nomeBloqueado, int timeoutMs) throws Exception {
        long fim = System.currentTimeMillis() + timeoutMs;
        byte[] probe = DnsWire.query(0x4242, nomeBloqueado, DnsQuery.TYPE_A);
        while (System.currentTimeMillis() < fim) {
            try {
                if (consultar(porta, probe, 500) != null) {
                    return;
                }
            } catch (IOException ignored) {
                // ainda não está no ar
            }
            Thread.sleep(50);
        }
        throw new IllegalStateException("o servidor não respondeu na porta " + porta);
    }

    /** Cria um blocks.json com os domínios dados. */
    static Path arquivoDeBloqueios(Path dir, String... dominios) throws IOException {
        Files.createDirectories(dir);
        Path arquivo = dir.resolve("blocks.json");
        StringBuilder json = new StringBuilder("{");
        Instant criado = Instant.parse("2026-01-01T00:00:00Z");
        for (int i = 0; i < dominios.length; i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append('"').append(dominios[i]).append("\":{\"domain\":\"")
                    .append(dominios[i]).append("\",\"createdAt\":\"")
                    .append(criado).append("\",\"expiresAt\":null}");
        }
        json.append('}');
        Files.writeString(arquivo, json.toString(), StandardCharsets.UTF_8);
        return arquivo;
    }

    /** Nomes de arquivo temporários usados nos testes. */
    static List<String> nomes(List<String> base, String... extras) {
        List<String> todos = new ArrayList<>(base);
        todos.addAll(Arrays.asList(extras));
        return todos;
    }
}
