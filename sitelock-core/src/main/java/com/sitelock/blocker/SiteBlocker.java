package com.sitelock.blocker;

import java.io.IOException;

/** Estratégia de bloqueio. Implementação atual: arquivo hosts do sistema. */
public interface SiteBlocker {

    /** Bloqueia o domínio (o implementador cobre as variantes com/sem www). */
    void block(String domain) throws IOException;

    /** Remove o bloqueio do domínio. */
    void unblock(String domain) throws IOException;
}
