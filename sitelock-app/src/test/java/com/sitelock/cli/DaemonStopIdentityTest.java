package com.sitelock.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * O {@code daemon stop} e o PID reciclado.
 *
 * <p>O SO recicla números de processo: o daemon morreu e o PID dele foi dado
 * a outro processo. Se o {@code daemon stop} confiasse só no "o PID existe",
 * mandaria um SIGTERM num processo que não é o SiteLock.
 */
class DaemonStopIdentityTest {

    @TempDir
    Path tmp;

    @Test
    void oStopNaoMataUmProcessoQueNaoEODaemon() throws Exception {
        Process alvo = processoAuxiliar();
        Path estado = tmp.resolve("estado");
        Files.createDirectories(estado);
        // O registro descreve OUTRO processo: o PID é o do alvo, a identidade
        // (instante de início) não é.
        Files.writeString(estado.resolve("daemon.json"),
                "{\"pid\":" + alvo.pid()
                        + ",\"startedAt\":\"2026-10-15T12:00:00Z\",\"stateDir\":\"" + estado
                        + "\",\"processStartedAt\":\"2026-10-15T12:00:00Z\"}",
                StandardCharsets.UTF_8);

        int exit;
        try {
            exit = new DaemonStopCommand(estado.resolve("blocks.json").toString()).call();
            assertTrue(alvo.isAlive(),
                    "o daemon stop não pode mandar sinal num processo que não é o daemon");
        } finally {
            ProcessosDeTeste.matar(alvo);
            alvo.waitFor();
        }

        assertEquals(1, exit, "sem daemon nosso, o stop tem que recusar");
    }

    // Um processo qualquer, que fica vivo até ser morto.
    private Process processoAuxiliar() throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(Paths.get(System.getProperty("java.home"), "bin", "java").toString());
        cmd.add("-cp");
        cmd.add(System.getProperty("java.class.path"));
        cmd.add(Dorme.class.getName());
        return new ProcessBuilder(cmd).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
    }

    /** Fica vivo até receber o sinal. */
    public static class Dorme {
        public static void main(String[] args) throws Exception {
            Thread.sleep(300_000);
        }
    }
}
