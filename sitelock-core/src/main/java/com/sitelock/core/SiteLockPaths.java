package com.sitelock.core;

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
 * Caminhos usados pelo SiteBlock.
 *
 * Sob sudo, user.home vira /root, então o estado usa a home do usuário
 * real (via SUDO_USER). SITELOCK_STATE_DIR / SITELOCK_HOSTS_FILE
 * sobrescrevem o padrão (útil para testes).
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

    /** Arquivo de lock para serializar processos concorrentes. */
    public static Path getStateLockFile() {
        return getStateDir().resolve(BLOCKS_FILE_NAME + ".lock");
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
        // Só o caminho varia por SO; a edição (HostsFileManager) é reutilizada.
        // Windows usa %SystemRoot% (ver DefaultHostsFileLocator).
        return new DefaultHostsFileLocator().getHostsPath();
    }

    /** Nome do usuário real sob sudo, vazio se não houver. */
    public static Optional<String> realUserName() {
        String sudoUser = System.getenv("SUDO_USER");
        if (sudoUser == null || sudoUser.isBlank()
                || "root".equals(sudoUser.strip().toLowerCase(Locale.ROOT))) {
            return Optional.empty();
        }
        return Optional.of(sudoUser.strip());
    }

    /**
     * Devolve a posse do arquivo ao usuário real quando roda com sudo.
     * Best-effort: ignora falhas silenciosamente.
     * Nunca usar no /etc/hosts (deve continuar root).
     */
    public static void chownToRealUserIfSudo(Path path) {
        Optional<String> realUser = realUserName();
        if (realUser.isEmpty()) {
            return;
        }
        chownTo(path, realUser.get());
    }

    /**
     * Devolve a posse ao usuário nomeado na property {@code sitelock.user},
     * caindo no comportamento de sempre ({@code SUDO_USER}) quando ela não
     * existir.
     *
     * <p>Existe porque um daemon iniciado por autostart roda como root
     * <b>sem</b> {@code SUDO_USER}: um unit de sistema do systemd e uma
     * LaunchDaemon não têm {@code User=}, e o {@code SiteLockPaths} só descobre
     * o usuário real a partir dessa variável. Sem esta property, o primeiro
     * sweep deixaria o {@code blocks.json} do root — a escrita atômica troca o
     * inode — e o próprio {@code status}, que a spec promete que roda sem
     * elevação, passaria a depender de root para ler.
     */
    public static void chownToUserIfConfigured(Path path) {
        String configured = System.getProperty("sitelock.user");
        if (configured != null && !configured.isBlank() && !"root".equals(configured.strip())) {
            chownTo(path, configured.strip());
            return;
        }
        chownToRealUserIfSudo(path);
    }

    /** Aplica posse e grupo ao usuário, best-effort. */
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

    /** Home do usuário real, mesmo sob sudo. */
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
