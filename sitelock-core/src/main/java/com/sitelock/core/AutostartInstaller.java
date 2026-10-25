package com.sitelock.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Installs and removes the autostart artifact, and invokes the scheduler.
 *
 * <p>{@code systemRoot} is {@code /} in production and a temporary directory in
 * tests; every artifact path comes from {@code systemRoot.resolve(...)}, and that
 * is what makes enable/disable testable without root.
 */
public final class AutostartInstaller {

    /**
     * Result of enable/disable: success and the detail for the user.
     *
     * <p>The path comes separately from the text: one is for the person's eyes,
     * the other for the artifact. Mixing the two produced a unit pointing at
     * nothing: {@code -jar "The jar is already in place: /opt/sitelock/siteblock.jar"}.
     *
     * @param detail the message for the user
     * @param path   the path of the jar or artifact; null when there is none
     */
    public record InstallResult(boolean success, String detail, Path path) {

        /** Result without a path: just the message. */
        public InstallResult(boolean success, String detail) {
            this(success, detail, null);
        }
    }

    private static final int COMMAND_TIMEOUT_SECONDS = 15;

    private final OsType os;
    private final Path systemRoot;
    private final CommandRunner runner;

    public AutostartInstaller(OsType os, Path systemRoot, CommandRunner runner) {
        this.os = os == null ? OsType.UNKNOWN : os;
        this.systemRoot = systemRoot == null ? Path.of("/") : systemRoot;
        this.runner = runner == null ? AutostartInstaller::runWithProcessBuilder : runner;
    }

    /**
     * Where the daemon jar is installed: a system directory that only root writes to.
     *
     * <p><b>{@code mvn clean} no longer brings the daemon down.</b> The unit pointed
     * at {@code target/}, which clean deletes: systemd entered a crash loop, and
     * block deadlines silently stopped being honored.
     *
     * <p>The daemon also stops running code the user can replace: the original jar
     * lives in a directory that is writable by design, which was the reason for the
     * elevation warning. The copy lives in a root-owned directory.
     *
     * @param systemRoot {@code /} in production; a test root in tests
     */
    public static Path installDir(OsType os, Path systemRoot) {
        Path root = systemRoot == null ? Path.of("/") : systemRoot;
        if (os != null && os.isWindows()) {
            return root.resolve("ProgramData/SiteBlock");
        }
        if (os != null && os.isMac()) {
            return root.resolve("usr/local/lib/sitelock");
        }
        return root.resolve("opt/sitelock");
    }

    /** The path of the installed jar. */
    public static Path installJarPath(OsType os, Path systemRoot) {
        return installDir(os, systemRoot).resolve("siteblock.jar");
    }

