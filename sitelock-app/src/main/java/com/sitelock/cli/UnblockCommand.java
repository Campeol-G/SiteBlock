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
import java.util.Scanner;
import java.util.concurrent.Callable;

/**
 * siteblock unblock <dominio...> [--force].
 * Temporário ativo só sai com --force + digitar SIM. Sem --force, nada muda.
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
            System.err.println("Erro: " + CliSupport.elevationHint("unblock " + String.join(" ", domains)));
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

        // A confirmação é digitada antes da transação: segurar a trava do estado
        // enquanto a pessoa escreve SIM travaria o daemon e qualquer outro
        // comando até ela terminar de digitar.
        final List<String> aSoltar = new ArrayList<>();
        boolean cancelledAny = false;
        for (String domain : domains) {
            BlockEntry entry = state.get(domain);
            if (entry == null) {
                System.out.println("'" + domain + "' não está bloqueado. Nada a fazer.");
                continue;
            }
            if (entry.isTemporaryAndActive(now)) {
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
            aSoltar.add(domain);
        }

        Plano plano = new Plano();
        try {
            service.inTransaction(
                    (estado, tx) -> {
                        for (String domain : aSoltar) {
                            BlockEntry entry = estado.get(domain);
                            if (entry == null) {
                                plano.ausentes.add(domain);
                                continue;
                            }
                            plano.anteriores.put(domain, entry);
                            estado.remove(domain);
                            try {
                                tx.save(estado);
                            } catch (IOException e) {
                                System.err.println("Erro ao salvar estado de '" + domain + "': " + e.getMessage());
                                estado.put(domain, entry);
                                plano.falhas++;
                                continue;
                            }
                            plano.gravados.add(domain);
                        }
                        return plano;
                    },
                    (p, estado, tx) -> {
                        for (String domain : p.gravados) {
                            try {
                                service.getBlocker().unblock(domain);
                            } catch (IOException e) {
                                try {
                                    estado.put(domain, p.anteriores.get(domain));
                                    tx.save(estado);
                                } catch (IOException rollbackFailure) {
                                    System.err.println("Erro CRÍTICO: falha no hosts E ao reverter o estado de '"
                                            + domain + "': " + rollbackFailure.getMessage());
                                }
                                System.err.println("Erro ao desbloquear '" + domain + "': " + e.getMessage());
                                p.falhas++;
                                continue;
                            }
                            System.out.println("Desbloqueado " + domain + ".");
                        }
                    });
        } catch (IOException e) {
            System.err.println("Erro ao gravar o estado: " + e.getMessage());
            return 1;
        }
        for (String domain : plano.ausentes) {
            System.out.println("'" + domain + "' não está bloqueado. Nada a fazer.");
        }
        int failures = plano.falhas;
        if (failures > 0) {
            return 1;
        }
        if (!cancelledAny) {
            // Best-effort: não muda o exit code; só avisa se o flush falhar.
            CliSupport.flushDnsBestEffort();
        }
        return cancelledAny ? 3 : 0;
    }

    /**
     * O que a transação do {@code unblock} carrega entre o estado e o hosts.
     *
     * <p>POR QUE: o estado e o hosts são dois lugares, e o desbloqueio tem que
     * sair nos dois. Guardar o que estava em cada domínio é o que permite
     * reverter só o que falhou.
     */
    private static final class Plano {

        final List<String> gravados = new ArrayList<>();
        final List<String> ausentes = new ArrayList<>();
        final Map<String, BlockEntry> anteriores = new LinkedHashMap<>();
        int falhas;
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
