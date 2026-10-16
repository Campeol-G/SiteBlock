package com.sitelock.core;

import com.sitelock.blocker.SiteBlocker;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Mantém JSON ({@link BlockStore}) e arquivo hosts ({@link SiteBlocker}) em sync.
 * Todo comando CLI chama {@link #sweepExpired()} no início.
 */
public class BlockService {

    private final BlockStore store;
    private final SiteBlocker blocker;
    private final Clock clock;
    private final Path hostsPath;

    public BlockService(BlockStore store, SiteBlocker blocker, Clock clock, Path hostsPath) {
        this.store = store;
        this.blocker = blocker;
        this.clock = clock;
        this.hostsPath = hostsPath;
    }

    /**
     * Resultado de uma varredura.
     *
     * @param removed domínios efetivamente liberados do hosts e do estado
     * @param failures mantido por compatibilidade de assinatura; com o
     *                 desbloqueio em lote a operação é atômica, então ou
     *                 tudo sai ou nada sai — este campo não tem mais caminho
     *                 que o preencha. Os chamadores só leem {@code removed}.
     * @param skippedDueToPermission true quando o hosts não está gravável e nada
     *                 foi tentado
     * @param state o estado depois da varredura
     */
    public record SweepResult(List<String> removed, List<String> failures,
                              boolean skippedDueToPermission, Map<String, BlockEntry> state) {
    }

    /**
     * Remove do hosts e do JSON os bloqueios já expirados.
     * Se o hosts não for gravável e houver expirados, nada muda e
     * {@code skippedDueToPermission} volta true.
     *
     * <p>O {@code load → unblockAll → save} inteiro roda dentro de uma
     * transação do {@link BlockStore}. Fora dela, um {@code block} feito de outro
     * terminal entre o {@code load} e o {@code save} seria apagado do estado — e
     * o domínio ficaria preso no hosts sem registro em lugar nenhum. Com o
     * daemon rodando a cada minuto, para sempre, essa janela não é "enquanto
     * alguém digita": ela está sempre aberta.
     */
    public SweepResult sweepExpired() throws IOException {
        return store.inTransaction(tx -> {
            Map<String, BlockEntry> state = tx.load();
            Instant now = Instant.now(clock);
            List<String> expired = new ArrayList<>();
            for (BlockEntry e : state.values()) {
                if (e.isExpired(now)) {
                    expired.add(e.getDomain());
                }
            }
            if (expired.isEmpty()) {
                return new SweepResult(List.of(), List.of(), false, state);
            }
            if (!isHostsWritable()) {
                return new SweepResult(List.of(), List.of(), true, state);
            }
            try {
                blocker.unblockAll(expired);
            } catch (IOException e) {
                // O lote é atômico: ou o hosts inteiro foi reescrito, ou nada foi.
                // Por isso o estado não é tocado — a próxima varredura tenta os
                // mesmos domínios de novo, em vez de deixá-los presos no JSON
                // sem registro no hosts, que é o modo de falha que o sweep
                // jamais repara porque varre o JSON.
                List<String> failures = new ArrayList<>();
                for (String domain : expired) {
                    failures.add(domain + ": " + e.getMessage());
                }
                throw new IOException("Falha ao limpar expirados do hosts: "
                        + String.join("; ", failures), e);
            }
            state.keySet().removeAll(expired);
            tx.save(state);
            return new SweepResult(List.copyOf(expired), List.of(), false, state);
        });
    }

    public boolean isHostsWritable() {
        if (Files.exists(hostsPath)) {
            return Files.isWritable(hostsPath);
        }
        Path parent = hostsPath.getParent();
        return parent == null || Files.isWritable(parent);
    }

    public BlockStore getStore() {
        return store;
    }

    public SiteBlocker getBlocker() {
        return blocker;
    }

    public Clock getClock() {
        return clock;
    }

    public Path getHostsPath() {
        return hostsPath;
    }
}
