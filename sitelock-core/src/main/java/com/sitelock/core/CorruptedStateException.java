package com.sitelock.core;

import java.io.IOException;

/**
 * blocks.json existe mas não pôde ser interpretado.
 * O programa não apaga nem recria sozinho; use {@code siteblock reset}.
 */
public class CorruptedStateException extends IOException {

    public CorruptedStateException(String message, Throwable cause) {
        super(message, cause);
    }
}
