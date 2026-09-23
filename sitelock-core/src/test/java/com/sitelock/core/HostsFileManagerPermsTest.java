package com.sitelock.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Escrita no hosts preserva permissões e repara 600. */
class HostsFileManagerPermsTest {

    @TempDir
    Path tmp;

    @Test
    void blockPreservesHostsPermissions() throws Exception {
        Path hosts = tmp.resolve("hosts");
        Files.writeString(hosts, "127.0.0.1 localhost\n", StandardCharsets.UTF_8);
        try {
            Files.setPosixFilePermissions(hosts, Set.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.GROUP_READ, PosixFilePermission.OTHERS_READ));
        } catch (UnsupportedOperationException e) {
            return;
        }
        Path backup = tmp.resolve("state/hosts.backup");
        Path lock = tmp.resolve("state/hosts.lock");

        HostsFileManager.blockInFile(hosts, backup, lock, "tiktok.com");

        assertEquals(Set.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.GROUP_READ, PosixFilePermission.OTHERS_READ),
                Files.getPosixFilePermissions(hosts));
        String content = Files.readString(hosts, StandardCharsets.UTF_8);
        assertTrue(content.contains("::1 tiktok.com"));
    }

    @Test
    void writeRepairsHostsLeftUnreadableByOldBug() throws Exception {
        Path hosts = tmp.resolve("hosts");
        Files.writeString(hosts, "127.0.0.1 localhost\n", StandardCharsets.UTF_8);
        try {
            Files.setPosixFilePermissions(hosts, Set.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException e) {
            return;
        }
        Path backup = tmp.resolve("state/hosts.backup");
        Path lock = tmp.resolve("state/hosts.lock");

        HostsFileManager.blockInFile(hosts, backup, lock, "tiktok.com");

        assertTrue(Files.getPosixFilePermissions(hosts).contains(PosixFilePermission.OTHERS_READ));
    }
}
