package com.sitelock.core;

import java.util.ArrayList;
import java.util.List;

/**
 * The JVM invocation that brings up the expiration daemon.
 *
 * <p>Single source for the four places that start the daemon: the systemd
 * unit, the macOS LaunchDaemon, the Windows task and the {@code daemon
 * start} fork. When the JVM command line was assembled in each of them, each
 * had its own shape — and each shape had its own defect.
 *
 * <p><b>The rule the JVM imposes:</b> with {@code -jar <jar>} the JVM reads the
 * {@code Main-Class} from the manifest and <b>stops interpreting options</b> — everything
 * after the jar path is a program argument. Two consequences,
 * both of which have already broken the daemon in production:
 *
 * <ul>
 *   <li>the class name after {@code -jar} is left as a dangling argument, and
 *       picocli rejects the whole invocation with "Unmatched argument at
 *       index 0" and exit code 2;</li>
 *   <li>a {@code -D} after {@code -jar} stops being a property and becomes
 *       an argument — "Unknown options", exit code 2 again.</li>
 * </ul>
 *
 * <p>Without {@code -jar} there is no manifest: then the JVM needs the class name, and
 * the {@code -D}s can come before or after {@code -cp}, because the classpath is an
 * option that consumes exactly one argument. That is why the class name is
 * appended only on the classpath path.
 *
 * <p>Final order: {@code java} → properties → {@code -jar} <i>or</i>
 * {@code -cp} + class → program arguments.
 */
public final class DaemonCommandLine {

    /** Main class; it is the {@code Main-Class} of the fat-jar manifest. */
    public static final String MAIN_CLASS = "com.sitelock.SiteLockApplication";

    private DaemonCommandLine() {
    }

    /**
     * @param javaBin      the JVM executable
     * @param jarPath      path of the fat-jar; null or empty = use the classpath
     * @param classpath    fallback classpath, used only when there is no jar
     * @param jvmProperties {@code -D...} properties, already in final form
     * @param appArgs      program arguments (what comes after the class)
     */
    public static List<String> build(String javaBin, String jarPath, String classpath,
                                     List<String> jvmProperties, List<String> appArgs) {
        List<String> cmd = new ArrayList<>();
        cmd.add(javaBin);
        if (jvmProperties != null) {
            cmd.addAll(jvmProperties);
        }
        if (jarPath != null && !jarPath.isBlank()) {
            cmd.add("-jar");
            cmd.add(jarPath);
            // No class name: with -jar, the manifest already says which one.
        } else {
            cmd.add("-cp");
            cmd.add(classpath == null ? "." : classpath);
            cmd.add(MAIN_CLASS);
        }
        if (appArgs != null) {
            cmd.addAll(appArgs);
        }
        return cmd;
    }
}