package com.sitelock.core;

import java.io.IOException;
import java.util.List;

/**
 * Runs a system command and returns the exit code.
 *
 * <p>It exists as its own interface so that {@link DnsCacheFlusher} and
 * {@link AutostartInstaller} can be tested without any real binary on the
 * PATH — and so there is a single executor contract, not two.
 */
@FunctionalInterface
public interface CommandRunner {

    int run(List<String> command) throws IOException;
}