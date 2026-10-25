package com.sitelock;

import com.sitelock.cli.BlockCommand;
import com.sitelock.cli.DaemonCommand;
import com.sitelock.cli.DnsCommand;
import com.sitelock.cli.CreateProfileCommand;
import com.sitelock.cli.ProfileCommand;
import com.sitelock.cli.ResetCommand;
import com.sitelock.cli.SaveProfileCommand;
import com.sitelock.cli.StatusCommand;
import com.sitelock.cli.DeleteProfileCommand;
import com.sitelock.cli.UninstallCommand;
import com.sitelock.cli.UnblockCommand;
import picocli.CommandLine;
import picocli.CommandLine.Command;

import java.util.concurrent.Callable;

/** SiteBlock entry point. */
@Command(name = "siteblock", description = "Blocks websites locally through the system hosts file.", mixinStandardHelpOptions = true, version = "SiteBlock 1.0.0", subcommands = {
    BlockCommand.class, UnblockCommand.class, UninstallCommand.class, StatusCommand.class, ResetCommand.class, DnsCommand.class,
    ProfileCommand.class, DaemonCommand.class,
    CreateProfileCommand.class, SaveProfileCommand.class, DeleteProfileCommand.class })
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
