package com.sitelock.cli;

import com.sitelock.core.ExpirationDaemonState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A identidade do daemon, vista de fora.
 *
 * <p>O registro é escrito pelo próprio daemon, num processo diferente do que
 * lê. Para o {@code daemon status} dizer "rodando" e para o {@code daemon
 * stop} achar o processo certo, o registro tem que carregar o instante de
 * início do processo — e ele tem que bater com o que o SO responde.
 */
class DaemonIdentityTest {

    @TempDir
    Path tmp;

    @Test
    void oRegistroEscritoPorOutroProcessoProvaAIdentidade() throws Exception {
        Path hosts = tmp.resolve("hosts");
        Files.writeString(hosts, "127.0.0.1 localhost\n", StandardCharsets.UTF_8);
        Path estado = tmp.resolve("estado");
        Files.createDirectories(estado);

        Process daemon = new ProcessBuilder(comandoDoDaemon(estado, hosts))
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
        try {
            Path registro = estado.resolve("daemon.json");
            for (int i = 0; i < 150 && !Files.exists(registro); i++) {
                Thread.sleep(100);
            }

            ExpirationDaemonState state = ExpirationDaemonState.read(estado).orElseThrow();
            assertNotNull(state.getProcessStartedAt(),
                    "o registro tem que guardar o instante de início do processo que o escreveu");
            assertTrue(ExpirationDaemonState.isAlive(state),
                    "quem lê de outro processo tem que reconhecer o daemon como vivo");
        } finally {
            daemon.destroyForcibly();
            daemon.waitFor();
        }
    }

    private List<String> comandoDoDaemon(Path estado, Path hosts) {
        List<String> cmd = new ArrayList<>();
        cmd.add(Paths.get(System.getProperty("java.home"), "bin", "java").toString());
        cmd.add("-cp");
        cmd.add(System.getProperty("java.class.path"));
        cmd.add("-Dsitelock.stateDir=" + estado.toAbsolutePath());
        cmd.add("-Dsitelock.hostsFile=" + hosts.toAbsolutePath());
        cmd.add("com.sitelock.SiteLockApplication");
        cmd.add("daemon");
        cmd.add("__run-daemon");
        cmd.add("--interval");
        cmd.add("60");
        return cmd;
    }
}
