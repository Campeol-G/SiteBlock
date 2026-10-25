package com.sitelock.core;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Normalization and validation of domains.
 *
 * Removes protocol, credentials, port, path/query/fragment, converts
 * to lowercase, strips trailing dot and an initial "www." prefix.
 * Blocking always covers "domain" + "www.domain".
 */
public final class DomainUtils {

    // DNS label: 1-63 chars, alphanumeric/hyphen, must not start/end with hyphen.
    private static final String LABEL = "(?!-)[A-Za-z0-9-]{1,63}(?<!-)";
    private static final Pattern DOMAIN_PATTERN =
            Pattern.compile("^" + LABEL + "(\\." + LABEL + ")+$");
    // Plain IPv4 — explicitly rejected (blocking an IP makes no sense here).
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
     * Normalizes and validates.
     * @throws IllegalArgumentException if it is empty, contains internal spaces, or is in an invalid format
     */
    public static NormalizationResult normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(
                    "Empty domain. Provide a valid domain, e.g.: tiktok.com");
        }
        String original = raw;
        String s = raw.trim();
        boolean changed = !s.equals(raw) || !s.equals(s.toLowerCase(Locale.ROOT));

        if (s.contains(" ") || s.contains("\t") || s.contains("\n")) {
            throw new IllegalArgumentException(
                    "Invalid domain (contains spaces): '" + original + "'. E.g.: tiktok.com");
        }

        int schemeIdx = s.indexOf("://");
        if (schemeIdx >= 0) {
            s = s.substring(schemeIdx + 3);
            changed = true;
            if (s.isBlank()) {
                throw new IllegalArgumentException(
                        "Invalid domain: '" + original + "'. E.g.: tiktok.com");
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

        if (s.equals("www")) {
            throw new IllegalArgumentException(
                    "Invalid domain: '" + original + "'. E.g.: tiktok.com");
        }
        // WHY: "www.com" is a legitimate domain, but without the prefix "com" is left, which
        // is not a domain. The prefix is only removed when what remains is still a valid domain.
        while (s.startsWith("www.")) {
            String withoutPrefix = s.substring("www.".length());
            if (!isValid(withoutPrefix)) {
                break;
            }
            s = withoutPrefix;
            changed = true;
        }

        validate(s, original);

        return new NormalizationResult(s, changed, original);
    }

    /** Validation only, without normalizing. */
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
        if (isOnlyWww(domain)) {
            return false;
        }
        if (domain.length() > 253) {
            return false;
        }
        // Requires a TLD with at least one letter and at least 2 chars
        // (rejects "example.123" and "example.c").
        String tld = domain.substring(domain.lastIndexOf('.') + 1);
        if (tld.length() < 2 || !tld.matches(".*[a-z].*")) {
            return false;
        }
        return true;
    }

    private static void validate(String domain, String original) {
        if (domain.isBlank()) {
            throw new IllegalArgumentException(
                    "Invalid domain: '" + original + "'. E.g.: tiktok.com");
        }
        if (IPV4_PATTERN.matcher(domain).matches()) {
            throw new IllegalArgumentException(
                    "IP addresses are not accepted; provide a domain, e.g.: tiktok.com (received: '"
                            + original + "')");
        }
        if (!DOMAIN_PATTERN.matcher(domain).matches() || domain.length() > 253) {
            throw new IllegalArgumentException(
                    "Invalid domain: '" + original + "' (interpreted as '" + domain
                            + "'). E.g.: tiktok.com");
        }
        if (isOnlyWww(domain)) {
            throw new IllegalArgumentException(
                    "Invalid domain: '" + original + "'. E.g.: tiktok.com");
        }
        String tld = domain.substring(domain.lastIndexOf('.') + 1);
        if (tld.length() < 2 || !tld.matches(".*[a-z].*")) {
            throw new IllegalArgumentException(
                    "Invalid domain (suspicious TLD): '" + original + "'. E.g.: tiktok.com");
        }
    }

    // "www"-only labels are not a domain: the line "127.0.0.1 www" would be left in the hosts file.
    private static boolean isOnlyWww(String domain) {
        for (String label : domain.split("\\.")) {
            if (!label.equals("www")) {
                return false;
            }
        }
        return true;
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