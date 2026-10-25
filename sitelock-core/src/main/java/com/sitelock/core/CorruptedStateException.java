package com.sitelock.core;

import java.io.IOException;

/**
 * A state file exists but could not be interpreted.
 * The program does not delete or recreate it on its own — the message says which
 * file it is; for {@code blocks.json} the fix is {@code siteblock reset}.
 */
public class CorruptedStateException extends IOException {

    public CorruptedStateException(String message, Throwable cause) {
        super(message, cause);
    }
}
