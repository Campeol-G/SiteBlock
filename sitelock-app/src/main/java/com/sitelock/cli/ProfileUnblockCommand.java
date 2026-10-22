package com.sitelock.cli;

import com.sitelock.blocker.SiteBlocker;
import com.sitelock.core.BlockEntry;
import com.sitelock.core.BlockService;
import com.sitelock.core.CorruptedStateException;
import com.sitelock.core.DurationParser;
import com.sitelock.core.Profile;
import com.sitelock.core.ProfileNames;
import com.sitelock.core.ProfileStore;
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
import java.util.Objects;
import java.util.concurrent.Callable;

/**
 * siteblock profile unblock &lt;nome&gt; [--force] — solta todos os domínios do
 * perfil de uma vez.
 *
 * <p>Duas camadas: o {@code --force}, que recusa tudo e sai com 3 enquanto houver domínio
 * ativo, e a confirmação digitada, que vem depois. Cancelar não desfaz nada — o comando
 * não toca em domínio algum.
 */
@Command(name = "unblock",
        description = "Libera todos os dominios de um perfil, de uma vez.",
        mixinStandardHelpOptions = true)
public class ProfileUnblockCommand implements Callable<Integer> {

    @Parameters(index = "0", paramLabel = "<nome>", description = "Nome do perfil.")
    String name;

    @Option(names = "--force", description = "Libera mesmo domínios que ainda têm prazo.")
    boolean force;

    @Option(names = "--hosts-file", hidden = true) String hostsFileOverride;
    @Option(names = "--state-file", hidden = true) String stateFileOverride;

    // Blocker injetado pelo teste; null = usa o do hosts do sistema.
    SiteBlocker blockerOverride;

    // Resposta injetada pelo teste; null = lê do stdin.
    String answer;

    public ProfileUnblockCommand() {
    }

    ProfileUnblockCommand(String name, boolean force, String hostsFileOverride, String stateFileOverride,
            String answer) {
        this(name, force, hostsFileOverride, stateFileOverride, answer, null);
    }

