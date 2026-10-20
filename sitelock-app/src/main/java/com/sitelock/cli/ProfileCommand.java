package com.sitelock.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.util.concurrent.Callable;

/**
 * siteblock profile ... — conjuntos de domínios salvos com nome.
 *
 * <p>Montar o mesmo conjunto toda vez é tedioso. Um perfil guarda a receita
 * (domínio + duração) uma vez; {@code apply} recarrega sem nunca encurtar o
 * que já está mais forte.
 *
 * <p><b>O nome do perfil aparece antes do subcomodo</b> — {@code profile
 * trabalho add tiktok.com} — porque é assim que a pessoa pensa: primeiro de
 * qual conjunto ela está falando, depois o que quer fazer com ele. O nome é
 * um posicional do comando {@code profile}, e os subcomandos que editam a
 * receita o lêem de lá.
 *
 * <p>Os subcomandos que já existiam não mudam de forma: {@code profile save
 * trabalho} continua funcionando, porque o nome deles é o posicional do
 * <b>próprio</b> subcomando. Só quem escreve {@code profile &lt;nome&gt; ...
 * passa pelo posicional do pai.
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
                ProfileUnblockCommand.class,
                ProfileAddCommand.class,
                ProfileRemoveCommand.class})
public class ProfileCommand implements Callable<Integer> {

    /**
     * Nome do perfil, para os subcomandos que editam a receita.
     *
     * <p>Opcional porque nem todo subcomando usa: {@code profile list} não
     * precisa de nome nenhum. Quem exige nome ({@code add}, {@code remove})
     * avisa quando ele falta.
     */
    @Parameters(index = "0", paramLabel = "<nome>", arity = "0..1",
            description = "Nome do perfil (usado por 'add' e 'remove').")
    String name;

    @Override
    public Integer call() {
        if (name != null && !name.isBlank()) {
            System.err.println("Informe o que fazer com o perfil '" + name + "':");
            System.err.println("  siteblock profile " + name + " add <dominio>");
            System.err.println("  siteblock profile " + name + " remove <dominio>");
            System.err.println("  siteblock profile show|apply|unblock " + name);
            return 2;
        }
        CommandLine.usage(this, System.out);
        return 2;
    }
}
