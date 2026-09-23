package com.sitelock;

import com.sitelock.cli.BlockCommand;
import com.sitelock.cli.ResetCommand;
import com.sitelock.cli.StatusCommand;
import com.sitelock.cli.UnblockCommand;
import picocli.CommandLine;
import picocli.CommandLine.Command;

import java.util.concurrent.Callable;

/**
 * Ponto de entrada do SiteLock (Etapa 1: bloqueio local via /etc/hosts).
 *
 * <p>Arquitetura preparada para a Etapa 2 (servidor DNS): o bloqueio real está
 * atrás da interface {@code com.sitelock.blocker.SiteBlocker}; o CLI e o
 * {@code com.sitelock.core.BlockService} dependem da abstração, de modo que um
 * futuro {@code DnsBlocker} possa ser composto sem alterar os comandos.
 */
@Command(name = "sitelock",
        description = "Bloqueia sites localmente via /etc/hosts.",
        mixinStandardHelpOptions = true,
        version = "SiteLock 1.0.0",
        subcommands = {BlockCommand.class, UnblockCommand.class, StatusCommand.class, ResetCommand.class})
public class SiteLockApplication implements Callable<Integer> {

    @Override
    public Integer call() {
        // Sem subcomando: mostra ajuda (código 2 = uso).
        CommandLine.usage(this, System.out);
        return 2;
    }

    public static void main(String[] args) {
        int exit = new CommandLine(new SiteLockApplication()).execute(args);
        System.exit(exit);
    }
}
