package com.sitelock.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Operação em lote no arquivo de hosts: um lock, uma leitura, uma escrita.
 *
 * <p>A comparação com N chamadas individuais é a propriedade que importa — se
 * o lote divergisse do caminho por domínio, todo o resto da feature (profile
 * apply de 40 domínios, sweep do daemon) passaria a produzir um arquivo que
 * ninguém esperava.
 */
class HostsFileManagerBulkTest {

    private static final String BASE_HOSTS = "127.0.0.1 localhost\n::1 localhost\n";

    @TempDir
    Path tmp;

    private Path hosts(String name) throws IOException {
        Path h = tmp.resolve(name);
        Files.writeString(h, BASE_HOSTS, StandardCharsets.UTF_8);
        return h;
    }

    private Path backup(String name) {
        return tmp.resolve(name + ".backup");
    }

    private Path lock(String name) {
        return tmp.resolve(name + ".lock");
    }

    private String read(Path h) throws IOException {
        return Files.readString(h, StandardCharsets.UTF_8);
    }

    @Test
    void bulkBlockMatchesIndividualCalls() throws Exception {
        Path lote = hosts("lote");
        HostsFileManager.blockInFile(lote, backup("lote"), lock("lote"),
                List.of("tiktok.com", "youtube.com"));

        Path individual = hosts("individual");
        HostsFileManager.blockInFile(individual, backup("individual"), lock("individual"), "tiktok.com");
        HostsFileManager.blockInFile(individual, backup("individual"), lock("individual"), "youtube.com");

        assertEquals(read(individual), read(lote));
        assertTrue(read(lote).contains("::1 localhost"), "o conteúdo original acima do bloco não pode ser tocado");
    }

    @Test
    void bulkUnblockRemovesEveryDomain() throws Exception {
        Path h = hosts("h");
        HostsFileManager.blockInFile(h, backup("h"), lock("h"), List.of("tiktok.com", "youtube.com"));
        HostsFileManager.unblockInFile(h, backup("h"), lock("h"), List.of("tiktok.com", "youtube.com"));

        String out = read(h);
        assertFalse(out.contains(HostsFileManager.START_MARKER), "bloco vazio deve remover os marcadores");
        assertFalse(out.contains(HostsFileManager.END_MARKER));
        assertFalse(out.contains("tiktok.com"));
        assertFalse(out.contains("youtube.com"));
        assertTrue(out.contains("127.0.0.1 localhost"), "o conteúdo original não pode ser tocado");
        assertTrue(out.contains("::1 localhost"));
    }

    @Test
    void bulkUnblockMatchesIndividualCalls() throws Exception {
        Path lote = hosts("lote");
        HostsFileManager.blockInFile(lote, backup("lote"), lock("lote"), List.of("tiktok.com", "youtube.com"));
        HostsFileManager.unblockInFile(lote, backup("lote"), lock("lote"), List.of("tiktok.com", "youtube.com"));

        Path individual = hosts("individual");
        HostsFileManager.blockInFile(individual, backup("individual"), lock("individual"), "tiktok.com");
        HostsFileManager.blockInFile(individual, backup("individual"), lock("individual"), "youtube.com");
        HostsFileManager.unblockInFile(individual, backup("individual"), lock("individual"), "tiktok.com");
        HostsFileManager.unblockInFile(individual, backup("individual"), lock("individual"), "youtube.com");

        assertEquals(read(individual), read(lote));
    }

    @Test
    void bulkIsIdempotent() throws Exception {
        Path h = hosts("h");
        HostsFileManager.blockInFile(h, backup("h"), lock("h"), List.of("tiktok.com", "youtube.com"));
        HostsFileManager.blockInFile(h, backup("h"), lock("h"), List.of("tiktok.com", "youtube.com"));

        assertEquals(1, countOccurrences(read(h), "127.0.0.1 tiktok.com"));
        assertEquals(1, countOccurrences(read(h), "::1 www.youtube.com"));
    }

    @Test
    void emptyListLeavesFileUntouched() throws Exception {
        Path h = hosts("h");
        String before = read(h);

        HostsFileManager.blockInFile(h, backup("h"), lock("h"), List.of());
        assertEquals(before, read(h), "lista vazia não pode criar bloco");

        HostsFileManager.blockInFile(h, backup("h"), lock("h"), List.of("tiktok.com"));
        String blocked = read(h);
        HostsFileManager.unblockInFile(h, backup("h"), lock("h"), List.of());

        assertEquals(blocked, read(h), "lista vazia não pode remover o bloco");
    }

    @Test
    void singleArgumentOverloadMatchesBatch() throws Exception {
        Path um = hosts("um");
        HostsFileManager.blockInFile(um, backup("um"), lock("um"), "tiktok.com");

        Path lote = hosts("lote");
        HostsFileManager.blockInFile(lote, backup("lote"), lock("lote"), List.of("tiktok.com"));

        assertEquals(read(lote), read(um));
    }

    private static int countOccurrences(String s, String needle) {
        int n = 0;
        int i = 0;
        while ((i = s.indexOf(needle, i)) >= 0) {
            n++;
            i += needle.length();
        }
        return n;
    }
}