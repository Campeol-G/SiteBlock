package com.sitelock.cli;

import com.sitelock.dns.DnsDaemonState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@code dns start} can only announce success after seeing the "listening"
 * mark in the daemon log.
 *
 * <p>The child daemon is a real process, and the test uses a fake compiled
 * {@code main}: it stays alive, but never announces that it is listening —
 * exactly what happened when the watchdog swallowed the first failure. The old
 * {@code start} looked only at "the process is alive" and printed
 * "DNS server started".
 */
class DnsStartListenTest {

    @TempDir
    Path tmp;

    private static final int PORT = 15398;

    @Test
    void withoutTheListeningMarkStartDoesNotAnnounceSuccess() throws Exception {
        Path stub = compileSilentDaemon();
        String originalClasspath = System.getProperty("java.class.path");
        System.setProperty("java.class.path", stub + File.pathSeparator + originalClasspath);

        Path stateFile = tmp.resolve("blocks.json");
        Files.writeString(stateFile, "{}", StandardCharsets.UTF_8);
        Path state = tmp; // the daemon state lives next to --state-file
        // Mark from a previous run: without the offset, start would find
        // this line and announce success with the current silent child.
        Files.writeString(DnsDaemonState.logPath(state),
                "[old] " + DnsStartCommand.LISTENING_MARKER + "\n",
                StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream original = System.out;
        System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
        int exit;
        try {
            exit = new CommandLine(new DnsCommand()).execute(
                    "start",
                    "--port", String.valueOf(PORT),
                    "--state-file", stateFile.toString());
        } finally {
            System.setOut(original);
            System.setProperty("java.class.path", originalClasspath);
            stop(state);
        }

        assertEquals(1, exit,
                "without the listening mark, start must fail: " + output.toString(StandardCharsets.UTF_8));
        assertFalse(output.toString(StandardCharsets.UTF_8).contains("DNS server started"),
                "it must not announce that the server came up");
    }

    // Compiles a fake com.sitelock.SiteLockApplication: it stays alive
    // without ever writing the listening mark.
    private Path compileSilentDaemon() throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assumeTrue(compiler != null, "the test needs a JDK (javac)");

        Path sources = tmp.resolve("stub-source/com/sitelock");
        Files.createDirectories(sources);
        Path source = sources.resolve("SiteLockApplication.java");
        Files.writeString(source,
                "package com.sitelock;\n"
                        + "public class SiteLockApplication {\n"
                        + "    public static void main(String[] args) throws Exception {\n"
                        + "        Thread.sleep(600000);\n"
                        + "    }\n"
                        + "}\n", StandardCharsets.UTF_8);

        Path output = tmp.resolve("stub-classes");
        Files.createDirectories(output);
        int rc = compiler.run(null, null, null,
                "-d", output.toAbsolutePath().toString(), source.toAbsolutePath().toString());
        assertEquals(0, rc, "could not compile the fake daemon");
        return output;
    }

    // Takes down the daemon the test started (the PID is in the state record).
    private void stop(Path state) {
        try {
            Optional<DnsDaemonState> snapshot = DnsDaemonState.read(state);
            assertNotNull(snapshot);
            if (snapshot.isPresent() && snapshot.get().getPid() > 0) {
                ProcessHandle.of(snapshot.get().getPid()).ifPresent(TestProcesses::kill);
            }
        } catch (Exception ignored) {
            // the test only needs the process not to be left hanging
        }
    }
}
