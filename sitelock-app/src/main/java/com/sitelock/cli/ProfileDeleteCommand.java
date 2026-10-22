package com.sitelock.cli;

import com.sitelock.core.Profile;
import com.sitelock.core.ProfileNames;
import com.sitelock.core.ProfileStore;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.util.concurrent.Callable;

/**
 * siteblock profile delete <nome>.
 *
 * <p>A confirmação é digitada, e não um {@code S/N}: apagar uma receita é o tipo de erro que
 * ninguém desfaz. Digitar outra coisa cancela com o código 3 — o mesmo do {@code unblock}
 * recusado, para que scripts distingam "não fiz" de "erro".
 */
@Command(name = "delete",
        description = "Apaga um perfil salvo.",
        mixinStandardHelpOptions = true)
public class ProfileDeleteCommand implements Callable<Integer> {

    @Parameters(index = "0", paramLabel = "<nome>", description = "Nome do perfil.")
    String name;

    @Option(names = "--state-file", hidden = true) String stateFileOverride;

    // Resposta injetada pelo teste; null = lê do stdin.
    String answer;

    public ProfileDeleteCommand() {
    }

    ProfileDeleteCommand(String name, String answer, String stateFileOverride) {
        this.name = name;
        this.answer = answer;
        this.stateFileOverride = stateFileOverride;
    }

    @Override
    public Integer call() {
        final String valid;
        try {
            valid = ProfileNames.require(name);
        } catch (IllegalArgumentException e) {
            System.err.println("Erro: " + e.getMessage());
            return 2;
        }

        ProfileStore store = new ProfileStore(CliSupport.resolveProfilesFile(stateFileOverride));
        final Profile profile;
        try {
            profile = store.load().get(valid);
        } catch (IOException e) {
            System.err.println("Erro ao ler perfis: " + e.getMessage());
            return 1;
        }
        if (profile == null) {
            System.err.println("Perfil '" + valid + "' não existe.");
            return 1;
        }

        System.out.println("Apagar o perfil '" + valid + "' (" + profile.size() + " domínio(s))?");
        System.out.print("Para confirmar, digite SIM e pressione Enter: ");
        System.out.flush();

        String resposta = answer != null ? answer : CliSupport.readLine();
        if (resposta == null || !resposta.trim().equalsIgnoreCase("SIM")) {
            System.out.println("Apagamento CANCELADO. O perfil continua existindo.");
            return 3;
        }

        try {
            store.delete(valid);
        } catch (IOException e) {
            System.err.println("Erro ao apagar o perfil: " + e.getMessage());
            return 1;
        }
        System.out.println("Perfil '" + valid + "' apagado.");
        return 0;
    }
}