package com.sitelock.core;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * Default implementation of {@link HostsFileLocator}.
 *
 * <ul>
 *   <li>Linux/macOS: {@code /etc/hosts}.</li>
 *   <li>Windows: {@code %SystemRoot%\System32\drivers\etc\hosts}.
 *       The drive letter is <b>not</b> assumed to be fixed: it uses the
 *       environment variable {@code SystemRoot} (e.g. {@code C:\Windows}). If the variable
 *       is missing/empty (old JVMs, minimal environment), it falls back to
 *       {@code C:\Windows}, and that is documented in the README as a limitation.</li>
 * </ul>
 */
public final class DefaultHostsFileLocator implements HostsFileLocator {

    private static final String FALLBACK_WINDOWS_ROOT = "C:\\Windows";

    private final OsType osType;
    private final Supplier<String> systemRootProvider;

    /** Uses the real OS and {@code System.getenv("SystemRoot")}. */
    public DefaultHostsFileLocator() {
        this(OsType.detect(), () -> System.getenv("SystemRoot"));
    }

    /** Injects the OS (for tests); SystemRoot comes from the real environment. */
    public DefaultHostsFileLocator(OsType osType) {
        this(osType, () -> System.getenv("SystemRoot"));
    }

    /** Injects the {@code os.name} value (for tests). */
    public DefaultHostsFileLocator(String osName) {
        this(OsType.detect(osName), () -> System.getenv("SystemRoot"));
    }

    // Injects OS + SystemRoot provider (for Windows fallback tests
    // without depending on the machine's environment).
    DefaultHostsFileLocator(OsType osType, Supplier<String> systemRootProvider) {
        this.osType = osType == null ? OsType.UNKNOWN : osType;
        this.systemRootProvider = systemRootProvider == null ? () -> null : systemRootProvider;
    }

    @Override
    public Path getHostsPath() {
        if (osType.isWindows()) {
            return windowsHostsPath(systemRootProvider.get());
        }
        // Linux, macOS and UNKNOWN (conservative: Unix default).
        return Paths.get("/etc/hosts");
    }

    static Path windowsHostsPath(String systemRoot) {
        String root = systemRoot == null || systemRoot.isBlank()
                ? FALLBACK_WINDOWS_ROOT
                : systemRoot.strip();
        // Strip the trailing separator to avoid "C:\Windows\\System32...".
        while (root.endsWith("\\") || root.endsWith("/")) {
            root = root.substring(0, root.length() - 1);
        }
        return Paths.get(root, "System32", "drivers", "etc", "hosts");
    }

    // Exposed so tests can assert the resolved OS.
    OsType getOsType() {
        return osType;
    }

    static String normalizeForLog(String osName) {
        return osName == null ? "" : osName.toLowerCase(Locale.ROOT);
    }
}
