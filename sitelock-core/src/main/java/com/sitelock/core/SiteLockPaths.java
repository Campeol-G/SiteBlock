package com.sitelock.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.UserPrincipal;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.util.Locale;
import java.util.Optional;

/**
 * Paths used by SiteBlock.
 *
 * Under sudo, user.home becomes /root, so the state uses the real user's
 * home (via SUDO_USER). SITELOCK_STATE_DIR / SITELOCK_HOSTS_FILE
 * override the defaults (useful for tests).
 */
public final class SiteLockPaths {

    private static final String STATE_DIR_NAME = ".sitelock";
    private static final String BLOCKS_FILE_NAME = "blocks.json";
    private static final String BACKUP_FILE_NAME = "hosts.backup";

    private SiteLockPaths() {
    }

    public static Path getStateDir() {
        String override = System.getProperty("sitelock.stateDir");
        if (override != null && !override.isBlank()) {
            return Paths.get(override);
        }
        String env = System.getenv("SITELOCK_STATE_DIR");
        if (env != null && !env.isBlank()) {
            return Paths.get(env);
        }
        return getRealUserHome().resolve(STATE_DIR_NAME);
    }

    public static Path getStateFile() {
        return getStateDir().resolve(BLOCKS_FILE_NAME);
    }

    public static Path getBackupFile() {
        return getStateDir().resolve(BACKUP_FILE_NAME);
    }

    /** Lock file to serialize concurrent processes. */
    public static Path getStateLockFile() {
        return getStateDir().resolve(BLOCKS_FILE_NAME + ".lock");
    }

    /**
     * Creates the directory (and its parents) and hands ownership back to the real user.
     *
     * <p>Creating {@code ~/.sitelock} as root without this adjustment leaves the directory
     * inaccessible to the next {@code status} running without sudo.
     */
    public static void createDirectories(Path dir) throws IOException {
        Files.createDirectories(dir);
        chownToUserIfConfigured(dir);
    }

    public static Path getHostsPath() {
        String override = System.getProperty("sitelock.hostsFile");
        if (override != null && !override.isBlank()) {
            return Paths.get(override);
        }
        String env = System.getenv("SITELOCK_HOSTS_FILE");
        if (env != null && !env.isBlank()) {
            return Paths.get(env);
        }
        // Only the path varies per OS; the editing (HostsFileManager) is reused.
        // Windows uses %SystemRoot% (see DefaultHostsFileLocator).
        return new DefaultHostsFileLocator().getHostsPath();
    }

    /** Name of the real user under sudo, empty when there is none. */
    public static Optional<String> realUserName() {
        String sudoUser = System.getenv("SUDO_USER");
        if (sudoUser == null || sudoUser.isBlank()
                || "root".equals(sudoUser.strip().toLowerCase(Locale.ROOT))) {
            return Optional.empty();
        }
        return Optional.of(sudoUser.strip());
    }

    /**
     * Hands file ownership back to the real user when running with sudo.
     * Best-effort: silently ignores failures.
     * Never use on /etc/hosts (it should stay root).
     */
    public static void chownToRealUserIfSudo(Path path) {
        Optional<String> realUser = realUserName();
        if (realUser.isEmpty()) {
            return;
        }
        chownTo(path, realUser.get());
    }

    /**
     * Hands ownership back to the user named in the {@code sitelock.user} property,
     * falling back to the usual behavior ({@code SUDO_USER}) when it does not exist.
     *
     * <p>It exists because the autostart daemon runs as root <b>without</b>
     * {@code SUDO_USER}: a systemd system unit and a LaunchDaemon have no
     * {@code User=}. Without this property, the first sweep would leave the
     * {@code blocks.json} owned by root — the atomic write swaps the inode — and
     * {@code status}, which runs without elevation, would become dependent on root to read.
     */
    public static void chownToUserIfConfigured(Path path) {
        String configured = System.getProperty("sitelock.user");
        if (configured != null && !configured.isBlank() && !"root".equals(configured.strip())) {
            chownTo(path, configured.strip());
            return;
        }
        chownToRealUserIfSudo(path);
    }

    // Applies owner and group to the user, best-effort.
    private static void chownTo(Path path, String user) {
        try {
            if (!Files.exists(path)) {
                return;
            }
            UserPrincipalLookupService lookup =
                    path.getFileSystem().getUserPrincipalLookupService();
            UserPrincipal userPrincipal = lookup.lookupPrincipalByName(user);
            Files.setOwner(path, userPrincipal);
            try {
                GroupPrincipal group = lookup.lookupPrincipalByGroupName(user);
                PosixFileAttributeView view =
                        Files.getFileAttributeView(path, PosixFileAttributeView.class);
                if (view != null) {
                    view.setGroup(group);
                }
            } catch (Exception ignored) {
            }
        } catch (Exception ignored) {
        }
    }

    /** Home of the real user, even under sudo. */
    public static Path getRealUserHome() {
        String sudoUser = System.getenv("SUDO_USER");
        if (sudoUser != null && !sudoUser.isBlank()
                && !"root".equals(sudoUser.strip().toLowerCase(Locale.ROOT))) {
            String user = sudoUser.strip();
            Path linuxHome = Paths.get("/home", user);
            if (Files.isDirectory(linuxHome)) {
                return linuxHome;
            }
            Path macHome = Paths.get("/Users", user);
            if (Files.isDirectory(macHome)) {
                return macHome;
            }
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            if (os.contains("mac")) {
                return macHome;
            }
            return linuxHome;
        }
        return Paths.get(System.getProperty("user.home"));
    }
}