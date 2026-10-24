package com.sitelock.cli;

import com.sitelock.core.Profile;
import com.sitelock.core.ProfileNames;
import com.sitelock.core.ProfileStore;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.util.concurrent.Callable;

/** siteblock delete profile <nome> — apaga um perfil (confirma SIM). */
@Command(name = "delete", description = "Apaga um perfil salvo.", mixinStandardHelpOptions = true)
public class DeleteProfileCommand implements Callable<Integer> {

    @Parameters(index = "0", paramLabel = "<sub>", description = "Sempre 'profile'.")
    String sub;

    @Parameters(index = "1", paramLabel = "<nome>", description = "Nome do perfil.")
    String name;

    @picocli.CommandLine.Option(names = "--state-file", hidden = true) String stateFileOverride;

    // Resposta injetada pelo teste; null = lê do stdin.
    String answer;

    public DeleteProfileCommand() {}

    DeleteProfileCommand(String sub, String name, String answer, String stateFileOverride) {
        this.sub = sub; this.name = name; this.answer = answer; this.stateFileOverride = stateFileOverride;
    }

    @Override
    public Integer call() {
        if (!"profile".equals(sub)) {
            System.err.println("Uso: siteblock delete profile <nome>");
            return 2;
        }
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
