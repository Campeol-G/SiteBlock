package com.sitelock.blocker;

import java.io.IOException;

/**
 * Abstração da estratégia de bloqueio.
 *
 * <p>Ponto de extensão para a Etapa 2 (servidor DNS p/ outros dispositivos
 * da rede): basta criar, por exemplo, {@code com.sitelock.dns.DnsBlocker
 * implements SiteBlocker} e compor/injetar junto ao {@code HostsFileBlocker}
 * no serviço — sem tocar no CLI. Nesta etapa só existe o bloqueio local
 * via {@code /etc/hosts}.
 */
public interface SiteBlocker {

    /** Bloqueia o domínio canônico (sem www; o implementador cobre as variantes). */
    void block(String domain) throws IOException;

    /** Remove o bloqueio do domínio canônico. */
    void unblock(String domain) throws IOException;
}
