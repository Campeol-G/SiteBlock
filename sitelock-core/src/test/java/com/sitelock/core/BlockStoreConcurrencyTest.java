package com.sitelock.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regressão: leituras concorrentes na mesma JVM não podem estourar
 * OverlappingFileLockException (o servidor DNS lê o estado em N threads).
 */
class BlockStoreConcurrencyTest {

    @TempDir
    Path tmp;

    @Test
    void leiturasConcorrentesNaoLancamOverlappingFileLock() throws Exception {
        Path stateFile = tmp.resolve("blocks.json");
        BlockStore store = new BlockStore(stateFile);
        Map<String, BlockEntry> blocks = new TreeMap<>();
        blocks.put("tiktok.com", new BlockEntry("tiktok.com",
                Instant.parse("2026-01-01T00:00:00Z"), null));
        store.save(blocks); // cria state + lock file

        int threads = 16;
        int readsPerThread = 25;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<Integer>> tasks = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                tasks.add(() -> {
                    int ok = 0;
                    for (int i = 0; i < readsPerThread; i++) {
                        Map<String, BlockEntry> loaded = store.load();
                        assertEquals(1, loaded.size());
                        ok++;
                    }
                    return ok;
                });
            }
            List<Future<Integer>> results = pool.invokeAll(tasks, 30, TimeUnit.SECONDS);
            int total = 0;
            for (Future<Integer> f : results) {
                assertTrue(f.isDone() && !f.isCancelled());
                total += f.get(); // relança qualquer exceção da worker (falha o teste)
            }
            assertEquals(threads * readsPerThread, total);
        } finally {
            pool.shutdownNow();
        }
    }
}
