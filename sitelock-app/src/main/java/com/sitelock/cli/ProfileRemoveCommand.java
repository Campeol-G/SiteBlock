package com.sitelock.cli;

import com.sitelock.core.BlockEntry;
import com.sitelock.core.BlockStore;
import com.sitelock.core.DomainUtils;
import com.sitelock.core.DurationParser;
import com.sitelock.core.Profile;
import com.sitelock.core.ProfileEntry;
import com.sitelock.core.ProfileNames;
import com.sitelock.core.ProfileStore;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;

/**
 * siteblock profile &lt;nome&gt; remove &lt;dominio...&gt;.
 *
 * <p>Tira domínios da receita de um perfil que já existe; como o {@code add}, não toca no
 * hosts. Avisa quando o domínio continua bloqueado, porque remover da receita não é soltar.
 */
@Command(name = "remove",
        description = "Tira dominios de um perfil existente.",
        mixinStandardHelpOptions = true)
public class ProfileRemoveCommand implements Callable<Integer> {

    @ParentCommand
    ProfileCommand parent;

    @Parameters(index = "0..*", paramLabel = "<dominio>",
            description = "Um ou mais dominios. Ex.: tiktok.com vm.tiktok.com",
            arity = "1..*")
    List<String> domainsRaw = new ArrayList<>();

    @Option(names = "--state-file", hidden = true) String stateFileOverride;

    // Relógio injetado pelo teste; null = o do sistema.
    Clock clockOverride;

    // Store injetado pelo teste; null = usa o do stateDir.
    ProfileStore storeOverride;

    public ProfileRemoveCommand() {
    }

    @Override
    public Integer call() {
        final String nome = nomeDoPerfil();
        if (nome == null) {
            return 1;
        }

        final List<String> domains = new ArrayList<>();
        for (String raw : domainsRaw) {
            try {
                DomainUtils.NormalizationResult r = DomainUtils.normalize(raw);
                if (r.normalized()) {
                    System.out.println("Aviso: entrada normalizada de '" + r.original()
                            + "' para '" + r.domain() + "'.");
                }
                if (!domains.contains(r.domain())) {
                    domains.add(r.domain());
                }
            } catch (IllegalArgumentException e) {
                System.err.println("Erro: " + e.getMessage());
                return 1;
            }
        }

        ProfileStore store = storeOverride != null ? storeOverride
                : new ProfileStore(CliSupport.resolveProfilesFile(stateFileOverride));

        List<String> removidos = new ArrayList<>();
        List<String> ausentes = new ArrayList<>();
        boolean[] naoExiste = {false};
        boolean[] nadaARemover = {false};
        // A leitura e a gravação da receita dentro de uma transação: dois
        // `profile remove` simultâneos não podem se perder.
        try {
            store.inTransaction(tx -> {
                Map<String, Profile> profiles = tx.load();
                Profile profile = profiles.get(nome);
                if (profile == null) {
                    naoExiste[0] = true;
                    return null;
                }
                Map<String, ProfileEntry> entries = new TreeMap<>(profile.getEntries());
                for (String domain : domains) {
                    if (entries.remove(domain) != null) {
                        removidos.add(domain);
                    } else {
                        ausentes.add(domain);
                    }
                }
                if (removidos.isEmpty()) {
                    nadaARemover[0] = true;
                    return null;
                }
                profiles.put(nome, new Profile(profile.getCreatedAt(), Instant.now(clock()), entries));
                tx.save(profiles);
                return null;
            });
        } catch (IOException e) {
            System.err.println("Erro ao salvar o perfil: " + e.getMessage());
            return 1;
        }
        if (naoExiste[0]) {
            System.err.println("Perfil '" + nome + "' não existe.");
            return 1;
        }
        if (nadaARemover[0]) {
            System.err.println("Nenhum dos domínios está no perfil '" + nome + "'. Nada foi alterado.");
            return 1;
        }

        System.out.println("Perfil '" + nome + "': " + removidos.size() + " domínio(s) removido(s).");
        for (String domain : removidos) {
            System.out.println("  - " + domain);
        }
        for (String domain : ausentes) {
            System.out.println("  (não estava no perfil: " + domain + ")");
        }

        // POR QUE: o aviso aponta o "unblock" individual — o "profile unblock" só
        // trabalha sobre os domínios do perfil, e este já não está mais lá. Sem o
        // aviso, quem assume que remover = soltar fica com o site bloqueado.
        avisarSeContinuaBloqueado(removidos);
        return 0;
    }

    // POR QUE: a leitura do estado é separada da escrita do perfil — falhar aqui só
    // impede o aviso, não a remoção, e desfazer a receita nova por causa de um estado
    // ilegível seria trocar um problema pequeno por um maior.
    private void avisarSeContinuaBloqueado(List<String> removidos) {
        final Map<String, BlockEntry> estado;
        try {
            estado = new BlockStore(CliSupport.resolveStateFile(stateFileOverride)).load();
        } catch (Exception e) {
            System.out.println("Aviso: não consegui ler o estado para conferir o que continua "
                    + "bloqueado (" + e.getMessage() + ").");
            return;
        }
        Instant now = Instant.now(clock());
        List<String> continuam = new ArrayList<>();
        for (String domain : removidos) {
            BlockEntry entry = estado.get(domain);
            if (entry != null && !entry.isExpired(now)) {
                continuam.add(domain);
                System.out.println("AVISO: " + domain + " continua bloqueado ("
                        + descricaoDoPrazo(entry, now) + ").");
            }
        }
        if (!continuam.isEmpty()) {
            System.out.println("       Tirar da receita não solta o bloqueio. Para liberar: "
                    + CliSupport.elevatedCommand("unblock " + String.join(" ", continuam) + " --force"));
        }
    }

    private static String descricaoDoPrazo(BlockEntry entry, Instant now) {
        if (entry.getExpiresAt() == null) {
            return "permanente";
        }
        Duration restante = Duration.between(now, entry.getExpiresAt());
        return "restam " + DurationParser.formatRemaining(restante);
    }

    // O relógio do comando: o injetado pelo teste, ou o da CLI.
    private Clock clock() {
        return clockOverride != null ? clockOverride : CliSupport.clock();
    }

    private String nomeDoPerfil() {
        if (parent == null || parent.name == null || parent.name.isBlank()) {
            System.err.println("Erro: informe o nome do perfil. "
                    + "Uso: siteblock profile <nome> remove <dominio>");
            return null;
        }
        try {
            return ProfileNames.require(parent.name);
        } catch (IllegalArgumentException e) {
            System.err.println("Erro: " + e.getMessage());
            return null;
        }
    }
}
