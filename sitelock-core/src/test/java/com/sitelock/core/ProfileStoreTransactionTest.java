package com.sitelock.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A transação do {@link ProfileStore}.
 *
 * <p>Dois {@code profile add} simultâneos se perdiam: cada um lia a receita,
 * acrescentava o seu domínio e gravava o arquivo inteiro — o último a gravar
 * apagava o domínio do outro. A receita é trabalho digitado; perdê-lo é pior
 * do que o estado de bloqueio, que se reconstrói.
 *
 * <p>O teste da escrita concorrente é determinístico: o outro escritor grava o
 * arquivo por fora da trava (como faria outra versão da ferramenta, um script
 * ou uma edição manual), e a transação tem que aplicar a própria mudança
 * <b>por cima</b> do que está no disco, sem apagar o que o outro escreveu.
 */
class ProfileStoreTransactionTest {

    @TempDir
    Path tmp;

    private static final Instant AGORA = Instant.parse("2026-10-15T12:02:11Z");

    private Path file() {
        return tmp.resolve("profiles.json");
    }

    private static Profile perfil(String... dominios) {
        Map<String, ProfileEntry> entradas = new TreeMap<>();
        for (String dominio : dominios) {
            entradas.put(dominio, new ProfileEntry(null));
        }
        return new Profile(AGORA, AGORA, entradas);
    }

    /** Grava o arquivo por fora da trava, como um escritor que não a respeita. */
    private void escreverPorForaDaTrava(Path profilesFile, Map<String, Profile> perfis)
            throws IOException {
        Files.writeString(profilesFile, ProfileStore.defaultMapper()
                .writerWithDefaultPrettyPrinter()
                .writeValueAsString(new ProfileDocument(ProfileDocument.CURRENT_VERSION, perfis)),
                StandardCharsets.UTF_8);
    }

    @Test
    void escritaConcorrenteNaoSePerde() throws Exception {
        ProfileStore store = new ProfileStore(file());
        store.save(Map.of("x", perfil("x.com")));

        store.inTransaction(tx -> {
            Map<String, Profile> perfis = tx.load();
            perfis.put("y", perfil("y.com"));

            Map<String, Profile> concorrente = new TreeMap<>(store.load());
            concorrente.put("z", perfil("z.com"));
            escreverPorForaDaTrava(file(), concorrente);

            tx.save(perfis);
            return null;
        });

        Map<String, Profile> depois = store.load();
        assertTrue(depois.containsKey("z"),
                "a escrita concorrente não pode se perder: " + depois.keySet());
        assertTrue(depois.containsKey("y"),
                "a mudança da transação tem que estar lá: " + depois.keySet());
    }
}
