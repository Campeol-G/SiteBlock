package com.sitelock.cli;

import com.sitelock.core.BlockEntry;
import com.sitelock.core.BlockStore;
import com.sitelock.core.DurationParser;
import com.sitelock.core.OsType;
import com.sitelock.core.PermissionAdvisor;
import com.sitelock.core.SiteLockPaths;
import com.sitelock.dns.DnsDaemonLock;
import com.sitelock.dns.DnsDaemonState;
import com.sitelock.dns.DnsServer;
import com.sitelock.dns.NetworkUtils;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * siteblock dns start [--port 53] [--upstream 1.1.1.1] [--for 2h].
 *
 * <p>Starts the DNS server in the background and returns right away. The daemon reuses the current
 * blocked list: {@code block}/{@code unblock} in another terminal take effect on the next
 * query. With {@code --for}, {@code dns stop} before the deadline requires {@code --force}.
 */
@Command(name = "start",
        description = "Starts the local DNS server in the background.",
        mixinStandardHelpOptions = true)
public class DnsStartCommand implements Callable<Integer> {

    // The log is appended between runs; above this, the next start archives it.
    // 5 MiB keeps several starts and still opens fast in printLogTail.
    static final long LOG_ROTATE_BYTES = 5L * 1024 * 1024;
    static final String LISTENING_MARKER = "SITELOCK_DNS_LISTENING";

    @Option(names = "--port", paramLabel = "<port>", defaultValue = "53",
            description = "Local UDP port (default: 53; use 5300 to test without sudo).")
    int port;

    @Option(names = "--upstream", paramLabel = "<ip>", defaultValue = "1.1.1.1",
            description = "Public DNS to forward non-blocked queries (default: 1.1.1.1).")
    String upstream;

    @Option(names = "--for", paramLabel = "<duration>",
            description = "Stops automatically after the duration. E.g. 30m, 2h, 1d. Omitted = until 'dns stop'.")
    String forDuration;

    @Option(names = "--allow-from", paramLabel = "<list>",
            description = "Allowed origins beyond the local ones (IP or CIDR, separated by commas).")
    String allowFrom;

    @Option(names = "--state-file", hidden = true) String stateFileOverride;

    public DnsStartCommand() {
    }

    DnsStartCommand(int port, String upstream, String forDuration, String stateFileOverride) {
        this.port = port;
        this.upstream = upstream;
        this.forDuration = forDuration;
        this.stateFileOverride = stateFileOverride;
    }

    @Override
    public Integer call() {
        if (port < 1 || port > 65535) {
            System.err.println("Error: invalid port: " + port + " (use 1-65535).");
            return 2;
        }

        final Duration duration;
        try {
            duration = forDuration == null ? null : DurationParser.parse(forDuration);
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            return 2;
        }

        final InetAddress upstreamAddr;
        try {
            upstreamAddr = InetAddress.getByName(upstream);
        } catch (Exception e) {
            System.err.println("Error: invalid upstream '" + upstream + "': " + e.getMessage());
            return 2;
        }

        // NOTE: a privileged port (< 1024) requires elevation only on Unix. On
        // Windows the Administrator is not called "root"; the bind below fails and the
        // log shows why, without a false sudo warning before trying.
        if (requiresElevationForPort(port, OsType.detect()) && !isRoot()) {
            String retry = "dns start" + (forDuration != null ? " --for " + forDuration : "");
            System.err.println("Error: " + new PermissionAdvisor().privilegedPortHint(port, retry));
            return 1;
        }

        // Validate before forking: a typo in --allow-from must not
        // show up only after the daemon is already up.
        try {
            DnsServer.parseAllowFrom(allowFrom);
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            return 2;
        }

        Path stateDir = DnsDaemonState.stateDirFor(stateFileOverride);
        Path stateFile = stateFileOverride != null && !stateFileOverride.isBlank()
                ? Paths.get(stateFileOverride)
                : stateDir.resolve("blocks.json");

        // The lock covers reading the state AND bringing up the child. Without it, two
        // simultaneous `dns start`s read "stopped", start two daemons and the last
        // record erases the first one's. The OS releases the lock if the process dies.
        final DnsDaemonLock lock;
        try {
            lock = DnsDaemonLock.tryAcquire(stateDir).orElse(null);
        } catch (IOException e) {
            System.err.println("Error obtaining DNS startup lock: " + e.getMessage());
            return 1;
        }
        if (lock == null) {
            System.err.println("Error: another 'siteblock dns start' is already in progress. "
                    + "Wait for it to finish and try again.");
            return 1;
        }
        try (DnsDaemonLock ignored = lock) {
            return startLocked(stateDir, stateFile, duration, upstreamAddr);
        } catch (IOException e) {
            System.err.println("Error releasing DNS startup lock: " + e.getMessage());
            return 1;
        }
    }

