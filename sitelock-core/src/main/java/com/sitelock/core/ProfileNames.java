package com.sitelock.core;

import java.util.regex.Pattern;

/**
 * Validação do nome de um perfil.
 *
 * <p>O nome vira chave de objeto JSON e pode aparecer em log e em nome de
 * arquivo. Uma lista de caracteres proibidos sempre deixa um buraco — o
 * Windows não usa os mesmos separadores, e ninguém lembra de proibir o que não
 *occurreu. Uma regex só não tem buraco: ela é a definição do que é aceitável.
 */
public final class ProfileNames {

    private static final Pattern VALID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    private ProfileNames() {
    }

    public static boolean isValid(String name) {
        return name != null && VALID.matcher(name).matches();
    }

    /**
     * @throws IllegalArgumentException (e não {@code IllegalStateException}) com
     *         mensagem pronta para o usuário final: o chamador não precisa
     *         espalhar código de saída
     */
    public static String require(String name) {
        if (!isValid(name)) {
            throw new IllegalArgumentException("Nome de perfil inválido: '" + name
                    + "'. Use apenas letras, números, '-' e '_' (até 64 caracteres).");
        }
        return name;
    }
}