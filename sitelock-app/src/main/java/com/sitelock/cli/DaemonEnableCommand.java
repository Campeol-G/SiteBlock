package com.sitelock.cli;

import com.sitelock.core.AutostartInstaller;
import com.sitelock.core.AutostartPaths;
import com.sitelock.core.CommandRunner;
import com.sitelock.core.OsType;
import com.sitelock.core.SiteLockPaths;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Callable;

// NOTE: the paths are resolved now and embedded in the artifact — the
// scheduler runs as root without SUDO_USER, and then user.home is /root: the daemon
// would read another user's blocks.json, and the feature would become a silent no-op.

// WHY: the jar is copied, not pointed at where it is — the unit pointed at
// target/, which mvn clean deletes, and it entered a crash loop. The copy lives
// in a directory only root writes to.

/**
 * siteblock daemon enable [<path>] — installs autostart.
 *
 * <p>It is a privileged installation: the daemon needs to write {@code /etc/hosts},
 * and normal SiteBlock usage already asks for root.
 */
@Command(name = "enable",
        description = "Installs the expiration daemon to come up at every boot.",
        mixinStandardHelpOptions = true)
public class DaemonEnableCommand implements Callable<Integer> {

    @Option(names = "--jar", paramLabel = "<path>",
            description = "Absolute path of the fat-jar. Default: the siteblock.jar property.")
    String jarPath;

    @Option(names = "--state-file", hidden = true) String stateFileOverride;

    // Injected for tests; null = real OS and root.
    OsType osOverride;
    Path systemRootOverride;
    CommandRunner runnerOverride;
    Boolean systemdPresentOverride;

    public DaemonEnableCommand() {
    }

    DaemonEnableCommand(OsType os, Path systemRoot, String jarPath, CommandRunner runner) {
        this.osOverride = os;
        this.systemRootOverride = systemRoot;
        this.jarPath = jarPath;
        this.runnerOverride = runner;
    }

    DaemonEnableCommand(OsType os, Path systemRoot, String jarPath, CommandRunner runner,
            Boolean systemdPresent) {
        this(os, systemRoot, jarPath, runner);
        this.systemdPresentOverride = systemdPresent;
    }

    @Override
    public Integer call() {
        OsType os = osOverride != null ? osOverride : OsType.detect();
        Path systemRoot = systemRootOverride != null ? systemRootOverride : Path.of("/");

        if (!hasScheduler(os)) {
            System.err.println("Could not find " + schedulerName(os) + " on this machine.");
            System.err.println("Without it, the daemon cannot be started automatically. "
                    + "You can still run the daemon manually, in another terminal:");
            System.err.println("  " + CliSupport.elevatedCommand("daemon start"));
            System.err.println("It releases expired entries while open — but closing "
                    + "the terminal stops the daemon.");
            return 1;
        }

        if (!isWritable(installDirectory(os, systemRoot))) {
            System.err.println("Error: " + CliSupport.elevationHint("daemon enable"));
            return 1;
        }

        AutostartPaths paths = new AutostartPaths(
                SiteLockPaths.getStateDir(),
                CliSupport.resolveHostsPath(null),
                javaPath(),
                resolvedJarPath(),
                System.getProperty("java.class.path", "."),
                realUser(),
                os);

        // The jar is installed before the artifact, so the unit points at the
        // stable copy and not at the build path.
        Path source = resolvedJarPath() != null ? Paths.get(resolvedJarPath()) : null;
        AutostartInstaller.InstallResult copy =
                AutostartInstaller.installJar(os, systemRoot, source);
        if (!copy.success()) {
            System.err.println("Error: " + copy.detail());
            return 1;
        }
        Path installedJar = copy.path();
        if (installedJar == null) {
            // NOTE: without the path there is no possible unit — better to fail here than
            // to write an ExecStart pointing at nothing.
            System.err.println("Error: " + copy.detail());
            return 1;
        }

        AutostartPaths finalPaths = new AutostartPaths(
                paths.stateDir(), paths.hostsFile(), paths.javaBin(),
                installedJar.toString(), paths.classpath(), paths.realUserName(), os);

        AutostartInstaller installer = new AutostartInstaller(os, systemRoot, runnerOverride);
        AutostartInstaller.InstallResult result = installer.install(finalPaths);
        if (!result.success()) {
            System.err.println("Error installing autostart: " + result.detail());
            return 1;
        }

        System.out.println("Expiration daemon installed. It will come up at every boot.");
        System.out.println("Jar: " + installedJar);
        String warning = installOwnerWarning(os, systemRoot);
        if (warning != null) {
            System.out.println(warning);
        }
        System.out.println("After rebuilding (mvn package), run again: "
                + CliSupport.elevatedCommand("daemon enable"));
        return 0;
    }

