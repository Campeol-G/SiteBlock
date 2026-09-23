package com.sitelock.core;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser estrito de durações para a flag {@code --for}.
 *
 * <p>Formato aceito (decisão documentada, resposta do usuário na Etapa 1):
 * apenas minutos/horas/dias minúsculos e colados, sem espaços.
 * Exemplos válidos: {@code 30m}, {@code 2h}, {@code 1d}, {@code 1h30m},
 * {@code 1d2h30m}. Qualquer outra coisa (segundos, semanas, maiúsculas,
 * espaços, valores negativos, zero) é rejeitada com mensagem + exemplo.
 */
public final class DurationParser {

    private static final Pattern PATTERN =
            Pattern.compile("^(?:(\\d+)d)?(?:(\\d+)h)?(?:(\\d+)m)?$");

    public static final String FORMAT_HELP =
            "Formato esperado: <numero>d<h><m> colados, ex.: 30m, 2h, 1h30m, 1d, 1d2h30m (apenas m/h/d minúsculos, sem espaços)";

    private DurationParser() {
    }

    /**
     * @param input texto de {@code --for}
     * @return duração correspondente
     * @throws IllegalArgumentException se o formato for inválido
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
            return total;
        } catch (ArithmeticException | NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Duração fora do intervalo suportado: '" + input + "'. " + FORMAT_HELP, e);
        }
    }

    /**
     * Formata um tempo restante de forma legível para o comando {@code status}.
     * Ex.: "1d 2h 3m", "2h 5m", "30m", "45s". Partes zeradas são omitidas
     * (exceto quando tudo é zero, caso em que retorna "0s").
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
        // Mostra segundos apenas se não houver dias/horas (evita "1d 2h 3m 4s" poluído),
        // ou se o total for menor que 1 minuto.
        if (seconds > 0 && (days == 0 && hours == 0)) {
            sb.append(seconds).append("s ");
        }
        if (sb.length() == 0) {
            return "0s";
        }
        return sb.toString().trim();
    }
}
