package com.sitelock.core;

import java.nio.file.Path;

/**
 * Everything the autostart artifact needs, already resolved and absolute.
 *
 * <p>These values are resolved at {@code daemon enable} time and embedded in
 * the artifact. Resolving later is too late: systemd units, LaunchDaemon and
 * {@code schtasks /ru SYSTEM} run as root <b>without {@code SUDO_USER}</b>.
 *
 * <p>In that case, {@code user.home} is {@code /root} or the SYSTEM profile: the daemon
 * would read a {@code blocks.json} that is not the one from
 * {@code sudo siteblock block}, and the whole feature becomes a silent no-op.
 *
 * @param stateDir      state directory of the real user
 * @param hostsFile     system hosts file
 * @param javaBin       absolute path to java
 * @param jarPath       absolute path to the fat-jar, or null to use the classpath
 * @param classpath     fallback classpath, when there is no jar
 * @param realUserName  user to whom ownership of the written files is handed back;
 *                      null when unknown
 * @param os            target operating system
 */
public record AutostartPaths(Path stateDir, Path hostsFile, String javaBin,
                            String jarPath, String classpath, String realUserName, OsType os) {
}