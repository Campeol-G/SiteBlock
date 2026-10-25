package com.sitelock.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The autostart artifact is tested as <b>generated text</b>, not as a scheduler:
 * CI runs on the three OSes, but the real {@code systemctl}/{@code launchctl}/
 * {@code schtasks} cannot be exercised.
 *
 * <p>The assertion that matters is a substring one: the text has to contain the
 * absolute {@code stateDir} and {@code hostsFile} — that is what catches a daemon
 * pointing at {@code /root}.
 */
class AutostartArtifactTest {

    private static final Path STATE_DIR_PATH = Path.of("/home/chico/.sitelock");
    private static final Path HOSTS_PATH = Path.of("/etc/hosts");
    // The artifact resolves paths with toAbsolutePath(); on Windows that
    // gains the drive letter, so the expectation has to resolve too.
    private static final String STATE_DIR = STATE_DIR_PATH.toAbsolutePath().toString();
    private static final String HOSTS = HOSTS_PATH.toAbsolutePath().toString();
    private static final String JAVA = "/usr/lib/jvm/temurin-26/bin/java";
    private static final String JAR = "/opt/sitelock/siteblock.jar";
    private static final String CP = "/opt/sitelock/classes";

    @TempDir
    Path tmp;

    private AutostartPaths paths(OsType os) {
        return new AutostartPaths(STATE_DIR_PATH, HOSTS_PATH, JAVA, JAR, CP, "chico", os);
    }

    private AutostartPaths paths(OsType os, String jarPath) {
        return new AutostartPaths(STATE_DIR_PATH, HOSTS_PATH, JAVA, jarPath, CP, "chico", os);
    }

    @Test
    void systemdUnitContainsPinnedPaths() {
        String unit = AutostartArtifact.systemdUnit(paths(OsType.LINUX));
        assertTrue(unit.contains("-Dsitelock.stateDir=" + STATE_DIR), unit);
        assertTrue(unit.contains("-Dsitelock.hostsFile=" + HOSTS), unit);
        assertTrue(unit.contains(JAVA), unit);
        assertTrue(unit.contains(JAR), unit);
        assertFalse(unit.contains("user.home"),
                "the artifact must not depend on user.home: under a system unit it is /root");
    }

    @Test
    void systemdUnitIsASystemUnit() {
        String unit = AutostartArtifact.systemdUnit(paths(OsType.LINUX));
        assertTrue(unit.contains("[Install]"), unit);
        assertTrue(unit.contains("WantedBy=multi-user.target"), unit);
        assertFalse(unit.contains("User="),
                "with User= the daemon does not write /etc/hosts — it is a system unit on purpose");
    }

    @Test
    void launchdPlistContainsPinnedPaths() {
        String plist = AutostartArtifact.launchdPlist(paths(OsType.MACOS));
        assertTrue(plist.contains("-Dsitelock.stateDir=" + STATE_DIR), plist);
        assertTrue(plist.contains("-Dsitelock.hostsFile=" + HOSTS), plist);
        assertTrue(plist.contains(JAVA), plist);
        assertTrue(plist.contains(JAR), plist);
        assertTrue(plist.contains("com.sitelock.sweeper"), plist);
        assertTrue(plist.contains("<key>RunAtLoad</key>"), plist);
        assertFalse(plist.contains("user.home"), plist);
    }

    @Test
    void schtasksCommandContainsPinnedPaths() {
        String cmd = AutostartArtifact.schtasksCommand(paths(OsType.WINDOWS));
        assertTrue(cmd.contains("-Dsitelock.stateDir=" + STATE_DIR), cmd);
        assertTrue(cmd.contains("-Dsitelock.hostsFile=" + HOSTS), cmd);
        assertTrue(cmd.contains("/sc ONLOGON"), cmd);
        assertTrue(cmd.contains("/rl HIGHEST"), cmd);
        assertTrue(cmd.contains("/tn " + AutostartArtifact.TASK_NAME), cmd);
    }

    @Test
    void schtasksArgsForceOverwriteExistingTask() {
        List<String> args = AutostartArtifact.schtasksArgs(paths(OsType.WINDOWS));

        assertEquals(1, args.stream().filter("/f"::equals).count(),
                "without /f the second daemon enable fails: " + args);
        assertEquals("/f", args.get(args.size() - 1));
        assertTrue(AutostartArtifact.schtasksCommand(paths(OsType.WINDOWS)).endsWith(" /f"));
    }

    @Test
    void windowsTriggersOnLogonNotOnBoot() {
        String cmd = AutostartArtifact.schtasksCommand(paths(OsType.WINDOWS));
        assertFalse(cmd.contains("/sc ONSTART"), cmd);
        assertFalse(cmd.contains("/ru SYSTEM"), cmd);
    }

    @Test
    void artifactEmbedsTheRealUserName() {
        assertTrue(AutostartArtifact.systemdUnit(paths(OsType.LINUX)).contains("-Dsitelock.user=chico"));
        assertTrue(AutostartArtifact.launchdPlist(paths(OsType.MACOS)).contains("-Dsitelock.user=chico"));
        assertTrue(AutostartArtifact.schtasksCommand(paths(OsType.WINDOWS)).contains("-Dsitelock.user=chico"));
    }

    @Test
    void artifactEscapesSpacesInPaths() {
        Path stateWithSpace = Path.of("/home/chico/My Projects/.sitelock");
        AutostartPaths withSpace = new AutostartPaths(
                stateWithSpace, HOSTS_PATH, "/opt/My JDK/bin/java", "/opt/My JDK/siteblock.jar",
                CP, "chico", OsType.LINUX);
        String unit = AutostartArtifact.systemdUnit(withSpace);

        // systemd does not do shell splitting: the quoting wraps the whole token,
        // property and path together, otherwise the space would become another argument.
        assertTrue(unit.contains("\"-Dsitelock.stateDir=" + stateWithSpace.toAbsolutePath() + "\""),
                unit);
        assertTrue(unit.contains("\"/opt/My JDK/bin/java\""), unit);
        assertTrue(unit.contains("\"/opt/My JDK/siteblock.jar\""), unit);
    }

