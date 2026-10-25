package com.sitelock.cli;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code daemon start} Javadoc must describe what the code does.
 *
 * <p>The daemon identity check is no longer only "the PID is alive": the record
 * stores the process start instant, and that is what proves the live PID is
 * still ours. A Javadoc that says otherwise is not documentation: it is a lie,
 * and the next person to touch the file trusts it.
 */
class DaemonStartCommandDocTest {

    @Test
    void orphanRecordJavadocDescribesTheCurrentCheck() throws Exception {
        String source = Files.readString(commandSource(), StandardCharsets.UTF_8);
        String javadoc = javadocFor(source, "discardOrphanRecord");

        assertFalse(javadoc.contains("the check is only"),
                "the check is not only the PID: " + javadoc);
        assertTrue(javadoc.contains("start instant"),
                "the Javadoc must mention the process start instant: " + javadoc);
        assertTrue(javadoc.contains("PID"),
                "the Javadoc must still mention the PID: " + javadoc);
    }

    // The command source file, walking up the tree until it is found.
    private static Path commandSource() {
        Path relative = Path.of("src/main/java/com/sitelock/cli/DaemonStartCommand.java");
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve(relative);
            if (Files.exists(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("DaemonStartCommand source not found");
    }

    // The Javadoc block that precedes the method.
    private static String javadocFor(String source, String method) {
        int declaration = source.lastIndexOf(method);
        int start = source.lastIndexOf("/**", declaration);
        int end = source.indexOf("*/", start);
        return source.substring(start, end + 2);
    }
}
