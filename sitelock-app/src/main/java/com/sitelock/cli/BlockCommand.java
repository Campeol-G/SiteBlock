package com.sitelock.cli;

import com.sitelock.core.BlockEntry;
import com.sitelock.core.BlockService;
import com.sitelock.core.CorruptedStateException;
import com.sitelock.core.DomainUtils;
import com.sitelock.core.DurationParser;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * siteblock block <dominio...> [--for <duracao>].
 * Valida todos antes de aplicar; se um for inválido, nada muda.
 */
@Command(name = "block",
        description = "Bloqueia um ou mais dominios.",
        mixinStandardHelpOptions = true)
public class BlockCommand implements Callable<Integer> {

    @Parameters(index = "0..*", paramLabel = "<dominio>",
            description = "Um ou mais dominios. Ex.: tiktok.com vm.tiktok.com",
            arity = "1..*")
    List<String> domainsRaw = new ArrayList<>();

    @Option(names = "--for", paramLabel = "<duracao>",
            description = "Duracao do bloqueio. Ex.: 30m, 2h, 1h30m, 1d. Omitido = permanente.")
    String forDuration;

    // Overrides para testes.
    @Option(names = "--hosts-file", hidden = true) String hostsFileOverride;
    @Option(names = "--state-file", hidden = true) String stateFileOverride;

    public BlockCommand() {
    }

    BlockCommand(List<String> domainsRaw, String forDuration, String hostsFileOverride,
                 String stateFileOverride) {
        this.domainsRaw = new ArrayList<>(domainsRaw);
        this.forDuration = forDuration;
        this.hostsFileOverride = hostsFileOverride;
        this.stateFileOverride = stateFileOverride;
    }

    @Override
    public Integer call() {
        final List<String> domains = new ArrayList<>();
        final Map<String, String> normalizedFrom = new LinkedHashMap<>();
        for (String raw : domainsRaw) {
            final String domain;
            try {
                DomainUtils.NormalizationResult r = DomainUtils.normalize(raw);
                domain = r.domain();
                if (r.normalized()) {
                    System.out.println("Aviso: entrada normalizada de '" + r.original()
                            + "' para '" + r.domain() + "'.");
                }
            } catch (IllegalArgumentException e) {
                System.err.println("Erro: " + e.getMessage());
                return 2;
            }
            if (!domains.contains(domain)) {
                domains.add(domain);
                normalizedFrom.put(domain, raw);
            }
        }

        final Duration duration;
        try {
            duration = forDuration == null ? null : DurationParser.parse(forDuration);
        } catch (IllegalArgumentException e) {
            System.err.println("Erro: " + e.getMessage());
            return 2;
        }

        BlockService service = CliSupport.newService(hostsFileOverride, stateFileOverride);

        if (!service.isHostsWritable()) {
            System.err.println("Erro: " + CliSupport.elevationHint("block " + String.join(" ", domains)
                    + (forDuration != null ? " --for " + forDuration : "")));
            return 1;
        }

        final Map<String, BlockEntry> state;
        try {
            BlockService.SweepResult sweep = service.sweepExpired();
            state = sweep.state();
            if (!sweep.removed().isEmpty()) {
                System.out.println("Limpeza: bloqueios expirados removidos: "
                        + String.join(", ", sweep.removed()));
            }
        } catch (CorruptedStateException e) {
            System.err.println("Erro: estado corrompido (" + e.getMessage() + ").");
            System.err.println("Nada foi alterado. Para recriar do zero, apague o arquivo manualmente ou rode: siteblock reset --yes");
            System.err.println("(O arquivo hosts (" + service.getHostsPath() + ") NÃO foi modificado por segurança.)");
            return 1;
        } catch (IOException e) {
            System.err.println("Erro ao limpar bloqueios expirados: " + e.getMessage());
            return 1;
        }

        Instant now = Instant.now(service.getClock());
        int failures = 0;
        for (String domain : domains) {
            BlockEntry previous = state.get(domain);
            Instant expiresAt = duration == null ? null : now.plus(duration);
            state.put(domain, new BlockEntry(domain, now, expiresAt));
            try {
                service.getStore().save(state);
            } catch (IOException e) {
                System.err.println("Erro ao salvar estado de '" + domain + "': " + e.getMessage());
                if (previous == null) {
                    state.remove(domain);
                } else {
                    state.put(domain, previous);
                }
                failures++;
                continue;
            }
            try {
                service.getBlocker().block(domain);
            } catch (IOException e) {
                try {
                    if (previous == null) {
                        state.remove(domain);
                    } else {
                        state.put(domain, previous);
                    }
                    service.getStore().save(state);
                } catch (IOException rollbackFailure) {
                    System.err.println("Erro CRÍTICO: falha no hosts E ao reverter o estado de '"
                            + domain + "': " + rollbackFailure.getMessage());
                }
                System.err.println("Erro ao bloquear '" + domain + "': " + e.getMessage());
                failures++;
                continue;
            }
            if (expiresAt == null) {
                System.out.println("Bloqueado " + domain + ".");
            } else {
                System.out.println("Bloqueado " + domain + " por "
                        + DurationParser.formatRemaining(duration) + ".");
            }
        }
        if (failures == 0) {
            // Best-effort: não muda o exit code; só avisa se o flush falhar.
            CliSupport.flushDnsBestEffort();
            return 0;
        }
        return 1;
    }
}
