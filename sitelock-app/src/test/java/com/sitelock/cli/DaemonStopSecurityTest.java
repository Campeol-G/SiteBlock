package com.sitelock.cli;

import com.sitelock.core.ExpirationDaemonState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * O {@code daemon stop} contra um daemon que roda como root.
 *
 * <p>Um usuário normal não pode mandar sinal num processo de outro usuário: o
 * JVM lança {@code SecurityException}. Sem tratamento, o picocli imprime a
 * pilha da exceção — que é o jeito mais feio de dizer " rode com sudo".
 */
class DaemonStopSecurityTest {

    @TempDir
    Path tmp;

    @Test
    void recusaDoSoViraMensagemComDicaDeElevacao() throws Exception {
        Path estado = tmp.resolve("estado");
        Files.createDirectories(estado);
        ExpirationDaemonState.write(estado, new ExpirationDaemonState(
                ProcessHandle.current().pid(), Instant.now(), estado.toString(), null));

        DaemonStopCommand cmd = new DaemonStopCommand(estado.resolve("blocks.json").toString()) {
            @Override
            void encerrar(ProcessHandle handle) {
                throw new SecurityException("uid do processo nao pode ser sinalizado");
            }
        };

        String saida = capturarErro(() -> cmd.call());

        assertTrue(saida.contains("permissão"),
                "a mensagem tem que dizer que faltou permissão: " + saida);
        assertTrue(saida.contains("daemon stop"),
                "a mensagem tem que dizer como repetir o comando: " + saida);
        assertFalse(saida.contains("Exception in thread"),
                "não pode estourar pilha: " + saida);
        assertFalse(saida.contains("\tat "),
                "não pode estourar pilha: " + saida);
    }

    @Test
    void oCodigoDeSaidaERrro() throws Exception {
        Path estado = tmp.resolve("estado");
        Files.createDirectories(estado);
        ExpirationDaemonState.write(estado, new ExpirationDaemonState(
                ProcessHandle.current().pid(), Instant.now(), estado.toString(), null));

        DaemonStopCommand cmd = new DaemonStopCommand(estado.resolve("blocks.json").toString()) {
            @Override
            void encerrar(ProcessHandle handle) {
                throw new SecurityException("uid do processo nao pode ser sinalizado");
            }
        };

        int[] codigo = new int[1];
        capturarErro(() -> codigo[0] = cmd.call());

        assertEquals(1, codigo[0], "sem permissão para encerrar é erro");
    }

    private static String capturarErro(Comando r) throws Exception {
        PrintStream original = System.err;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setErr(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        try {
            r.run();
        } finally {
            System.setErr(original);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    @FunctionalInterface
    private interface Comando {
        int run() throws Exception;
    }
}
