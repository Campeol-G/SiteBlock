package com.sitelock.core;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Normalização e validação de domínios.
 *
 * Remove protocolo, credenciais, porta, caminho/query/fragmento, converte
 * para minúsculas, tira ponto final e um prefixo "www." inicial.
 * O bloqueio sempre cobre "dominio" + "www.dominio".
 */
public final class DomainUtils {

    // Rótulo DNS: 1-63 chars, alfanumérico/hífen, sem começar/terminar com hífen.
    private static final String LABEL = "(?!-)[A-Za-z0-9-]{1,63}(?<!-)";
    private static final Pattern DOMAIN_PATTERN =
            Pattern.compile("^" + LABEL + "(\\." + LABEL + ")+$");
    // IPv4 simples — rejeitado explicitamente (bloquear IP não faz sentido aqui).
    private static final Pattern IPV4_PATTERN =
            Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");

    private DomainUtils() {
    }

    public record NormalizationResult(String domain, boolean normalized, String original) {
        public NormalizationResult {
            Objects.requireNonNull(domain);
            Objects.requireNonNull(original);
        }
    }

    /**
     * Normaliza e valida.
     * @throws IllegalArgumentException se vazio, com espaços internos ou formato inválido
     */
    public static NormalizationResult normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(
                    "Domínio vazio. Informe um domínio válido, ex.: tiktok.com");
        }
        String original = raw;
        String s = raw.trim();
        boolean changed = !s.equals(raw) || !s.equals(s.toLowerCase(Locale.ROOT));

        if (s.contains(" ") || s.contains("\t") || s.contains("\n")) {
            throw new IllegalArgumentException(
                    "Domínio inválido (contém espaços): '" + original + "'. Ex.: tiktok.com");
        }

        int schemeIdx = s.indexOf("://");
        if (schemeIdx >= 0) {
            s = s.substring(schemeIdx + 3);
            changed = true;
            if (s.isBlank()) {
                throw new IllegalArgumentException(
                        "Domínio inválido: '" + original + "'. Ex.: tiktok.com");
            }
        }

        int atIdx = s.lastIndexOf('@');
        if (atIdx >= 0) {
            s = s.substring(atIdx + 1);
            changed = true;
        }

        int cut = indexOfFirst(s, '/', '?', '#');
        if (cut >= 0) {
            s = s.substring(0, cut);
            changed = true;
        }

        if (s.indexOf(':') == s.lastIndexOf(':') && s.contains(":")) {
            s = s.substring(0, s.indexOf(':'));
            changed = true;
        }

        String lower = s.toLowerCase(Locale.ROOT);
        if (!lower.equals(s)) {
            changed = true;
        }
        s = lower;
        while (s.endsWith(".")) {
            s = s.substring(0, s.length() - 1);
            changed = true;
        }

        if (s.equals("www") || s.startsWith("www.")) {
            if (s.equals("www")) {
                throw new IllegalArgumentException(
                        "Domínio inválido: '" + original + "'. Ex.: tiktok.com");
            }
            s = s.substring("www.".length());
            changed = true;
        }

        validate(s, original);

        return new NormalizationResult(s, changed, original);
    }

    /** Apenas valida, sem normalizar. */
    public static boolean isValid(String domain) {
        if (domain == null || domain.isBlank()) {
            return false;
        }
        if (!DOMAIN_PATTERN.matcher(domain).matches()) {
            return false;
        }
        if (IPV4_PATTERN.matcher(domain).matches()) {
            return false;
        }
        if (domain.length() > 253) {
            return false;
        }
        // Exige TLD com ao menos uma letra e ao menos 2 chars
        // (rejeita "exemplo.123" e "exemplo.c").
        String tld = domain.substring(domain.lastIndexOf('.') + 1);
        if (tld.length() < 2 || !tld.matches(".*[a-z].*")) {
            return false;
        }
        return true;
    }

    private static void validate(String domain, String original) {
        if (domain.isBlank()) {
            throw new IllegalArgumentException(
                    "Domínio inválido: '" + original + "'. Ex.: tiktok.com");
        }
        if (IPV4_PATTERN.matcher(domain).matches()) {
            throw new IllegalArgumentException(
                    "Endereços IP não são aceitos; informe um domínio, ex.: tiktok.com (recebido: '"
                            + original + "')");
        }
        if (!DOMAIN_PATTERN.matcher(domain).matches() || domain.length() > 253) {
            throw new IllegalArgumentException(
                    "Domínio inválido: '" + original + "' (interpretado como '" + domain
                            + "'). Ex.: tiktok.com");
        }
        String tld = domain.substring(domain.lastIndexOf('.') + 1);
        if (tld.length() < 2 || !tld.matches(".*[a-z].*")) {
            throw new IllegalArgumentException(
                    "Domínio inválido (TLD suspeito): '" + original + "'. Ex.: tiktok.com");
        }
    }

    private static int indexOfFirst(String s, char... chars) {
        int best = -1;
        for (char c : chars) {
            int i = s.indexOf(c);
            if (i >= 0 && (best < 0 || i < best)) {
                best = i;
            }
        }
        return best;
    }
}
