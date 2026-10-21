package com.sitelock.cli;

import com.sitelock.blocker.SiteBlocker;
import com.sitelock.core.BlockEntry;
import com.sitelock.core.BlockService;
import com.sitelock.core.CorruptedStateException;
import com.sitelock.core.DurationParser;
import com.sitelock.core.Profile;
import com.sitelock.core.ProfileMerge;
import com.sitelock.core.ProfileNames;
import com.sitelock.core.ProfileStore;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;

/**
 * siteblock profile apply <nome> — recarrega a receita sem nunca encurtar o
 * que já está mais forte.
 *
 * <p>A ordem é Estado → hosts, e ela não é negociável. O caminho inverso tem
 * um pior modo de falha: entrada no hosts sem registro no estado significa um
 * domínio preso para sempre, porque o {@code sweepExpired()} varre o JSON, não
 * o hosts. A ordem escolhida tem a janela oposta — se o processo for morto
 * entre os dois passos, o estado diz "bloqueado" e o hosts não foi escrito, ou
 * seja, o domínio está de fato livre. Fail-open e silencioso.
 *
 * <p>E é <b>um</b> {@code save} e <b>um</b> {@code blockAll}. Pelo caminho
 * por domínio, um perfil de 40 domínios fazia 40 travadas do hosts, e a
 * verificação otimista de concorrência do {@code HostsFileManager} podia
 * matá-lo no domínio k, com os k-1 anteriores já aplicados: estado parcial e
 * um erro depois de metade do trabalho feito.
 */
@Command(name = "apply",
        description = "Aplica um perfil, so aumentando o prazo de cada dominio.",
        mixinStandardHelpOptions = true)
public class ProfileApplyCommand implements Callable<Integer> {

    @Parameters(index = "0", paramLabel = "<nome>", description = "Nome do perfil.")
    String name;

    @Option(names = "--hosts-file", hidden = true) String hostsFileOverride;
    @Option(names = "--state-file", hidden = true) String stateFileOverride;

    /** Blocker injetado pelo teste; null = usa o do hosts do sistema. */
    SiteBlocker blockerOverride;

    public ProfileApplyCommand() {
    }

    ProfileApplyCommand(String name, String hostsFileOverride, String stateFileOverride,
            SiteBlocker blockerOverride) {
        this.name = name;
        this.hostsFileOverride = hostsFileOverride;
        this.stateFileOverride = stateFileOverride;
        this.blockerOverride = blockerOverride;
    }

