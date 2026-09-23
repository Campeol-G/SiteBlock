package com.sitelock.core;

import java.io.IOException;

/**
 * Indica que {@code blocks.json} existe mas não pôde ser interpretado
 * (JSON inválido, tipos inesperados, etc.).
 *
 * <p>Decisão (resposta do usuário — modo não-interativo): o programa
 * <b>não</b> apaga nem recria o arquivo sozinho. Quem chamou deve abortar
 * a operação e orientar o usuário a apagar manualmente ou rodar
 * {@code siteblock reset} (ver {@code ResetCommand}).
 */
public class CorruptedStateException extends IOException {

    public CorruptedStateException(String message, Throwable cause) {
        super(message, cause);
    }
}
