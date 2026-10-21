package com.sitelock.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * profile unblock — duas camadas de resistência, e elas não são a mesma coisa.
 *
 * <p>A externa é o {@code --force}: sem ele, qualquer domínio do perfil que
 * esteja ativo faz o comando recusar tudo. A interna é a confirmação digitada,
 * uma só, antes de tocar em qualquer domínio — 40 prompts de "SIM" são
 * exatamente o atrito que a feature existe para remover.
 *
 * <p>Os permanentes entram na confirmação porque um bloqueio permanente é o
 * compromisso mais forte que a pessoa assumiu, não o mais fraco. Soltá-lo
 * calado seria o caminho mais curto para descartar uma trava ilimitada sem
 * digitar uma tecla.
 */
class ProfileUnblockTest {

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

    private int block(String duracao, String... domains) throws Exception {
        return new BlockCommand(List.of(domains), duracao, hosts().toString(), state()).call();
    }

    private int save(String nome) throws Exception {
        return new ProfileSaveCommand(nome, false, hosts().toString(), state()).call();
    }

    private int unblock(String nome, boolean force, String answer) throws Exception {
        return new ProfileUnblockCommand(nome, force, hosts().toString(), state(), answer).call();
    }

    private String blocks() throws Exception {
        return Files.readString(Path.of(state()), StandardCharsets.UTF_8);
    }

    private String hostsContent() throws Exception {
        return Files.readString(hosts(), StandardCharsets.UTF_8);
    }

    @Test
    void refusesWithoutForceWhenTemporaryIsActive() throws Exception {
        assertEquals(0, block("4h", "tiktok.com"));
        assertEquals(0, save("trabalho"));
        String antes = blocks();

        assertEquals(3, unblock("trabalho", false, "SIM"));
        assertEquals(antes, blocks());
    }

    @Test
    void refusesWithoutForceWhenOnlyPermanentsAreBlocked() throws Exception {
        assertEquals(0, block(null, "tiktok.com", "youtube.com"));
        assertEquals(0, save("permanentes"));
        String antes = blocks();

        assertEquals(3, unblock("permanentes", false, "SIM"));

        assertEquals(antes, blocks(),
                "sem --force, um perfil só de travas ilimitadas não sai — nem com SIM na mão");
    }

    @Test
    void wrongConfirmationUnblocksNothing() throws Exception {
        assertEquals(0, block("4h", "tiktok.com"));
        assertEquals(0, save("trabalho"));
        String antes = blocks();

        assertEquals(3, unblock("trabalho", true, ""));

        assertEquals(antes, blocks());
        assertTrue(hostsContent().contains("tiktok.com"), "o hosts não pode ser tocado");
    }

    @Test
    void confirmationUnblocksEveryDomain() throws Exception {
        assertEquals(0, block("4h", "tiktok.com", "youtube.com"));
        assertEquals(0, save("trabalho"));

        assertEquals(0, unblock("trabalho", true, "SIM"));

        assertFalse(blocks().contains("tiktok.com"), blocks());
        assertFalse(blocks().contains("youtube.com"), blocks());
        assertFalse(hostsContent().contains("tiktok.com"), hostsContent());
    }

    @Test
    void permanentsAreListedInTheConfirmation() throws Exception {
        assertEquals(0, block(null, "tiktok.com"));
        assertEquals(0, save("permanentes"));

        String saida = capture(() -> unblock("permanentes", true, "SIM"));

        assertTrue(saida.contains("tiktok.com"), saida);
        assertTrue(saida.contains("permanente"), saida);
    }

    @Test
    void onlyOnePromptForManyDomains() throws Exception {
        String[] muitos = new String[12];
        for (int i = 0; i < muitos.length; i++) {
            muitos[i] = "site" + i + ".com";
        }
        assertEquals(0, block("4h", muitos));
        assertEquals(0, save("enorme"));

        String saida = capture(() -> unblock("enorme", true, "SIM"));

        assertEquals(1, count(saida, "digite SIM"), "uma pergunta só, não uma por domínio:\n" + saida);
    }

