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
 * Resolve os caminhos usados pelo SiteBlock.
 *
 * <p>Decisão crítica (resposta do usuário — "Home do usuário real"): quando o
 * programa roda com {@code sudo}, {@code user.home}/{@code $HOME} apontam para
 * {@code /root}, o que espalharia o estado em {@code /root/.sitelock}.
 * Para manter um único estado por usuário real, resolvemos assim:
 * <ol>
 *   <li>Se a propriedade {@code -Dsitelock.stateDir} ou a env
 *       {@code SITELOCK_STATE_DIR} estiver definida, ela vence (útil p/ testes).</li>
 *   <li>Senão, se {@code SUDO_USER} existir e não for {@code root}, usamos a
 *       home desse usuário ({@code /home/<user>} ou {@code /Users/<user>} se
 *       existir; senão o melhor esforço via {@code user.home}).</li>
 *   <li>Senão, {@code System.getProperty("user.home") + "/.sitelock"}.</li>
 * </ol>
 *
 * <p>O arquivo hosts pode ser sobrescrito via {@code -Dsitelock.hostsFile} ou
 * env {@code SITELOCK_HOSTS_FILE} (usado por testes p/ não tocar no
 * {@code /etc/hosts} real). Por padrão: {@code /etc/hosts}.
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

    /** Arquivo de lock usado para serializar processos SiteLock concorrentes. */
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
        return Paths.get("/etc/hosts");
    }

    /**
     * Nome do usuário real quando há sudo (vazio quando não há).
     */
    public static Optional<String> realUserName() {
        String sudoUser = System.getenv("SUDO_USER");
        if (sudoUser == null || sudoUser.isBlank()
                || "root".equals(sudoUser.strip().toLowerCase(Locale.ROOT))) {
            return Optional.empty();
        }
        return Optional.of(sudoUser.strip());
    }

    /**
     * Devolve a posse de um arquivo/diretório ao usuário real quando o programa
     * roda com sudo.
     *
     * <p><b>Motivação (bug real da Etapa 1):</b> o {@code block} com sudo criava
     * {@code blocks.json}, {@code *.lock} e {@code hosts.backup} como
     * {@code root:root} dentro da home do usuário. Depois, o {@code status}
     * <b>sem</b> sudo falhava com {@code Erro ao ler estado: ...blocks.json.lock}
     * porque {@link BlockStore#load()} tentava abrir o lock com
     * {@code CREATE+WRITE}. Best-effort: em FS não-POSIX ou sem permissão, ignora
     * silenciosamente (o comportamento anterior é mantido).
     *
     * <p>NUNCA chame isto no {@code /etc/hosts} — o hosts deve continuar
     * {@code root:root}; lá o que se preserva são as permissões (ver
     * {@link HostsFileManager#writeAtomically}).
     */
    public static void chownToRealUserIfSudo(Path path) {
        Optional<String> realUser = realUserName();
        if (realUser.isEmpty()) {
            return;
        }
        try {
            if (!Files.exists(path)) {
                return;
            }
            UserPrincipalLookupService lookup =
                    path.getFileSystem().getUserPrincipalLookupService();
            UserPrincipal user = lookup.lookupPrincipalByName(realUser.get());
            Files.setOwner(path, user);
            try {
                GroupPrincipal group = lookup.lookupPrincipalByGroupName(realUser.get());
                PosixFileAttributeView view =
                        Files.getFileAttributeView(path, PosixFileAttributeView.class);
                if (view != null) {
                    view.setGroup(group);
                }
            } catch (Exception ignored) {
                // Grupo homônimo pode não existir; o dono já foi corrigido.
            }
        } catch (Exception ignored) {
            // Best-effort: sem permissão, FS não-POSIX, usuário desconhecido, etc.
        }
    }

    /**
     * Home do usuário real, mesmo sob sudo. Package-private para testes via
     * reflexão? Mantido público para transparência e testabilidade.
     */
    public static Path getRealUserHome() {
        String sudoUser = System.getenv("SUDO_USER");
        if (sudoUser != null && !sudoUser.isBlank()
                && !"root".equals(sudoUser.strip().toLowerCase(Locale.ROOT))) {
            String user = sudoUser.strip();
            // Layouts comuns; verifica existência antes de assumir.
            Path linuxHome = Paths.get("/home", user);
            if (Files.isDirectory(linuxHome)) {
                return linuxHome;
            }
            Path macHome = Paths.get("/Users", user);
            if (Files.isDirectory(macHome)) {
                return macHome;
            }
            // Melhor esforço: mesmo sem o diretório existir, prefere /home/<user>
            // no Linux para não cair em /root silenciosamente.
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            if (os.contains("mac")) {
                return macHome;
            }
            return linuxHome;
        }
        return Paths.get(System.getProperty("user.home"));
    }
}
