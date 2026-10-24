package com.sitelock.cli;

import com.sitelock.core.ProfileNames;
import com.sitelock.core.Profile;
import com.sitelock.core.ProfileStore;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;

/** siteblock create profile <nome> — cria um perfil vazio. */
@Command(name = "create", description = "Cria um perfil vazio.", mixinStandardHelpOptions = true)
public class CreateProfileCommand implements Callable<Integer> {

    @Parameters(index = "0", paramLabel = "<sub>", description = "Sempre 'profile'.")
    String sub;

    @Parameters(index = "1", paramLabel = "<nome>", description = "Nome do perfil.")
    String name;

    @picocli.CommandLine.Option(names = "--state-file", hidden = true) String stateFileOverride;

    public CreateProfileCommand() {}

    CreateProfileCommand(String sub, String name, String stateFileOverride) {
        this.sub = sub;
        this.name = name;
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
        if (!"profile".equals(sub)) {
            System.err.println("Uso: siteblock create profile <nome>");
            return 2;
        }
        ProfileStore store = new ProfileStore(CliSupport.resolveProfilesFile(stateFileOverride));
        boolean[] jaExiste = {false};
        try {
            store.inTransaction(tx -> {
                Map<String, Profile> profiles = tx.load();
                if (profiles.containsKey(valid)) {
                    jaExiste[0] = true;
                } else {
                    profiles.put(valid, new Profile(Instant.now(CliSupport.clock()), Instant.now(CliSupport.clock()), new TreeMap<>()));
                    tx.save(profiles);
                }
                return null;
            });
        } catch (IOException e) {
            System.err.println("Erro ao criar perfil: " + e.getMessage());
            return 1;
        }
        if (jaExiste[0]) {
            System.out.println("Perfil '" + valid + "' já existe.");
            return 0;
        }
        System.out.println("Perfil '" + valid + "' criado (vazio).");
        return 0;
    }
}
