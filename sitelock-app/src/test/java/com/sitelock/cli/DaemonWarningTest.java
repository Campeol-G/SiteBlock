package com.sitelock.cli;

import com.sitelock.core.BlockEntry;
import com.sitelock.core.BlockStore;
import com.sitelock.core.ExpirationDaemonState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * O aviso de daemon ausente.
 *
 * <p>Sem ele, a feature continua invisível e o comportamento antigo permanece o
 * que a pessoa vai achar normal: digita {@code --for 4h}, o prazo vence, e o
 * site segue preso porque ninguém avisou que era preciso outro comando.
 *
 * <p>Regra do gatilho: avisa quando <b>não há daemon vivo</b> <b>ou</b> <b>não
 * há autostart instalado</b>. Os dois são defeito do ponto de vista de quem
 * digitou o prazo, e mesmo com autostart instalado o daemon pode estar naquele
 * boot atrasado.
 */
class DaemonWarningTest {

    @TempDir
    Path tmp;

    private Path hosts;

    private Path hosts() throws Exception {
        if (hosts == null) {
            hosts = tmp.resolve("hosts");
            Files.writeString(hosts, "127.0.0.1 localhost\n", StandardCharsets.UTF_8);
        }
        return hosts;
    }

    private String state() {
        return tmp.resolve("estado/blocks.json").toString();
    }

    private Path daemonJson() {
        return tmp.resolve("estado/daemon.json");
    }

    private Resultado rodar(ThrowingRunnable r) throws Exception {
        PrintStream outOriginal = System.out;
        PrintStream errOriginal = System.err;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream fake = new PrintStream(buffer, true, StandardCharsets.UTF_8);
        System.setOut(fake);
        System.setErr(fake);
        try {
            r.run();
        } finally {
            System.setOut(outOriginal);
            System.setErr(errOriginal);
        }
        return new Resultado(buffer.toString(StandardCharsets.UTF_8));
    }

    private record Resultado(String texto) {
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private void instalarDaemonVivo() throws Exception {
        ExpirationDaemonState.write(tmp.resolve("estado"), new ExpirationDaemonState(
                ProcessHandle.current().pid(), Instant.now(), tmp.resolve("estado").toString(), null));
    }

    @Test
    void blockWithDurationWarnsWhenNoDaemonExists() throws Exception {
        Resultado r = rodar(() -> new BlockCommand(List.of("tiktok.com"), "30m",
                hosts().toString(), state()).call());

        assertTrue(r.texto().contains("AVISO:"), r.texto());
        assertTrue(r.texto().contains("daemon enable"),
                "o aviso precisa dizer o comando: " + r.texto());
        assertTrue(r.texto().contains("só vai ser aplicado"),
                "o aviso precisa dizer a consequência real: " + r.texto());
    }

    @Test
    void blockPermanentDoesNotWarn() throws Exception {
        Resultado r = rodar(() -> new BlockCommand(List.of("tiktok.com"), null,
                hosts().toString(), state()).call());

        // O bloqueio tem que ter acontecido: sem isso a ausência do aviso
        // seria verdade por acidente, e não por o bloqueio ser permanente.
        assertTrue(r.texto().contains("Bloqueado tiktok.com"), r.texto());
        assertFalse(r.texto().contains("AVISO:"),
                "bloqueio permanente não tem prazo para vencer: " + r.texto());
    }

    @Test
    void blockDoesNotWarnWhenDaemonIsRunning() throws Exception {
        instalarDaemonVivo();

        Resultado r = rodar(() -> new BlockCommand(List.of("tiktok.com"), "30m",
                hosts().toString(), state()).call());

        assertTrue(r.texto().contains("Bloqueado tiktok.com"), r.texto());
        assertFalse(r.texto().contains("AVISO:"),
                "com daemon vivo e este teste sem autostart real, o aviso seria ruido: " + r.texto());
    }

    @Test
    void applyWithTemporaryWarnsWhenNoDaemonExists() throws Exception {
        assertEquals(0, new BlockCommand(List.of("tiktok.com"), "4h", hosts().toString(), state()).call());
        assertEquals(0, new ProfileSaveCommand("trabalho", false, hosts().toString(), state()).call());

        Resultado r = rodar(() -> new ProfileApplyCommand("trabalho", hosts().toString(),
                state(), null).call());

        assertTrue(r.texto().contains("AVISO:"), r.texto());
        assertTrue(r.texto().contains("daemon enable"), r.texto());
    }

    @Test
    void statusShowsDaemonLineWhenTemporaryIsActive() throws Exception {
        assertEquals(0, new BlockCommand(List.of("tiktok.com"), "4h", hosts().toString(), state()).call());

        Resultado r = rodar(() -> new StatusCommand(hosts().toString(), state()).call());

        assertTrue(r.texto().contains("daemon enable"),
                "o status tem que dizer o comando que liga o daemon: " + r.texto());
    }

    @Test
    void statusWithoutTemporaryHasNoDaemonLine() throws Exception {
        assertEquals(0, new BlockCommand(List.of("tiktok.com"), null, hosts().toString(), state()).call());

        Resultado r = rodar(() -> new StatusCommand(hosts().toString(), state()).call());

        assertTrue(r.texto().contains("Domínios bloqueados (1):"),
                "o bloqueio tem que estar listado: " + r.texto());
        assertFalse(r.texto().contains("daemon enable"),
                "sem temporário ativo não há o que avisar: " + r.texto());
    }

    @Test
    void statusWithOnlyPermanentsHasNoDaemonLine() throws Exception {
        Map<String, BlockEntry> estado = new TreeMap<>();
        estado.put("tiktok.com", new BlockEntry("tiktok.com", Instant.now(), null));
        Files.createDirectories(tmp.resolve("estado"));
        new BlockStore(Path.of(state())).save(estado);

        Resultado r = rodar(() -> new StatusCommand(hosts().toString(), state()).call());

        assertTrue(r.texto().contains("Domínios bloqueados (1):"),
                "o bloqueio tem que estar listado: " + r.texto());
        assertFalse(r.texto().contains("daemon enable"), r.texto());
    }
}