    private int startLocked(Path stateDir, Path stateFile, Duration duration,
                            InetAddress upstreamAddr) {
        try {
            Optional<DnsDaemonState> existing = DnsDaemonState.read(stateDir);
            if (existing.isPresent() && DnsDaemonState.isAlive(existing.get())) {
                System.err.println("Error: DNS server is already running (pid "
                        + existing.get().getPid() + ", port " + existing.get().getPort() + ").");
                System.err.println("Use 'siteblock dns status' to see it or 'siteblock dns stop' to stop it.");
                return 1;
            }
            if (existing.isPresent()) {
                DnsDaemonState.delete(stateDir); // Dead PID: clear stale state.
            }
        } catch (IOException e) {
            System.err.println("Error reading the daemon state: " + e.getMessage());
            return 1;
        }

        List<String> cmd = buildChildCommand(upstreamAddr.getHostAddress());
        Path logFile = DnsDaemonState.logPath(stateDir);
        final long logOffset;
        try {
            logOffset = prepareLog(logFile);
        } catch (IOException e) {
            System.err.println("Error preparing the daemon log: " + e.getMessage());
            return 1;
        }
        final Process process;
        try {
            SiteLockPaths.createDirectories(stateDir);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
            pb.redirectErrorStream(true);
            // stdin: PIPE (default) with immediate EOF — the daemon never reads stdin,
            // and DISCARD is not valid for input (only output/error).
            process = pb.start();
            SiteLockPaths.chownToUserIfConfigured(logFile);
            try {
                process.getOutputStream().close();
            } catch (IOException ignored) {
            }
        } catch (IOException e) {
            System.err.println("Error starting the daemon: " + e.getMessage());
            return 1;
        }

        long pid = process.pid();
        Instant now = Instant.now(Clock.systemUTC());
        Instant expiresAt = duration == null ? null : now.plus(duration);
        try {
            // The parent sees the CHILD's start instant; recording it here closes the
            // window in which the record exists without identity and is trusted only on the PID.
            DnsDaemonState state = new DnsDaemonState(
                    pid, port, upstreamAddr.getHostAddress(), now, expiresAt);
            process.info().startInstant().ifPresent(state::setProcessStartedAt);
            DnsDaemonState.write(stateDir, state);
        } catch (IOException e) {
            System.err.println("Error recording the daemon (pid " + pid + "): " + e.getMessage());
            process.destroyForcibly();
            return 1;
        }

        // Gives the child until ~3s to bind; if it dies, shows the reason from the log.
        boolean alive = false;
        boolean listening = false;
        for (int i = 0; i < 6; i++) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (!ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
                alive = false;
                break;
            }
            alive = true;
            if (logShowsListening(logFile, logOffset)) {
                listening = true;
                break;
            }
        }
        if (!alive) {
            System.err.println("Error: the DNS server did not come up (pid " + pid + " exited).");
            printLogTail(logFile);
            try {
                DnsDaemonState.deleteIfOwned(stateDir, pid);
            } catch (IOException ignored) {
            }
            return 1;
        }
        // A live process is not the same as a listening server: the daemon may
        // be alive and never open the port (the watchdog would sleep and try again,
        // forever). Only the mark in the log proves it is answering.
        if (!listening) {
            System.err.println("Error: the DNS server did not come up: the process (pid " + pid
                    + ") is alive, but it did not announce that it is listening.");
            process.destroyForcibly();
            printLogTail(logFile);
            try {
                DnsDaemonState.deleteIfOwned(stateDir, pid);
            } catch (IOException ignored) {
            }
            return 1;
        }

