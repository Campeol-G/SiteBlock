package com.sitelock.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Estado e lock de instância única do daemon.
 *
 * <p>O lock é {@code FileLock} e não "está rodando porque o PID existe": um
 * {@code kill -9} não órfãoa um {@code FileLock}, porque o SO o libera na
 * morte do processo. Um lock por PID sobreviveria ao processo e travaria o
 * próximo boot.
 */
class ExpirationDaemonStateTest {

    @TempDir
    Path tmp;

    private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");

    @Test
    void roundTripPreservesEveryField() throws Exception {
        Instant last = NOW.plusSeconds(900);
        ExpirationDaemonState state = new ExpirationDaemonState(4242, NOW,
                tmp.toString(), last);

        ExpirationDaemonState.write(tmp, state);
        Optional<ExpirationDaemonState> read = ExpirationDaemonState.read(tmp);

        assertTrue(read.isPresent());
        assertEquals(4242, read.get().getPid());
        assertEquals(NOW, read.get().getStartedAt());
        assertEquals(tmp.toString(), read.get().getStateDir());
        assertEquals(last, read.get().getLastSweepAt());
    }

    @Test
    void missingFileReadsEmpty() throws Exception {
        assertTrue(ExpirationDaemonState.read(tmp).isEmpty());
    }

    @Test
    void emptyFileReadsEmpty() throws Exception {
        Files.writeString(ExpirationDaemonState.statePath(tmp), "   ", StandardCharsets.UTF_8);
        assertTrue(ExpirationDaemonState.read(tmp).isEmpty());
    }

    @Test
    void updateLastSweepKeepsPidAndStartedAt() throws Exception {
        ExpirationDaemonState.write(tmp, new ExpirationDaemonState(4242, NOW, tmp.toString(), null));

        Instant depois = NOW.plusSeconds(300);
        ExpirationDaemonState.updateLastSweep(tmp, depois);

        ExpirationDaemonState state = ExpirationDaemonState.read(tmp).orElseThrow();
        assertEquals(depois, state.getLastSweepAt());
        assertEquals(4242, state.getPid(), "atualizar o sweep não pode trocar o dono do registro");
        assertEquals(NOW, state.getStartedAt());
        assertEquals(tmp.toString(), state.getStateDir());
    }

    // POR QUE: a escrita substitui o arquivo inteiro em vez de truncar o mesmo —
    // um Files.writeString deixa o daemon.json vazio entre o truncate e o write,
    // e quem lê nesse instante ("daemon status", "daemon stop") recebe "não há daemon".
    @Test
    void aEscritaSubstituiOArquivoEmVezDeTruncar() throws Exception {
        Path alvo = ExpirationDaemonState.statePath(tmp);
        ExpirationDaemonState.write(tmp, new ExpirationDaemonState(1, NOW, tmp.toString(), null));
        String antes = identidadeDoArquivo(alvo);
        ExpirationDaemonState.write(tmp, new ExpirationDaemonState(2, NOW, tmp.toString(), null));
        String depois = identidadeDoArquivo(alvo);

        Assumptions.assumeFalse(antes.isEmpty(), "este SO não expõe a identidade do arquivo");
        assertNotEquals(antes, depois,
                "a escrita tem que ser atômica: um arquivo novo substitui o antigo, "
                        + "senão quem lê no meio da escrita vê o registro truncado");
        assertFalse(Files.exists(alvo.resolveSibling(alvo.getFileName() + ".tmp")),
                "o arquivo temporário tem que sumir depois da troca");
    }

    // Identidade do arquivo: dois arquivos com a mesma identidade são o mesmo.
    private static String identidadeDoArquivo(Path arquivo) throws IOException {
        Object chave = Files.readAttributes(arquivo, BasicFileAttributes.class).fileKey();
        if (chave != null) {
            return chave.toString();
        }
        try {
            return Files.getAttribute(arquivo, "unix:ino").toString();
        } catch (UnsupportedOperationException | IOException e) {
            return "";
        }
    }

