package com.sitelock.cli;

import com.sitelock.blocker.HostsFileBlocker;
import com.sitelock.blocker.SiteBlocker;
import com.sitelock.core.BlockService;
import com.sitelock.core.BlockStore;
import com.sitelock.core.DnsCacheFlusher;
import com.sitelock.core.PermissionAdvisor;
import com.sitelock.core.SiteLockPaths;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;

/** Helpers compartilhados pelos comandos CLI. */
final class CliSupport {

    static final String PROFILES_FILE_NAME = "profiles.json";

    private CliSupport() {
    }

    static Path resolveHostsPath(String override) {
        if (override != null && !override.isBlank()) {
            return Paths.get(override);
        }
        return SiteLockPaths.getHostsPath();
    }

    static Path resolveStateFile(String override) {
        if (override != null && !override.isBlank()) {
            return Paths.get(override);
        }
        return SiteLockPaths.getStateFile();
    }

    static Path resolveBackupFile(String stateFileOverride) {
        if (stateFileOverride != null && !stateFileOverride.isBlank()) {
            Path sf = Paths.get(stateFileOverride);
            return sf.resolveSibling("hosts.backup");
        }
        return SiteLockPaths.getBackupFile();
    }

    /**
     * Diretório de estado considerando o override de {@code --state-file}.
     *
     * <p>Sem isto, um comando leria o {@code daemon.json} do {@code ~/.sitelock}
     * real enquanto operava no arquivo de teste — ou, pior, na máquina de quem
     * está testando. Mesmo raciocínio de {@link DnsDaemonState#stateDirFor(String)}.
     */
    static java.nio.file.Path stateDirFor(String stateFileOverride) {
        if (stateFileOverride != null && !stateFileOverride.isBlank()) {
            java.nio.file.Path sf = Paths.get(stateFileOverride);
            java.nio.file.Path parent = sf.getParent();
            return parent != null ? parent : Paths.get(".");
        }
        return SiteLockPaths.getStateDir();
    }

    /**
     * Onde mora o {@code profiles.json}: irmão do {@code blocks.json}, pelo mesmo
     * motivo do {@code hosts.backup} e do {@code hosts.lock} — o {@code --state-file}
     * dos testes aponta o diretório inteiro, sem precisar de uma flag nova.
     */
    static Path resolveProfilesFile(String stateFileOverride) {
        if (stateFileOverride != null && !stateFileOverride.isBlank()) {
            return Paths.get(stateFileOverride).resolveSibling(PROFILES_FILE_NAME);
        }
        return SiteLockPaths.getStateDir().resolve(PROFILES_FILE_NAME);
    }

    static BlockService newService(String hostsOverride, String stateOverride) {
        Path hostsPath = resolveHostsPath(hostsOverride);
        Path stateFile = resolveStateFile(stateOverride);
        Path backupFile = resolveBackupFile(stateOverride);
        Path hostsLock = stateFile.resolveSibling("hosts.lock");
        BlockStore store = new BlockStore(stateFile);
        HostsFileBlocker blocker = new HostsFileBlocker(hostsPath, backupFile, hostsLock);
        return new BlockService(store, blocker, Clock.systemUTC(), hostsPath);
    }

    /**
     * Com o blocker injetado, para os testes que precisam que a escrita no hosts
     * falhe — é o único jeito de provar que o rollback do {@code profile apply}
     * acontece, já que ninguém vai tornar o {@code /etc/hosts} ilegível no teste.
     */
    static BlockService newService(String hostsOverride, String stateOverride, SiteBlocker blocker) {
        Path stateFile = resolveStateFile(stateOverride);
        return new BlockService(new BlockStore(stateFile), blocker, Clock.systemUTC(),
                resolveHostsPath(hostsOverride));
    }

    /**
     * Dica de elevação específica por SO (componente central: PermissionAdvisor).
     * No Windows instrui "Executar como administrador"; no Unix, "sudo".
     */
    static String elevationHint(String subcommand) {
        return new PermissionAdvisor().elevationHint(SiteLockPaths.getHostsPath(), subcommand);
    }

    /**
     * @deprecated mantido para compatibilidade com testes/código existente;
     *             delega para {@link #elevationHint(String)}.
     */
    @Deprecated
    static String sudoHint(String subcommand) {
        return elevationHint(subcommand);
    }

    /**
     * Monta a dica com sudo usando o caminho real do binário, porque
     * ~/.local/bin costuma ficar fora do secure_path do sudo.
     * No Windows o prefixo "sudo" não se aplica (ver PermissionAdvisor),
     * mas este método é mantido para compatibilidade dos testes.
     */
    static String sudoCommand(String subcommand) {
        String bin = System.getProperty("siteblock.bin", "siteblock");
        String display = "/usr/local/bin/siteblock".equals(bin) ? "siteblock" : bin;
        if (display.contains(" ")) {
            display = "\"" + display + "\"";
        }
        String args = subcommand == null || subcommand.isBlank() ? "" : " " + subcommand;
        return "sudo " + display + args;
    }

    /**
     * Flush do cache DNS após modificação no hosts — best-effort.
     * Nunca lança exceção nem muda o exit code do comando; só avisa.
     */
    static void flushDnsBestEffort() {
        try {
            new DnsCacheFlusher().flushBestEffort();
        } catch (Exception ignored) {
        }
    }
}
