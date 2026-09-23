package com.sitelock.cli;

import com.sitelock.core.BlockEntry;
import com.sitelock.core.BlockService;
import com.sitelock.core.CorruptedStateException;
import com.sitelock.core.DurationParser;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * {@code siteblock status} — lista bloqueios. Não exige sudo para listar;
 * a limpeza de expirados só ocorre se houver escrita no hosts.
 */
@Command(name = "status", description = "Lista dominios bloqueados e tempo restante.",
        mixinStandardHelpOptions = true)
public class StatusCommand implements Callable<Integer> {

    @Option(names = "--hosts-file", hidden = true) String hostsFileOverride;
    @Option(names = "--state-file", hidden = true) String stateFileOverride;

    public StatusCommand() {
    }

    StatusCommand(String hostsFileOverride, String stateFileOverride) {
        this.hostsFileOverride = hostsFileOverride;
        this.stateFileOverride = stateFileOverride;
    }

    @Override
    public Integer call() {
        BlockService service = CliSupport.newService(hostsFileOverride, stateFileOverride);

        final Map<String, BlockEntry> state;
        try {
            if (service.isHostsWritable()) {
                BlockService.SweepResult sweep = service.sweepExpired();
                state = sweep.state();
                if (!sweep.removed().isEmpty()) {
                    System.out.println("Limpeza: bloqueios expirados removidos: "
                            + String.join(", ", sweep.removed()));
                }
            } else {
                state = service.getStore().load();
                long pending = state.values().stream()
                        .filter(e -> e.isExpired(Instant.now(service.getClock())))
                        .count();
                if (pending > 0) {
                    System.out.println("Aviso: há " + pending + " bloqueio(s) expirado(s) pendentes de limpeza.");
                    System.out.println("Rode com sudo (ex.: sudo siteblock status) para concluir a limpeza no /etc/hosts.");
                }
            }
        } catch (CorruptedStateException e) {
            System.err.println("Erro: estado corrompido (" + e.getMessage() + ").");
            System.err.println("Nada foi alterado. Para recriar do zero, apague o arquivo manualmente ou rode: siteblock reset --yes");
            return 1;
        } catch (IOException e) {
            System.err.println("Erro ao ler estado: " + e.getMessage());
            return 1;
        }

        if (state.isEmpty()) {
            System.out.println("Nenhum domínio bloqueado.");
            return 0;
        }

        Instant now = Instant.now(service.getClock());
        List<String> lines = new ArrayList<>();
        for (BlockEntry e : state.values()) {
            String remaining;
            if (e.getExpiresAt() == null) {
                remaining = "permanente";
            } else if (!now.isBefore(e.getExpiresAt())) {
                remaining = "expirado (pendente limpeza)";
            } else {
                remaining = DurationParser.formatRemaining(Duration.between(now, e.getExpiresAt()));
            }
            lines.add(e.getDomain() + " — " + remaining);
        }
        System.out.println("Domínios bloqueados (" + lines.size() + "):");
        lines.forEach(l -> System.out.println("  " + l));
        return 0;
    }
}
