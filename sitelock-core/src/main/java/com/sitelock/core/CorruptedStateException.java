package com.sitelock.core;

import java.io.IOException;

/**
 * Um arquivo de estado existe mas não pôde ser interpretado.
 * O programa não apaga nem recria sozinho — a mensagem diz qual arquivo é; no
 * {@code blocks.json} o caminho é {@code siteblock reset}.
 */
public class CorruptedStateException extends IOException {

    public CorruptedStateException(String message, Throwable cause) {
        super(message, cause);
    }
}
