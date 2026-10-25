package com.sitelock.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.util.concurrent.Callable;

/**
 * siteblock profile ... — named sets of domains.
 *
 * <p>A profile stores the recipe (domain + duration) once; {@code apply} reloads it
 * without ever shortening what is already stronger. The name comes before the subcommand
 * ({@code profile work add tiktok.com}): first which set, then what to do.
 */
@Command(name = "profile",
        description = "Named sets of saved domains.",
        mixinStandardHelpOptions = true,
        subcommands = {
                ProfileListCommand.class,
                ProfileShowCommand.class,
                ProfileApplyCommand.class,
                ProfileUnblockCommand.class,
                ProfileAddCommand.class,
                ProfileRemoveCommand.class})
public class ProfileCommand implements Callable<Integer> {

    // The profile name, for the subcommands that edit the recipe ("add" and "remove").
    // Optional because not every subcommand uses it: "profile list" does not need a name, and
    // whoever requires a name warns when it is missing. The older subcommands have their own name.
    @Parameters(index = "0", paramLabel = "<name>", arity = "0..1",
            description = "Profile name (used by 'add' and 'remove').")
    String name;

    @Override
    public Integer call() {
        if (name != null && !name.isBlank()) {
            System.err.println("Say what to do with profile '" + name + "':");
            System.err.println("  siteblock profile " + name + " show");
            System.err.println("  siteblock profile " + name + " apply");
            System.err.println("  siteblock profile " + name + " unblock [--force]");
            System.err.println("  siteblock profile " + name + " add <domain...> [--for <duration>]");
            System.err.println("  siteblock profile " + name + " remove <domain...>");
            return 2;
        }
        CommandLine.usage(this, System.out);
        return 2;
    }
}
