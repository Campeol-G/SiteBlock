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
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * siteblock profile unblock &lt;nome&gt; [--force] — solta todos os domínios do
 * perfil de uma vez.
 *
 * <p>Há <b>duas</b> camadas de resistência, e elas não são a mesma coisa:
 *
 * <ol>
 *   <li>A externa é o {@code --force}: sem ele, qualquer domínio do perfil que
 *       esteja ativo agora faz o comando recusar tudo e sair com 3 — e "ativo"
 *       inclui permanente. Se o gate cobrisse só os temporários, um perfil só de
 *       travas ilimitadas sairia sem flag e sem digitar uma tecla.</li>
 *   <li>A interna é a confirmação digitada: uma única pergunta antes de tocar
 *       em qualquer domínio. A lista mostra todos os do perfil que estão no
 *       estado, com o tempo restante de cada temporário e {@code permanente} nos
 *       que não têm prazo.</li>
 * </ol>
 *
 * <p>Por que os permanentes entram na confirmação, quando o {@code unblock}
 * individual os solta em silêncio: um bloqueio permanente é o compromisso
 * <b>mais forte</b> que a pessoa assumiu, não o mais fraco — é o que se faz
 * quando se quer fechar a porta de vez. Soltá-lo calado seria o caminho mais
 * curto para descartar uma trava ilimitada sem digitar uma tecla, exatamente o
 * atalho que o {@code --force} existe para não criar. O {@code unblock}
 * individual fica como está, por não ser escopo mudá-lo.
 *
 * <p>Cancelar não desfaz nada: o comando não toca em domínio algum.
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

    /** Blocker injetado pelo teste; null = usa o do hosts do sistema. */
    SiteBlocker blockerOverride;

    /** Resposta injetada pelo teste; null = lê do stdin. */
    String answer;

    public ProfileUnblockCommand() {
    }

    ProfileUnblockCommand(String name, boolean force, String hostsFileOverride, String stateFileOverride,
            String answer) {
        this.name = name;
        this.force = force;
        this.hostsFileOverride = hostsFileOverride;
        this.stateFileOverride = stateFileOverride;
        this.answer = answer;
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
        Duration total = Duration.ZERO;
        for (String domain : profile.getEntries().keySet()) {
            BlockEntry entry = state.get(domain);
            if (entry == null) {
                continue;
            }
            alvos.add(domain);
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

        if (!force) {
            System.err.println("Desbloqueio RECUSADO para: " + String.join(", ", descricao) + ".");
            System.err.println("Para liberar mesmo assim, rode de novo com --force "
                    + "(será pedida confirmação digitada). Nada foi alterado.");
            return 3;
        }

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

        String resposta = answer != null ? answer : ProfileDeleteCommand.readLine();
        if (resposta == null || !resposta.trim().equalsIgnoreCase("SIM")) {
            System.out.println("Desbloqueio CANCELADO. Todos continuam bloqueados.");
            return 3;
        }

        try {
            service.getStore().save(remover(state, alvos));
        } catch (IOException e) {
            System.err.println("Erro ao salvar o estado: " + e.getMessage());
            return 1;
        }
        try {
            service.getBlocker().unblockAll(alvos);
        } catch (IOException e) {
            System.err.println("Erro ao remover os domínios do hosts: " + e.getMessage());
            return 1;
        }

        System.out.println("Desbloqueados " + alvos.size() + " domínio(s) do perfil '" + valid + "'.");
        CliSupport.flushDnsBestEffort();
        return 0;
    }

    private static Map<String, BlockEntry> remover(Map<String, BlockEntry> state, List<String> alvos) {
        Map<String, BlockEntry> novo = new java.util.HashMap<>(state);
        for (String domain : alvos) {
            novo.remove(domain);
        }
        return novo;
    }
}