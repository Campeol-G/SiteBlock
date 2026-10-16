package com.sitelock.cli;

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
import java.util.Map;
import java.util.concurrent.Callable;

/** siteblock profile show <nome> — o detalhe de um perfil, com as durações. */
@Command(name = "show",
        description = "Mostra o conteudo de um perfil.",
        mixinStandardHelpOptions = true)
public class ProfileShowCommand implements Callable<Integer> {

    @Parameters(index = "0", paramLabel = "<nome>", description = "Nome do perfil.")
    String name;

    @Option(names = "--state-file", hidden = true) String stateFileOverride;

    public ProfileShowCommand() {
    }

    ProfileShowCommand(String name, String stateFileOverride) {
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

        System.out.println("Perfil '" + valid + "' (" + profile.size() + " domínio(s)):");
        for (Map.Entry<String, ProfileEntry> e : profile.getEntries().entrySet()) {
            Long millis = e.getValue() == null ? null : e.getValue().getDurationMillis();
            String duracao = millis == null ? "permanente"
                    : DurationParser.formatRemaining(Duration.ofMillis(millis));
            System.out.println("  " + e.getKey() + " — " + duracao);
        }
        return 0;
    }
}