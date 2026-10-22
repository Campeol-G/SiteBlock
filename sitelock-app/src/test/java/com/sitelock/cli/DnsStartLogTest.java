package com.sitelock.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * O log é append entre subidas. A marca de escuta antiga não pode fazer o novo
 * {@code dns start} dizer sucesso antes do filho atual abrir a porta.
 */
class DnsStartLogTest {

    @TempDir
    Path tmp;

    @Test
    void marcaAntesDoOffsetNaoContaComoEscutaAtual() throws Exception {
        Path log = tmp.resolve("dns-server.log");
        Files.writeString(log, "[old] " + DnsStartCommand.LISTENING_MARKER + "\n",
                StandardCharsets.UTF_8);
        long offset = Files.size(log);

        Files.writeString(log, "[new] ainda fazendo bind\n", StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);
        assertFalse(DnsStartCommand.logShowsListening(log, offset),
                "a marca da execução anterior não pode valer para esta");

        Files.writeString(log, "[new] " + DnsStartCommand.LISTENING_MARKER + "\n",
                StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        assertTrue(DnsStartCommand.logShowsListening(log, offset),
                "a marca escrita depois do offset é a da execução atual");
    }

    @Test
    void logGrandeEhArquivadoAntesDaNovaSubida() throws Exception {
        Path log = tmp.resolve("dns-server.log");
        Files.write(log, new byte[(int) DnsStartCommand.LOG_ROTATE_BYTES + 1]);

        assertEquals(0, DnsStartCommand.prepareLog(log));
        assertFalse(Files.exists(log), "o log atual tem que sair do caminho");
        assertTrue(Files.exists(tmp.resolve("dns-server.log.1")),
                "o conteúdo antigo tem que ser preservado no arquivo .1");
    }
}
