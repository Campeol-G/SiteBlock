package com.sitelock;

import com.sitelock.cli.BlockCommand;
import com.sitelock.cli.DnsCommand;
import com.sitelock.cli.ResetCommand;
import com.sitelock.cli.StatusCommand;
import com.sitelock.cli.UnblockCommand;
import picocli.CommandLine;
import picocli.CommandLine.Command;

import java.util.concurrent.Callable;

/** Ponto de entrada do SiteBlock. */
@Command(name = "siteblock", description = "Bloqueia sites localmente via arquivo hosts do sistema.", mixinStandardHelpOptions = true, version = "SiteBlock 1.0.0", subcommands = {
    BlockCommand.class, UnblockCommand.class, StatusCommand.class, ResetCommand.class, DnsCommand.class })
public class SiteLockApplication implements Callable<Integer> {

  @Override
  public Integer call() {
    CommandLine.usage(this, System.out);
    return 2;
  }

  public static void main(String[] args) {
    int exit = new CommandLine(new SiteLockApplication()).execute(args);
    System.exit(exit);
  }
}
