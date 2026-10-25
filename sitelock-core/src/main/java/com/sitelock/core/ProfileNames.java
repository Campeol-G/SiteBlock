package com.sitelock.core;

import java.util.regex.Pattern;

/**
 * Validation of a profile name.
 *
 * <p>The name becomes a JSON object key and may appear in logs and in file
 * names. A list of forbidden characters always leaves a gap — Windows uses
 * different separators, and nobody remembers to forbid what never came up. A
 * regex has no gaps: it defines exactly what is acceptable.
 */
public final class ProfileNames {

    private static final Pattern VALID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    private ProfileNames() {
    }

    public static boolean isValid(String name) {
        return name != null && VALID.matcher(name).matches();
    }

    /**
     * @throws IllegalArgumentException (not {@code IllegalStateException}) with a
     *         message ready for the end user: the caller does not need to
     *         spread exit codes around
     */
    public static String require(String name) {
        if (!isValid(name)) {
            throw new IllegalArgumentException("Invalid profile name: '" + name
                    + "'. Use only letters, numbers, '-' and '_' (up to 64 characters).");
        }
        return name;
    }
}