    // WHY: ownership of the files the daemon writes goes back to the real
    // user, and not user.name: under su the JVM resolves user.name from the uid (0 =
    // "root"), which chownToUserIfConfigured refuses — without a return, reset
    // --yes would stop working without su.

    // NOTE: SiteLockPaths.realUserName() reads SUDO_USER and already rejects root;
    // without su — from a pipeline, for example — it returns empty and the artifact does
    // not embed a user.
    private String realUser() {
        return SiteLockPaths.realUserName().orElse(null);
    }

    // Missing systemd is common: containers, WSL, slim distros. Pretending it
    // worked would be worse than saying it did not — the person would think they are
    // covered when they are not.
    private boolean hasScheduler(OsType os) {
        if (systemdPresentOverride != null) {
            return systemdPresentOverride;
        }
        if (os.isWindows() || os.isMac()) {
            return true;
        }
        return Files.isDirectory(Path.of("/run/systemd/system"));
    }

    private static String schedulerName(OsType os) {
        if (os.isMac()) {
            return "launchctl";
        }
        return "systemd";
    }

    // The directory that needs to be writable: on Linux and macOS it is the artifact's
    // directory (/etc/systemd/system, /Library/LaunchDaemons); on Windows there is
    // no artifact file, and it decides the jar's directory (ProgramData/SiteBlock).
    private static Path installDirectory(OsType os, Path systemRoot) {
        Path artifact = AutostartInstaller.artifactPath(os, systemRoot);
        if (artifact != null) {
            Path parent = artifact.getParent();
            return parent != null ? parent : artifact;
        }
        return AutostartInstaller.installDir(os, systemRoot);
    }

    // Walks up to the nearest existing ancestor, because the directory may still
    // not exist (/run/systemd/system does not exist on a machine without systemd):
    // what decides is whether we reached a writable directory.

    // NOTE: the argument is the directory that needs to be writable, and not the
    // artifact file — on Windows there is no file.
    private static boolean isWritable(Path directory) {
        if (directory == null) {
            return false;
        }
        Path existing = directory;
        while (existing != null && !Files.exists(existing)) {
            existing = existing.getParent();
        }
        return existing != null && Files.isWritable(existing);
    }

    private static String javaPath() {
        String javaHome = System.getProperty("java.home", "");
        Path javaBin = Paths.get(javaHome, "bin", "java");
        return Files.isExecutable(javaBin) ? javaBin.toString() : "java";
    }

    // The property first; the classpath is the fallback, and it is a single jar in the documented usage.
    private String resolvedJarPath() {
        if (jarPath != null && !jarPath.isBlank()) {
            return jarPath;
        }
        String prop = System.getProperty("siteblock.jar");
        return prop == null || prop.isBlank() ? null : prop;
    }

    // The daemon jar lives in a directory only root writes to: if the installation
    // directory is NOT root's — the case of a directory that already existed and belonged to the
    // user — the daemon runs user code as root.
    private String installOwnerWarning(OsType os, Path systemRoot) {
        if (os.isWindows()) {
            // NOTE: on Windows there is no root uid nor chown — what protects the
            // jar is the folder's ACL, and the owner of ProgramData is the user who
            // installed it. Asking for a chown there is asking for a command that does not exist.
            return null;
        }
        Path dir = AutostartInstaller.installDir(os, systemRoot);
        try {
            String owner = Files.getOwner(dir).getName();
            if (!"root".equals(owner.toLowerCase(java.util.Locale.ROOT))) {
                return "Warning: " + dir + " is not owned by root — the daemon would run a jar you "
                        + "can swap. Run: sudo chown root " + dir;
            }
        } catch (Exception e) {
            // No permission to read owner: cannot confirm anything, and the default
            // warning would alarm without reason.
        }
        return null;
    }
}