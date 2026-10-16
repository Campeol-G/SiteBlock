package com.sitelock.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

import java.util.concurrent.Callable;

/**
 * siteblock daemon ... — o daemon de expiração.
 *
 * <p>É o que faz um bloqueio temporário vencer sozinho, sem ninguém abrir o
 * terminal. Ele só remove; bloquear continua sendo coisa do {@code block} e do
 * {@code profile apply}.
 */
@Command(name = "daemon",
        description = "Daemon que libera sozinho os bloqueios cujo prazo vence.",
        mixinStandardHelpOptions = true,
        subcommands = {
                DaemonStartCommand.class,
                DaemonStopCommand.class,
                DaemonStatusCommand.class,
                DaemonRunCommand.class})
public class DaemonCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        CommandLine.usage(this, System.out);
        return 2;
    }
}