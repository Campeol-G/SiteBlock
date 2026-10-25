package com.sitelock.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

import java.util.concurrent.Callable;

/**
 * siteblock daemon ... — the expiration daemon.
 *
 * <p>It is what makes a temporary block expire on its own, without anyone opening the
 * terminal. It only removes; blocking stays the job of {@code block} and of
 * {@code profile apply}.
 */
@Command(name = "daemon",
        description = "Daemon that releases blocks on its own when their deadline expires.",
        mixinStandardHelpOptions = true,
        subcommands = {
                DaemonStartCommand.class,
                DaemonStopCommand.class,
                DaemonStatusCommand.class,
                DaemonEnableCommand.class,
                DaemonDisableCommand.class,
                DaemonRunCommand.class})
public class DaemonCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        CommandLine.usage(this, System.out);
        return 2;
    }
}