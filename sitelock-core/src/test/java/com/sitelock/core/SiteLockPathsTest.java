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
 * O diretório de estado criado por um processo elevado não pode ficar só para
 * root: o próximo {@code status} roda sem sudo e precisa lê-lo.
 */
@EnabledOnOs({OS.LINUX, OS.MAC})
class SiteLockPathsTest {

    @TempDir
    Path tmp;

    @Test
    void createDirectoriesCreatesAndAppliesTheConfiguredOwner() throws Exception {
        String anterior = System.getProperty("sitelock.user");
        String usuario = System.getProperty("user.name");
        try {
            System.setProperty("sitelock.user", usuario);
            Path dir = tmp.resolve("a/b/.sitelock");

            SiteLockPaths.createDirectories(dir);

            assertTrue(Files.isDirectory(dir));
            assertEquals(usuario, Files.getOwner(dir).getName(),
                    "o diretório tem que pertencer ao usuário configurado");
        } finally {
            if (anterior == null) {
                System.clearProperty("sitelock.user");
            } else {
                System.setProperty("sitelock.user", anterior);
            }
        }
    }
}
