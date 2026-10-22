package com.sitelock.cli;

import com.sitelock.SiteLockApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * siteblock profile &lt;nome&gt; remove &lt;dominio...&gt;.
 *
 * <p>Tirar um domínio da receita não é soltar o bloqueio: o hosts continua
 * com a linha, e o comando tem que avisar isso — é o que fecha a conta entre o
 * que a pessoa pediu e o que a máquina está fazendo.
 *
 * <p>Os testes rodam pela CLI de verdade: o nome do perfil é posicional do
 * comando {@code profile}, e um teste que chamasse o comando direto passaria
 * com o parsing quebrado.
 */
class ProfileRemoveCommandTest {

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

    private Path profilesFile() {
        return tmp.resolve("estado/profiles.json");
    }

    // Roda a CLI de verdade, com o --state-file apontando para o temporário.
    private int run(String... args) {
        List<String> todos = new ArrayList<>(List.of(args));
        todos.add("--state-file");
        todos.add(state());
        return new CommandLine(new SiteLockApplication()).execute(todos.toArray(new String[0]));
    }

    // Bloqueia e salva um perfil com os domínios dados.
    private void prepararPerfil(String nome, String... dominios) throws Exception {
        List<String> args = new ArrayList<>();
        args.add("block");
        args.addAll(List.of(dominios));
        args.add("--hosts-file");
        args.add(hosts().toString());
        assertEquals(0, run(args.toArray(new String[0])));
        assertEquals(0, run("profile", "save", nome));
    }

    private String perfil() throws Exception {
        return Files.readString(profilesFile(), StandardCharsets.UTF_8);
    }

    // Exit code + stdout/stderr de um comando.
    private record Resultado(int exit, String texto) {
    }

    private Resultado rodar(Comando cmd) throws Exception {
        PrintStream outOriginal = System.out;
        PrintStream errOriginal = System.err;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream fake = new PrintStream(buffer, true, StandardCharsets.UTF_8);
        System.setOut(fake);
        System.setErr(fake);
        int exit;
        try {
            exit = cmd.run();
        } catch (Exception e) {
            throw new AssertionError(e);
        } finally {
            System.setOut(outOriginal);
            System.setErr(errOriginal);
        }
        return new Resultado(exit, buffer.toString(StandardCharsets.UTF_8));
    }

    @FunctionalInterface
    private interface Comando {
        int run() throws Exception;
    }

    @Test
    void removeTiraODominioDaReceita() throws Exception {
        prepararPerfil("trabalho", "tiktok.com", "youtube.com");

        assertEquals(0, run("profile", "trabalho", "remove", "youtube.com"));

        String json = perfil();
        assertFalse(json.contains("youtube.com"), "o domínio tem que sair da receita: " + json);
        assertTrue(json.contains("tiktok.com"), "o resto da receita tem que continuar: " + json);
    }

    @Test
    void removeNaoTocaNoHosts() throws Exception {
        prepararPerfil("trabalho", "tiktok.com");
        String antes = Files.readString(hosts(), StandardCharsets.UTF_8);

        assertEquals(0, run("profile", "trabalho", "remove", "tiktok.com"));

        assertEquals(antes, Files.readString(hosts(), StandardCharsets.UTF_8),
                "tirar da receita não solta o bloqueio: o hosts não pode mudar");
    }

    @Test
    void removeAvisaQueODominioContinuaBloqueado() throws Exception {
        prepararPerfil("trabalho", "tiktok.com");

        Resultado r = rodar(() -> run("profile", "trabalho", "remove", "tiktok.com"));

        assertEquals(0, r.exit(), r.texto());
        assertTrue(r.texto().contains("tiktok.com"), r.texto());
        assertTrue(r.texto().toLowerCase().contains("continua bloqueado"),
                "tem que avisar que o bloqueio continua: " + r.texto());
        assertTrue(r.texto().toLowerCase().contains("unblock"),
                "tem que dizer o comando que ainda solta: " + r.texto());
    }

    @Test
    void removeFalhaQuandoODominioNaoEstaNaReceita() throws Exception {
        prepararPerfil("trabalho", "tiktok.com");
        String antes = perfil();

        Resultado r = rodar(() -> run("profile", "trabalho", "remove", "youtube.com"));

        assertEquals(1, r.exit(), r.texto());
        assertTrue(r.texto().toLowerCase().contains("nada foi alterado"),
                "a recusa tem que dizer que nada mudou: " + r.texto());
        assertEquals(antes, perfil(), "um domínio que não está na receita não pode mudar nada");
    }

    @Test
    void removeFalhaQuandoOPerfilNaoExiste() throws Exception {
        Resultado r = rodar(() -> run("profile", "nao-existe", "remove", "tiktok.com"));

        assertEquals(1, r.exit(), r.texto());
        assertTrue(r.texto().toLowerCase().contains("não existe"),
                "a recusa tem que dizer que o perfil não existe: " + r.texto());
        assertFalse(Files.exists(profilesFile()),
                "não pode criar receita no caminho de um perfil que não existe");
    }

    @Test
    void removeFalhaComNomeDePerfilInvalido() throws Exception {
        Resultado r = rodar(() -> run("profile", "../evil", "remove", "tiktok.com"));

        assertEquals(1, r.exit(), r.texto());
        assertTrue(r.texto().toLowerCase().contains("inválido"),
                "a recusa tem que dizer que o nome é inválido: " + r.texto());
    }

    @Test
    void removeFalhaComDominioInvalido() throws Exception {
        prepararPerfil("trabalho", "tiktok.com");
        String antes = perfil();

        Resultado r = rodar(() -> run("profile", "trabalho", "remove", "nao e um dominio"));

        assertEquals(1, r.exit(), r.texto());
        assertTrue(r.texto().toLowerCase().contains("erro"),
                "a recusa tem que dizer que o domínio é inválido: " + r.texto());
        assertEquals(antes, perfil(), "um domínio inválido não pode mudar nada");
    }

    // POR QUE: a leitura do estado existe só para o aviso do "continua bloqueado".
    // Falhar nela impede o aviso, não a edição da receita — desfazer a remoção
    // por causa de um arquivo quebrado seria trocar um problema pequeno por um maior.
    /**
     * O estado ilegível não pode impedir a remoção.
     */
    @Test
    void removeSobreviveAoEstadoIlegivel() throws Exception {
        prepararPerfil("trabalho", "tiktok.com");
        Files.writeString(Path.of(state()), "{ isto não é json", StandardCharsets.UTF_8);

        Resultado r = rodar(() -> run("profile", "trabalho", "remove", "tiktok.com"));

        assertEquals(0, r.exit(), r.texto());
        assertFalse(perfil().contains("tiktok.com"),
                "a remoção tem que acontecer mesmo com o estado quebrado: " + perfil());
        assertTrue(r.texto().toLowerCase().contains("não consegui ler o estado"),
                "a falha da leitura tem que ser dita: " + r.texto());
    }
}
