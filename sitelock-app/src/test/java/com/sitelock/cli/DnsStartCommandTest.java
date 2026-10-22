package com.sitelock.cli;

import com.sitelock.dns.DnsDaemonState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * O {@code dns start} de verdade: sobe um daemon numa porta alta e conversa
 * com ele.
 *
 * <p>Os testes do módulo DNS não tinham nenhum teste de socket; aqui o daemon
 * sobe de verdade (outro processo), o que é o caminho que o usuário percorre.
 */
class DnsStartCommandTest {

    @TempDir
    Path tmp;

    private static final int PORTA = 15399;

    @Test
    void allowFromChegaAoDaemon() throws Exception {
        Path stateFile = tmp.resolve("blocks.json");
        Files.writeString(stateFile, "{}", StandardCharsets.UTF_8);
        // O estado do daemon mora no mesmo diretório do --state-file.
        Path estado = tmp;

        int exit = new CommandLine(new DnsCommand()).execute(
                "start",
                "--port", String.valueOf(PORTA),
                "--upstream", "127.0.0.1",
                "--allow-from", "203.0.113.0/24",
                "--state-file", stateFile.toString());

        try {
            assertEquals(0, exit, "o daemon deveria ter subido");
            Path log = DnsDaemonState.logPath(estado);
            String texto = Files.readString(log, StandardCharsets.UTF_8);
            assertTrue(texto.contains("SITELOCK_DNS_LISTENING"),
                    "o daemon precisa anunciar que está escutando: " + texto);
            assertTrue(texto.contains("203.0.113.0/24"),
                    "a origem liberada tem que chegar ao daemon: " + texto);
        } finally {
            encerrar(estado);
        }
    }

    /**
     * Um registro corrompido não pode trancar a máquina: o usuário precisa
     * conseguir subir o servidor DNS sem apagar arquivo à mão.
     */
    @Test
    void registroCorrompidoNaoImpedeOServidorDeSubir() throws Exception {
        Path stateFile = tmp.resolve("blocks.json");
        Files.writeString(stateFile, "{}", StandardCharsets.UTF_8);
        Files.writeString(tmp.resolve("dns-server.json"),
                "{ \"pid\": 12, \"port\":", StandardCharsets.UTF_8);

        int exit = new CommandLine(new DnsCommand()).execute(
                "start",
                "--port", String.valueOf(PORTA + 1),
                "--upstream", "127.0.0.1",
                "--state-file", stateFile.toString());
        try {
            assertEquals(0, exit,
                    "um registro corrompido não pode impedir o servidor DNS de subir");
            DnsDaemonState state = DnsDaemonState.read(tmp).orElseThrow();
            assertTrue(state.getPid() > 0, "o registro novo tem que valer");
        } finally {
            encerrar(tmp);
        }
    }

    // Derruba o daemon que o teste subiu (o PID está no registro de estado).
    private void encerrar(Path estado) {
        try {
            Optional<DnsDaemonState> state = DnsDaemonState.read(estado);
            if (state.isPresent() && state.get().getPid() > 0) {
                ProcessHandle.of(state.get().getPid()).ifPresent(ProcessosDeTeste::matar);
            }
        } catch (IOException ignored) {
            // o teste só precisa que o processo não fique pendurado
        }
    }
}
