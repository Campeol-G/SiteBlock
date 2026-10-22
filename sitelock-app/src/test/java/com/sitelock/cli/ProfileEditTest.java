package com.sitelock.cli;

import com.sitelock.SiteLockApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Editar a receita de um perfil que já existe:
 * {@code siteblock profile <nome> add <dominio>} e
 * {@code siteblock profile <nome> remove <dominio>}.
 *
 * <p>Os testes rodam pela CLI de verdade — {@code CommandLine.execute} — e não
 * por construção direta do comando. A forma escolhida põe o nome do perfil
 * <b>antes</b> do subcomando, e isso só funciona se o picocli acoplar o
 * posicional do pai com o subcomodo certo. Um teste que chamasse
 * {@code new ProfileAddCommand(...).call()} passaria com o parsing todo
 * quebrado.
 */
class ProfileEditTest {

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
        return tmp.resolve("state/blocks.json").toString();
    }

    private Path profilesFile() {
        return tmp.resolve("state/profiles.json");
    }

    // Roda a CLI de verdade, com o --state-file apontando para o temporário.
    private int run(String... args) {
        List<String> todos = new java.util.ArrayList<>(List.of(args));
        todos.add("--state-file");
        todos.add(state());
        return new picocli.CommandLine(new SiteLockApplication()).execute(todos.toArray(new String[0]));
    }

    private void block(String... domains) throws Exception {
        assertEquals(0, new BlockCommand(List.of(domains), "4h", hosts().toString(), state()).call());
    }

    private void salvar(String nome) throws Exception {
        assertEquals(0, new ProfileSaveCommand(nome, false, hosts().toString(), state()).call());
    }

    private String perfil() throws Exception {
        return Files.readString(profilesFile(), StandardCharsets.UTF_8);
    }

    // ---- add ----

    @Test
    void addPutsTheDomainInTheProfile() throws Exception {
        block("tiktok.com");
        salvar("trabalho");

        assertEquals(0, run("profile", "trabalho", "add", "youtube.com"));

        String json = perfil();
        assertTrue(json.contains("youtube.com"), json);
        assertTrue(json.contains("tiktok.com"), "o que já estava tem que continuar: " + json);
    }

    @Test
    void addWithoutDurationIsPermanent() throws Exception {
        block("tiktok.com");
        salvar("trabalho");

        assertEquals(0, run("profile", "trabalho", "add", "youtube.com"));

        assertTrue(perfil().contains("\"youtube.com\""), perfil());
        assertEquals(-1L, duracaoDe(perfil(), "youtube.com"),
                "sem --for o domínio entra permanente: " + perfil());
    }

    @Test
    void addWithDurationStoresIt() throws Exception {
        block("tiktok.com");
        salvar("trabalho");

        assertEquals(0, run("profile", "trabalho", "add", "youtube.com", "--for", "4h"));

        long ms = duracaoDe(perfil(), "youtube.com");
        assertTrue(ms > 0, "tem que guardar a duração: " + ms);
        assertTrue(ms <= 4 * 3600_000L, "não pode estourar as 4h: " + ms);
    }

    @Test
    void addFailsWhenTheProfileDoesNotExist() {
        assertEquals(1, run("profile", "nao-existe", "add", "youtube.com"));
        assertFalse(Files.exists(profilesFile()), "não pode criar perfil no caminho do add");
    }

    @Test
    void addRejectsInvalidDomain() throws Exception {
        block("tiktok.com");
        salvar("trabalho");
        String antes = perfil();

        assertEquals(1, run("profile", "trabalho", "add", "nao e um dominio"));

        assertEquals(antes, perfil(), "um domínio inválido não pode mudar nada");
    }

    @Test
    void addReplacesTheEntryWhenTheDomainIsAlreadyThere() throws Exception {
        block("tiktok.com");
        salvar("trabalho");
        // tiktok.com entra permanente (o block foi de 4h, mas o save grava o
        // que resta); adicionar de novo sem --for mantém permanente.
        assertEquals(0, run("profile", "trabalho", "add", "tiktok.com", "--for", "10m"));
        assertEquals(0, run("profile", "trabalho", "add", "tiktok.com"));

        assertEquals(-1L, duracaoDe(perfil(), "tiktok.com"),
                "a receita tem que guardar o último pedido, não acumular: " + perfil());
    }

    @Test
    void addNormalizesTheDomain() throws Exception {
        block("tiktok.com");
        salvar("trabalho");

        assertEquals(0, run("profile", "trabalho", "add", "www.youtube.com"));

        assertTrue(perfil().contains("\"youtube.com\""), "tem que normalizar: " + perfil());
        assertFalse(perfil().contains("www.youtube.com"), perfil());
    }

    // ---- remove ----

    @Test
    void removeTakesTheDomainOutOfTheProfile() throws Exception {
        block("tiktok.com", "youtube.com");
        salvar("trabalho");

        assertEquals(0, run("profile", "trabalho", "remove", "youtube.com"));

        String json = perfil();
        assertFalse(json.contains("youtube.com"), json);
        assertTrue(json.contains("tiktok.com"), "o resto tem que continuar: " + json);
    }

    @Test
    void removeWarnsWhenTheDomainIsStillBlocked() throws Exception {
        block("tiktok.com");
        salvar("trabalho");

        String saida = capture(() -> run("profile", "trabalho", "remove", "tiktok.com"));

        assertTrue(saida.contains("tiktok.com"), saida);
        assertTrue(saida.toLowerCase().contains("continua bloqueado"),
                "tem que avisar que o bloqueio continua: " + saida);
    }

    @Test
    void removeFailsWhenTheDomainIsNotInTheProfile() throws Exception {
        block("tiktok.com");
        salvar("trabalho");
        String antes = perfil();

        assertEquals(1, run("profile", "trabalho", "remove", "youtube.com"));

        assertEquals(antes, perfil());
    }

    // ---- a forma do comando não pode quebrar os outros ----

    @Test
    void theOtherProfileCommandsStillWork() throws Exception {
        block("tiktok.com");
        salvar("trabalho");

        assertEquals(0, run("profile", "list"));
        assertEquals(0, run("profile", "show", "trabalho"));
        assertEquals(0, run("profile", "apply", "trabalho", "--hosts-file", hosts().toString()));
    }

    /**
     * O nome do perfil virou posicional do comando {@code profile}. O perigo
     * concreto é o picocli passar a engolir o nome dos subcomandos que já o
     * tinham como posicional próprio — {@code profile save trabalho} tem que
     * continuar funcionando, senão o comando inteiro quebra.
     */
    @Test
    void saveAndDeleteStillTakeTheirOwnName() throws Exception {
        block("tiktok.com");

        assertEquals(0, run("profile", "save", "trabalho"));
        assertTrue(perfil().contains("tiktok.com"), perfil());

        // O delete pede confirmação digitada lendo o System.in.
        java.io.InputStream originalIn = System.in;
        System.setIn(new java.io.ByteArrayInputStream("SIM\n".getBytes(StandardCharsets.UTF_8)));
        try {
            assertEquals(0, run("profile", "delete", "trabalho"));
        } finally {
            System.setIn(originalIn);
        }
        // O delete esvazia o profiles.json; o que tem que sumir é o perfil.
        assertFalse(perfil().contains("trabalho"), "o perfil tem que ter sumido: " + perfil());
        assertEquals(1, run("profile", "show", "trabalho"));
    }

    @Test
    void addWithoutANameSaysHowToCallIt() {
        String saida = capture(() -> run("profile", "add", "youtube.com"));

        assertTrue(saida.contains("siteblock profile <nome> add <dominio>"),
                "a mensagem tem que mostrar a forma inteira: " + saida);
    }

    @Test
    void addRejectsAnInvalidProfileName() {
        assertEquals(1, run("profile", "../evil", "add", "youtube.com"));
    }

    // ---- o relógio é injetável ----

    /**
     * O {@code add} e o {@code remove} usavam {@code Instant.now()} direto,
     * enquanto o resto do projeto injeta {@link java.time.Clock}. Com o
     * relógio cru não dá para testar com tempo controlado — e o que se grava
     * depende do instante.
     */
    @Test
    void addGravaComOInstanteInjetado() throws Exception {
        block("tiktok.com");
        salvar("trabalho");
        Instant fixo = Instant.parse("2020-01-02T03:04:05Z");

        ProfileCommand parent = new ProfileCommand();
        parent.name = "trabalho";
        ProfileAddCommand cmd = new ProfileAddCommand();
        cmd.parent = parent;
        cmd.domainsRaw = List.of("youtube.com");
        cmd.stateFileOverride = state();
        cmd.clockOverride = Clock.fixed(fixo, ZoneOffset.UTC);

        assertEquals(0, cmd.call());

        assertTrue(perfil().contains("2020-01-02T03:04:05Z"),
                "o updatedAt tem que sair do relógio injetado: " + perfil());
    }

    @Test
    void removeGravaComOInstanteInjetado() throws Exception {
        block("tiktok.com", "youtube.com");
        salvar("trabalho");
        Instant fixo = Instant.parse("2020-01-02T03:04:05Z");

        ProfileCommand parent = new ProfileCommand();
        parent.name = "trabalho";
        ProfileRemoveCommand cmd = new ProfileRemoveCommand();
        cmd.parent = parent;
        cmd.domainsRaw = List.of("youtube.com");
        cmd.stateFileOverride = state();
        cmd.clockOverride = Clock.fixed(fixo, ZoneOffset.UTC);

        assertEquals(0, cmd.call());

        assertTrue(perfil().contains("2020-01-02T03:04:05Z"),
                "o updatedAt tem que sair do relógio injetado: " + perfil());
    }

    /**
     * O aviso de "continua bloqueado" também depende do relógio: com o
     * bloqueio já vencido, não há o que avisar.
     */
    @Test
    void removeUsaOInjetadoParaSaberOQueContinuaBloqueado() throws Exception {
        block("tiktok.com");
        salvar("trabalho");

        ProfileCommand parent = new ProfileCommand();
        parent.name = "trabalho";
        ProfileRemoveCommand cmd = new ProfileRemoveCommand();
        cmd.parent = parent;
        cmd.domainsRaw = List.of("tiktok.com");
        cmd.stateFileOverride = state();
        // O bloqueio de 4h já venceu nesse instante.
        cmd.clockOverride = Clock.fixed(Instant.now().plusSeconds(10 * 3600), ZoneOffset.UTC);

        String saida = capture(() -> cmd.call());

        assertFalse(saida.toLowerCase().contains("continua bloqueado"),
                "bloqueio vencido não pode gerar aviso: " + saida);
    }

    // ---- helpers ----

    private static long duracaoDe(String json, String domain) {
        int i = json.indexOf("\"" + domain + "\"");
        assertTrue(i >= 0, "domínio ausente do JSON: " + json);
        int j = json.indexOf("durationMillis", i);
        assertTrue(j > 0, "durationMillis ausente: " + json);
        String resto = json.substring(j);
        int abre = resto.indexOf(':');
        int fecha = resto.indexOf('\n', abre);
        String num = resto.substring(abre + 1, fecha).replace(",", "").replace(" ", "").trim();
        return "null".equals(num) ? -1L : Long.parseLong(num);
    }

    private static String capture(Runnable r) {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        try {
            r.run();
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }
}
