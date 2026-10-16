package com.sitelock.cli;

import com.sitelock.core.BlockEntry;
import com.sitelock.core.BlockStore;
import com.sitelock.core.ExpirationDaemonLock;
import com.sitelock.core.ExpirationDaemonState;
import com.sitelock.core.HostsFileManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * O laço de expiração e o seu ciclo de vida.
 *
 * <p>O teste que importa aqui é
 * {@link #pinnedStateDirIsUsedWhenSudoUserIsAbsent()}: ele sobe o daemon num
 * subprocesso com {@code SUDO_USER} removido do ambiente e {@code user.home}
 * apontando para um diretório vazio, e afirma que ele operou sobre o
 * {@code stateDir} cravado. Um daemon que caísse no {@code user.home} leria o
 * {@code blocks.json} do root, não acharia nada, e todo o resto continuaria
 * verde — é um no-op silencioso, e nenhum teste escrito à mão o veria.
 */
class DaemonCliTest {

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

    private Path stateDir() {
        return tmp.resolve("estado");
    }

    private Path stateFile() {
        return stateDir().resolve("blocks.json");
    }

    private void semear(Map<String, Instant> entradas) throws Exception {
        Files.createDirectories(stateDir());
        Map<String, BlockEntry> state = new TreeMap<>();
        Instant criadas = Instant.parse("2026-10-15T08:00:00Z");
        for (Map.Entry<String, Instant> e : entradas.entrySet()) {
            state.put(e.getKey(), new BlockEntry(e.getKey(), criadas, e.getValue()));
        }
        new BlockStore(stateFile()).save(state);
    }

    private static Map<String, Instant> prazos(Object... pares) {
        Map<String, Instant> m = new TreeMap<>();
        for (int i = 0; i < pares.length; i += 2) {
            m.put((String) pares[i], (Instant) pares[i + 1]);
        }
        return m;
    }

    private String estado() throws Exception {
        return Files.readString(stateFile(), StandardCharsets.UTF_8);
    }

    private String hostsConteudo() throws Exception {
        return Files.readString(hosts(), StandardCharsets.UTF_8);
    }

    // ---- execução do daemon num subprocesso ----

    private List<String> comandoDoDaemon(String... extras) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(Paths.get(System.getProperty("java.home"), "bin", "java").toString());
        cmd.add("-cp");
        cmd.add(System.getProperty("java.class.path"));
        cmd.add("-Dsitelock.stateDir=" + stateDir());
        cmd.add("-Dsitelock.hostsFile=" + hosts());
        cmd.add("-Duser.home=" + tmp.resolve("home-vazio"));
        cmd.add("-Dsiteblock.user=" + System.getProperty("user.name", ""));
        cmd.add("com.sitelock.SiteLockApplication");
        cmd.add("daemon");
        cmd.add("__run-daemon");
        for (String e : extras) {
            cmd.add(e);
        }
        return cmd;
    }

    private Process iniciar(String... extras) throws Exception {
        Files.createDirectories(tmp.resolve("home-vazio"));
        ProcessBuilder pb = new ProcessBuilder(comandoDoDaemon(extras));
        // Sem SUDO_USER: é o que o systemd, o launchd e o Task Scheduler fazem.
        pb.environment().remove("SUDO_USER");
        pb.redirectErrorStream(true);
        // A saída vai para arquivo, e não para um pipe: um daemon de laço nunca
        // fecha stdout, e ler o stream até o EOF travaria o teste para sempre.
        log = tmp.resolve("daemon-saida.log");
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
        return pb.start();
    }

    private Path log;

    private String saidaDoDaemon() {
        try {
            return Files.exists(log) ? Files.readString(log, StandardCharsets.UTF_8) : "";
        } catch (Exception e) {
            return "";
        }
    }

    // ---- os testes ----

    @Test
    void pinnedStateDirIsUsedWhenSudoUserIsAbsent() throws Exception {
        HostsFileManager.blockInFile(hosts(), tmp.resolve("hosts.backup"), tmp.resolve("hosts.lock"),
                List.of("vencido.com"));
        semear(prazos("vencido.com", Instant.parse("2020-01-01T00:00:00Z")));

        Process p = iniciar("--once");
        int exit = p.waitFor();
        String saida = saidaDoDaemon();

        assertEquals(0, exit, "o daemon falhou:\n" + saida);
        assertFalse(hostsConteudo().contains("vencido.com"),
                "o hosts cravado deveria ter perdido o domínio — se não perdeu, o daemon "
                        + "leu user.home em vez do stateDir:\n" + saida);
        assertFalse(estado().contains("vencido.com"), "o estado cravado deveria ter perdido o domínio");
    }

    @Test
    void onceSweepRemovesNothingWhenNothingExpired() throws Exception {
        HostsFileManager.blockInFile(hosts(), tmp.resolve("hosts.backup"), tmp.resolve("hosts.lock"),
                List.of("permanente.com"));
        semear(prazos("permanente.com", null));
        String antes = hostsConteudo();

        Process p = iniciar("--once");
        int exit = p.waitFor();
        String saida = saidaDoDaemon();

        assertEquals(0, exit, saida);
        assertEquals(antes, hostsConteudo(), "permanente não pode ser tocado");
        assertTrue(estado().contains("permanente.com"));
    }

    @Test
    void childWritesItsOwnStateFile() throws Exception {
        semear(prazos("permanente.com", null));
        Files.createDirectories(tmp.resolve("home-vazio"));

        Process p = iniciar("--interval", "1");
        Path daemonJson = ExpirationDaemonState.statePath(stateDir());

        try {
            ExpirationDaemonState state = null;
            for (int i = 0; i < 100 && state == null; i++) {
                Thread.sleep(100);
                if (Files.exists(daemonJson)) {
                    state = ExpirationDaemonState.read(stateDir()).orElse(null);
                }
            }
            assertTrue(state != null,
                "o daemon tem que escrever o próprio daemon.json — sob autostart não há pai "
                        + "para escrever por ele");
            assertEquals(p.pid(), state.getPid(), "o PID gravado tem que ser o do próprio daemon");
            assertEquals(stateDir().toString(), state.getStateDir(),
                    "o stateDir gravado tem que ser o cravado, não o do user.home");
        } finally {
            p.destroyForcibly();
            p.waitFor();
        }

        // destroyForcibly é SIGKILL: o shutdown hook NÃO roda, então o registro
        // pode ficar para trás. Isso é aceitável — e é justamente por isso que o
        // lock de instância única é FileLock e não "o PID existe": o que importa
        // é que um registro órfão não bloqueie o próximo daemon.
        if (Files.exists(daemonJson)) {
            assertFalse(ExpirationDaemonState.isAlive(
                            ExpirationDaemonState.read(stateDir()).orElse(null)),
                    "um registro de processo morto tem que ser reconhecido como morto");
        }
    }

    @Test
    void concurrentBlocksSurviveADaemonSweep() throws Exception {
        // O daemon varre a cada segundo; enquanto isso, um `block` sai de outro
        // processo. Sem a transação no BlockStore, o sweep grava o estado que
        // leu antes e o bloqueio concorrente some do JSON — ficando preso no
        // hosts sem registro, que é o modo de falha que o sweep nunca repara
        // porque varre justamente o JSON.
        semear(prazos("vencido.com", Instant.parse("2020-01-01T00:00:00Z")));

        Process daemon = iniciar("--interval", "1");
        try {
            for (int i = 0; i < 100 && !Files.exists(ExpirationDaemonState.statePath(stateDir())); i++) {
                Thread.sleep(100);
            }
            assertTrue(daemon.isAlive(), "o daemon não subiu");

            for (int i = 0; i < 5; i++) {
                assertEquals(0, new BlockCommand(List.of("concorrente" + i + ".com"), null,
                        hosts().toString(), stateFile().toString()).call());
                Thread.sleep(150);
            }

            // Deixa o sweep completar mais vezes ainda.
            Thread.sleep(1500);

            String estado = Files.readString(stateFile(), StandardCharsets.UTF_8);
            for (int i = 0; i < 5; i++) {
                assertTrue(estado.contains("concorrente" + i + ".com"),
                        "o bloqueio concorrente " + i + " foi apagado pelo sweep:\n" + estado);
            }
            assertFalse(estado.contains("vencido.com"), "o expirado tem que ter saido");
        } finally {
            daemon.destroyForcibly();
            daemon.waitFor();
        }
    }

    @Test
    void theLoopSurvivesCorruptedState() throws Exception {
        // O teste precisa do LAÇO, não do --once: o caminho de --once sai com 1
        // e nunca itera, então ele não prova nada sobre "o daemon renasce".
        Files.createDirectories(stateDir());
        Files.writeString(stateFile(), "{ nao é json", StandardCharsets.UTF_8);

        Process p = iniciar("--interval", "1");
        try {
            String acumulado = "";
            for (int i = 0; i < 40 && !acumulado.contains("corromp"); i++) {
                Thread.sleep(100);
                acumulado = saidaDoDaemon();
            }
            assertTrue(p.isAlive(), "o daemon não pode morrer por estado corrompido — "
                    + "quem volta a avisar é ele:\n" + acumulado);
            assertTrue(acumulado.toLowerCase().contains("corromp"), acumulado);
            assertTrue(acumulado.contains("próxima volta"),
                    "tem que dizer que vai tentar de novo: " + acumulado);
        } finally {
            p.destroyForcibly();
            p.waitFor();
        }
    }

    @Test
    void onceSweepFailsLoudlyOnCorruptedState() throws Exception {
        Files.createDirectories(stateDir());
        Files.writeString(stateFile(), "{ nao é json", StandardCharsets.UTF_8);

        Process p = iniciar("--once");
        int exit = p.waitFor();
        String saida = saidaDoDaemon();

        assertEquals(1, exit, "estado corrompido é erro: " + saida);
        assertTrue(saida.toLowerCase().contains("corromp"), saida);
        assertFalse(saida.contains("Exception in thread"), "não pode estourar pilha:\n" + saida);
    }

    @Test
    void theLoopHoldsTheLockWhileAlive() throws Exception {
        // --once não trava nada por desenho, então medir "lock liberado" depois
        // dele mede nada. O que importa é o LAÇO segurar a trava, que é o que
        // impede duas instâncias.
        semear(prazos("permanente.com", null));
        Process p = iniciar("--interval", "1");
        try {
            Path daemonJson = ExpirationDaemonState.statePath(stateDir());
            for (int i = 0; i < 100 && !Files.exists(daemonJson); i++) {
                Thread.sleep(100);
            }
            IOException erro = assertThrows(IOException.class,
                    () -> ExpirationDaemonLock.acquire(stateDir()),
                    "com o daemon vivo, nenhuma outra instância pode pegar a trava");
            assertTrue(erro.getMessage().contains("daemon de expiração rodando"), erro.getMessage());
        } finally {
            p.destroyForcibly();
            p.waitFor();
        }
    }

    @Test
    void lockIsReleasedWhenTheProcessDies() throws Exception {
        semear(prazos("permanente.com", null));
        Process p = iniciar("--interval", "1");
        Path daemonJson = ExpirationDaemonState.statePath(stateDir());
        for (int i = 0; i < 100 && !Files.exists(daemonJson); i++) {
            Thread.sleep(100);
        }
        p.destroyForcibly();
        p.waitFor();

        // O SO libera o FileLock na morte do processo; é o motivo de o lock ser
        // FileLock e não "o PID existe".
        ExpirationDaemonLock lock = ExpirationDaemonLock.acquire(stateDir());
        assertTrue(lock != null);
        lock.close();
    }

    // ---- comandos de ciclo de vida ----
    // ---- comandos de ciclo de vida ----

    @Test
    void startRefusesWithoutRoot() throws Exception {
        Path semGravacao = tmp.resolve("sem-pai/hosts");
        semear(prazos("permanente.com", null));

        Capture saida = captureErr(() -> new DaemonStartCommand("60", semGravacao.toString(),
                stateFile().toString()).call());

        assertEquals(1, saida.exit());
        assertTrue(saida.texto().contains("sudo") || saida.texto().toLowerCase().contains("permiss"),
                saida.texto());
        assertFalse(Files.exists(ExpirationDaemonState.statePath(stateDir())),
                "nem pode ter forked um filho que não conseguiria escrever no hosts");
    }

    @Test
    void startRefusesWhenDaemonAlreadyRunning() throws Exception {
        semear(prazos("permanente.com", null));
        ExpirationDaemonState.write(stateDir(), new ExpirationDaemonState(
                ProcessHandle.current().pid(), Instant.now(), stateDir().toString(), null));

        int exit = new DaemonStartCommand("60", hosts().toString(), stateFile().toString()).call();

        assertEquals(1, exit);
    }

    @Test
    void staleRegistrationIsDiscardedInsteadOfBlockingForever() throws Exception {
        // PID que não existe + registro no disco: sem isto o start recusa para
        // sempre, e não há --force para destravar.
        semear(prazos("permanente.com", null));
        ExpirationDaemonState.write(stateDir(), new ExpirationDaemonState(
                Integer.MAX_VALUE + 7, Instant.now(), stateDir().toString(), null));

        assertTrue(DaemonStartCommand.descartarRegistroOrfao(stateDir()));
        assertFalse(Files.exists(ExpirationDaemonState.statePath(stateDir())));
    }

    @Test
    void liveRegistrationIsNeverDiscarded() throws Exception {
        semear(prazos("permanente.com", null));
        ExpirationDaemonState.write(stateDir(), new ExpirationDaemonState(
                ProcessHandle.current().pid(), Instant.now(), stateDir().toString(), null));

        assertFalse(DaemonStartCommand.descartarRegistroOrfao(stateDir()),
                "registro de processo vivo não pode ser apagado");
        assertTrue(Files.exists(ExpirationDaemonState.statePath(stateDir())));
    }

    @Test
    void statusReportsNoDaemonWhenPidFileIsMissing() throws Exception {
        Capture saida = captureOut(() -> new DaemonStatusCommand(stateFile().toString()).call());
        assertEquals(0, saida.exit());
        assertTrue(saida.texto().contains("não está rodando"), saida.texto());
    }

    @Test
    void statusWorksWithoutRoot() throws Exception {
        semear(prazos("permanente.com", null));
        Instant sweep = Instant.parse("2026-10-15T11:00:00Z");
        ExpirationDaemonState.write(stateDir(), new ExpirationDaemonState(
                ProcessHandle.current().pid(), Instant.now(), stateDir().toString(), sweep));

        Capture saida = captureOut(() -> new DaemonStatusCommand(stateFile().toString()).call());

        assertEquals(0, saida.exit());
        assertTrue(saida.texto().contains(String.valueOf(ProcessHandle.current().pid())), saida.texto());
        // O instante é gravado em UTC e mostrado no fuso da máquina: o teste
        // formata do mesmo jeito em vez de cravar "11:00".
        String esperado = DateTimeFormatter.ofPattern("dd/MM HH:mm")
                .withZone(ZoneId.systemDefault()).format(sweep);
        assertTrue(saida.texto().contains(esperado), "esperava '" + esperado + "':\n" + saida.texto());
    }

    @Test
    void stopReportsWhenNoDaemonIsRunning() {
        int exit = new DaemonStopCommand(stateFile().toString()).call();
        assertEquals(1, exit);
    }

    // ---- captura de saída ----

    private record Capture(int exit, String texto) {
    }

    private static Capture captureOut(ThrowingInt r) throws Exception {
        return capture(r, false);
    }

    private static Capture captureErr(ThrowingInt r) throws Exception {
        return capture(r, true);
    }

    private static Capture capture(ThrowingInt r, boolean stderr) throws Exception {
        PrintStream original = stderr ? System.err : System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream fake = new PrintStream(buffer, true, StandardCharsets.UTF_8);
        if (stderr) {
            System.setErr(fake);
        } else {
            System.setOut(fake);
        }
        int exit;
        try {
            exit = r.run();
        } finally {
            if (stderr) {
                System.setErr(original);
            } else {
                System.setOut(original);
            }
        }
        return new Capture(exit, buffer.toString(StandardCharsets.UTF_8));
    }

    @FunctionalInterface
    private interface ThrowingInt {
        int run() throws Exception;
    }
}