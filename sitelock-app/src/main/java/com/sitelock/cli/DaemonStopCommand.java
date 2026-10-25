package com.sitelock.cli;

import com.sitelock.core.ExpirationDaemonState;
import com.sitelock.core.SiteLockPaths;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * siteblock daemon stop — stops the daemon.
 *
 * <p>The final sweep belongs to the child, not the shutdown hook: it is the one that knows what
 * has already expired. The parent only sends the signal and waits.
 */
@Command(name = "stop",
        description = "Stops the expiration daemon.",
        mixinStandardHelpOptions = true)
public class DaemonStopCommand implements Callable<Integer> {

    @Option(names = "--state-file", hidden = true) String stateFileOverride;

    public DaemonStopCommand() {
    }

    DaemonStopCommand(String stateFileOverride) {
        this.stateFileOverride = stateFileOverride;
    }

    @Override
    public Integer call() {
        java.nio.file.Path stateDir = CliSupport.stateDirFor(stateFileOverride);
        Optional<ExpirationDaemonState> state;
        try {
            state = ExpirationDaemonState.read(stateDir);
        } catch (IOException e) {
            System.err.println("Error reading the daemon state: " + e.getMessage());
            return 1;
        }
        if (state.isEmpty() || !ExpirationDaemonState.isAlive(state.get())) {
            System.err.println("No daemon is running.");
            return 1;
        }

        long pid = state.get().getPid();
        Optional<ProcessHandle> handle = ProcessHandle.of(pid);
        if (handle.isEmpty()) {
            System.err.println("No daemon is running (pid " + pid + " does not exist).");
            return 1;
        }

        try {
            stopProcess(handle.get());
        } catch (SecurityException e) {
            return noPermissionToStop(pid);
        }
        for (int i = 0; i < 50; i++) {
            if (!handle.get().isAlive()) {
                System.out.println("Expiration daemon stopped (pid " + pid + ").");
                return 0;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        try {
            handle.get().destroyForcibly();
        } catch (SecurityException e) {
            return noPermissionToStop(pid);
        }
        System.out.println("Expiration daemon stopped by force (pid " + pid
                + "); it did not respond to the exit request.");
        return 0;
    }

    // WHY: the daemon runs as root and the command runs as the user: the OS refuses
    // the signal. Without this message, picocli prints the exception stack.
    private int noPermissionToStop(long pid) {
        System.err.println("Error: no permission to stop pid " + pid
                + " (daemon started elevated?). " + CliSupport.elevationHint("daemon stop"));
        return 1;
    }

    // WHY: extracted from call so the test can simulate the OS refusal — the
    // real SecurityException only happens with the daemon running as root.
    void stopProcess(ProcessHandle handle) {
        handle.destroy();
    }
}