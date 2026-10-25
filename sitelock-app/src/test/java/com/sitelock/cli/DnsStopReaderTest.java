package com.sitelock.cli;

import com.sitelock.dns.DnsDaemonState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code dns stop} reads the confirmation from the keyboard without swallowing
 * the rest.
 *
 * <p>The old reader was a new {@code Scanner} per question: it read stdin into
 * a 1 KB buffer and consumed everything the person had typed. The next question
 * found nothing — the defect that already hit {@code unblock} with two domains,
 * where the second was cancelled even with "YES" typed twice.
 *
 * <p>The reader is a single one for the whole process: the line left over on the
 * keyboard remains available for whoever reads next.
 */
class DnsStopReaderTest {

    @TempDir
    Path tmp;

    @Test
    void confirmationDoesNotSwallowTheRestOfTheKeyboard() throws Exception {
        Process target = auxiliaryProcess();
        try {
            Path stateFile = tmp.resolve("state/blocks.json");
            Files.createDirectories(stateFile.getParent());
            DnsDaemonState.write(stateFile.getParent(), new DnsDaemonState(
                    target.pid(), 15399, "127.0.0.1", Instant.now(),
                    Instant.now().plusSeconds(3600)));

            InputStream original = System.in;
            System.setIn(new ByteArrayInputStream("YES\nREST\n".getBytes(StandardCharsets.UTF_8)));
            int exit;
            String rest;
            try {
                exit = new DnsStopCommand(true, stateFile.toString()).call();
                // The read happens with the virtual keyboard still in place: the
                // test process's stdin returns nothing, and reading from it blocks.
                rest = CliSupport.readLine();
            } finally {
                System.setIn(original);
            }

            assertEquals(0, exit, "the typed YES must count: the stop was confirmed");
            assertEquals("REST", rest,
                    "the next line must not have been swallowed by the confirmation reader");
        } finally {
            TestProcesses.kill(target);
            target.waitFor();
        }
    }

    // Any process, which stays alive until it is killed.
    private Process auxiliaryProcess() throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(Paths.get(System.getProperty("java.home"), "bin", "java").toString());
        cmd.add("-cp");
        cmd.add(System.getProperty("java.class.path"));
        cmd.add(Sleep.class.getName());
        return new ProcessBuilder(cmd).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
    }

    /** Stays alive until it receives the signal. */
    public static class Sleep {
        public static void main(String[] args) throws Exception {
            Thread.sleep(300_000);
        }
    }
}
