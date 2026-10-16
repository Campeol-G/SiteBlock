package com.sitelock.cli;

import com.sitelock.blocker.SiteBlocker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * profile apply — merge em memória, um save, um blockAll, rollback se o hosts
 * falhar. O caminho por domínio morria no meio de um perfil de 40 domínios e
 * deixava estado parcial; o lote existe para que o comando seja tudo ou nada.
 */
class ProfileApplyTest {

    @TempDir
    Path tmp;

    /** Criar uma vez: reescrever apagaria os bloqueios que o teste acabou de fazer. */
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

    private void block(String... domains) throws Exception {
        assertEquals(0, new BlockCommand(List.of(domains), "4h", hosts().toString(), state()).call());
    }

    private int save(String nome) throws Exception {
        return new ProfileSaveCommand(nome, false, hosts().toString(), state()).call();
    }

    private int apply(String nome) throws Exception {
        return new ProfileApplyCommand(nome, hosts().toString(), state(), null).call();
    }

    private String hostsContent() throws Exception {
        return Files.readString(hosts(), StandardCharsets.UTF_8);
    }

    private String blocksJson() throws Exception {
        return Files.readString(Path.of(state()), StandardCharsets.UTF_8);
    }

    /** Lê o expiresAt de um domínio direto do JSON, sem passar pela CLI. */
    private Instant expiresAt(String domain) throws Exception {
        String json = blocksJson();
        int dominio = json.indexOf("\"" + domain + "\"");
        assertTrue(dominio >= 0, "domínio ausente do estado: " + json);
        int chave = json.indexOf("\"expiresAt\"", dominio);
        assertTrue(chave > 0, "expiresAt ausente: " + json);
        int doisPontos = json.indexOf(':', chave);
        int fim = json.indexOf('\n', doisPontos);
        String valor = json.substring(doisPontos + 1, fim < 0 ? json.length() : fim)
                .trim().replace(",", "").replace("\"", "").trim();
        return Instant.parse(valor);
    }

    @Test
    void applyBlocksEveryDomainOfTheProfile() throws Exception {
        block("tiktok.com", "youtube.com");
        assertEquals(0, save("trabalho"));
        assertEquals(0, apply("trabalho"));

        String h = hostsContent();
        assertTrue(h.contains("127.0.0.1 tiktok.com"), h);
        assertTrue(h.contains("127.0.0.1 youtube.com"), h);
        assertTrue(blocksJson().contains("tiktok.com"), blocksJson());
    }

    @Test
    void applyIsIdempotent() throws Exception {
        block("tiktok.com", "youtube.com");
        assertEquals(0, save("trabalho"));
        assertEquals(0, apply("trabalho"));
        String antes = hostsContent();

        String saida = capture(() -> apply("trabalho"));

        assertEquals(antes, hostsContent(), "o segundo apply não pode tocar no hosts");
        assertFalse(saida.contains("(novo)"), "nada pode ser criado de novo: " + saida);
    }

    @Test
    void applyNeverShortens() throws Exception {
        assertEquals(0, new BlockCommand(List.of("tiktok.com"), "10h", hosts().toString(), state()).call());
        assertEquals(0, save("curto"));
        Instant antes = expiresAt("tiktok.com");

        assertEquals(0, apply("curto"));
        Instant depois = expiresAt("tiktok.com");

        // Não pode encurtar. Pode até esticar um pouquinho: o perfil guarda o que
        // restava no momento do save, e os milissegundos entre o save e o apply
        // fazem o prazo andar para frente. A regra é max(), não "igual".
        assertFalse(depois.isBefore(antes),
                "perfil mais fraco não pode encurtar prazo mais forte: " + antes + " -> " + depois);
    }

    @Test
    void applyRaisesShortBlock() throws Exception {
        // A receita oferece 4h; o estado em que ela vai cair tem só 30min.
        // É a linha da tabela "temporário, restam 30min | 8h | RAISED".
        assertEquals(0, new BlockCommand(List.of("tiktok.com"), "4h", hosts().toString(), state()).call());
        assertEquals(0, save("longo"));
        assertEquals(0, new BlockCommand(List.of("tiktok.com"), "30m", hosts().toString(), state()).call());
        Instant antes = expiresAt("tiktok.com");

        assertEquals(0, apply("longo"));

        Instant depois = expiresAt("tiktok.com");
        assertTrue(depois.isAfter(antes.plusSeconds(3 * 3600)),
                "30min tem que virar as ~4h do perfil: " + antes + " -> " + depois);
    }

    @Test
    void rollbackWhenBlockAllFails() throws Exception {
        block("tiktok.com");
        assertEquals(0, save("trabalho"));
        String antes = blocksJson();

        SiteBlocker falho = new SiteBlocker() {
            @Override
            public void block(String domain) {
            }

            @Override
            public void unblock(String domain) {
            }

            @Override
            public void blockAll(List<String> domains) throws IOException {
                throw new IOException("hosts somente leitura");
            }

            @Override
            public void unblockAll(List<String> domains) {
            }
        };

        int exit = new ProfileApplyCommand("trabalho", hosts().toString(), state(), falho).call();

        assertEquals(1, exit);
        assertEquals(antes, blocksJson(),
                "se o hosts não aceitou, o estado tem que voltar — senão o JSON diz bloqueado e o hosts não tem a linha");
    }

    @Test
    void applyUnknownProfileFails() throws Exception {
        assertEquals(1, apply("nao-existe"));
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static String capture(ThrowingRunnable r) throws Exception {
        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        try {
            r.run();
        } finally {
            System.setOut(original);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }
}