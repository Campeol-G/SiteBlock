package com.sitelock.core;

import java.io.IOException;
import java.util.List;

/**
 * Executa um comando do sistema e devolve o exit code.
 *
 * <p>Existe como interface própria para que {@link DnsCacheFlusher} e
 * {@link AutostartInstaller} possam ser testados sem nenhum binário real no
 * PATH — e para que a escolha de executor seja uma, não duas.
 */
@FunctionalInterface
public interface CommandRunner {

    int run(List<String> command) throws IOException;
}