package com.sitelock.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A verbose external command must not fill the pipe before waitFor.
 *
 * <p>The two executors that spawn processes from the outside drain the output in parallel
 * (or discard it, when the text is not used). The scenario reproduces a command that
 * writes more than the pipe's default buffer.
 */
@EnabledOnOs({OS.LINUX, OS.MAC})
class ExternalCommandDrainTest {

    private static List<String> verboseCommand() {
        return List.of("sh", "-c", "yes x | head -c 200000");
    }

    @Test
    void flusherDrainsOutputBeforeWaiting() throws Exception {
        assertEquals(0, DnsCacheFlusher.runWithProcessBuilder(verboseCommand()));
    }

    @Test
    void installerDiscardsOutputWithoutBlocking() throws Exception {
        assertEquals(0, AutostartInstaller.runWithProcessBuilder(verboseCommand()));
    }
}