    @Override
    public Integer call() {
        final String valid;
        try {
            valid = ProfileNames.require(name);
        } catch (IllegalArgumentException e) {
            System.err.println("Erro: " + e.getMessage());
            return 1;
        }

        BlockService service = blockerOverride != null
                ? CliSupport.newService(hostsFileOverride, stateFileOverride, blockerOverride)
                : CliSupport.newService(hostsFileOverride, stateFileOverride);

        if (!service.isHostsWritable()) {
            System.err.println("Erro: " + CliSupport.elevationHint("profile apply " + valid));
            return 1;
        }

        final Profile profile;
        try {
            profile = new ProfileStore(CliSupport.resolveProfilesFile(stateFileOverride)).load().get(valid);
        } catch (IOException e) {
            System.err.println("Erro ao ler perfis: " + e.getMessage());
            return 1;
        }
        if (profile == null) {
            System.err.println("Perfil '" + valid + "' não existe.");
            return 1;
        }

        try {
            BlockService.SweepResult sweep = service.sweepExpired();
            if (!sweep.removed().isEmpty()) {
                System.out.println("Limpeza: bloqueios expirados removidos: "
                        + String.join(", ", sweep.removed()));
            }
        } catch (CorruptedStateException e) {
            System.err.println("Erro: estado corrompido (" + e.getMessage() + ").");
            System.err.println("Nada foi alterado.");
            return 1;
        } catch (IOException e) {
            System.err.println("Erro ao limpar bloqueios expirados: " + e.getMessage());
            return 1;
        }

        Instant now = Instant.now(service.getClock());
        Plano plano = new Plano();
        try {
            service.inTransaction(
                    (estado, tx) -> {
                        Map<String, ProfileMerge.Decision> decisions =
                                ProfileMerge.merge(estado, profile, now);

                        // POR QUE: TreeMap e não HashMap — o estado é gravado como
                        // JSON, e o JSON tem que sair sempre na mesma ordem. Com
                        // HashMap, o apply gravava o arquivo em ordem arbitrária e
                        // o block em ordem alfabética: o mesmo arquivo, dois
                        // formatos, e um diff que não é de verdade.
                        Map<String, BlockEntry> novoEstado = new TreeMap<>(estado);
                        for (Map.Entry<String, ProfileMerge.Decision> e : decisions.entrySet()) {
                            String domain = e.getKey();
                            ProfileMerge.Decision d = e.getValue();
                            if (d.action() != ProfileMerge.Action.KEPT) {
                                novoEstado.put(domain, d.entry());
                                plano.alterados++;
                                // Só o que este apply criou tem prazo novo: um RAISED
                                // para permanente não tem o que cumprir.
                                plano.criouTemporario |= d.entry().getExpiresAt() != null;
                            } else {
                                plano.mantidos++;
                            }
                            System.out.println("  " + domain + " — " + descrever(d, now));
                        }
                        plano.decisions = decisions;

                        if (plano.alterados > 0) {
                            try {
                                tx.save(novoEstado);
                            } catch (IOException e) {
                                plano.erroAoSalvar = e;
                            }
                        }
                        return plano;
                    },
                    (p, estado, tx) -> {
                        if (p.erroAoSalvar != null || p.alterados == 0) {
                            return;
                        }
                        // TODOS os domínios do perfil, inclusive os KEPT. Isso não é
                        // desperdício: addDomain é idempotente, e é o que permite ao
                        // apply CONSERTAR um estado que diz bloqueado com o hosts sem a
                        // linha. Esse desvio acontece quando um apply anterior morre
                        // entre o save e o blockAll; sem reescrever os KEPT, todo apply
                        // seguinte veria "nada a fazer" para sempre e o site ficaria
                        // aberto com o status mentindo.
                        List<String> todos = new ArrayList<>(p.decisions.keySet());
                        service.getBlocker().blockAll(todos);
                    });
        } catch (IOException e) {
            // O hosts não aceitou: a transação já devolveu o estado ao que era.
            for (Throwable suprimido : e.getSuppressed()) {
                System.err.println("Erro CRÍTICO: falha no hosts E ao reverter o estado: "
                        + suprimido.getMessage());
            }
            System.err.println("Erro ao bloquear os domínios do perfil: " + e.getMessage());
            System.err.println("Nada foi alterado.");
            return 1;
        }
        if (plano.erroAoSalvar != null) {
            System.err.println("Erro ao salvar o estado: " + plano.erroAoSalvar.getMessage());
            return 1;
        }
        if (plano.alterados > 0) {
            CliSupport.flushDnsBestEffort();
        }

        System.out.println("Perfil '" + valid + "': " + plano.decisions.size() + " domínio(s), "
                + plano.alterados + " alterados, " + plano.mantidos + " mantidos.");
        if (plano.criouTemporario) {
            CliSupport.warnIfNoDaemon(stateFileOverride);
        }
        return 0;
    }

    /**
     * O que a transação do {@code profile apply} carrega entre o estado e o hosts.
     *
     * <p>POR QUE: o merge e a gravação acontecem em lados diferentes da
     * transação, e o comando precisa das contagens e das decisões para
     * imprimir o resumo e decidir se escreve no hosts.
     */
    private static final class Plano {

        Map<String, ProfileMerge.Decision> decisions = new TreeMap<>();
        int alterados;
        int mantidos;
        boolean criouTemporario;
        IOException erroAoSalvar;
    }

    private String descrever(ProfileMerge.Decision d, Instant now) {
        BlockEntry entry = d.entry();
        String prazo = entry.getExpiresAt() == null ? "permanente"
                : DurationParser.formatRemaining(Duration.between(now, entry.getExpiresAt()));
        switch (d.action()) {
            case ADDED:
                return prazo + " (novo)";
            case RAISED:
                return prazo + " (aumentado)";
            case KEPT:
            default:
                return "já bloqueado, " + prazo + " (mantido)";
        }
    }
}