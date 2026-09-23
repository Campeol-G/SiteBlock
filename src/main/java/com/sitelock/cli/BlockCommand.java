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
 * {@code siteblock block <dominio...> [--for <duracao>]}.
 *
 * <p>P1: aceita 1..N domínios na mesma invocação (ex.: TikTok usa
 * {@code vm./vt./cdn} além do apex). A mesma duração vale para todos.
 * A validação é feita para TODOS antes de aplicar qualquer um: se um domínio
 * for inválido, nada é alterado (exit 2).
 */
@Command(name = "block",
        description = "Bloqueia um ou mais dominios via /etc/hosts (cada um com dominio + www + IPv4/IPv6).",
        mixinStandardHelpOptions = true)
public class BlockCommand implements Callable<Integer> {

    @Parameters(index = "0..*", paramLabel = "<dominio>",
            description = "Um ou mais dominios. Ex.: tiktok.com vm.tiktok.com",
            arity = "1..*")
    List<String> domainsRaw = new ArrayList<>();

    @Option(names = "--for", paramLabel = "<duracao>",
            description = "Duracao do bloqueio. Ex.: 30m, 2h, 1h30m, 1d. Omitido = permanente.")
    String forDuration;

    // Overrides ocultos para testes (não tocar no /etc/hosts real).
    @Option(names = "--hosts-file", hidden = true) String hostsFileOverride;
    @Option(names = "--state-file", hidden = true) String stateFileOverride;

    // Construtor padrão exigido pelo picocli.
    public BlockCommand() {
    }

    // Construtor para testes.
    BlockCommand(List<String> domainsRaw, String forDuration, String hostsFileOverride,
                 String stateFileOverride) {
        this.domainsRaw = new ArrayList<>(domainsRaw);
        this.forDuration = forDuration;
        this.hostsFileOverride = hostsFileOverride;
        this.stateFileOverride = stateFileOverride;
    }

    @Override
    public Integer call() {
        // 1. Normaliza + valida TODOS antes de aplicar qualquer um.
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

        // 2. Valida duração.
        final Duration duration;
        try {
            duration = forDuration == null ? null : DurationParser.parse(forDuration);
        } catch (IllegalArgumentException e) {
            System.err.println("Erro: " + e.getMessage());
            return 2;
        }

        BlockService service = CliSupport.newService(hostsFileOverride, stateFileOverride);

        // 3. Permissão: editar hosts exige root.
        if (!service.isHostsWritable()) {
            System.err.println("Erro: " + CliSupport.sudoHint("block " + String.join(" ", domains)
                    + (forDuration != null ? " --for " + forDuration : "")));
            return 1;
        }

        // 4. Expiração automática no início de TODA execução (limitação conhecida:
        // só atualiza quando o usuário roda um comando — ver comentário em BlockService).
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
            System.err.println("(O /etc/hosts NÃO foi modificado por segurança.)");
            return 1;
        } catch (IOException e) {
            System.err.println("Erro ao limpar bloqueios expirados: " + e.getMessage());
            return 1;
        }

        // 5. Aplica domínio a domínio (JSON primeiro, hosts depois, com rollback
        // individual para nunca deixar os dois divergentes).
        Instant now = Instant.now(service.getClock());
        int failures = 0;
        for (String domain : domains) {
            boolean updating = state.containsKey(domain);
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
            if (updating) {
                System.out.println("Aviso: '" + domain + "' já estava bloqueado — duração atualizada.");
            }
            if (expiresAt == null) {
                System.out.println("Bloqueado " + domain + " (+ www." + domain
                         + ", IPv4+IPv6) permanentemente (até 'siteblock unblock " + domain + "').");
            } else {
                System.out.println("Bloqueado " + domain + " (+ www." + domain + ", IPv4+IPv6) por "
                        + DurationParser.formatRemaining(duration) + ".");
            }
        }
        return failures == 0 ? 0 : 1;
    }
}
