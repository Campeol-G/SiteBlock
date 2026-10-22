package com.sitelock.core;

import com.sitelock.blocker.SiteBlocker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code sweepExpired()} é o caminho que o daemon exercita a cada minuto, então
 * é o caminho que precisa ser em lote: 40 domínios vencendo juntos não podem
 * custar 40 travadas do hosts, nem falhar deixando metade removida.
 */
class BlockServiceSweepTest {

    @TempDir
    Path tmp;

    private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");

    // Blocker que registra o que recebeu, sem tocar em arquivo nenhum.
    private static final class RecordingBlocker implements SiteBlocker {

        final List<List<String>> unblockAllCalls = new ArrayList<>();
        final List<List<String>> blockAllCalls = new ArrayList<>();
        int unblockCalls;
        boolean failOnUnblockAll;

        @Override
        public void block(String domain) {
        }

        @Override
        public void blockAll(List<String> domains) {
            blockAllCalls.add(List.copyOf(domains));
        }

        @Override
        public void unblock(String domain) {
            unblockCalls++;
        }

        @Override
        public void unblockAll(List<String> domains) throws IOException {
            unblockAllCalls.add(List.copyOf(domains));
            if (failOnUnblockAll) {
                throw new IOException("hosts somente leitura");
            }
        }
    }

    private Path hosts() throws IOException {
        Path h = tmp.resolve("hosts");
        Files.writeString(h, "127.0.0.1 localhost\n");
        return h;
    }

    private BlockStore store() {
        return new BlockStore(tmp.resolve("state/blocks.json"));
    }

    private BlockService service(Path hostsPath, SiteBlocker blocker) {
        return new BlockService(store(), blocker, Clock.fixed(NOW, ZoneOffset.UTC), hostsPath);
    }

    private void seed(BlockStore store, Map<String, Instant> entries) throws IOException {
        Map<String, BlockEntry> state = new TreeMap<>();
        for (Map.Entry<String, Instant> e : entries.entrySet()) {
            state.put(e.getKey(), new BlockEntry(e.getKey(), NOW.minusSeconds(3600), e.getValue()));
        }
        store.save(state);
    }

    // Map.of não aceita valor nulo, e "permanente" é justamente null.
    private static Map<String, Instant> deadlines(Object... pares) {
        Map<String, Instant> m = new TreeMap<>();
        for (int i = 0; i < pares.length; i += 2) {
            m.put((String) pares[i], (Instant) pares[i + 1]);
        }
        return m;
    }

    private Map<String, Instant> onDisk() throws IOException {
        Map<String, Instant> out = new TreeMap<>();
        for (BlockEntry e : store().load().values()) {
            out.put(e.getDomain(), e.getExpiresAt());
        }
        return out;
    }

    @Test
    void sweepRemovesEveryExpiredDomainInOneCall() throws Exception {
        Path h = hosts();
        BlockStore store = store();
        seed(store, deadlines(
                "tiktok.com", NOW.minusSeconds(3600),
                "youtube.com", NOW.minusSeconds(60),
                "discord.com", NOW.minusSeconds(1),
                "permanente.com", null));

        RecordingBlocker blocker = new RecordingBlocker();
        BlockService.SweepResult result = service(h, blocker).sweepExpired();

        assertEquals(1, blocker.unblockAllCalls.size(), "um sweep, uma chamada em lote");
        assertEquals(3, blocker.unblockAllCalls.get(0).size());
        assertEquals(0, blocker.unblockCalls, "o caminho por domínio não pode mais ser usado");
        assertEquals(List.of("discord.com", "tiktok.com", "youtube.com"), result.removed());
        assertEquals(deadlines("permanente.com", null), onDisk());
    }

    @Test
    void sweepKeepsPermanentsUntouched() throws Exception {
        Path h = hosts();
        seed(store(), deadlines("a.com", null, "b.com", null));

        RecordingBlocker blocker = new RecordingBlocker();
        service(h, blocker).sweepExpired();

        assertTrue(blocker.unblockAllCalls.isEmpty());
        assertEquals(deadlines("a.com", null, "b.com", null), onDisk());
    }

    @Test
    void sweepIsSkippedWhenHostsNotWritable() throws Exception {
        // Hosts inexistente com pai inexistente: isHostsWritable() cai no diretório
        // pai, e um diretório inexistente não é gravável. É o jeito portátil de
        // simular negação — chmod não significa nada no Windows, que roda a CI.
        Path h = tmp.resolve("sem-pai/hosts");
        BlockStore store = store();
        seed(store, deadlines("tiktok.com", NOW.minusSeconds(60)));

        RecordingBlocker blocker = new RecordingBlocker();
        BlockService.SweepResult result = service(h, blocker).sweepExpired();

        assertTrue(result.skippedDueToPermission());
        assertTrue(result.removed().isEmpty());
        assertTrue(blocker.unblockAllCalls.isEmpty());
        assertTrue(onDisk().containsKey("tiktok.com"), "sem escrita no hosts, o estado fica como está");
    }

    @Test
    void sweepFailureLeavesStateOnDisk() throws Exception {
        Path h = hosts();
        BlockStore store = store();
        seed(store, deadlines(
                "tiktok.com", NOW.minusSeconds(60),
                "youtube.com", NOW.minusSeconds(60)));

        RecordingBlocker blocker = new RecordingBlocker();
        blocker.failOnUnblockAll = true;

        BlockService service = service(h, blocker);
        assertThrows(IOException.class, service::sweepExpired);

        assertEquals(2, onDisk().size(), "falha no lote não pode remover nada do estado");
        assertFalse(blocker.unblockAllCalls.isEmpty());
    }
}