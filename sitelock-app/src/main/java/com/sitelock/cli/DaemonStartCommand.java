package com.sitelock.cli;

import com.sitelock.core.DaemonCommandLine;
import com.sitelock.core.ExpirationDaemonState;
import com.sitelock.core.SiteLockPaths;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

/**
 * siteblock daemon start [<interval>] — brings up the daemon in the background.
 *
 * <p>It is not required for SiteBlock to work; without it, a deadline is only honored
 * when you run another siteblock command. {@code daemon enable} is what
 * makes that happen on its own at every boot.
 */
@Command(name = "start",
        description = "Starts the expiration daemon in the background.",
        mixinStandardHelpOptions = true)
public class DaemonStartCommand implements Callable<Integer> {

    @Option(names = "--interval", defaultValue = "60", paramLabel = "<seconds>",
            description = "Sleep ceiling between sweeps (default 60).")
    String intervalSeconds;

    @Option(names = "--hosts-file", hidden = true) String hostsFileOverride;
    @Option(names = "--state-file", hidden = true) String stateFileOverride;

    public DaemonStartCommand() {
    }

    DaemonStartCommand(String intervalSeconds, String hostsFileOverride, String stateFileOverride) {
        this.intervalSeconds = intervalSeconds;
        this.hostsFileOverride = hostsFileOverride;
        this.stateFileOverride = stateFileOverride;
    }

    @Override
    public Integer call() {
        var service = CliSupport.newService(hostsFileOverride, stateFileOverride);
        if (!service.isHostsWritable()) {
            // Refuses BEFORE forking. A child that does not write the hosts file is a
            // daemon that comes up, sweeps, removes nothing, and nobody notices.
            System.err.println("Error: " + CliSupport.elevationHint("daemon start"));
            return 1;
        }

        Path stateDir = CliSupport.stateDirFor(stateFileOverride);
        Optional<ExpirationDaemonState> existing;
        try {
            existing = ExpirationDaemonState.read(stateDir);
        } catch (IOException e) {
            System.err.println("Error reading the daemon state: " + e.getMessage());
            return 1;
        }
        if (existing.isPresent() && ExpirationDaemonState.isAlive(existing.get())) {
            System.err.println("A daemon is already running (pid " + existing.get().getPid()
                    + "). Use 'siteblock daemon status' to see it, or 'daemon stop' to stop it.");
            return 1;
        }
        try {
            discardOrphanRecord(stateDir);
        } catch (IOException e) {
            System.err.println("Warning: could not clean up the daemon's old record: "
                    + e.getMessage());
        }

        List<String> cmd = buildChildCommand(stateDir, service.getHostsPath());
        Path logFile = ExpirationDaemonState.logPath(stateDir);
        final Process process;
        try {
            Files.createDirectories(stateDir);
            // The log belongs to the child but is born from the parent: rotating here is what
            // guarantees the tail shows the error of THIS run, and not of
            // the previous one.
            prepareLog(logFile);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
            pb.redirectErrorStream(true);
            process = pb.start();
            try {
                process.getOutputStream().close();
            } catch (IOException ignored) {
            }
        } catch (IOException e) {
            System.err.println("Error starting the daemon: " + e.getMessage());
            return 1;
        }

        long pid = process.pid();

        // The one who writes daemon.json is the CHILD. The parent only waits for it to appear and
        // confirms that the recorded PID belongs to the live process.
        boolean registered = false;
        for (int i = 0; i < 40; i++) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            Optional<ExpirationDaemonState> state;
            try {
                state = ExpirationDaemonState.read(stateDir);
            } catch (IOException e) {
                System.err.println("Error reading the daemon record: " + e.getMessage());
                return 1;
            }
            if (state.isPresent() && state.get().getPid() == pid) {
                registered = true;
                break;
            }
            if (!process.isAlive()) {
                break;
            }
        }

        if (!registered) {
            process.destroyForcibly();
            System.err.println("Error: the daemon did not come up (pid " + pid + " exited).");
            System.out.println("Last log lines:");
            printLogTail(logFile);
            return 1;
        }

