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
 * Orquestra {@link BlockStore} (JSON) + {@link SiteBlocker} (/etc/hosts).
 *
 * <p>Centraliza a varredura de expiração para que todo comando CLI a execute
 * no início (ver chamadas em {@code cli.*}).
 *
 * <p><b>FUTURO (Etapa 2 — daemon):</b> a checagem "sob demanda" só atualiza
 * quando o usuário roda um comando. Para limpeza contínua, chamar
 * {@link #sweepExpired()} a partir de um serviço em background — ex.: um
 * {@code systemd timer} executando {@code siteblock status} periodicamente, ou
 * uma thread agendada ({@code ScheduledExecutorService}) dentro de um daemon.
 * O método já é idempotente e seguro para chamadas concorrentes/periódicas.
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

    /** Resultado da varredura de expirados. */
    public record SweepResult(List<String> removed, List<String> failures,
                              boolean skippedDueToPermission, Map<String, BlockEntry> state) {
    }

    /**
     * Remove do hosts + do JSON todos os bloqueios com expiração passada.
     *
     * <p>Se o hosts não for gravável e houver expirados, nada é alterado e
     * {@code skippedDueToPermission=true} (o chamador decide: {@code status}
     * avisa; {@code block}/{@code unblock} já barraram antes por falta de sudo).
     */
    public SweepResult sweepExpired() throws IOException {
        Map<String, BlockEntry> state = store.load();
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
        List<String> removed = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        for (String domain : expired) {
            try {
                blocker.unblock(domain);
                state.remove(domain);
                removed.add(domain);
            } catch (IOException e) {
                failures.add(domain + ": " + e.getMessage());
            }
        }
        if (!removed.isEmpty()) {
            store.save(state);
        }
        if (!failures.isEmpty()) {
            throw new IOException("Falha ao limpar expirados do hosts: " + String.join("; ", failures));
        }
        return new SweepResult(List.copyOf(removed), List.of(), false, state);
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