    // POR QUE: o SO recicla PID, e "o PID existe" mente: o daemon stop mandaria
    // um SIGTERM num processo que não é o SiteLock.
    @Test
    void umPidRecicladoNaoPassaPorDaemonVivo() {
        ExpirationDaemonState reciclado = new ExpirationDaemonState(
                ProcessHandle.current().pid(), NOW, "/x", null, NOW);

        assertFalse(ExpirationDaemonState.isAlive(reciclado),
                "o PID existe, mas não é o processo que o registro descreve");
    }

    @Test
    void oProcessoQueGravouORegistroEstaVivo() {
        Instant inicio = ProcessHandle.current().info().startInstant().orElseThrow();
        ExpirationDaemonState state = new ExpirationDaemonState(
                ProcessHandle.current().pid(), inicio, "/x", null, inicio);

        assertTrue(ExpirationDaemonState.isAlive(state),
                "o processo que escreveu o registro tem que ser reconhecido como vivo");
    }

    @Test
    void registroAntigoSemOInstanteContinuaFuncionando() throws Exception {
        // Um daemon.json escrito por uma versão antiga não tem o instante de
        // início: sem ele, sobra a checagem do PID.
        Files.writeString(ExpirationDaemonState.statePath(tmp),
                "{\"pid\":" + ProcessHandle.current().pid()
                        + ",\"startedAt\":\"2026-10-15T12:00:00Z\",\"stateDir\":\"/x\"}",
                StandardCharsets.UTF_8);

        ExpirationDaemonState state = ExpirationDaemonState.read(tmp).orElseThrow();

        assertNull(state.getProcessStartedAt(), "o registro antigo não tem o campo novo");
        assertTrue(ExpirationDaemonState.isAlive(state),
                "um registro antigo tem que continuar funcionando: só a checagem do PID");
    }

    @Test
    void aEscritaGravaOInstanteDeInicioDoProcesso() throws Exception {
        ExpirationDaemonState state = new ExpirationDaemonState(
                ProcessHandle.current().pid(), NOW, tmp.toString(), null);

        ExpirationDaemonState.write(tmp, state);

        ExpirationDaemonState lido = ExpirationDaemonState.read(tmp).orElseThrow();
        assertEquals(ProcessHandle.current().info().startInstant().orElseThrow(),
                lido.getProcessStartedAt(),
                "o registro tem que guardar o instante de início do PROCESSO");
        assertEquals(lido.getProcessStartedAt(), lido.getStartedAt(),
                "o 'Subiu em:' tem que mostrar o instante de início do processo");
    }

    // POR QUE: updateLastSweep é read-modify-write. Sem trava, ele lê o registro
    // e outra escrita grava o PID novo nesse intervalo — o updateLastSweep
    // devolve o registro velho por cima, e o PID do daemon some.
    @Test
    void escritasConcorrentesNaoRevertemOPid() throws Exception {
        ExpirationDaemonState.write(tmp, new ExpirationDaemonState(1, NOW, tmp.toString(), null));

        int voltas = 300;
        CyclicBarrier largada = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> quemGrava = pool.submit(() -> {
                largada.await();
                for (int i = 0; i < voltas; i++) {
                    ExpirationDaemonState.write(tmp,
                            new ExpirationDaemonState(1000 + i, NOW, tmp.toString(), null));
                }
                return null;
            });
            Future<?> quemAtualiza = pool.submit(() -> {
                largada.await();
                for (int i = 0; i < voltas; i++) {
                    ExpirationDaemonState.updateLastSweep(tmp, NOW.plusSeconds(i));
                }
                return null;
            });
            quemGrava.get(60, TimeUnit.SECONDS);
            quemAtualiza.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        ExpirationDaemonState state = ExpirationDaemonState.read(tmp).orElseThrow();
        assertEquals(1000 + voltas - 1, state.getPid(),
                "o PID não pode voltar atrás: um updateLastSweep devolveu o registro antigo");
    }

    @Test
    void deleteRemovesTheStateFile() throws Exception {        ExpirationDaemonState.write(tmp, new ExpirationDaemonState(1, NOW, tmp.toString(), null));
        ExpirationDaemonState.delete(tmp);
        assertFalse(Files.exists(ExpirationDaemonState.statePath(tmp)));
    }