        System.out.println("Expiration daemon started (pid " + pid
                + ", ceiling of " + intervalSeconds + "s).");
        System.out.println("Temporary blocks now expire on their own. Log at: " + logFile);
        String warning = userWritablePathWarning();
        if (warning != null) {
            System.out.println(warning);
        }
        return 0;
    }

    // WHY: the two path properties are passed on to the child — without them
    // start and enable would read different files. The line comes from the same source
    // (DaemonCommandLine): the JVM that runs the daemon is the same in both cases.

    // NOTE: when both lines were assembled by hand, the enable left the
    // class name after the -jar and the start left the properties: neither
    // invocation came up, and neither said why.
    List<String> buildChildCommand(Path stateDir, Path hostsPath) {
        String javaHome = System.getProperty("java.home", "");
        Path javaBin = Paths.get(javaHome, "bin", "java");
        String javaCmd = Files.isExecutable(javaBin) ? javaBin.toString() : "java";

        List<String> properties = new ArrayList<>();
        // The child's log is read as UTF-8 by the tail and by the tests; without this
        // Windows writes in the native code page and reading breaks.
        properties.add("-Dstdout.encoding=UTF-8");
        properties.add("-Dstderr.encoding=UTF-8");
        String binProp = System.getProperty("siteblock.bin");
        if (binProp != null && !binProp.isBlank()) {
            properties.add("-Dsiteblock.bin=" + binProp);
        }
        properties.add("-Dsitelock.stateDir=" + stateDir.toAbsolutePath());
        properties.add("-Dsitelock.hostsFile=" + hostsPath.toAbsolutePath());
        // The child inherits the parent's SUDO_USER, but what if the parent runs as root without
        // su (su, container)? Then nobody would know to whom to hand back ownership of the
        // files the daemon writes — and status would start asking for elevation.
        SiteLockPaths.realUserName().ifPresent(u -> properties.add("-Dsitelock.user=" + u));

        List<String> appArgs = new ArrayList<>(
                List.of("daemon", "__run-daemon", "--interval", intervalSeconds));
        if (stateFileOverride != null && !stateFileOverride.isBlank()) {
            appArgs.add("--state-file");
            appArgs.add(stateFileOverride);
        }

        return DaemonCommandLine.build(javaCmd, System.getProperty("siteblock.jar"),
                System.getProperty("java.class.path", "."), properties, appArgs);
    }

    // Ceiling for each generation of the daemon log.
    static final long LOG_MAX_BYTES = 64 * 1024;

    // WHY: without this the log only grows — the child's appendTo keeps what was
    // already there, and printLogTail would show the tail of an old run. The
    // previous one goes to daemon.log.1, capped at the ceiling, and daemon.log starts over.

    // Ownership goes back to the real user: the parent runs as root, and a log that only
    // root reads breaks the promise that daemon status works without elevation.
    static void prepareLog(Path logFile) throws IOException {
        Path previous = logFile.resolveSibling(logFile.getFileName() + ".1");
        if (Files.exists(logFile) && Files.size(logFile) > 0) {
            preservePreviousGeneration(logFile, previous);
        }
        Path parent = logFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        // TRUNCATE_EXISTING on the same inode: the file stays the same file
        // (with the same ownership and permissions) — only the content is zeroed.
        Files.writeString(logFile, "", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
        SiteLockPaths.chownToUserIfConfigured(logFile);
        SiteLockPaths.chownToUserIfConfigured(previous);
    }

    // Keeps only the tail of the previous log in the rotated file.
    private static void preservePreviousGeneration(Path source, Path destination) throws IOException {
        byte[] tail = readTail(source, LOG_MAX_BYTES);
        Files.write(destination, tail, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }

    // The last max bytes of the file, without the line cut in the middle.
    private static byte[] readTail(Path file, long max) throws IOException {
        try (SeekableByteChannel channel = Files.newByteChannel(file, StandardOpenOption.READ)) {
            long size = channel.size();
            long start = Math.max(0, size - max);
            channel.position(start);
            ByteBuffer buffer = ByteBuffer.allocate((int) (size - start));
            while (buffer.hasRemaining() && channel.read(buffer) > 0) {
            }
            byte[] tail = buffer.array();
            if (start == 0) {
                return tail; // the whole file fit: nothing was cut
            }
            // The tail starts in the middle of a line: that line goes out.
            int brk = firstLine(tail);
            return brk < 0 ? tail : Arrays.copyOfRange(tail, brk + 1, tail.length);
        }
    }

    // Index of the first line break, or -1.
    private static int firstLine(byte[] bytes) {
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == '\n') {
                return i;
            }
        }
        return -1;
    }

    // NOTE: an orphan record blocks daemon start forever (there is no --force)
    // and daemon stop would send a signal to an unrelated process. Returns true only
    // when there was an orphan and it was removed.
    /**
     * The identity check is not just "the PID is alive": the record stores the
     * process start instant, and that is what proves the live PID is still
     * ours — the OS recycles PID numbers. An old record, without the instant,
     * is still trusted on the PID.
     */
    static boolean discardOrphanRecord(Path stateDir) throws IOException {
        Optional<ExpirationDaemonState> existing = ExpirationDaemonState.read(stateDir);
        if (existing.isEmpty() || ExpirationDaemonState.isAlive(existing.get())) {
            return false;
        }
        ExpirationDaemonState.delete(stateDir);
        System.out.println("Old daemon record (pid " + existing.get().getPid()
                + ") discarded: the process is no longer running.");
        return true;
    }

    // daemon start runs the jar from where it is — normally the build's,
    // in a directory the user can swap. It is a warning, never a refusal: the target is
    // the distracted user, not the attacker.

    // The correction already exists and is a command: daemon enable installs a copy
    // of the jar in a root-owned directory and points the initialization there.
    private String userWritablePathWarning() {
        String jar = System.getProperty("siteblock.jar");
        if (jar == null || jar.isBlank()) {
            return null;
        }
        Path p;
        try {
            p = Paths.get(jar).toAbsolutePath();
        } catch (RuntimeException e) {
            return null;
        }
        return "Warning: the daemon runs as root and executes the jar at " + p
                + ", which you can swap. To prevent that, run: "
                + CliSupport.elevatedCommand("daemon enable");
    }

    private static void printLogTail(Path logFile) {
        try {
            List<String> lines = Files.readAllLines(logFile);
            for (int i = Math.max(0, lines.size() - 10); i < lines.size(); i++) {
                System.err.println("  " + lines.get(i));
            }
        } catch (IOException ignored) {
        }
    }

    // Waits for the process to finish; used by daemon stop.
    static boolean waitForExit(Process process, long seconds) {
        try {
            return process.waitFor(seconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}