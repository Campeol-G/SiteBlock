package com.sitelock.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The state directory created by an elevated process must not stay owned by
 * root only: the next {@code status} runs without sudo and needs to read it.
 */
@EnabledOnOs({OS.LINUX, OS.MAC})
class SiteLockPathsTest {

    @TempDir
    Path tmp;

    @Test
    void createDirectoriesCreatesAndAppliesTheConfiguredOwner() throws Exception {
        String previous = System.getProperty("sitelock.user");
        String user = System.getProperty("user.name");
        try {
            System.setProperty("sitelock.user", user);
            Path dir = tmp.resolve("a/b/.sitelock");

            SiteLockPaths.createDirectories(dir);

            assertTrue(Files.isDirectory(dir));
            assertEquals(user, Files.getOwner(dir).getName(),
                    "the directory has to belong to the configured user");
        } finally {
            if (previous == null) {
                System.clearProperty("sitelock.user");
            } else {
                System.setProperty("sitelock.user", previous);
            }
        }
    }
}
