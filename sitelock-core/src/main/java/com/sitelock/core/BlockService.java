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

    /**
     * O conteúdo do hosts.
     *
     * <p>POR QUE: o {@code status} roda sem elevação e precisa conferir o
     * hosts contra o estado. {@link #isHostsWritable()} fala de GRAVAR, não
     * de LER: os dois são coisas diferentes, e só a leitura interessa aqui.
     *
     * @throws IOException se o hosts não pôde ser lido
     */
    public String readHostsContent() throws IOException {
        return HostsFileManager.readHosts(hostsPath);
    }

    /**
     * Aplica uma mudança de estado e a escrita no hosts como uma unidade.
     *
     * <p>É o ponto de entrada único de tudo que grava estado: o {@code load} e o
     * {@code save} acontecem dentro da mesma trava do {@link BlockStore}, e o
     * hosts é escrito dentro dela também. Sem isso, um {@code block} feito em
     * outro terminal entre o {@code load} e o {@code save} é apagado do JSON —
     * e o domínio continua no {@code /etc/hosts}, sem registro em lugar nenhum.
     *
     * <p>A ordem é Estado → hosts, e ela não é negociável. O caminho inverso tem
     * um pior modo de falha: entrada no hosts sem registro no estado significa
     * um domínio preso para sempre, porque o {@code sweepExpired()} varre o
     * JSON, não o hosts. A ordem escolhida tem a janela oposta — se o processo
     * for morto entre os dois passos, o estado diz "bloqueado" e o hosts não foi
     * escrito, ou seja, o domínio está de fato livre. Fail-open e silencioso.
     *
     * <p>Se a escrita no hosts falhar, o estado volta ao que era: é o
     * rollback que impede o estado de mentir sobre o hosts.
     *
     * @param transform o que fazer com o estado carregado; recebe uma cópia
     *                  que pode mutar à vontade e a transação, para gravar
     * @param hosts     a escrita no hosts
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
