package com.sitelock.cli;

import com.sitelock.core.BlockService;
import com.sitelock.core.BlockEntry;
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
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;

/** siteblock save profile <nome> [--force] — salva os bloqueios ativos como perfil. */
@Command(name = "save", description = "Salva os bloqueios ativos como perfil.", mixinStandardHelpOptions = true)
public class SaveProfileCommand implements Callable<Integer> {

    @Parameters(index = "0", paramLabel = "<sub>", description = "Sempre 'profile'.")
    String sub;

    @Parameters(index = "1", paramLabel = "<nome>", description = "Nome do perfil.")
    String name;

    @Option(names = "--force", description = "Sobrescreve um perfil existente.")
    boolean force;

    @Option(names = "--hosts-file", hidden = true) String hostsFileOverride;
    @Option(names = "--state-file", hidden = true) String stateFileOverride;

    public SaveProfileCommand() {}

    SaveProfileCommand(String sub, String name, boolean force, String hostsFileOverride, String stateFileOverride) {
        this.sub = sub; this.name = name; this.force = force; this.hostsFileOverride = hostsFileOverride; this.stateFileOverride = stateFileOverride;
    }

    @Override
    public Integer call() {
        if (!"profile".equals(sub)) {
            System.err.println("Uso: siteblock save profile <nome> [--force]");
            return 2;
        }
        final String valid;
        try {
            valid = ProfileNames.require(name);
        } catch (IllegalArgumentException e) {
            System.err.println("Erro: " + e.getMessage());
            return 1;
        }
        ProfileStore store = new ProfileStore(CliSupport.resolveProfilesFile(stateFileOverride));
        BlockService service = CliSupport.newService(hostsFileOverride, stateFileOverride);
        final Map<String, BlockEntry> state;
        try {
            state = service.sweepExpired().state();
        } catch (CorruptedStateException e) {
            System.err.println("Erro: estado corrompido (" + e.getMessage() + ").");
            return 1;
        } catch (IOException e) {
            System.err.println("Erro ao ler o estado: " + e.getMessage());
            return 1;
        }
        Instant now = Instant.now(service.getClock());
        Map<String, ProfileEntry> entries = new TreeMap<>();
        for (BlockEntry e : state.values()) {
            if (e == null || e.getDomain() == null || e.isExpired(now)) continue;
            Long millis = e.getExpiresAt() == null ? null : java.time.Duration.between(now, e.getExpiresAt()).toMillis();
            entries.put(e.getDomain(), new ProfileEntry(millis));
        }
        if (entries.isEmpty()) {
            System.err.println("Nada está bloqueado agora — um perfil vazio não faria nada.");
            return 1;
        }
        boolean[] jaExiste = {false};
        try {
            store.inTransaction(tx -> {
                Map<String, Profile> profiles = tx.load();
                if (profiles.containsKey(valid) && !force) {
                    jaExiste[0] = true;
                    return null;
                }
                Instant createdAt = profiles.containsKey(valid) ? profiles.get(valid).getCreatedAt() : now;
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
