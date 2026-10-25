package com.sitelock.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Generates the autostart artifact text for each OS.
 *
 * <p>Pure: no path is resolved in here. Everything arrives already absolute via
 * {@link AutostartPaths} — resolving in the generator would resolve at the wrong
 * moment, when the daemon runs as root without {@code SUDO_USER}.
 */
public final class AutostartArtifact {

    /** Task name on Windows. */
    public static final String TASK_NAME = "sitelock-sweeper";
    /** LaunchDaemon label on macOS. */
    public static final String LABEL = "com.sitelock.sweeper";
    /** Unit name on Linux. */
    public static final String UNIT_NAME = "sitelock-sweeper.service";

    private static final int DEFAULT_INTERVAL_SECONDS = 60;

    private AutostartArtifact() {
    }

    /**
     * The daemon command, as tokens.
     *
     * <p>Shared by the three artifacts — it is the list that becomes the ExecStart, the
     * ProgramArguments or the {@code /tr}. Having a single source prevents the case
     * where macOS gets the right path and Linux gets the wrong one.
     *
     * <p>The shape of the line is assembled by {@link DaemonCommandLine}, which is also
     * what the {@code daemon start} fork uses: the unit and the fork need to
     * produce the <b>same</b> invocation, otherwise one works and the other does not.
     */
    public static List<String> commandLine(AutostartPaths p) {
        List<String> properties = new ArrayList<>();
        properties.add("-Dsitelock.stateDir=" + p.stateDir().toAbsolutePath());
        properties.add("-Dsitelock.hostsFile=" + p.hostsFile().toAbsolutePath());
        if (p.realUserName() != null && !p.realUserName().isBlank()) {
            properties.add("-Dsitelock.user=" + p.realUserName());
        }
        return DaemonCommandLine.build(p.javaBin(), p.jarPath(), p.classpath(), properties,
                List.of("daemon", "__run-daemon", "--interval",
                        String.valueOf(DEFAULT_INTERVAL_SECONDS)));
    }

    /** systemd unit, for {@code /etc/systemd/system/sitelock-sweeper.service}. */
    public static String systemdUnit(AutostartPaths p) {
        StringBuilder sb = new StringBuilder();
        sb.append("[Unit]\n");
        sb.append("Description=SiteBlock: releases expired blocks on its own\n");
        sb.append("Documentation=https://github.com/Campeol-G/SiteBlock\n");
        sb.append("\n[Service]\n");
        sb.append("Type=simple\n");
        sb.append("ExecStart=").append(execStart(p)).append('\n');
        sb.append("Restart=always\n");
        sb.append("RestartSec=5\n");
        // A system unit on purpose: no User=, because without elevation the daemon
        // cannot remove anything from the hosts file — which is all it does.
        sb.append("\n[Install]\n");
        sb.append("WantedBy=multi-user.target\n");
        return sb.toString();
    }

    /** LaunchDaemon plist, for {@code /Library/LaunchDaemons}. */
    public static String launchdPlist(AutostartPaths p) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" "
                + "\"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n");
        sb.append("<plist version=\"1.0\">\n<dict>\n");
        sb.append("  <key>Label</key>\n  <string>").append(LABEL).append("</string>\n");
        sb.append("  <key>ProgramArguments</key>\n  <array>\n");
        for (String arg : commandLine(p)) {
            sb.append("    <string>").append(escapeXml(arg)).append("</string>\n");
        }
        sb.append("  </array>\n");
        sb.append("  <key>RunAtLoad</key>\n  <true/>\n");
        sb.append("  <key>KeepAlive</key>\n  <true/>\n");
        sb.append("</dict>\n</plist>\n");
        return sb.toString();
    }

    /**
     * The {@code schtasks} command that installs the task.
     *
     * <p>Triggers on <b>ONLOGON</b>, not ONSTART: {@code /ru SYSTEM} would change
     * the process identity, and every file in the state directory would end up
     * owned by SYSTEM.
     *
     * <p>{@code ONLOGON} with {@code HIGHEST} gives the root the daemon needs without
     * that cost. The only case lost is "enabled, nobody logged in" — and without a session there
     * is no browser to be blocked.
     */
    public static String schtasksCommand(AutostartPaths p) {
        return String.join(" ", schtasksArgs(p));
    }

    /** The same command as a token list, for the installer to run directly. */
    public static List<String> schtasksArgs(AutostartPaths p) {
        List<String> args = new ArrayList<>();
        args.add("schtasks");
        args.add("/create");
        args.add("/sc");
        args.add("ONLOGON");
        args.add("/rl");
        args.add("HIGHEST");
        args.add("/tn");
        args.add(TASK_NAME);
        args.add("/tr");
        args.add(quotedTaskCommand(p));
        // /f overwrites the existing task; without it, a second `daemon enable`
        // fails because the task already exists.
        args.add("/f");
        return args;
    }

    /** Windows command to remove the task. */
    public static List<String> schtasksDeleteArgs() {
        return List.of("schtasks", "/delete", "/tn", TASK_NAME, "/f");
    }

    private static String quotedTaskCommand(AutostartPaths p) {
        // The /tr is a single argument: quotes on the outside, and doubled
        // quotes inside — that is cmd.exe's rule, not the Unix shell's.
        StringBuilder sb = new StringBuilder("\"");
        for (String arg : commandLine(p)) {
            sb.append(arg.contains(" ") ? "\\\"\"" + arg + "\\\"\" " : arg).append(' ');
        }
        sb.append('"');
        return sb.toString().trim();
    }

    private static String execStart(AutostartPaths p) {
        StringBuilder sb = new StringBuilder();
        for (String arg : commandLine(p)) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            // systemd does not do shell splitting: the space would break the directive.
            sb.append(arg.contains(" ") ? "\"" + arg + "\"" : arg);
        }
        return sb.toString();
    }

    private static String escapeXml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}