package com.sitelock.blocker;

import java.io.IOException;
import java.util.List;

/** Blocking strategy. Current implementation: system hosts file. */
public interface SiteBlocker {

    /** Blocks the domain (the implementer covers variants with/without www). */
    void block(String domain) throws IOException;

    /** Removes the block for the domain. */
    void unblock(String domain) throws IOException;

    // WHY: no default on purpose — the interface has a single implementation,
    // and forcing the compiler to find both methods is cheaper than hunting an
    // UnsupportedOperationException in production.
    /**
     * Blocks several domains at once.
     */
    void blockAll(List<String> domains) throws IOException;

    /** Removes the block for several domains at once. */
    void unblockAll(List<String> domains) throws IOException;
}
