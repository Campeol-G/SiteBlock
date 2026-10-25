package com.sitelock.cli;

import com.sitelock.core.BlockService;
import com.sitelock.core.CorruptedStateException;
import com.sitelock.core.ExpirationDaemonLock;
import com.sitelock.core.ExpirationDaemonState;
import com.sitelock.core.SiteLockPaths;
import com.sitelock.core.SweeperSchedule;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

// NOTE: the daemon only removes — BlockService.sweepExpired() is the only thing
// it calls, plus the cache flush. If someone puts a block in here,
// a wrong profile apply starts looking like a valid block.

// NOTE: whoever writes daemon.json is this process, and not the daemon
// start parent — under autostart there is no parent. If it depended on the parent, status would say
// "stopped" and stop would have nothing to kill.

/**
 * siteblock daemon __run-daemon — the expiration loop. Internal command
 * (hidden); do not call it directly.
 */
@Command(name = "__run-daemon", hidden = true,
        description = "Runs the expiration loop in the foreground (internal use by 'daemon start').")
public class DaemonRunCommand implements Callable<Integer> {

    @Option(names = "--interval", defaultValue = "60",
            description = "Sleep ceiling between sweeps, in seconds.")
    int intervalSeconds;

    @Option(names = "--once", hidden = true,
            description = "Does one sweep and exits. Debugging and tests.")
    boolean once;

    @Option(names = "--state-file", hidden = true) String stateFileOverride;

    // Serializes the loop's sweep with the shutdown hook's final sweep.
    private final Object sweepMonitor = new Object();

    public DaemonRunCommand() {
    }

    @Override
    public Integer call() {
        Path stateDir = CliSupport.stateDirFor(stateFileOverride);
        Duration maxInterval = Duration.ofSeconds(Math.max(1, intervalSeconds));
        // WHY: the idle interval is THE SAME as the ceiling, and never 5 minutes — a 5 min sleep
        // leaves a 1-minute block stuck for up to 5 minutes after
        // it expires, which is the defect the daemon exists to eliminate.
        Duration idleInterval = maxInterval;

        if (once) {
            // Debug mode: no record, no lock and no shutdown hook. A
            // --once stopped by mistake must not leave state behind nor
            // take the lock of a daemon that is actually running.
            return sweepOnce(CliSupport.newService(null, stateFileOverride));
        }

        final ExpirationDaemonLock lock;
        try {
            lock = ExpirationDaemonLock.acquire(stateDir);
        } catch (IOException e) {
            System.err.println("[sitelock] " + e.getMessage());
            return 1;
        }

        final AtomicBoolean stopping = new AtomicBoolean(false);
        final CountDownLatch done = new CountDownLatch(1);
        // WHY: the final sweep has to RUN ONCE and neither side can finish
        // before it. With a simple CAS, if the main won the race the hook would return right away and the JVM
        // would exit in the middle of the
        // sweep; the FutureTask makes everyone wait for the same result.
        final FutureTask<Integer> finalSweep = new FutureTask<>(() -> {
            synchronized (sweepMonitor) {
                return sweepOnce(CliSupport.newService(null, stateFileOverride));
            }
        });
        final AtomicBoolean sweepTriggered = new AtomicBoolean(false);
        final Runnable triggerSweep = () -> {
            if (sweepTriggered.compareAndSet(false, true)) {
                finalSweep.run();
            }
        };
        try {
            ExpirationDaemonState.write(stateDir, new ExpirationDaemonState(
                    ProcessHandle.current().pid(), Instant.now(Clock.systemUTC()),
                    stateDir.toString(), null));

            Thread hook = new Thread(() -> {
                stopping.set(true);
                done.countDown();
                // The final sweep runs HERE, not only on the main: on SIGTERM the JVM
                // exits when the hooks finish, and the main may never be
                // scheduled before that.
                triggerSweep.run();
                awaitFinalSweep(finalSweep);
            }, "sitelock-sweeper-shutdown");
            Runtime.getRuntime().addShutdownHook(hook);

            System.out.println("[sitelock] expiration daemon up (pid "
                    + ProcessHandle.current().pid() + ", ceiling of " + intervalSeconds + "s).");
            System.out.println("[sitelock] state: " + stateDir);
            System.out.flush();

            while (!stopping.get()) {
                BlockService.SweepResult sweep = sweep();
                if (sweep == null) {
                    if (!sleep(done, (int) idleInterval.toSeconds())) {
                        break;
                    }
                    continue;
                }
                long next = sweep.skippedDueToPermission()
                        ? idleInterval.toSeconds()
                        : SweeperSchedule.nextSleep(sweep.state(), Instant.now(Clock.systemUTC()),
                                maxInterval, idleInterval).toSeconds();
                if (!sleep(done, next)) {
                    break;
                }
            }

            // Nobody leaves here without the final sweep completed — including the
            // hook, which is what the JVM waits for on SIGTERM.
            triggerSweep.run();
            return awaitFinalSweep(finalSweep);
        } catch (IOException e) {
            System.err.println("[sitelock] fatal daemon failure: " + e.getMessage());
            return 1;
        } finally {
            try {
                ExpirationDaemonState.delete(stateDir);
            } catch (IOException ignored) {
            }
            try {
                lock.close();
            } catch (IOException ignored) {
            }
        }
    }

