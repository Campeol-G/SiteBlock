package com.sitelock.cli;

import com.sitelock.core.ExpirationDaemonState;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * O log do daemon: um por execução, com tamanho controlado e legível pelo
 * usuário.
 *
 * <p>O log é criado pelo pai do {@code daemon start}, que roda como root, e o
 * filho só ANEXA nele. Sem rotação, o arquivo cresce para sempre e o
 * {@code printLogTail} — que existe para mostrar o erro de AGORA — mostra a
 * cauda de uma execução antiga.
 */
class DaemonLogRotationTest {

    @TempDir
    Path tmp;

    @Test
    void oLogRecomecaVazioParaOTailMostrarOErroDeAgora() throws Exception {
        Path log = ExpirationDaemonState.logPath(tmp);
        Files.writeString(log, "texto de ajuda do picocli de uma execucao antiga\n".repeat(20),
                StandardCharsets.UTF_8);

        DaemonStartCommand.prepararLog(log);

        assertEquals("", Files.readString(log, StandardCharsets.UTF_8),
                "o log tem que comecar vazio: o tail mostra o erro de AGORA, nao o de outro dia");
    }

    @Test
    void aExecucaoAnteriorFicaNoLogRotacionado() throws Exception {
        Path log = ExpirationDaemonState.logPath(tmp);
        Files.writeString(log, "erro antigo\n", StandardCharsets.UTF_8);

        DaemonStartCommand.prepararLog(log);

        Path rotacionado = log.resolveSibling(log.getFileName() + ".1");
        assertTrue(Files.exists(rotacionado), "o log anterior nao pode ser jogado fora");
        assertTrue(Files.readString(rotacionado, StandardCharsets.UTF_8).contains("erro antigo"),
                "o log anterior tem que ficar legivel no arquivo rotacionado");
    }

    @Test
    void oLogRotacionadoTemTamanhoLimitado() throws Exception {
        Path log = ExpirationDaemonState.logPath(tmp);
        Files.writeString(log, "x".repeat((int) (DaemonStartCommand.LOG_MAX_BYTES * 3)),
                StandardCharsets.UTF_8);

        DaemonStartCommand.prepararLog(log);

        Path rotacionado = log.resolveSibling(log.getFileName() + ".1");
        long tamanho = Files.size(rotacionado);
        assertTrue(tamanho <= DaemonStartCommand.LOG_MAX_BYTES,
                "o log anterior tem que caber no teto, mas ficou com " + tamanho + " bytes");
    }

    @Test
    void aPosseDoLogVoltaParaOUsuarioReal() throws Exception {
        // O daemon roda como root: sem chown, o log fica legivel so por ele —
        // e a promessa de que o status funciona sem elevacao vai embora.
        String usuario = System.getProperty("user.name", "");
        UserPrincipalLookupService lookup = tmp.getFileSystem().getUserPrincipalLookupService();
        GroupPrincipal esperado;
        try {
            esperado = lookup.lookupPrincipalByGroupName(usuario);
        } catch (Exception e) {
            esperado = null;
        }
        Optional<String> outro = grupoSecundario(usuario);
        Assumptions.assumeTrue(esperado != null && outro.isPresent(),
                "precisa de um grupo com o nome do usuario e de um grupo secundario dele");

        Path log = ExpirationDaemonState.logPath(tmp);
        Files.writeString(log, "conteudo\n", StandardCharsets.UTF_8);
        PosixFileAttributeView view = Files.getFileAttributeView(log, PosixFileAttributeView.class);
        view.setGroup(lookup.lookupPrincipalByGroupName(outro.get()));

        String salvo = System.getProperty("sitelock.user");
        System.setProperty("sitelock.user", usuario);
        try {
            DaemonStartCommand.prepararLog(log);
        } finally {
            if (salvo == null) {
                System.clearProperty("sitelock.user");
            } else {
                System.setProperty("sitelock.user", salvo);
            }
        }

        assertEquals(esperado.getName(), view.readAttributes().group().getName(),
                "o log tem que voltar para o usuario real, como o daemon.json");
    }

    /**
     * O comando de verdade: o log que o filho escreve tem que ser o desta
     * execução, e não a cauda da anterior.
     */
    @Test
    void oStartDeVerdadeRotacionaOLog() throws Exception {
        Path hosts = tmp.resolve("hosts");
        Files.writeString(hosts, "127.0.0.1 localhost\n", StandardCharsets.UTF_8);
        Path estado = tmp.resolve("estado");
        Files.createDirectories(estado);
        Path log = ExpirationDaemonState.logPath(estado);
        Files.writeString(log, "erro de uma execucao antiga\n", StandardCharsets.UTF_8);

        int exit;
        try {
            exit = new DaemonStartCommand("60", hosts.toString(),
                    estado.resolve("blocks.json").toString()).call();
        } finally {
            matarDaemon(estado);
        }

        assertEquals(0, exit, "o daemon tinha que subir");
        String conteudo = Files.readString(log, StandardCharsets.UTF_8);
        assertFalse(conteudo.contains("execucao antiga"),
                "o log ainda tem o texto da execução anterior:\n" + conteudo);
        assertTrue(conteudo.contains("daemon de expiração no ar"),
                "o log tem que ser o da execução de agora:\n" + conteudo);
    }

    // Mata o daemon que o start subiu, se ele tiver subido.
    private static void matarDaemon(Path estado) {
        try {
            ExpirationDaemonState.read(estado).ifPresent(state -> {
                ProcessHandle.of(state.getPid()).ifPresent(ProcessosDeTeste::matar);
            });
        } catch (IOException e) {
            // o teste que importa é o da rotação
        }
    }

    // Um grupo do qual o usuario participa e que nao e o principal dele.
    private static Optional<String> grupoSecundario(String usuario) {        Path etc = Path.of("/etc/group");
        if (!Files.isReadable(etc)) {
            return Optional.empty();
        }
        try {
            for (String linha : Files.readAllLines(etc, StandardCharsets.UTF_8)) {
                String[] partes = linha.split(":");
                if (partes.length < 4 || partes[0].equals(usuario)) {
                    continue;
                }
                for (String membro : partes[3].split(",")) {
                    if (membro.equals(usuario)) {
                        return Optional.of(partes[0]);
                    }
                }
            }
        } catch (IOException e) {
            return Optional.empty();
        }
        return Optional.empty();
    }
}
