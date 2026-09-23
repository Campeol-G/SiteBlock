package com.sitelock.core;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Normalização + validação de domínios.
 *
 * <p>Decisão (resposta do usuário na Etapa 1 — "Normalizar tudo"):
 * <ul>
 *   <li>Remove protocolo ({@code https://}), credenciais ({@code user@}), porta
 *       ({@code :8080}), caminho/query/fragmento ({@code /feed?a=b#c}).</li>
 *   <li>Converte para minúsculas, remove espaços das bordas e ponto final
 *       ("tiktok.com." → "tiktok.com").</li>
 *   <li>Remove UM prefixo {@code www.} inicial ("www.tiktok.com" → "tiktok.com").
 *       Subdomínios diferentes de www são preservados
 *       ("m.tiktok.com" continua "m.tiktok.com").</li>
 *   <li>O bloqueio em si sempre cobre as DUAS variantes:
 *       {@code dominio} + {@code www.dominio} (ver {@code HostsFileManager}).</li>
 * </ul>
 *
 * <p>Se qualquer normalização acontecer, o chamador deve avisar o usuário
 * (ex.: "normalizado de 'HTTPS://WWW.TikTok.com/feed' para 'tiktok.com'").
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

    /** Resultado da normalização. */
    public record NormalizationResult(String domain, boolean normalized, String original) {
        public NormalizationResult {
            Objects.requireNonNull(domain);
            Objects.requireNonNull(original);
        }
    }

    /**
     * Normaliza e valida.
     *
     * @param raw entrada do usuário
     * @return domínio canônico minúsculo, sem www
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

        // 1. Espaços internos são sempre erro (ex.: "tik tok.com").
        if (s.contains(" ") || s.contains("\t") || s.contains("\n")) {
            throw new IllegalArgumentException(
                    "Domínio inválido (contém espaços): '" + original + "'. Ex.: tiktok.com");
        }

        // 2. Remove protocolo "scheme://", se houver.
        int schemeIdx = s.indexOf("://");
        if (schemeIdx >= 0) {
            s = s.substring(schemeIdx + 3);
            changed = true;
            if (s.isBlank()) {
                throw new IllegalArgumentException(
                        "Domínio inválido: '" + original + "'. Ex.: tiktok.com");
            }
        }

        // 3. Remove credenciais "user:pass@" se houver.
        int atIdx = s.lastIndexOf('@');
        if (atIdx >= 0) {
            s = s.substring(atIdx + 1);
            changed = true;
        }

        // 4. Corta caminho/query/fragmento: primeira ocorrência de / ? #.
        int cut = indexOfFirst(s, '/', '?', '#');
        if (cut >= 0) {
            s = s.substring(0, cut);
            changed = true;
        }

        // 5. Corta porta ":8080" (apenas se houver um único ':' — evita quebrar IPv6,
        // que de todo modo será rejeitado na validação).
        if (s.indexOf(':') == s.lastIndexOf(':') && s.contains(":")) {
            s = s.substring(0, s.indexOf(':'));
            changed = true;
        }

        // 6. Minúsculas + remove ponto(s) final(is).
        String lower = s.toLowerCase(Locale.ROOT);
        if (!lower.equals(s)) {
            changed = true;
        }
        s = lower;
        while (s.endsWith(".")) {
            s = s.substring(0, s.length() - 1);
            changed = true;
        }

        // 7. Remove UM "www." inicial.
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

    /** Apenas valida (sem normalizar). Útil para testes. */
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