    @Test
    void isAliveFollowsThePid() {
        ExpirationDaemonState vivo =
                new ExpirationDaemonState(ProcessHandle.current().pid(), NOW, tmp.toString(), null);
        assertTrue(ExpirationDaemonState.isAlive(vivo));

        // PID 0 e um PID improvável não são processos vivos.
        assertFalse(ExpirationDaemonState.isAlive(new ExpirationDaemonState(0, NOW, "/x", null)));
        assertFalse(ExpirationDaemonState.isAlive(
                new ExpirationDaemonState(Integer.MAX_VALUE + 7, NOW, "/x", null)));
        assertFalse(ExpirationDaemonState.isAlive(null));
    }

    @Test
    void acquireIsExclusive() throws Exception {
        ExpirationDaemonLock primeiro = ExpirationDaemonLock.acquire(tmp);
        assertNotNull(primeiro);

        IOException erro = assertThrows(IOException.class, () -> ExpirationDaemonLock.acquire(tmp));
        assertTrue(erro.getMessage().contains("daemon de expiração rodando"), erro.getMessage());
        assertTrue(erro.getMessage().contains("daemon stop"), "a mensagem tem que dizer o que fazer");

        primeiro.close();
        // Liberado: outro daemon pode pegar.
        ExpirationDaemonLock segundo = ExpirationDaemonLock.acquire(tmp);
        assertNotNull(segundo);
        segundo.close();
    }

    @Test
    void lockFileIsCreatedInTheStateDir() throws Exception {
        Path estado = tmp.resolve("estado");
        ExpirationDaemonLock lock = ExpirationDaemonLock.acquire(estado);
        try {
            assertTrue(Files.exists(estado.resolve("daemon.lock")));
        } finally {
            lock.close();
        }
    }

    @Test
    void chownToUserIfConfiguredFollowsTheProperty() throws Exception {
        String usuarioAtual = System.getProperty("user.name", "");
        Assumptions.assumeFalse(usuarioAtual.isBlank(), "o SO não disse quem é o usuário");
        Assumptions.assumeFalse("root".equals(usuarioAtual),
                "rodando como root, chown para si mesmo não prova nada");

        Path arquivo = tmp.resolve("estado.json");
        Files.writeString(arquivo, "{}", StandardCharsets.UTF_8);

        UserPrincipalLookupService lookup = arquivo.getFileSystem().getUserPrincipalLookupService();
        Assumptions.assumeTrue(Files.getFileAttributeView(arquivo, PosixFileAttributeView.class) != null,
                "sem POSIX não há grupo para verificar");
        String grupoDoUsuario = nomeDoGrupoDoUsuario(usuarioAtual, lookup);
        Assumptions.assumeTrue(grupoDoUsuario != null,
                "o usuário não tem grupo com o mesmo nome: o chown não troca o grupo");
        String grupoSecundario = grupoSecundarioDoUsuario(usuarioAtual, grupoDoUsuario);
        Assumptions.assumeTrue(grupoSecundario != null,
                "o usuário não tem grupo secundário: não há como distinguir o chown de um no-op");

        Assumptions.assumeTrue(trocaGrupo(arquivo, lookup, grupoSecundario),
                "não deu para trocar o grupo do arquivo de teste");
        assertEquals(grupoSecundario, nomeDoGrupo(arquivo),
                "pré-condição: o arquivo tem que começar com o grupo trocado");

        System.setProperty("sitelock.user", usuarioAtual);
        SiteLockPaths.chownToUserIfConfigured(arquivo);

        assertEquals(usuarioAtual, Files.getOwner(arquivo).getName(),
                "com -Dsitelock.user o daemon devolve o arquivo ao dono mesmo sem SUDO_USER");
        assertEquals(grupoDoUsuario, nomeDoGrupo(arquivo),
                "o chown tem que devolver o grupo ao do usuário: com o chown virado em no-op "
                        + "o arquivo fica com o grupo trocado");
    }

