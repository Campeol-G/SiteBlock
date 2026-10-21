package com.sitelock.cli;

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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;

/**
 * siteblock profile &lt;nome&gt; add &lt;dominio...&gt; [--for &lt;duracao&gt;].
 *
 * <p>Adiciona domínios à receita de um perfil que já existe, sem precisar
 * bloquear nada agora. É o complemento do {@code profile save}, que só sabe
 * guardar o que já está bloqueado: quem tem uma lista de domínios na cabeça e
 * quer guardá-la como receita não tinha caminho.
 *
 * <p><b>Não toca no hosts.</b> Editar a receita é uma operação de arquivo
 * puro: não pede sudo, não escreve no {@code /etc/hosts}, e não muda o que
 * está bloqueado. Quem quiser aplicar a receita nova usa {@code profile apply}
 * — e aí sim vale a ordem Estado → hosts, com o rollback que o {@code apply}
 * já faz.
 *
 * <p>O que fica guardado é o <b>prazo restante no momento do add</b>, como no
 * {@code save}: a mesma razão — um perfil é receita, não histórico. E o que
 * fica gravado é o que você pediu agora: adicionar um domínio que já estava no
 * perfil <b>substitui</b> a entrada, em vez de acumular. Uma receita com
 * duas durações para o mesmo domínio não é receita de ninguém.
 *
 * <p>Sem {@code --for}, o domínio entra permanente — que é o mesmo contrato do
 * {@code block}, e a mesma coisa que a maioria dos domínios de uma lista de
 * bloqueio quer ser.
 */
@Command(name = "add",
        description = "Adiciona dominios a um perfil existente.",
        mixinStandardHelpOptions = true)
public class ProfileAddCommand implements Callable<Integer> {

    @ParentCommand
    ProfileCommand parent;

    @Parameters(index = "0..*", paramLabel = "<dominio>",
            description = "Um ou mais dominios. Ex.: tiktok.com vm.tiktok.com",
            arity = "1..*")
    List<String> domainsRaw = new ArrayList<>();

    @Option(names = "--for", paramLabel = "<duracao>",
            description = "Prazo guardado no perfil. Ex.: 30m, 2h, 1d. Omitido = permanente.")
    String forDuration;

    @Option(names = "--state-file", hidden = true) String stateFileOverride;

    /** Store injetado pelo teste; null = usa o do stateDir. */
    ProfileStore storeOverride;

    public ProfileAddCommand() {
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

        final Duration duration;
        try {
            duration = forDuration == null ? null : DurationParser.parse(forDuration);
        } catch (IllegalArgumentException e) {
            System.err.println("Erro: " + e.getMessage());
            return 1;
        }

        ProfileStore store = storeOverride != null ? storeOverride
                : new ProfileStore(CliSupport.resolveProfilesFile(stateFileOverride));

        Long millis = duration == null ? null : duration.toMillis();
        List<String> adicionados = new ArrayList<>();
        List<String> substituidos = new ArrayList<>();
        boolean[] naoExiste = {false};
        // A leitura e a gravação da receita dentro de uma transação: dois
        // `profile add` simultâneos não podem se perder.
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
                    if (entries.containsKey(domain)) {
                        substituidos.add(domain);
                    } else {
                        adicionados.add(domain);
                    }
                    entries.put(domain, new ProfileEntry(millis));
                }
                profiles.put(nome, new Profile(profile.getCreatedAt(), Instant.now(), entries));
                tx.save(profiles);
                return null;
            });
        } catch (IOException e) {
            System.err.println("Erro ao salvar o perfil: " + e.getMessage());
            return 1;
        }
        if (naoExiste[0]) {
            System.err.println("Perfil '" + nome + "' não existe.");
            System.err.println("Para criar um perfil novo, bloqueie os domínios e rode: "
                    + "siteblock profile save " + nome);
            return 1;
        }

        String prazo = duration == null ? "permanente"
                : DurationParser.formatRemaining(duration);
        System.out.println("Perfil '" + nome + "': " + adicionados.size() + " domínio(s) adicionado(s), "
                + substituidos.size() + " substituído(s).");
        for (String domain : adicionados) {
            System.out.println("  + " + domain + " — " + prazo);
        }
        for (String domain : substituidos) {
            System.out.println("  ~ " + domain + " — " + prazo + " (substituiu a entrada anterior)");
        }
        System.out.println("A receita mudou; o que está bloqueado agora não mudou. "
                + "Rode 'siteblock profile apply " + nome + "' para aplicar.");
        return 0;
    }

    /**
     * O nome vem do pai: {@code siteblock profile <nome> add ...}.
     *
     * <p>Sem nome não há o que fazer — e a mensagem diz a forma inteira, porque
     * o erro mais provável é a pessoa ter digitado {@code siteblock profile add}
     * esperando que o "add" fosse o nome do perfil.
     */
    private String nomeDoPerfil() {
        if (parent == null || parent.name == null || parent.name.isBlank()) {
            System.err.println("Erro: informe o nome do perfil. "
                    + "Uso: siteblock profile <nome> add <dominio>");
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
