package com.sitelock.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine;

import java.util.concurrent.Callable;

/**
 * siteblock dns ... — servidor DNS local para bloquear domínios em todos os
 * dispositivos da rede (celulares, notebooks) que apontarem seu DNS para esta máquina.
 */
@Command(name = "dns",
        description = "Servidor DNS local: bloqueia dominios em todos os dispositivos da rede.",
        mixinStandardHelpOptions = true,
        subcommands = {
                DnsStartCommand.class,
                DnsStopCommand.class,
                DnsStatusCommand.class,
                DnsRunDaemonCommand.class})
public class DnsCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        CommandLine.usage(this, System.out);
        return 2;
    }
}
