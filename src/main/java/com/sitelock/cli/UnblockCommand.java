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
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.Callable;

/**
 * {@code siteblock unblock <dominio...> [--force]}.
 *
 * <p>Regra anti-impulso (por domínio): bloqueio temporário ainda ativo só pode
 * ser removido com {@code --force} + confirmação digitada ({@code SIM}).
 * Sem {@code --force}, domínios protegidos são recusados (exit 3) e
 * <b>nada é alterado</b> — evita desbloqueio parcial acidental.
 */
@Command(name = "unblock", description = "Remove o bloqueio de um ou mais dominios.",
        mixinStandardHelpOptions = true)
public class UnblockCommand implements Callable<Integer> {

    @Parameters(index = "0..*", paramLabel = "<dominio>",
            description = "Um ou mais dominios. Ex.: tiktok.com vm.tiktok.com",
            arity = "1..*")
    List<String> domainsRaw = new ArrayList<>();

    @Option(names = {"--force", "-f"},
            description = "Permite desbloqueio antecipado de bloqueio temporário (pede confirmação digitada por dominio).")
    boolean force;

    @Option(names = "--hosts-file", hidden = true) String hostsFileOverride;
    @Option(names = "--state-file", hidden = true) String stateFileOverride;

    public UnblockCommand() {
    }

    UnblockCommand(List<String> domainsRaw, boolean force, String hostsFileOverride,
                   String stateFileOverride) {
        this.domainsRaw = new ArrayList<>(domainsRaw);
        this.force = force;
        this.hostsFileOverride = hostsFileOverride;
        this.stateFileOverride = stateFileOverride;
    }

    @Override
    public Integer call() {
        final List<String> domains = new ArrayList<>();
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
            }
        }

        BlockService service = CliSupport.newService(hostsFileOverride, stateFileOverride);

        if (!service.isHostsWritable()) {
            System.err.println("Erro: " + CliSupport.sudoHint("unblock " + String.join(" ", domains)));
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
            return 1;
        } catch (IOException e) {
            System.err.println("Erro ao limpar bloqueios expirados: " + e.getMessage());
            return 1;
        }

        Instant now = Instant.now(service.getClock());

        // Pré-checagem anti-impulso: sem --force, qualquer domínio protegido
        // aborta TUDO antes de alterar qualquer coisa.
        if (!force) {
            List<String> protectedDomains = new ArrayList<>();
            for (String domain : domains) {
                BlockEntry entry = state.get(domain);
                if (entry != null && entry.isTemporaryAndActive(now)) {
                    protectedDomains.add(domain + " (restam "
                            + DurationParser.formatRemaining(Duration.between(now, entry.getExpiresAt()))
                            + ")");
                }
            }
            if (!protectedDomains.isEmpty()) {
                System.err.println("Desbloqueio RECUSADO para: " + String.join(", ", protectedDomains) + ".");
                System.err.println("Para desbloquear antecipadamente, rode de novo com --force "
                        + "(será pedida confirmação digitada por dominio). Nada foi alterado.");
                return 3;
            }
        }

        int failures = 0;
        boolean cancelledAny = false;
        for (String domain : domains) {
            BlockEntry entry = state.get(domain);
            if (entry == null) {
                System.out.println("'" + domain + "' não está bloqueado. Nada a fazer.");
                continue;
            }
            if (entry.isTemporaryAndActive(now)) {
                // Aqui force == true (sem force já abortamos acima).
                Duration remaining = Duration.between(now, entry.getExpiresAt());
                System.out.println("!!! AVISO FORTE !!!");
                System.out.println("Você está prestes a DESBLOQUEAR ANTECIPADAMENTE '" + domain + "',");
                System.out.println("que ainda teria " + DurationParser.formatRemaining(remaining)
                        + " de bloqueio. Isso enfraquece sua proteção contra procrastinação.");
                System.out.print("Para confirmar, digite SIM e pressione Enter (qualquer outra coisa cancela): ");
                System.out.flush();
                String answer = readLine();
                if (answer == null || !answer.trim().equalsIgnoreCase("SIM")) {
                    System.out.println("Desbloqueio antecipado CANCELADO para '" + domain + "'. Continua bloqueado.");
                    cancelledAny = true;
                    continue;
                }
            }
            state.remove(domain);
            try {
                service.getStore().save(state);
            } catch (IOException e) {
                System.err.println("Erro ao salvar estado de '" + domain + "': " + e.getMessage());
                state.put(domain, entry);
                failures++;
                continue;
            }
            try {
                service.getBlocker().unblock(domain);
            } catch (IOException e) {
                try {
                    state.put(domain, entry);
                    service.getStore().save(state);
                } catch (IOException rollbackFailure) {
                    System.err.println("Erro CRÍTICO: falha no hosts E ao reverter o estado de '"
                            + domain + "': " + rollbackFailure.getMessage());
                }
                System.err.println("Erro ao desbloquear '" + domain + "': " + e.getMessage());
                failures++;
                continue;
            }
            System.out.println("Desbloqueado " + domain + " (+ www." + domain + ").");
        }
        if (failures > 0) {
            return 1;
        }
        return cancelledAny ? 3 : 0;
    }

    private static String readLine() {
        try {
            Scanner scanner = new Scanner(System.in);
            if (!scanner.hasNextLine()) {
                return null;
            }
            return scanner.nextLine();
        } catch (Exception e) {
            return null;
        }
    }
}
