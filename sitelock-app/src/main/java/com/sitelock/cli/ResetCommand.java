package com.sitelock.cli;

import com.sitelock.core.BlockStore;
import com.sitelock.core.SiteLockPaths;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * siteblock reset --yes — deletes blocks.json. Does not change the hosts file.
 */
@Command(name = "reset", description = "Deletes the state (blocks.json), recreating it empty. Does not change the hosts file.",
        mixinStandardHelpOptions = true)
public class ResetCommand implements Callable<Integer> {

    @Option(names = {"-y", "--yes"}, description = "Confirms the operation without asking.")
    boolean yes;

    @Option(names = "--state-file", hidden = true) String stateFileOverride;

    public ResetCommand() {
    }

    ResetCommand(boolean yes, String stateFileOverride) {
        this.yes = yes;
        this.stateFileOverride = stateFileOverride;
    }

    @Override
    public Integer call() {
        if (!yes) {
            System.err.println("This will delete ALL block records from the state (blocks.json).");
            System.err.println("The hosts file (" + SiteLockPaths.getHostsPath() + ") will NOT be changed by this command.");
            System.err.println("Run again with --yes to confirm: siteblock reset --yes");
            return 2;
        }
        Path stateFile = CliSupport.resolveStateFile(stateFileOverride);
        try {
            new BlockStore(stateFile).clear();
        } catch (Exception e) {
            System.err.println("Error recreating the state: " + e.getMessage());
            return 1;
        }
        System.out.println("State recreated empty at " + stateFile + ".");
        System.out.println("Reminder: the hosts file was not changed; use 'unblock' to clear any remaining blocks.");
        return 0;
    }
}