        System.out.println("DNS server started (pid " + pid + ", port " + port
                + ", upstream " + upstreamAddr.getHostAddress() + ").");
        NetworkUtils.printStartupInfo(port, upstreamAddr.getHostAddress());
        System.out.println("Blocked domains now: " + countActive(stateFile)
                + " (changes via 'block'/'unblock' take effect on the next query, no restart needed).");
        if (expiresAt != null) {
            System.out.println("Automatic shutdown in "
                    + DurationParser.formatRemaining(duration)
                    + " (--for); 'dns stop' before that requires --force.");
        }
        System.out.println("WARNING: while devices point here, if this server "
                + "goes down they lose the internet entirely. To undo it, set the device's DNS back "
                + "to automatic/DHCP. Log at: " + logFile);
        return 0;
    }

    private List<String> buildChildCommand(String upstreamIp) {
        String javaHome = System.getProperty("java.home", "");
        Path javaBin = Paths.get(javaHome, "bin", "java");
        String javaCmd = Files.isExecutable(javaBin) ? javaBin.toString() : "java";
        String classpath = System.getProperty("java.class.path", ".");

        List<String> cmd = new ArrayList<>();
        cmd.add(javaCmd);
        // The child's log is read as UTF-8 by the tail and by the tests; without this
        // Windows writes in the native code page and reading breaks.
        cmd.add("-Dstdout.encoding=UTF-8");
        cmd.add("-Dstderr.encoding=UTF-8");
        String binProp = System.getProperty("siteblock.bin");
        if (binProp != null && !binProp.isBlank()) {
            cmd.add("-Dsiteblock.bin=" + binProp);
        }
        // The child has to see the SAME state as the parent: without these properties, a
        // daemon started as root without SUDO_USER (autostart) operates in /root/.sitelock
        // while `dns status` looks at ~/.sitelock — status says "STOPPED" with the
        // server up, and `dns stop` does not stop it.
        cmd.add("-Dsitelock.stateDir="
                + DnsDaemonState.stateDirFor(stateFileOverride).toAbsolutePath());
        String user = System.getProperty("sitelock.user");
        if (user == null || user.isBlank()) {
            user = SiteLockPaths.realUserName().orElse(null);
        }
        if (user != null && !user.isBlank()) {
            cmd.add("-Dsitelock.user=" + user);
        }
        cmd.add("-cp");
        cmd.add(classpath);
        cmd.add("com.sitelock.SiteLockApplication");
        cmd.add("dns");
        cmd.add("__run-daemon");
        cmd.add("--port");
        cmd.add(String.valueOf(port));
        cmd.add("--upstream");
        cmd.add(upstreamIp);
        if (forDuration != null) {
            cmd.add("--for");
            cmd.add(forDuration);
        }
        if (allowFrom != null && !allowFrom.isBlank()) {
            cmd.add("--allow-from");
            cmd.add(allowFrom);
        }
        if (stateFileOverride != null && !stateFileOverride.isBlank()) {
            cmd.add("--state-file");
            cmd.add(stateFileOverride);
        }
        return cmd;
    }

    private static boolean isRoot() {
        return "root".equals(System.getProperty("user.name"));
    }

    /**
     * A port below 1024 requires elevation only on Unix. On Windows, elevation is an
     * Administrator token, not the user name "root"; trying the bind and showing the
     * log is more useful than refusing before trying.
     */
    static boolean requiresElevationForPort(int port, OsType os) {
        return port > 0 && port < 1024 && os != null && !os.isWindows();
    }

    /**
     * Returns the offset from which the current start writes to the log.
     *
     * <p>Log above {@link #LOG_ROTATE_BYTES} becomes {@code .1} and the offset is
     * zero; that way the listening mark of an old run is not confused
     * with this one's.
     */
    static long prepareLog(Path logFile) throws IOException {
        if (!Files.exists(logFile)) {
            return 0;
        }
        long size = Files.size(logFile);
        if (size <= LOG_ROTATE_BYTES) {
            return size;
        }
        Path archived = logFile.resolveSibling(logFile.getFileName() + ".1");
        Files.move(logFile, archived, StandardCopyOption.REPLACE_EXISTING);
        return 0;
    }

    static boolean logShowsListening(Path logFile, long offset) {
        byte[] needle = LISTENING_MARKER.getBytes(StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(logFile, StandardOpenOption.READ)) {
            if (offset > channel.size()) {
                return false; // file swapped/truncated during startup
            }
            channel.position(offset);
            ByteBuffer buffer = ByteBuffer.allocate(8192);
            int matched = 0;
            while (channel.read(buffer) > 0) {
                buffer.flip();
                while (buffer.hasRemaining()) {
                    byte b = buffer.get();
                    if (b == needle[matched]) {
                        matched++;
                        if (matched == needle.length) {
                            return true;
                        }
                    } else {
                        matched = b == needle[0] ? 1 : 0;
                    }
                }
                buffer.clear();
            }
            return false;
        } catch (IOException e) {
            return false;
        }
    }

    private static void printLogTail(Path logFile) {
        try {
            List<String> lines = Files.readAllLines(logFile);
            int from = Math.max(0, lines.size() - 20);
            System.err.println("--- last lines of log (" + logFile + ") ---");
            lines.subList(from, lines.size()).forEach(l -> System.err.println(l));
        } catch (IOException e) {
            System.err.println("(could not read log: " + e.getMessage() + ")");
        }
    }

    private static String countActive(Path stateFile) {
        try {
            Map<String, BlockEntry> state = new BlockStore(stateFile).load();
            Instant now = Instant.now(Clock.systemUTC());
            long active = state.values().stream().filter(e -> !e.isExpired(now)).count();
            return String.valueOf(active);
        } catch (Exception e) {
            return "? (state unreadable: " + e.getMessage() + ")";
        }
    }
}
