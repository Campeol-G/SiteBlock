package com.sitelock.cli;

import com.sitelock.core.BlockEntry;
import com.sitelock.core.BlockService;
import com.sitelock.core.CorruptedStateException;
import com.sitelock.core.DurationParser;
import com.sitelock.core.Profile;
import com.sitelock.core.ProfileEntry;
import com.sitelock.core.ProfileNames;
import com.sitelock.core.ProfileStore;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;

/**
 * siteblock profile save <nome> [--force].
 *
 * <p>Guarda o que está bloqueado <b>agora</b> como uma receita reutilizável: o
 * que vai para o arquivo é o prazo restante de cada domínio, nunca o
 * {@code expiresAt} original. Um perfil é receita, não histórico — guardar o
 * instante transformaria um perfil de 6 meses em "já vencido".
 *
 * <p>Recusa sobrescrever sem {@code --force}: perder a receita sem querer é o
 * pior erro possível aqui.
 */
@Command(name = "save",
        description = "Salva os bloqueios ativos como um perfil nomeado.",
        mixinStandardHelpOptions = true)
public class ProfileSaveCommand implements Callable<Integer> {

    @Parameters(index = "0", paramLabel = "<nome>", description = "Nome do perfil.")
    String name;

    @Option(names = "--force", description = "Sobrescreve um perfil que já existe.")
    boolean force;

    @Option(names = "--hosts-file", hidden = true) String hostsFileOverride;
    @Option(names = "--state-file", hidden = true) String stateFileOverride;

    /** Store injetado pelo teste; null = usa o do stateDir. */
    ProfileStore storeOverride;

    public ProfileSaveCommand() {
    }

    ProfileSaveCommand(String name, boolean force, String hostsFileOverride, String stateFileOverride) {
        this.name = name;
        this.force = force;
        this.hostsFileOverride = hostsFileOverride;
        this.stateFileOverride = stateFileOverride;
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

        ProfileStore store = storeOverride != null ? storeOverride
                : new ProfileStore(CliSupport.resolveProfilesFile(stateFileOverride));
        BlockService service = CliSupport.newService(hostsFileOverride, stateFileOverride);

        final Map<String, BlockEntry> state;
        try {
            state = service.sweepExpired().state();
        } catch (CorruptedStateException e) {
            System.err.println("Erro: estado corrompido (" + e.getMessage() + ").");
            System.err.println("Nada foi alterado.");
            return 1;
        } catch (IOException e) {
            System.err.println("Erro ao ler o estado: " + e.getMessage());
            return 1;
        }

        Instant now = Instant.now(service.getClock());

        // O sweep não garante "só o que está ativo agora": quando o hosts não
        // está gravável ele devolve o estado intacto (skippedDueToPermission) e
        // os expirados continuam no mapa. Filtrar aqui evita gravar um domínio
        // vencido como se fosse receita viva.
        Map<String, ProfileEntry> entries = new TreeMap<>();
        for (BlockEntry e : state.values()) {
            if (e == null || e.getDomain() == null || e.isExpired(now)) {
                continue;
            }
            Long millis = e.getExpiresAt() == null ? null
                    : Duration.between(now, e.getExpiresAt()).toMillis();
            entries.put(e.getDomain(), new ProfileEntry(millis));
        }

        if (entries.isEmpty()) {
            System.err.println("Nada está bloqueado agora — um perfil vazio não faria nada.");
            return 1;
        }

        // A leitura e a gravação das receitas dentro de uma transação: dois
        // `profile save` simultâneos não podem se perder, e um perfil escrito
        // por outro terminal no meio do caminho tem que continuar lá.
        boolean[] jaExiste = {false};
        try {
            store.inTransaction(tx -> {
                Map<String, Profile> profiles = tx.load();
                if (profiles.containsKey(valid) && !force) {
                    jaExiste[0] = true;
                    return null;
                }
                Instant createdAt = profiles.containsKey(valid)
                        ? profiles.get(valid).getCreatedAt() : now;
                profiles.put(valid, new Profile(createdAt, now, entries));
                tx.save(profiles);
                return null;
            });
        } catch (IOException e) {
            System.err.println("Erro ao salvar o perfil: " + e.getMessage());
            return 1;
        }
        if (jaExiste[0]) {
            System.err.println("Já existe um perfil '" + valid + "'. Use --force para sobrescrever.");
            return 1;
        }

        System.out.println("Perfil '" + valid + "' salvo com " + entries.size() + " domínio(s).");
        return 0;
    }
}