package com.sitelock.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sitelock.core.Profile;
import com.sitelock.core.ProfileStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** profile save/list/show/delete contra arquivos temporários. */
class ProfileCommandsTest {

    @TempDir
    Path tmp;

    // Criar uma vez: reescrever apagaria os bloqueios que o teste acabou de fazer.
    private Path hosts() throws Exception {
        if (hosts == null) {
            hosts = tmp.resolve("hosts");
            Files.writeString(hosts, "127.0.0.1 localhost\n", StandardCharsets.UTF_8);
        }
        return hosts;
    }

    private Path hosts;

    private String state() {
        return tmp.resolve("state/blocks.json").toString();
    }

    private Path profilesFile() {
        return tmp.resolve("state/profiles.json");
    }

    private void block(String... domains) throws Exception {
        assertEquals(0, new BlockCommand(List.of(domains), "4h", hosts().toString(), state()).call());
    }

    @Test
    void saveStoresRemainingDurations() throws Exception {
        block("tiktok.com");
        assertEquals(0, new ProfileSaveCommand("trabalho", false, hosts().toString(), state()).call());

        String json = Files.readString(profilesFile(), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"trabalho\""), json);
        assertTrue(json.contains("tiktok.com"), json);
        assertTrue(json.contains("durationMillis"), json);

