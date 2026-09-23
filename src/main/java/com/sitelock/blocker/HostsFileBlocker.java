package com.sitelock.blocker;

import com.sitelock.core.HostsFileManager;
import com.sitelock.core.SiteLockPaths;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Estratégia de bloqueio local via {@code /etc/hosts}.
 * (Etapa futura: {@code com.sitelock.dns.DnsBlocker} implementará
 * {@link SiteBlocker} para cobrir outros dispositivos da rede.)
 */
public class HostsFileBlocker implements SiteBlocker {

    private final Path hostsPath;
    private final Path backupPath;
    private final Path lockFile;

    public HostsFileBlocker(Path hostsPath, Path backupPath, Path lockFile) {
        this.hostsPath = hostsPath;
        this.backupPath = backupPath;
        this.lockFile = lockFile;
    }

    /** Usa os caminhos padrão ({@link SiteLockPaths}). */
    public HostsFileBlocker() {
        this(SiteLockPaths.getHostsPath(), SiteLockPaths.getBackupFile(),
                SiteLockPaths.getStateDir().resolve("hosts.lock"));
    }

    @Override
    public void block(String domain) throws IOException {
        HostsFileManager.blockInFile(hostsPath, backupPath, lockFile, domain);
    }

    @Override
    public void unblock(String domain) throws IOException {
        HostsFileManager.unblockInFile(hostsPath, backupPath, lockFile, domain);
    }
}
