package com.sitelock.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The daemon jar is installed into a stable copy.
 *
 * <p>Without it, the unit points at the build's {@code target/} — which
 * {@code mvn clean} deletes — and systemd enters a crash loop: block deadlines
 * stop being honored, with no message explaining why.
 *
 * <p>The copy lives in a root-owned directory: the daemon stops executing code from
 * a path the user can swap.
 */
class JarInstallTest {

    @TempDir
    Path tmp;

    private Path exampleJar(String content) throws IOException {
        Path jar = tmp.resolve("siteblock-1.0.0.jar");
        Files.writeString(jar, content, StandardCharsets.UTF_8);
        return jar;
    }

    @Test
    void installDirPerOs() {
        assertEquals(Path.of("/opt/sitelock"),
                AutostartInstaller.installDir(OsType.LINUX, Path.of("/")));
        assertEquals(Path.of("/usr/local/lib/sitelock"),
                AutostartInstaller.installDir(OsType.MACOS, Path.of("/")));
        assertEquals(Path.of("/ProgramData/SiteBlock"),
                AutostartInstaller.installDir(OsType.WINDOWS, Path.of("/")));
    }

    @Test
    void installJarPathSitsInsideTheInstallDir() {
        assertEquals(Path.of("/opt/sitelock/siteblock.jar"),
                AutostartInstaller.installJarPath(OsType.LINUX, Path.of("/")));
    }

    @Test
    void installCopiesTheJarToTheStableLocation() throws Exception {
        Path source = exampleJar("version 1");
        Path destination = AutostartInstaller.installJarPath(OsType.LINUX, tmp);

        AutostartInstaller.InstallResult r = AutostartInstaller.installJar(OsType.LINUX, tmp, source);

        assertTrue(r.success(), r.detail());
        assertTrue(Files.exists(destination), "the jar must exist at " + destination);
        assertEquals("version 1", Files.readString(destination, StandardCharsets.UTF_8));
    }

    @Test
    void installOverwritesAnOlderCopy() throws Exception {
        Path source = exampleJar("version 1");
        AutostartInstaller.installJar(OsType.LINUX, tmp, source);

        Files.writeString(source, "version 2", StandardCharsets.UTF_8);
        AutostartInstaller.installJar(OsType.LINUX, tmp, source);

        assertEquals("version 2", Files.readString(
                AutostartInstaller.installJarPath(OsType.LINUX, tmp), StandardCharsets.UTF_8),
                "reinstalling has to update the copy — that is how the daemon starts running the "
                        + "new version after a rebuild");
    }

    @Test
    void installFailsClearlyWhenTheSourceIsMissing() {
        Path source = tmp.resolve("does-not-exist.jar");

        AutostartInstaller.InstallResult r =
                AutostartInstaller.installJar(OsType.LINUX, tmp, source);

        assertFalse(r.success());
        assertTrue(r.detail().contains("does-not-exist.jar"), r.detail());
    }

    @Test
    void installDoesNotCopyOntoItself() throws Exception {
        Path destination = AutostartInstaller.installJarPath(OsType.LINUX, tmp);
        Files.createDirectories(destination.getParent());
        Files.writeString(destination, "original", StandardCharsets.UTF_8);

        AutostartInstaller.InstallResult r =
                AutostartInstaller.installJar(OsType.LINUX, tmp, destination);

        assertTrue(r.success(), r.detail());
        assertEquals("original", Files.readString(destination, StandardCharsets.UTF_8),
                "copying the jar over itself would delete it");
    }

