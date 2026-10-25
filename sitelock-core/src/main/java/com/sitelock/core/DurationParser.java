package com.sitelock.core;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Duration parser for the {@code --for} flag. Expected format is in
 * {@link #FORMAT_HELP}.
 */
public final class DurationParser {

    private static final Pattern PATTERN =
            Pattern.compile("^(?:(\\d+)d)?(?:(\\d+)h)?(?:(\\d+)m)?$");

    public static final String FORMAT_HELP =
            "Expected format: <number>d<h><m> concatenated, e.g. 30m, 2h, 1h30m, 1d, 1d2h30m (only lowercase m/h/d, no spaces)";

    // WHY: no explicit cap; a valid duration entry overflows
    // in Instant.plus (DateTimeException) or Duration.toMillis (ArithmeticException).
    // 1,000 years fits easily in both and is far more than any real use.
    public static final long MAX_DAYS = 365_000;
    public static final Duration MAX_DURATION = Duration.ofDays(MAX_DAYS);
    public static final String LIMIT_HELP = "Max: " + MAX_DAYS + "d (1000 years).";

    private DurationParser() {
    }

    /**
     * @param input text from {@code --for}
     * @throws IllegalArgumentException if the text is not in format, is
     *         zero or exceeds the supported range
     */
    public static Duration parse(String input) {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("Empty duration. " + FORMAT_HELP);
        }
        Matcher m = PATTERN.matcher(input);
        if (!m.matches()) {
            throw new IllegalArgumentException("Invalid duration: '" + input + "'. " + FORMAT_HELP);
        }
        String d = m.group(1);
        String h = m.group(2);
        String min = m.group(3);
        if (d == null && h == null && min == null) {
            throw new IllegalArgumentException("Invalid duration: '" + input + "'. " + FORMAT_HELP);
        }
        try {
            long days = d == null ? 0 : Long.parseLong(d);
            long hours = h == null ? 0 : Long.parseLong(h);
            long minutes = min == null ? 0 : Long.parseLong(min);
            Duration total = Duration.ofDays(days).plusHours(hours).plusMinutes(minutes);
            if (total.isZero() || total.isNegative()) {
                throw new IllegalArgumentException(
                        "Duration must be greater than zero: '" + input + "'. " + FORMAT_HELP);
            }
            if (total.compareTo(MAX_DURATION) > 0) {
                throw outOfRange(input, null);
            }
            return total;
        } catch (ArithmeticException | NumberFormatException e) {
            throw outOfRange(input, e);
        }
    }

    private static IllegalArgumentException outOfRange(String input, Exception cause) {
        return new IllegalArgumentException(
                "Duration out of supported range: '" + input + "'. "
                        + FORMAT_HELP + " " + LIMIT_HELP,
                cause);
    }

    /**
     * Formats remaining time for status. E.g. "1d 2h 3m", "30m", "45s".
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
