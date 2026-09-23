package com.sitelock.cli;

import com.sitelock.blocker.HostsFileBlocker;
import com.sitelock.core.BlockService;
import com.sitelock.core.BlockStore;
import com.sitelock.core.SiteLockPaths;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;

/**
 * Helpers compartilhados pelos comandos CLI.
 */
final class CliSupport {

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

    static BlockService newService(String hostsOverride, String stateOverride) {
        Path hostsPath = resolveHostsPath(hostsOverride);
        Path stateFile = resolveStateFile(stateOverride);
        Path backupFile = resolveBackupFile(stateOverride);
        Path hostsLock = stateFile.resolveSibling("hosts.lock");
        BlockStore store = new BlockStore(stateFile);
        HostsFileBlocker blocker = new HostsFileBlocker(hostsPath, backupFile, hostsLock);
        return new BlockService(store, blocker, Clock.systemUTC(), hostsPath);
    }

    static String sudoHint(String subcommand) {
        return "Sem permissão de escrita em " + SiteLockPaths.getHostsPath()
                + ". Rode com sudo, ex.: " + sudoCommand(subcommand);
    }

    /**
     * Monta a invocação com sudo que realmente funciona no terminal.
     *
     * <p>Motivação: o {@code siteblock} costuma morar em {@code ~/.local/bin},
     * fora do {@code secure_path} do sudo — então um {@code sudo siteblock ...}
     * puro falha com "command not found". O wrapper informa seu caminho
     * absoluto via {@code -Dsiteblock.bin=...} e a dica usa esse caminho
     * (quando a instalação é em {@code /usr/local/bin}, o nome curto basta).
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
}
