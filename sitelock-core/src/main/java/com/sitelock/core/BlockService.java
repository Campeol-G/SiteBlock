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
import java.util.TreeMap;

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
     * @param failures vazio sempre — o desbloqueio em lote é atômico (tudo sai ou
     *                 nada sai), então o campo só sobra por compatibilidade de
     *                 assinatura; os chamadores leem {@code removed}
     * @param skippedDueToPermission true quando o hosts não está gravável e nada
     *                 foi tentado
     * @param state o estado depois da varredura
     */
    public record SweepResult(List<String> removed, List<String> failures,
                              boolean skippedDueToPermission, Map<String, BlockEntry> state) {
    }

    // POR QUE: o load → unblockAll → save roda inteiro numa transação do BlockStore:
    // fora dela, um block de outro terminal entre o load e o save seria apagado do
    // estado, e o domínio ficaria preso no hosts sem registro (janela sempre aberta).
    /**
     * Remove do hosts e do JSON os bloqueios já expirados.
     * Se o hosts não for gravável e houver expirados, nada muda e
     * {@code skippedDueToPermission} volta true.
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
                // Por isso o estado não é tocado — a próxima varredura tenta de novo,
                // em vez de deixar o domínio preso no JSON (que o sweep não varre).
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

    // POR QUE: o status roda sem elevação e precisa conferir o hosts contra o
    // estado — isHostsWritable() fala de GRAVAR, não de LER, e só a leitura interessa.
    /**
     * O conteúdo do hosts.
     *
     * @throws IOException se o hosts não pôde ser lido
     */
    public String readHostsContent() throws IOException {
        return HostsFileManager.readHosts(hostsPath);
    }

    // POR QUE: load, save e a escrita no hosts ficam sob a mesma trava do
    // BlockStore — fora dela, um block de outro terminal entre o load e o save
    // seria apagado do JSON, e o domínio continuaria no hosts sem registro.

    // A ordem é Estado → hosts e não a inversa: o caminho inverso prende o domínio
    // para sempre (o sweep varre o JSON, não o hosts). A ordem escolhida deixa o
    // domínio livre se o processo morrer no meio — fail-open e silencioso.
    /**
     * Aplica uma mudança de estado e a escrita no hosts como uma unidade.
     *
     * @param transform o que fazer com o estado carregado; recebe uma cópia
     *                  que pode mutar à vontade e a transação, para gravar
     */
    public <R> R inTransaction(StateTransform<R> transform, HostsWriter<R> hosts)
            throws IOException {
        return store.inTransaction(tx -> {
            Map<String, BlockEntry> before = tx.load();
            Map<String, BlockEntry> working = new TreeMap<>(before);
            R result = transform.apply(working, tx);
            try {
                hosts.write(result, working, tx);
            } catch (IOException e) {
                // O hosts não aceitou: o estado tem que voltar, senão o JSON
                // diz "bloqueado" e o hosts não tem a linha.
                try {
                    tx.save(before);
                } catch (IOException rollback) {
                    e.addSuppressed(rollback);
                }
                throw e;
            }
            return result;
        });
    }

    /** O que se faz com o estado carregado, dentro da transação. */
    @FunctionalInterface
    public interface StateTransform<R> {
        R apply(Map<String, BlockEntry> state, BlockStore.Transaction tx) throws IOException;
    }

    /** A escrita no hosts, dentro da transação. */
    @FunctionalInterface
    public interface HostsWriter<R> {
        void write(R result, Map<String, BlockEntry> state, BlockStore.Transaction tx)
                throws IOException;
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
