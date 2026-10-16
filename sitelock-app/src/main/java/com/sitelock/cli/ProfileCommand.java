package com.sitelock.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

import java.util.concurrent.Callable;

/**
 * siteblock profile ... — conjuntos de domínios salvos com nome.
 *
 * <p>Montar o mesmo conjunto toda vez é tedioso. Um perfil guarda a receita
 * (domínio + duração) uma vez; {@code apply} recarrega sem nunca encurtar o
 * que já está mais forte.
 */
@Command(name = "profile",
        description = "Conjuntos de dominios salvos com nome.",
        mixinStandardHelpOptions = true,
        subcommands = {
                ProfileSaveCommand.class,
                ProfileListCommand.class,
                ProfileShowCommand.class,
                ProfileDeleteCommand.class,
                ProfileApplyCommand.class,
                ProfileUnblockCommand.class})
public class ProfileCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        CommandLine.usage(this, System.out);
        return 2;
    }
}