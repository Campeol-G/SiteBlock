package com.sitelock.cli;

import com.sitelock.core.Profile;
import com.sitelock.core.ProfileStore;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.Callable;

/** siteblock profile list — nomes e tamanho de cada perfil salvo. */
@Command(name = "list",
        description = "Lista os perfis salvos.",
        mixinStandardHelpOptions = true)
public class ProfileListCommand implements Callable<Integer> {

    @Option(names = "--state-file", hidden = true) String stateFileOverride;

    public ProfileListCommand() {
    }

    ProfileListCommand(String stateFileOverride) {
        this.stateFileOverride = stateFileOverride;
    }

    @Override
    public Integer call() {
        final Map<String, Profile> profiles;
        try {
            profiles = new ProfileStore(CliSupport.resolveProfilesFile(stateFileOverride)).load();
        } catch (IOException e) {
            System.err.println("Erro ao ler perfis: " + e.getMessage());
            return 1;
        }

        if (profiles.isEmpty()) {
            System.out.println("Nenhum perfil salvo. Crie um com: siteblock save profile <nome>");
            return 0;
        }

        System.out.println("Perfis salvos (" + profiles.size() + "):");
        for (Map.Entry<String, Profile> e : profiles.entrySet()) {
            System.out.println("  " + e.getKey() + " — " + e.getValue().size() + " domínio(s)");
        }
        return 0;
    }
}