    @Test
    void theArtifactPointsAtTheInstalledCopyNotAtTheSource() throws Exception {
        Path source = exampleJar("version 1");
        Path destination = AutostartInstaller.installJarPath(OsType.LINUX, tmp);
        AutostartInstaller.installJar(OsType.LINUX, tmp, source);

        AutostartPaths paths = new AutostartPaths(
                Path.of("/home/chico/.sitelock"), Path.of("/etc/hosts"),
                "/usr/bin/java", destination.toString(), "/opt/classes", "chico", OsType.LINUX);
        String unit = AutostartArtifact.systemdUnit(paths);

        assertTrue(unit.contains(destination.toString()), unit);
        assertFalse(unit.contains(source.toString()),
                "a unit cannot point at the source jar — that is what mvn clean deletes:\n" + unit);
    }

    @Test
    void theInstalledCopyIsNotNextToTheBuildOutput() {
        Path source = Path.of("/home/chico/Projects/SiteBlock/sitelock-app/target/siteblock-1.0.0.jar");
        Path destination = AutostartInstaller.installJarPath(OsType.LINUX, Path.of("/"));

        assertNotEquals(source, destination);
        assertFalse(destination.toString().contains("target"),
                "the destination must not be inside target/: " + destination);
    }

    @Test
    void installDirIsCreatedWithTheJarInsideIt() throws Exception {
        Path source = exampleJar("version 1");
        Path dir = AutostartInstaller.installDir(OsType.LINUX, tmp);

        assertFalse(Files.exists(dir));
        AutostartInstaller.installJar(OsType.LINUX, tmp, source);

        assertTrue(Files.isDirectory(dir));
        assertTrue(Files.exists(dir.resolve("siteblock.jar")));
    }

    @Test
    void resultSeparatesTheJarPathFromTheMessage() throws Exception {
        Path source = exampleJar("version 1");

        AutostartInstaller.InstallResult r =
                AutostartInstaller.installJar(OsType.LINUX, tmp, source);

        Path destination = AutostartInstaller.installJarPath(OsType.LINUX, tmp);
        assertTrue(r.success(), r.detail());
        assertEquals(destination, r.path(),
                "the jar path is separate from the message — mixed together, the sentence became "
                        + "the unit's path");
        assertTrue(r.detail().contains(destination.toString()), r.detail());
    }

    @Test
    void theResultHasThePathWhenTheJarIsAlreadyInPlace() throws Exception {
        // It is the enable run from the already installed jar: the detail
        // was the sentence "The jar is already in place: ..." and the unit started pointing
        // at it.
        Path destination = AutostartInstaller.installJarPath(OsType.LINUX, tmp);
        Files.createDirectories(destination.getParent());
        Files.writeString(destination, "original", StandardCharsets.UTF_8);

        AutostartInstaller.InstallResult r =
                AutostartInstaller.installJar(OsType.LINUX, tmp, destination);

        assertTrue(r.success(), r.detail());
        assertEquals(destination, r.path());
    }

    @Test
    void installAndDisableReturnTheArtifactPath() {
        AutostartInstaller installer =
                new AutostartInstaller(OsType.LINUX, tmp, c -> 0);
        AutostartPaths paths = new AutostartPaths(
                Path.of("/home/chico/.sitelock"), Path.of("/etc/hosts"),
                "/usr/bin/java", "/opt/sitelock/siteblock.jar", "/opt/classes",
                "chico", OsType.LINUX);

        AutostartInstaller.InstallResult installed = installer.install(paths);
        assertTrue(installed.success(), installed.detail());
        assertEquals(installer.artifactPath(), installed.path());

        AutostartInstaller.InstallResult removed = installer.disable();
        assertTrue(removed.success(), removed.detail());
        assertEquals(installer.artifactPath(), removed.path());
    }

    @Test
    void listOfFilesInInstallDirIsJustTheJar() throws Exception {
        Path source = exampleJar("version 1");
        AutostartInstaller.installJar(OsType.LINUX, tmp, source);

        try (var stream = Files.list(AutostartInstaller.installDir(OsType.LINUX, tmp))) {
            List<String> names = stream.map(p -> p.getFileName().toString()).toList();
            assertEquals(List.of("siteblock.jar"), names);
        }
    }
}
