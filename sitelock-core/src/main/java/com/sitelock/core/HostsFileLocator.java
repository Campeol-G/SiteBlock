package com.sitelock.core;

import java.nio.file.Path;

/**
 * Returns the path of the operating system's hosts file.
 *
 * <p>Only the path varies by OS — all the editing logic (markers
 * {@code # SITELOCK-START} / {@code # SITELOCK-END}, atomic write via
 * temp file + rename in {@link HostsFileManager}) is reused without duplication.
 */
public interface HostsFileLocator {

    /** Path of the hosts file on the current (or simulated, in tests) OS. */
    Path getHostsPath();
}