    /**
     * Copies the jar to the stable location.
     *
     * <p>Always overwrites: reinstalling is how the daemon starts running the new
     * version after a rebuild. Copying the jar over itself is skipped — the
     * destination vanishes in the middle of the copy and leaves the daemon without
     * an executable.
     *
     * @param source jar to install; null or missing = error
     */
    public static InstallResult installJar(OsType os, Path systemRoot, Path source) {
        if (source == null) {
            return new InstallResult(false,
                    "I don't know which jar to install. Run from the packaged siteblock, or "
                            + "provide the path: siteblock daemon enable --jar <path>");
        }
        Path sourceAbs = source.toAbsolutePath();
        if (!Files.exists(sourceAbs)) {
            return new InstallResult(false, "The jar " + sourceAbs + " does not exist. "
                    + "Run 'mvn package' to build it, or provide another path with --jar.");
        }
        Path target = installJarPath(os, systemRoot);
        if (target.toAbsolutePath().equals(sourceAbs)) {
            return new InstallResult(true, "The jar is already in place: " + target, target);
        }
        try {
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.copy(sourceAbs, target,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return new InstallResult(true, "Jar installed at " + target, target);
        } catch (IOException e) {
            return new InstallResult(false,
                    "Failed to install the jar at " + target + ": " + e.getMessage());
        }
    }

    /**
     * Where the artifact lives on this OS.
     *
     * <p>On Windows there is no file: the definition lives in the Task Scheduler
     * task, so there is no path to return. That is why this returns
     * {@code null} and {@link #install} writes nothing.
     *
     * <p>Writing would create a garbage {@code C:\etc\systemd\system\} tree with a
     * Linux unit inside it, and still print "installed".
     *
     * @param systemRoot {@code /} in production; a test root in tests
     * @return the artifact path, or null if this OS does not use a file
     */
    public static Path artifactPath(OsType os, Path systemRoot) {
        if (os != null && os.isWindows()) {
            return null;
        }
        Path root = systemRoot == null ? Path.of("/") : systemRoot;
        if (os != null && os.isMac()) {
            return root.resolve("Library/LaunchDaemons/" + AutostartArtifact.LABEL + ".plist");
        }
        return root.resolve("etc/systemd/system/" + AutostartArtifact.UNIT_NAME);
    }

    public Path artifactPath() {
        return artifactPath(os, systemRoot);
    }

    /**
     * Is autostart enabled?
     *
     * <p>Static and without a {@code CommandRunner} on purpose: {@code daemon status}
     * and the {@code block --for} warning check this without needing elevation or
     * building an executor.
     *
     * <p>On Linux and macOS it is just an existence check for the file. On Windows
     * there is no file, so it is a {@code schtasks /query} — also without elevation,
     * and also read-only.
     */
    public static boolean isEnabled(OsType os, Path systemRoot) {
        if (os != null && os.isWindows()) {
            try {
                return runWithProcessBuilder(
                        List.of("schtasks", "/query", "/tn", AutostartArtifact.TASK_NAME)) == 0;
            } catch (Exception e) {
                return false;
            }
        }
        Path artifact = artifactPath(os, systemRoot);
        return artifact != null && Files.exists(artifact);
    }

    /** Writes the artifact and enables the scheduler. */
    public InstallResult install(AutostartPaths paths) {
        Path target = artifactPath();
        if (target == null) {
            // Windows: the definition lives in the task, not in a file.
            List<String> command = AutostartArtifact.schtasksArgs(paths);
            try {
                int exit = runner.run(command);
                if (exit != 0) {
                    return new InstallResult(false,
                            String.join(" ", command) + " failed with code " + exit);
                }
            } catch (IOException e) {
                return new InstallResult(false, String.join(" ", command) + ": " + e.getMessage());
            }
            return new InstallResult(true, String.join(" ", command));
        }
        try {
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            String content = os.isMac()
                    ? AutostartArtifact.launchdPlist(paths)
                    : AutostartArtifact.systemdUnit(paths);
            Files.writeString(target, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return new InstallResult(false,
                    "Failed to write " + target + ": " + e.getMessage());
        }

        List<String> command = schedulerEnableCommand(os, systemRoot);
        try {
            // daemon-reload before enable: without it, systemd may answer with the
            // old unit cached and ignore the one we just wrote.
            runner.run(List.of("systemctl", "daemon-reload"));
        } catch (IOException ignored) {
            // Without systemd there is nothing to reload; enable below reports it.
        }
        try {
            int exit = runner.run(command);
            if (exit != 0) {
                return new InstallResult(false, String.join(" ", command) + " failed with code " + exit);
            }
        } catch (IOException e) {
            return new InstallResult(false, String.join(" ", command) + ": " + e.getMessage());
        }
        return new InstallResult(true, String.join(" ", command), target);
    }

    /** Removes the artifact and disables the scheduler. */
    public InstallResult disable() {
        Path target = artifactPath();
        if (target != null) {
            try {
                Files.deleteIfExists(target);
            } catch (IOException e) {
                return new InstallResult(false, "Failed to remove the artifact: " + e.getMessage());
            }
        }
        List<String> command = os.isWindows() ? AutostartArtifact.schtasksDeleteArgs()
                : schedulerDisableCommand(os, systemRoot);
        try {
            runner.run(command);
        } catch (IOException e) {
            // The artifact is already gone; a missing scheduler is the least of our problems.
            return new InstallResult(true, "Artifact removed; " + String.join(" ", command)
                    + " could not be run (" + e.getMessage() + ").", target);
        }
        return new InstallResult(true, String.join(" ", command), target);
    }

    static List<String> schedulerEnableCommand(OsType os, Path systemRoot) {
        if (os != null && os.isMac()) {
            return List.of("launchctl", "bootstrap", "system",
                    String.valueOf(artifactPath(os, systemRoot)));
        }
        return List.of("systemctl", "enable", "--now", AutostartArtifact.UNIT_NAME);
    }

    static List<String> schedulerDisableCommand(OsType os, Path systemRoot) {
        if (os != null && os.isMac()) {
            return List.of("launchctl", "bootout", "system",
                    String.valueOf(artifactPath(os, systemRoot)));
        }
        return List.of("systemctl", "disable", "--now", AutostartArtifact.UNIT_NAME);
    }

    static int runWithProcessBuilder(List<String> command) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        // None of the output is used here; discarding it avoids filling the pipe and
        // hanging before waitFor on a verbose command.
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        Process p = pb.start();
        boolean done;
        try {
            done = p.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            throw new IOException("interrupted while waiting for " + command.get(0), e);
        }
        if (!done) {
            p.destroyForcibly();
            throw new IOException("timeout (" + COMMAND_TIMEOUT_SECONDS + "s) on "
                    + String.join(" ", command).toLowerCase(Locale.ROOT));
        }
        return p.exitValue();
    }
}