    @Test
    void expiredDomainsPassWithoutPrompt() throws Exception {
        // Bloqueio temporário que já venceu: o sweep o remove no começo do
        // comando, então ele nem aparece na confirmação.
        assertEquals(0, block("4h", "tiktok.com"));
        assertEquals(0, save("trabalho"));
        envenenar("tiktok.com");

        String saida = capture(() -> unblock("trabalho", true, "SIM"));

        assertFalse(saida.contains("tiktok.com"), "já expirado não entra na lista: " + saida);
        assertFalse(blocks().contains("tiktok.com"));
    }

    @Test
    void emptyTargetListDoesNotAskAnything() throws Exception {
        // Um perfil sem nada bloqueado não existe: o save recusa.
        assertEquals(1, save("vazio"),
                "sem bloqueio ativo não há o que salvar: o save tem que falhar");
        assertEquals(0, block(null, "tiktok.com"));
        assertEquals(0, save("so-tiktok"));

        // O perfil só tem tiktok, que já foi solto: a lista fica vazia.
        assertEquals(0, unblock("so-tiktok", true, "SIM"));
        String saida = capture(() -> unblock("so-tiktok", false, null));

        assertFalse(saida.contains("digite SIM"), "lista vazia não pergunta nada: " + saida);
        assertTrue(saida.contains("Nada a fazer"),
                "lista vazia tem que dizer que não há o que fazer: " + saida);
        assertEquals(0, unblock("so-tiktok", false, null),
                "e sem --force também, porque não há nada a perder");
    }

    @Test
    void refusesWhenHostsIsNotWritable() throws Exception {
        // Sem esta trava o pior desfecho é silencioso: o estado é gravado, a
        // escrita no hosts falha, e o domínio fica preso sem registro em lugar
        // nenhum — todo comando passa a dizer "não está bloqueado".
        assertEquals(0, block(null, "tiktok.com"));
        assertEquals(0, save("trabalho"));
        String antes = blocks();

        Path semGravacao = tmp.resolve("sem-pai/hosts");
        int exit = new ProfileUnblockCommand("trabalho", true, semGravacao.toString(),
                state(), "SIM").call();

        assertEquals(1, exit);
        assertEquals(antes, blocks(), "o estado não pode ser apagado se o hosts não aceitou");
    }

    @Test
    void rollbackWhenUnblockAllFails() throws Exception {
        assertEquals(0, block("4h", "tiktok.com"));
        assertEquals(0, save("trabalho"));
        String antes = blocks();

        com.sitelock.blocker.SiteBlocker falho = new com.sitelock.blocker.SiteBlocker() {
            @Override
            public void block(String domain) {
            }

            @Override
            public void unblock(String domain) {
            }

            @Override
            public void blockAll(List<String> domains) {
            }

            @Override
            public void unblockAll(List<String> domains) throws java.io.IOException {
                throw new java.io.IOException("hosts somente leitura");
            }
        };

        int exit = new ProfileUnblockCommand("trabalho", true, hosts().toString(),
                state(), "SIM", falho).call();

        assertEquals(1, exit);
        assertEquals(antes, blocks(),
                "se o hosts não aceitou, o estado tem que voltar — senão o domínio fica preso");
    }

    @Test
    void unknownProfileFails() throws Exception {
        String saida = capture(() -> unblock("nao-existe", true, "SIM"));

        assertEquals(1, unblock("nao-existe", true, "SIM"));
        assertTrue(saida.contains("não existe"),
                "a recusa tem que dizer que o perfil não existe: " + saida);
    }

    /** Troca o expiresAt do domínio por um instante já vencido. */
    private void envenenar(String domain) throws Exception {
        String json = blocks();
        int chave = json.indexOf("\"" + domain + "\"");
        int expira = json.indexOf("\"expiresAt\"", chave);
        int doisPontos = json.indexOf(':', expira);
        int fim = json.indexOf('\n', doisPontos);
        json = json.substring(0, doisPontos) + ": \"2020-01-01T00:00:00Z\""
                + json.substring(fim < 0 ? json.length() : fim);
        Files.writeString(Path.of(state()), json, StandardCharsets.UTF_8);
    }

    private static int count(String s, String needle) {
        int n = 0;
        int i = 0;
        while ((i = s.indexOf(needle, i)) >= 0) {
            n++;
            i += needle.length();
        }
        return n;
    }

    /** Captura stdout e stderr: as recusas do comando saem no stderr. */
    private static String capture(ThrowingRunnable r) throws Exception {
        PrintStream original = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        try {
            r.run();
        } finally {
            System.setOut(original);
            System.setErr(originalErr);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}