package com.sitelock.blocker;

import com.sitelock.core.HostsFileManager;
import com.sitelock.core.SiteLockPaths;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** Local blocking via the system hosts file (e.g. /etc/hosts on Linux/macOS). */
public class HostsFileBlocker implements SiteBlocker {

    private final Path hostsPath;
    private final Path backupPath;
    private final Path lockFile;

    public HostsFileBlocker(Path hostsPath, Path backupPath, Path lockFile) {
        this.hostsPath = hostsPath;
        this.backupPath = backupPath;
        this.lockFile = lockFile;
    }

    /** Uses the default paths. */
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

    @Override
    public void blockAll(List<String> domains) throws IOException {
        HostsFileManager.blockInFile(hostsPath, backupPath, lockFile, domains);
    }

    @Override
    public void unblockAll(List<String> domains) throws IOException {
        HostsFileManager.unblockInFile(hostsPath, backupPath, lockFile, domains);
    }
}
