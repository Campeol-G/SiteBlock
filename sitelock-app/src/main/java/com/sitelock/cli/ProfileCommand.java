package com.sitelock.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.util.concurrent.Callable;

/**
 * siteblock profile ... — conjuntos de domínios salvos com nome.
 *
 * <p>Um perfil guarda a receita (domínio + duração) uma vez; o {@code apply} recarrega
 * sem nunca encurtar o que já está mais forte. O nome vem antes do subcomando
 * ({@code profile trabalho add tiktok.com}): primeiro de qual conjunto, depois o que fazer.
 */
@Command(name = "profile",
        description = "Conjuntos de dominios salvos com nome.",
        mixinStandardHelpOptions = true,
        subcommands = {
                ProfileListCommand.class,
                ProfileShowCommand.class,
                ProfileApplyCommand.class,
                ProfileUnblockCommand.class,
                ProfileAddCommand.class,
                ProfileRemoveCommand.class})
public class ProfileCommand implements Callable<Integer> {

    // Nome do perfil, para os subcomandos que editam a receita ("add" e "remove").
    // Opcional porque nem todo subcomando usa: "profile list" não precisa de nome, e
    // quem exige nome avisa quando ele falta. Os subcomandos antigos têm nome próprio.
    @Parameters(index = "0", paramLabel = "<nome>", arity = "0..1",
            description = "Nome do perfil (usado por 'add' e 'remove').")
    String name;

    @Override
    public Integer call() {
        if (name != null && !name.isBlank()) {
            System.err.println("Informe o que fazer com o perfil '" + name + "':");
            System.err.println("  siteblock profile " + name + " show");
            System.err.println("  siteblock profile " + name + " apply");
            System.err.println("  siteblock profile " + name + " unblock [--force]");
            System.err.println("  siteblock profile " + name + " add <dominio...> [--for <duracao>]");
            System.err.println("  siteblock profile " + name + " remove <dominio...>");
            return 2;
        }
        CommandLine.usage(this, System.out);
        return 2;
    }
}
