package com.sitelock.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine;

import java.util.concurrent.Callable;

/**
 * siteblock dns ... — local DNS server to block domains on every device on the
 * network (phones, laptops) that points its DNS at this machine.
 */
@Command(name = "dns",
        description = "Local DNS server: blocks domains on every device on the network.",
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