    // A failure here does NOT kill the daemon: it records and returns null to sleep the
    // idle interval and try again.

    // NOTE: CorruptedStateException is the likely case (the user edited the
    // JSON by hand) and it must not become a daemon that does not come back — worse than
    // not cleaning, because the user does not even get warned that warnings stopped.
    private BlockService.SweepResult sweep() {
        try {
            synchronized (sweepMonitor) {
                BlockService.SweepResult sweep =
                        CliSupport.newService(null, stateFileOverride).sweepExpired();
                if (!sweep.removed().isEmpty()) {
                    for (String domain : sweep.removed()) {
                        System.out.println("[sitelock] expired and released: " + domain);
                    }
                    try {
                        ExpirationDaemonState.updateLastSweep(stateDir(), Instant.now(Clock.systemUTC()));
                        CliSupport.flushDnsBestEffort();
                    } catch (IOException e) {
                        System.err.println("[sitelock] failed to record the sweep: " + e.getMessage());
                    }
                    System.out.flush();
                }
                return sweep;
            }
        } catch (CorruptedStateException e) {
            System.err.println("[sitelock] corrupted state (" + e.getMessage()
                    + "); trying again on next turn.");
            System.err.flush();
            return null;
        } catch (IOException e) {
            System.err.println("[sitelock] sweep failed: " + e.getMessage() + "; continuing.");
            System.err.flush();
            return null;
        }
    }

    private Path stateDir() {
        return CliSupport.stateDirFor(stateFileOverride);
    }

    // Returns false when the loop should finish.
    private boolean sleep(CountDownLatch done, long seconds) {
        try {
            return !done.await(Math.max(1, seconds), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // Waits for the final sweep to finish, whether it came from the main or the hook. The hook
    // has to wait even when it loses the race: if it returned before, the
    // JVM would kill the process in the middle of the sweep the main is running. No
    // timeout on purpose — the price of exiting halfway is higher.
    private static int awaitFinalSweep(FutureTask<Integer> finalSweep) {
        try {
            return finalSweep.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 1;
        } catch (ExecutionException e) {
            System.err.println("[sitelock] final sweep failed: " + e.getMessage());
            return 1;
        }
    }

    private int sweepOnce(BlockService service) {
        try {
            BlockService.SweepResult sweep = service.sweepExpired();
            for (String domain : sweep.removed()) {
                System.out.println("[sitelock] expired and released: " + domain);
            }
            if (!sweep.removed().isEmpty()) {
                CliSupport.flushDnsBestEffort();
            }
            System.out.flush();
            return 0;
        } catch (CorruptedStateException e) {
            System.err.println("[sitelock] corrupted state: " + e.getMessage());
            System.err.println("[sitelock] Nothing was changed. Delete the file "
                    + service.getStore().getStateFile() + " or run: siteblock reset --yes");
            return 1;
        } catch (IOException e) {
            System.err.println("[sitelock] sweep failed: " + e.getMessage());
            return 1;
        }
    }

    /** Alternative entry point for direct testing. */
    public static int run(int intervalSeconds, boolean once, String stateFileOverride) {
        DaemonRunCommand cmd = new DaemonRunCommand();
        cmd.intervalSeconds = intervalSeconds;
        cmd.once = once;
        cmd.stateFileOverride = stateFileOverride;
        return cmd.call();
    }
}