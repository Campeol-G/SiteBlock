package com.sitelock.core;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser de durações para a flag {@code --for}. O formato aceito está em
 * {@link #FORMAT_HELP}.
 */
public final class DurationParser {

    private static final Pattern PATTERN =
            Pattern.compile("^(?:(\\d+)d)?(?:(\\d+)h)?(?:(\\d+)m)?$");

    public static final String FORMAT_HELP =
            "Formato esperado: <numero>d<h><m> colados, ex.: 30m, 2h, 1h30m, 1d, 1d2h30m (apenas m/h/d minúsculos, sem espaços)";

    // POR QUE: sem teto explícito, uma entrada válida para o Duration estoura
    // no Instant.plus (DateTimeException) ou no Duration.toMillis (ArithmeticException).
    // 1.000 anos cabe com folga nos dois e é muito mais do que qualquer uso real.
    public static final long MAX_DAYS = 365_000;
    public static final Duration MAX_DURATION = Duration.ofDays(MAX_DAYS);
    public static final String LIMIT_HELP = "Máximo: " + MAX_DAYS + "d (1000 anos).";

    private DurationParser() {
    }

    /**
     * @param input texto de {@code --for}
     * @throws IllegalArgumentException se o texto não estiver no formato, for
     *         zero ou exceder o intervalo suportado
     */
    public static Duration parse(String input) {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("Duração vazia. " + FORMAT_HELP);
        }
        Matcher m = PATTERN.matcher(input);
        if (!m.matches()) {
            throw new IllegalArgumentException("Duração inválida: '" + input + "'. " + FORMAT_HELP);
        }
        String d = m.group(1);
        String h = m.group(2);
        String min = m.group(3);
        if (d == null && h == null && min == null) {
            throw new IllegalArgumentException("Duração inválida: '" + input + "'. " + FORMAT_HELP);
        }
        try {
            long days = d == null ? 0 : Long.parseLong(d);
            long hours = h == null ? 0 : Long.parseLong(h);
            long minutes = min == null ? 0 : Long.parseLong(min);
            Duration total = Duration.ofDays(days).plusHours(hours).plusMinutes(minutes);
            if (total.isZero() || total.isNegative()) {
                throw new IllegalArgumentException(
                        "Duração deve ser maior que zero: '" + input + "'. " + FORMAT_HELP);
            }
            if (total.compareTo(MAX_DURATION) > 0) {
                throw foraDoIntervalo(input, null);
            }
            return total;
        } catch (ArithmeticException | NumberFormatException e) {
            throw foraDoIntervalo(input, e);
        }
    }

    private static IllegalArgumentException foraDoIntervalo(String input, Exception cause) {
        return new IllegalArgumentException(
                "Duração fora do intervalo suportado: '" + input + "'. "
                        + FORMAT_HELP + " " + LIMIT_HELP,
                cause);
    }

    /**
     * Formata tempo restante para o status. Ex.: "1d 2h 3m", "30m", "45s".
     */
    public static String formatRemaining(Duration remaining) {
        if (remaining == null || remaining.isZero() || remaining.isNegative()) {
            return "0s";
        }
        long totalSeconds = remaining.getSeconds();
        long days = totalSeconds / 86_400;
        long hours = (totalSeconds % 86_400) / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;

        StringBuilder sb = new StringBuilder();
        if (days > 0) {
            sb.append(days).append("d ");
        }
        if (hours > 0) {
            sb.append(hours).append("h ");
        }
        if (minutes > 0) {
            sb.append(minutes).append("m ");
        }
        if (seconds > 0 && (days == 0 && hours == 0)) {
            sb.append(seconds).append("s ");
        }
        if (sb.length() == 0) {
            return "0s";
        }
        return sb.toString().trim();
    }
}