    @Test
    void chownToUserIfConfiguredWithoutPropertyDoesNothing() throws Exception {
        String usuarioAtual = System.getProperty("user.name", "");
        Assumptions.assumeFalse(usuarioAtual.isBlank(), "o SO não disse quem é o usuário");
        String sudoUser = System.getenv("SUDO_USER");
        Assumptions.assumeTrue(sudoUser == null || sudoUser.isBlank()
                        || "root".equals(sudoUser.strip()),
                "sob sudo o caminho sem property é outro: quem devolve a posse é o SUDO_USER");

        Path arquivo = tmp.resolve("sem-property.json");
        Files.writeString(arquivo, "{}", StandardCharsets.UTF_8);

        UserPrincipalLookupService lookup = arquivo.getFileSystem().getUserPrincipalLookupService();
        Assumptions.assumeTrue(Files.getFileAttributeView(arquivo, PosixFileAttributeView.class) != null,
                "sem POSIX não há grupo para verificar");
        String grupoDoUsuario = nomeDoGrupoDoUsuario(usuarioAtual, lookup);
        Assumptions.assumeTrue(grupoDoUsuario != null,
                "o usuário não tem grupo com o mesmo nome: o chown não troca o grupo");
        String grupoSecundario = grupoSecundarioDoUsuario(usuarioAtual, grupoDoUsuario);
        Assumptions.assumeTrue(grupoSecundario != null,
                "o usuário não tem grupo secundário: não há como distinguir o chown de um no-op");
        Assumptions.assumeTrue(trocaGrupo(arquivo, lookup, grupoSecundario),
                "não deu para trocar o grupo do arquivo de teste");

        System.clearProperty("sitelock.user");
        SiteLockPaths.chownToUserIfConfigured(arquivo);

        assertEquals(grupoSecundario, nomeDoGrupo(arquivo),
                "sem a property o chown não pode rodar: o grupo tem que continuar como estava");
        assertEquals(usuarioAtual, Files.getOwner(arquivo).getName(),
                "sem a property, o comportamento é o de sempre: só SUDO_USER devolve a posse");
    }

    // POR QUE: a property vive na JVM do surefire (uma por módulo). Se ficar de
    // pé depois de uma falha, todo save seguinte passa pelo caminho do chown — e
    // a ordem de execução dos testes passa a importar.
    @AfterEach
    void limpaAPropertyDoUsuario() {
        System.clearProperty("sitelock.user");
    }

    // ATENÇÃO: nem todo sistema tem grupo com o mesmo nome do usuário (no macOS
    // o grupo principal se chama "staff"). Sem esse grupo o chown não troca o
    // grupo, e o teste não teria o que verificar.
    private static String nomeDoGrupoDoUsuario(String usuario, UserPrincipalLookupService lookup) {
        try {
            GroupPrincipal grupo = lookup.lookupPrincipalByGroupName(usuario);
            return grupo.getName();
        } catch (IOException | IllegalArgumentException e) {
            return null;
        }
    }

    // POR QUE: sem um grupo diferente, não dá para trocar o grupo do arquivo — e
    // o grupo é a única diferença observável entre "o chown rodou" e "o chown foi
    // um no-op".
    private static String grupoSecundarioDoUsuario(String usuario, String grupoDoChown) {
        Path arquivoDeGrupos = Path.of("/etc/group");
        if (!Files.isReadable(arquivoDeGrupos)) {
            return null;
        }
        try {
            for (String linha : Files.readAllLines(arquivoDeGrupos, StandardCharsets.UTF_8)) {
                String[] partes = linha.split(":");
                if (partes.length < 4 || partes[0].equals(grupoDoChown)) {
                    continue;
                }
                for (String membro : partes[3].split(",")) {
                    if (usuario.equals(membro.strip())) {
                        return partes[0];
                    }
                }
            }
        } catch (IOException e) {
            return null;
        }
        return null;
    }

    // Troca o grupo do arquivo (o dono pode mudar para um grupo seu).
    private static boolean trocaGrupo(Path arquivo, UserPrincipalLookupService lookup, String grupo) {
        try {
            PosixFileAttributeView posix =
                    Files.getFileAttributeView(arquivo, PosixFileAttributeView.class);
            posix.setGroup(lookup.lookupPrincipalByGroupName(grupo));
            return true;
        } catch (IOException | IllegalArgumentException | UnsupportedOperationException e) {
            return false;
        }
    }

    // O grupo do arquivo, pelo nome.
    private static String nomeDoGrupo(Path arquivo) throws IOException {
        return Files.readAttributes(arquivo, PosixFileAttributes.class).group().getName();
    }
}