    @Test
    void artifactFallsBackToClasspathWhenNoJar() {
        String unit = AutostartArtifact.systemdUnit(paths(OsType.LINUX, null));
        assertTrue(unit.contains("-cp " + CP), unit);
        assertFalse(unit.contains("-jar"), unit);

        String plist = AutostartArtifact.launchdPlist(paths(OsType.MACOS, null));
        assertTrue(plist.contains("<string>-cp</string>"), plist);
        assertTrue(plist.contains("<string>" + CP + "</string>"), plist);
    }

    @Test
    void commandLineWithJarDoesNotRepeatTheMainClass() {
        // With -jar the JVM reads the Main-Class from the manifest: repeating the class name
        // here hands picocli a dangling argument, and the daemon dies with
        // exit code 2 before sweeping anything.
        List<String> cmd = AutostartArtifact.commandLine(paths(OsType.LINUX));
        assertFalse(cmd.contains("com.sitelock.SiteLockApplication"),
                "with -jar the class name is redundant and breaks the daemon: " + cmd);
    }

    @Test
    void jvmPropertiesComeBeforeTheJar() {
        // After -jar the JVM stops reading options: whatever comes there is a program
        // argument, and picocli rejects it with "Unknown options".
        List<String> cmd = AutostartArtifact.commandLine(paths(OsType.LINUX));
        int prop = cmd.indexOf("-Dsitelock.stateDir=" + STATE_DIR);
        int jar = cmd.indexOf("-jar");
        assertTrue(prop >= 0 && jar >= 0 && prop < jar,
                "the JVM property has to come before the -jar: " + cmd);
    }

    @Test
    void commandLineWithClasspathKeepsTheMainClass() {
        // Without -jar there is no manifest: the JVM needs the class name.
        List<String> cmd = AutostartArtifact.commandLine(paths(OsType.LINUX, null));
        assertTrue(cmd.contains("com.sitelock.SiteLockApplication"),
                "without -jar the class name is mandatory: " + cmd);
        assertTrue(cmd.indexOf("-cp") < cmd.indexOf("com.sitelock.SiteLockApplication"),
                "the classpath has to come before the class: " + cmd);
    }

    @Test
    void artifactPathPerOs() {
        assertEquals(Path.of("/etc/systemd/system/sitelock-sweeper.service"),
                AutostartInstaller.artifactPath(OsType.LINUX, Path.of("/")));
        assertEquals(Path.of("/Library/LaunchDaemons/com.sitelock.sweeper.plist"),
                AutostartInstaller.artifactPath(OsType.MACOS, Path.of("/")));
    }

    @Test
    void installThenDisableIsSymmetric() {
        RecordingRunner runner = new RecordingRunner();
        AutostartInstaller installer = new AutostartInstaller(OsType.LINUX, tmp, runner);

        assertFalse(AutostartInstaller.isEnabled(OsType.LINUX, tmp));

        AutostartInstaller.InstallResult installed = installer.install(paths(OsType.LINUX));
        assertTrue(installed.success(), installed.detail());
        assertTrue(Files.exists(installer.artifactPath()), "the artifact has to exist after enable");
        assertTrue(AutostartInstaller.isEnabled(OsType.LINUX, tmp));

        AutostartInstaller.InstallResult removed = installer.disable();
        assertTrue(removed.success(), removed.detail());
        assertFalse(Files.exists(installer.artifactPath()), "disable has to remove what enable created");
        assertFalse(AutostartInstaller.isEnabled(OsType.LINUX, tmp));
    }

    @Test
    void installRunsTheScheduler() {
        RecordingRunner runner = new RecordingRunner();
        new AutostartInstaller(OsType.LINUX, tmp, runner).install(paths(OsType.LINUX));
        assertTrue(runner.commands.stream().anyMatch(c -> c.get(0).equals("systemctl")
                && c.contains("enable") && c.contains("--now")), runner.commands.toString());

        RecordingRunner mac = new RecordingRunner();
        new AutostartInstaller(OsType.MACOS, tmp, mac).install(paths(OsType.MACOS));
        assertTrue(mac.commands.stream().anyMatch(c -> c.get(0).equals("launchctl")
                && c.contains("bootstrap")), mac.commands.toString());

        RecordingRunner win = new RecordingRunner();
        new AutostartInstaller(OsType.WINDOWS, tmp, win).install(paths(OsType.WINDOWS));
        assertTrue(win.commands.stream().anyMatch(c -> c.get(0).equals("schtasks")
                && c.contains("/create")), win.commands.toString());
    }

    @Test
    void disableRunsTheStopCommand() {
        RecordingRunner runner = new RecordingRunner();
        AutostartInstaller installer = new AutostartInstaller(OsType.LINUX, tmp, runner);
        installer.install(paths(OsType.LINUX));
        runner.commands.clear();

        installer.disable();

        assertTrue(runner.commands.stream().anyMatch(c -> c.get(0).equals("systemctl")
                && c.contains("disable") && c.contains("--now")), runner.commands.toString());
    }

    private static final class RecordingRunner implements CommandRunner {
        final List<List<String>> commands = new ArrayList<>();

        @Override
        public int run(List<String> command) {
            commands.add(List.copyOf(command));
            return 0;
        }
    }
}