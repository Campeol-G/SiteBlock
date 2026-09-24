package com.sitelock.core;

import java.util.Locale;

/**
 * Sistema operacional normalizado a partir de {@code os.name}.
 *
 * <p>Centraliza a detecção para que {@link HostsFileLocator},
 * {@link PermissionAdvisor} e {@link DnsCacheFlusher} decidam o comportamento
 * por SO num único ponto, em vez de espalhar {@code System.getProperty("os.name")}
 * pelo código.
 *
 * <p>Detecção case-insensitive, cobrindo variações comuns:
 * "Windows 10", "Windows 11", "Mac OS X", "Darwin", "Linux".
 */
public enum OsType {
    LINUX,
    MACOS,
    WINDOWS,
    UNKNOWN;

    /** Detecta a partir do {@code os.name} real da JVM. */
    public static OsType detect() {
        return detect(System.getProperty("os.name", ""));
    }

    /**
     * Detecta a partir de um valor injetado de {@code os.name}.
     * Existe para permitir testes unitários dos três ramos em qualquer máquina,
     * sem depender do SO onde o teste roda.
     *
     * <p>Ordem importa: "darwin" contém "win" como substring, então mac/darwin
     * é testado antes de win.
     */
    public static OsType detect(String osName) {
        String n = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (n.contains("mac") || n.contains("darwin")) {
            return MACOS;
        }
        if (n.contains("win")) {
            return WINDOWS;
        }
        if (n.contains("linux") || n.contains("nix") || n.contains("nux")) {
            return LINUX;
        }
        return UNKNOWN;
    }

    public boolean isWindows() {
        return this == WINDOWS;
    }

    public boolean isMac() {
        return this == MACOS;
    }

    /** Linux + macOS + UNKNOWN (comportamento Unix-like por padrão). */
    public boolean isUnixLike() {
        return this == LINUX || this == MACOS || this == UNKNOWN;
    }
}