        // O que se grava é o que resta, não as 4h originais: um profile é receita.
        long ms = extractedDurationMillis(json, "tiktok.com");
        assertTrue(ms > 0, "prazo restante deve ser positivo: " + ms);
        assertTrue(ms < 4 * 3600_000L, "não pode gravar as 4h originais: " + ms);
    }

    @Test
    void saveFailsWithNoActiveBlocks() throws Exception {
        int exit = new ProfileSaveCommand("vazio", false, hosts().toString(), state()).call();
        assertEquals(1, exit);
        assertFalse(Files.exists(profilesFile()), "perfil vazio não faz nada e esconde o erro");
    }

    @Test
    void saveRefusesToOverwriteWithoutForce() throws Exception {
        block("tiktok.com");
        assertEquals(0, new ProfileSaveCommand("trabalho", false, hosts().toString(), state()).call());
        String primeiro = Files.readString(profilesFile(), StandardCharsets.UTF_8);

        block("youtube.com");
        assertEquals(1, new ProfileSaveCommand("trabalho", false, hosts().toString(), state()).call());
        assertEquals(primeiro, Files.readString(profilesFile(), StandardCharsets.UTF_8),
                "sem --force o perfil existente tem que ficar intacto");

        assertEquals(0, new ProfileSaveCommand("trabalho", true, hosts().toString(), state()).call());
        assertTrue(Files.readString(profilesFile(), StandardCharsets.UTF_8).contains("youtube.com"));
    }

    @Test
    void saveRejectsInvalidName() throws Exception {
        block("tiktok.com");
        assertEquals(1, new ProfileSaveCommand("../evil", false, hosts().toString(), state()).call());
    }

    @Test
    void addRejectsDurationThatWouldOverflowMillis() throws Exception {
        block("tiktok.com");
        assertEquals(0, new ProfileSaveCommand("trabalho", false,
                hosts().toString(), state()).call());

        ProfileCommand parent = new ProfileCommand();
        parent.name = "trabalho";
        ProfileAddCommand cmd = new ProfileAddCommand();
        cmd.parent = parent;
        cmd.domainsRaw = List.of("youtube.com");
        cmd.forDuration = "999999999999d";
        cmd.stateFileOverride = state();

        assertEquals(1, cmd.call());
        assertFalse(Files.readString(profilesFile(), StandardCharsets.UTF_8).contains("youtube.com"),
                "duração inválida não pode gravar entrada no perfil");
    }

    @Test
    void saveSkipsExpiredThatAreStillPending() throws Exception {
        // hosts não gravável: o sweep devolve skippedDueToPermission e o
        // expirado continua no estado. Ele não pode entrar no perfil — senão
        // aplicar a receita amanhã re-bloquearia um domínio que já venceu.
        Path sf = Path.of(state());
        Files.createDirectories(sf.getParent());
        Files.writeString(sf, """
                {
                  "vencido.com" : {
                    "domain" : "vencido.com",
                    "createdAt" : "2020-01-01T00:00:00Z",
                    "expiresAt" : "2020-01-01T01:00:00Z"
                  },
                  "vivo.com" : {
                    "domain" : "vivo.com",
                    "createdAt" : "2020-01-01T00:00:00Z",
                    "expiresAt" : null
                  }
                }
                """, StandardCharsets.UTF_8);

        Path semGravacao = tmp.resolve("sem-pai/hosts");
        assertEquals(0, new ProfileSaveCommand("limpo", false, semGravacao.toString(), state()).call());

        String perfil = Files.readString(profilesFile(), StandardCharsets.UTF_8);
        assertFalse(perfil.contains("vencido.com"),
                "expirado pendente não pode virar entrada de perfil: " + perfil);
        assertTrue(perfil.contains("vivo.com"), perfil);
    }

    /**
     * Salvar um perfil não pode apagar a receita que outro terminal gravou.
     *
     * <p>O comando lia as receitas e gravava o arquivo inteiro: uma receita
     * escrita no meio do caminho sumia. O teste é determinístico — o outro
     * escritor grava o arquivo por fora da trava, no momento em que o comando
     * carrega as receitas.
     */
    @Test
    void saveNaoPerdeAReceitaEscritaPorOutroTerminal() throws Exception {
        block("tiktok.com");

        ProfileStore espiao = new ProfileStore(profilesFile()) {
            @Override
            public Map<String, Profile> load() throws IOException {
                Map<String, Profile> perfis = super.load();
                escreverReceitaPorForaDaTrava(profilesFile(), "outro");
                return perfis;
            }
        };

        ProfileSaveCommand cmd = new ProfileSaveCommand("trabalho", false,
                hosts().toString(), state());
        cmd.storeOverride = espiao;
        assertEquals(0, cmd.call());

        Map<String, Profile> depois = new ProfileStore(profilesFile()).load();
        assertTrue(depois.containsKey("trabalho"),
                "o perfil salvo tem que estar lá: " + depois.keySet());
        assertTrue(depois.containsKey("outro"),
                "a receita escrita por outro terminal não pode se perder: " + depois.keySet());
    }

    // Grava o profiles.json por fora da trava, como um escritor que não a respeita.
    private void escreverReceitaPorForaDaTrava(Path file, String nome) throws IOException {
        String json = """
                {
                  "version" : 1,
                  "profiles" : {
                    "%s" : {
                      "createdAt" : "2026-10-15T12:02:11Z",
                      "updatedAt" : "2026-10-15T12:02:11Z",
                      "entries" : {
                        "outro.com" : { "durationMillis" : null }
                      }
                    }
                  }
                }
                """.formatted(nome);
        Files.writeString(file, json, StandardCharsets.UTF_8);
    }

    @Test
    void listShowsNameAndCount() throws Exception {        block("tiktok.com", "youtube.com");
        assertEquals(0, new ProfileSaveCommand("trabalho", false, hosts().toString(), state()).call());
        assertEquals(0, new ProfileSaveCommand("descanso", false, hosts().toString(), state()).call());

        String out = capture(() -> new ProfileListCommand(state()).call());
        assertTrue(out.contains("trabalho"), out);
        assertTrue(out.contains("descanso"), out);
        assertTrue(out.contains("2"), "tem que mostrar o número de domínios: " + out);
    }

    @Test
    void listWithNoProfilesSaysSo() {
        String out = capture(() -> new ProfileListCommand(state()).call());
        assertEquals(0, new ProfileListCommand(state()).call());
        assertTrue(out.toLowerCase().contains("nenhum"), out);
    }

    @Test
    void showListsDomainsWithDurations() throws Exception {
        assertEquals(0, new BlockCommand(List.of("tiktok.com"), null, hosts().toString(), state()).call());
        assertEquals(0, new ProfileSaveCommand("permanentes", false, hosts().toString(), state()).call());

        String out = capture(() -> new ProfileShowCommand("permanentes", state()).call());
        assertTrue(out.contains("tiktok.com"), out);
        assertTrue(out.contains("permanente"), out);
    }

    @Test
    void showUnknownProfileFails() throws Exception {
        assertEquals(1, new ProfileShowCommand("nao-existe", state()).call());
    }

    @Test
    void deleteRemovesProfile() throws Exception {
        block("tiktok.com");
        assertEquals(0, new ProfileSaveCommand("temporario", false, hosts().toString(), state()).call());

        assertEquals(0, new ProfileDeleteCommand("temporario", "SIM", state()).call());

        assertEquals(1, new ProfileShowCommand("temporario", state()).call());
    }

    @Test
    void deleteCancelledKeepsProfile() throws Exception {
        block("tiktok.com");
        assertEquals(0, new ProfileSaveCommand("temporario", false, hosts().toString(), state()).call());

        assertEquals(3, new ProfileDeleteCommand("temporario", "nao", state()).call());

        assertEquals(0, new ProfileShowCommand("temporario", state()).call(),
                "cancelar não pode apagar o perfil");
    }

    @Test
    void deleteUnknownProfileFails() throws Exception {
        assertEquals(1, new ProfileDeleteCommand("nao-existe", "SIM", state()).call());
    }

    private static long extractedDurationMillis(String json, String domain) {
        int i = json.indexOf("\"" + domain + "\"");
        assertTrue(i >= 0, "domínio ausente do JSON: " + json);
        int j = json.indexOf("durationMillis", i);
        assertTrue(j > 0, "durationMillis ausente: " + json);
        String resto = json.substring(j);
        int abre = resto.indexOf(':');
        int fecha = resto.indexOf('\n', abre);
        String num = resto.substring(abre + 1, fecha).replace(",", "").replace(" ", "").trim();
        return Long.parseLong(num);
    }

    private static String capture(Runnable r) {
        java.io.PrintStream original = System.out;
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        System.setOut(new java.io.PrintStream(buffer, true, java.nio.charset.StandardCharsets.UTF_8));
        try {
            r.run();
        } finally {
            System.setOut(original);
        }
        return buffer.toString(java.nio.charset.StandardCharsets.UTF_8);
    }
}