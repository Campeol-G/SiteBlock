package com.sitelock.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Deleting a profile reads the confirmation from the keyboard without
 * swallowing the rest.
 *
 * <p>The reader used to be a new {@code BufferedReader} per call: it read stdin
 * in one block and kept the rest of the typed line in its own buffer, which died
 * with it. The next line from the keyboard disappeared.
 *
 * <p>The reader is a single one for the whole process: the line left over remains
 * available for whoever reads next.
 */
class ProfileDeleteReaderTest {

    @TempDir
    Path tmp;

    @Test
    void confirmationDoesNotSwallowTheRestOfTheKeyboard() throws Exception {
        Path hosts = tmp.resolve("hosts");
        Files.writeString(hosts, "127.0.0.1 localhost\n", StandardCharsets.UTF_8);
        Path stateFile = tmp.resolve("state/blocks.json");
        Files.createDirectories(stateFile.getParent());
        assertEquals(0, new BlockCommand(List.of("tiktok.com"), null,
                hosts.toString(), stateFile.toString()).call());
        assertEquals(0, new ProfileSaveCommand("work", false,
                hosts.toString(), stateFile.toString()).call());

        InputStream original = System.in;
        System.setIn(new ByteArrayInputStream("YES\nREST\n".getBytes(StandardCharsets.UTF_8)));
        int exit;
        String rest;
        try {
            exit = new ProfileDeleteCommand("work", null, stateFile.toString()).call();
            // The read happens with the virtual keyboard still in place: the
            // test process's stdin returns nothing, and reading from it blocks.
            rest = CliSupport.readLine();
        } finally {
            System.setIn(original);
        }

        assertEquals(0, exit, "the typed YES must count: the profile was deleted");
        assertEquals("REST", rest,
                "the next line must not have been swallowed by the confirmation reader");
    }
}
