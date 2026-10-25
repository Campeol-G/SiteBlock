package com.sitelock.cli;

import java.util.concurrent.TimeUnit;

/**
 * Test utilities for child processes.
 *
 * <p>NOTE: {@code destroyForcibly()} is asynchronous. A test that exits
 * with the process still alive leaves the DNS server port occupied for a
 * moment — and the next execution fails with "address already in use".
 */
final class TestProcesses {

    private TestProcesses() {
    }

    /** Kills the process and waits for it to die. */
    static void kill(ProcessHandle handle) {
        if (handle == null) {
            return;
        }
        handle.destroyForcibly();
        try {
            handle.onExit().get(5, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // the process was already signaled; nothing more to do here
        }
    }

    /** Kills the process and waits for it to die. */
    static void kill(Process process) {
        if (process == null) {
            return;
        }
        process.destroyForcibly();
        try {
            process.waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Kills the process of a PID, if it exists. */
    static void killPid(long pid) {
        if (pid <= 0) {
            return;
        }
        ProcessHandle.of(pid).ifPresent(TestProcesses::kill);
    }
}
