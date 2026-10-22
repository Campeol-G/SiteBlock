package com.sitelock.core;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Escrita no hosts preserva as permissões e repara o arquivo deixado em 600.
 *
 * <p>Só faz sentido onde existe permissão POSIX. Onde não há, o teste é pulado
 * e a suíte reporta o pulo — engolir a {@code UnsupportedOperationException}
 * deixaria a CI do Windows verde sem cobertura nenhuma.
 */
@EnabledOnOs({OS.LINUX, OS.MAC})
class HostsFileManagerPermsTest {

    @TempDir
    Path tmp;

    @Test
    void blockPreservesHostsPermissions() throws Exception {
        Path hosts = tmp.resolve("hosts");
        Files.writeString(hosts, "127.0.0.1 localhost\n", StandardCharsets.UTF_8);
        Assumptions.assumeTrue(Files.getFileAttributeView(hosts, PosixFileAttributeView.class) != null,
                "o FS do temporário não expõe permissões POSIX");
        Files.setPosixFilePermissions(hosts, Set.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.GROUP_READ, PosixFilePermission.OTHERS_READ));
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
        Assumptions.assumeTrue(Files.getFileAttributeView(hosts, PosixFileAttributeView.class) != null,
                "o FS do temporário não expõe permissões POSIX");
        Files.setPosixFilePermissions(hosts, Set.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        Path backup = tmp.resolve("state/hosts.backup");
        Path lock = tmp.resolve("state/hosts.lock");

        HostsFileManager.blockInFile(hosts, backup, lock, "tiktok.com");

        assertTrue(Files.getPosixFilePermissions(hosts).contains(PosixFilePermission.OTHERS_READ));
    }
}