    ProfileUnblockCommand(String name, boolean force, String hostsFileOverride, String stateFileOverride,
            String answer, SiteBlocker blockerOverride) {
        this.name = name;
        this.force = force;
        this.hostsFileOverride = hostsFileOverride;
        this.stateFileOverride = stateFileOverride;
        this.answer = answer;
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

        // ATENÇÃO: sem esta trava, quem esquecer o sudo tem o pior desfecho possível: o
        // estado é gravado primeiro, a escrita no hosts falha depois, e o domínio fica
        // preso no hosts sem registro. A recuperação seria editar o /etc/hosts à mão.
        if (!service.isHostsWritable()) {
            System.err.println("Erro: " + CliSupport.elevationHint("profile unblock " + valid));
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

        final Map<String, BlockEntry> state;
        try {
            state = service.sweepExpired().state();
        } catch (CorruptedStateException e) {
            System.err.println("Erro: estado corrompido (" + e.getMessage() + ").");
            System.err.println("Nada foi alterado.");
            return 1;
        } catch (IOException e) {
            System.err.println("Erro ao limpar bloqueios expirados: " + e.getMessage());
            return 1;
        }

        Instant now = Instant.now(service.getClock());
        List<String> alvos = new ArrayList<>();
        List<String> descricao = new ArrayList<>();
        Map<String, BlockEntry> confirmados = new LinkedHashMap<>();
        Duration total = Duration.ZERO;
        for (String domain : profile.getEntries().keySet()) {
            BlockEntry entry = state.get(domain);
            if (entry == null) {
                continue;
            }
            alvos.add(domain);
            confirmados.put(domain, entry);
            if (entry.isTemporaryAndActive(now)) {
                Duration restante = Duration.between(now, entry.getExpiresAt());
                total = total.plus(restante);
                descricao.add(domain + " (restam " + DurationParser.formatRemaining(restante) + ")");
            } else {
                descricao.add(domain + " (permanente)");
            }
        }

        if (alvos.isEmpty()) {
            System.out.println("Nenhum domínio do perfil '" + valid + "' está bloqueado. Nada a fazer.");
            return 0;
        }

        // ATENÇÃO: o gate cobre também os permanentes. Se cobrisse só os temporários, um
        // perfil só de travas ilimitadas sairia sem flag e sem digitar uma tecla.
        if (!force) {
            System.err.println("Desbloqueio RECUSADO para: " + String.join(", ", descricao) + ".");
            System.err.println("Para liberar mesmo assim, rode de novo com --force "
                    + "(será pedida confirmação digitada). Nada foi alterado.");
            return 3;
        }

        // POR QUE: os permanentes entram na confirmação, quando o "unblock" individual os
        // solta em silêncio — permanente é o compromisso mais forte que a pessoa assumiu, e
        // soltá-lo calado seria o atalho que o "--force" existe para não criar.
        System.out.println("Você está prestes a DESBLOQUEAR " + alvos.size() + " domínio(s) do perfil '"
                + valid + "':");
        for (String d : descricao) {
            System.out.println("  " + d);
        }
        if (!total.isZero()) {
            System.out.println("Total de tempo de bloqueio que está sendo descartado: "
                    + DurationParser.formatRemaining(total) + ".");
        }
        System.out.print("Para confirmar, digite SIM e pressione Enter (qualquer outra coisa cancela): ");
        System.out.flush();

        String resposta = answer != null ? answer : CliSupport.readLine();
        if (resposta == null || !resposta.trim().equalsIgnoreCase("SIM")) {
            System.out.println("Desbloqueio CANCELADO. Todos continuam bloqueados.");
            return 3;
        }

        // POR QUE: a transação é o único ponto de entrada que grava estado — o load e o
        // save na mesma trava, e o hosts escrito dentro dela, na ordem Estado → hosts.
        // Fora daqui, o block de outro terminal some do JSON e o domínio fica preso.
        final boolean[] chegouNoHosts = {false};
        final String[] conflito = {null};
        try {
            service.inTransaction(
                    (estado, tx) -> {
                        // Confirmação e remoção não podem ter estados diferentes: se
                        // qualquer alvo mudou, o lote inteiro é cancelado.
                        for (String domain : alvos) {
                            if (!Objects.equals(estado.get(domain), confirmados.get(domain))) {
                                conflito[0] = domain;
                                return null;
                            }
                        }
                        for (String domain : alvos) {
                            estado.remove(domain);
                        }
                        tx.save(estado);
                        return null;
                    },
                    (resultado, estado, tx) -> {
                        if (conflito[0] != null) {
                            return;
                        }
                        chegouNoHosts[0] = true;
                        service.getBlocker().unblockAll(alvos);
                    });
        } catch (IOException e) {
            if (!chegouNoHosts[0]) {
                System.err.println("Erro ao salvar o estado: " + e.getMessage());
                return 1;
            }
            // A transação já devolveu o estado ao que era. Se a devolução
            // falhou, o estado mente sobre o hosts — e o domínio fica preso
            // sem registro, que é o que ninguém no projeto consegue limpar.
            if (e.getSuppressed().length > 0) {
                System.err.println("Erro CRÍTICO: falha no hosts E ao reverter o estado: "
                        + e.getSuppressed()[0].getMessage());
                System.err.println("Os domínios " + String.join(", ", alvos)
                        + " podem ter ficado no arquivo hosts sem registro. "
                        + "Rode: " + CliSupport.elevatedCommand("unblock " + alvos.get(0) + " --force"));
            }
            System.err.println("Erro ao remover os domínios do hosts: " + e.getMessage());
            System.err.println("Nada foi alterado.");
            return 1;
        }

        if (conflito[0] != null) {
            System.err.println("'" + conflito[0] + "' mudou enquanto você confirmava; "
                    + "o lote inteiro foi cancelado. Tente de novo.");
            return 1;
        }

        System.out.println("Desbloqueados " + alvos.size() + " domínio(s) do perfil '" + valid + "'.");
        CliSupport.flushDnsBestEffort();
        return 0;
    }
}