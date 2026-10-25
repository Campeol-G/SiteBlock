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
 * Writing to the hosts file preserves permissions and repairs a file left at 600.
 *
 * <p>It only makes sense where POSIX permissions exist. Where they do not, the test is skipped
 * and the suite reports the skip — swallowing the {@code UnsupportedOperationException}
 * would leave Windows CI green with no coverage at all.
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
                "the temp file's FS does not expose POSIX permissions");
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
                "the temp file's FS does not expose POSIX permissions");
        Files.setPosixFilePermissions(hosts, Set.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        Path backup = tmp.resolve("state/hosts.backup");
        Path lock = tmp.resolve("state/hosts.lock");

        HostsFileManager.blockInFile(hosts, backup, lock, "tiktok.com");

        assertTrue(Files.getPosixFilePermissions(hosts).contains(PosixFilePermission.OTHERS_READ));
    }
}
