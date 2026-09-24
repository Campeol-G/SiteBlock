package com.sitelock.core;

import java.nio.file.Path;

/**
 * Retorna o caminho do arquivo hosts do sistema operacional.
 *
 * <p>Só o caminho varia por SO — toda a lógica de edição (marcadores
 * {@code # SITELOCK-START} / {@code # SITELOCK-END}, escrita atômica via
 * temporário + rename em {@link HostsFileManager}) é reutilizada sem duplicação.
 */
public interface HostsFileLocator {

    /** Caminho do arquivo hosts no SO corrente (ou simulado, em testes). */
    Path getHostsPath();
}
