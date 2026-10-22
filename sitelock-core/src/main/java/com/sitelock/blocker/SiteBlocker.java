package com.sitelock.blocker;

import java.io.IOException;
import java.util.List;

/** Estratégia de bloqueio. Implementação atual: arquivo hosts do sistema. */
public interface SiteBlocker {

    /** Bloqueia o domínio (o implementador cobre as variantes com/sem www). */
    void block(String domain) throws IOException;

    /** Remove o bloqueio do domínio. */
    void unblock(String domain) throws IOException;

    // POR QUE: sem default de propósito — a interface tem uma única implementação,
    // e forçar o compilador a achar os dois métodos é mais barato que caçar um
    // UnsupportedOperationException em produção.
    /**
     * Bloqueia vários domínios de uma vez.
     */
    void blockAll(List<String> domains) throws IOException;

    /** Remove o bloqueio de vários domínios de uma vez. */
    void unblockAll(List<String> domains) throws IOException;
}
