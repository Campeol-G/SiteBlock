package com.sitelock.core;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * Implementação padrão de {@link HostsFileLocator}.
 *
 * <ul>
 *   <li>Linux/macOS: {@code /etc/hosts}.</li>
 *   <li>Windows: {@code %SystemRoot%\System32\drivers\etc\hosts}.
 *       A letra do disco <b>não</b> é assumida como fixa: usa a variável de
 *       ambiente {@code SystemRoot} (ex.: {@code C:\Windows}). Se a variável
 *       estiver ausente/vazia (JVMs antigas, ambiente mínimo), cai para
 *       {@code C:\Windows} e isso fica documentado no README como limitação.</li>
 * </ul>
 */
public final class DefaultHostsFileLocator implements HostsFileLocator {

    private static final String FALLBACK_WINDOWS_ROOT = "C:\\Windows";

    private final OsType osType;
    private final Supplier<String> systemRootProvider;

    /** Usa o SO real e {@code System.getenv("SystemRoot")}. */
    public DefaultHostsFileLocator() {
        this(OsType.detect(), () -> System.getenv("SystemRoot"));
    }

    /** Injeta o SO (para testes); SystemRoot vem do ambiente real. */
    public DefaultHostsFileLocator(OsType osType) {
        this(osType, () -> System.getenv("SystemRoot"));
    }

    /** Injeta o valor de {@code os.name} (para testes). */
    public DefaultHostsFileLocator(String osName) {
        this(OsType.detect(osName), () -> System.getenv("SystemRoot"));
    }

    /**
     * Injeta SO + provedor de SystemRoot (para testes do fallback Windows
     * sem depender do ambiente da máquina).
     */
    DefaultHostsFileLocator(OsType osType, Supplier<String> systemRootProvider) {
        this.osType = osType == null ? OsType.UNKNOWN : osType;
        this.systemRootProvider = systemRootProvider == null ? () -> null : systemRootProvider;
    }

    @Override
    public Path getHostsPath() {
        if (osType.isWindows()) {
            return windowsHostsPath(systemRootProvider.get());
        }
        // Linux, macOS e UNKNOWN (conservador: padrão Unix).
        return Paths.get("/etc/hosts");
    }

    /** Monta o caminho Windows a partir do SystemRoot informado. */
    static Path windowsHostsPath(String systemRoot) {
        String root = systemRoot == null || systemRoot.isBlank()
                ? FALLBACK_WINDOWS_ROOT
                : systemRoot.strip();
        // Remove barra final para evitar "C:\Windows\\System32...".
        while (root.endsWith("\\") || root.endsWith("/")) {
            root = root.substring(0, root.length() - 1);
        }
        return Paths.get(root, "System32", "drivers", "etc", "hosts");
    }

    /** Exposto para testes afirmarem o SO resolvido. */
    OsType getOsType() {
        return osType;
    }

    /** Normaliza uma string de SO para exibição em logs (minúsculas). */
    static String normalizeForLog(String osName) {
        return osName == null ? "" : osName.toLowerCase(Locale.ROOT);
    }
}
