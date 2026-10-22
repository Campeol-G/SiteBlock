package com.sitelock.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class DurationParserTest {

    @Test
    void parsesMinutesOnly() {
        assertEquals(Duration.ofMinutes(30), DurationParser.parse("30m"));
    }

    @Test
    void parsesHoursOnly() {
        assertEquals(Duration.ofHours(2), DurationParser.parse("2h"));
    }

    @Test
    void parsesDaysOnly() {
        assertEquals(Duration.ofDays(1), DurationParser.parse("1d"));
    }

    @Test
    void parsesCombinedHourMinute() {
        assertEquals(Duration.ofMinutes(90), DurationParser.parse("1h30m"));
    }

    @Test
    void parsesFullCombination() {
        Duration expected = Duration.ofDays(1).plusHours(2).plusMinutes(30);
        assertEquals(expected, DurationParser.parse("1d2h30m"));
    }

    @Test
    void parsesDaysAndMinutesWithoutHours() {
        assertEquals(Duration.ofDays(1).plusMinutes(5), DurationParser.parse("1d5m"));
    }

    @Test
    void acceptsTheMaximumSupportedDuration() {
        assertEquals(DurationParser.MAX_DURATION, DurationParser.parse("365000d"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"365001d", "365000d1m", "999999999999d",
            "999999999999999999999d"})
    void rejectsDurationsBeyondTheSupportedRange(String input) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> DurationParser.parse(input));
        assertTrue(e.getMessage().contains("'" + input + "'"), e.getMessage());
        assertTrue(e.getMessage().contains("fora do intervalo"), e.getMessage());
        assertTrue(e.getMessage().contains(DurationParser.LIMIT_HELP), e.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "30", "30s", "1w", "1H", "30M", "1h 30m",
            "1h30", "h30m", "-5m", "0m", "0h", "0d", "1d2h30m5s", "1.5h", "--for"})
    void rejectsInvalidFormats(String input) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> DurationParser.parse(input));
        // POR QUE: a mensagem tem que citar a entrada rejeitada. Sem isso a
        // asserção só conferia o exemplo fixo da ajuda — que vale para
        // qualquer entrada, e portanto não prova nada.
        assertTrue(e.getMessage().contains("'" + input + "'"),
                "a mensagem tem que citar a entrada rejeitada, mas foi: " + e.getMessage());
        assertTrue(e.getMessage().contains(DurationParser.FORMAT_HELP),
                "a mensagem tem que dizer o formato esperado, mas foi: " + e.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  "})
    void rejectsBlankInputSayingItIsEmpty(String input) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> DurationParser.parse(input));
        assertTrue(e.getMessage().contains("vazia"),
                "a mensagem tem que dizer que a duração está vazia, mas foi: " + e.getMessage());
        assertTrue(e.getMessage().contains(DurationParser.FORMAT_HELP),
                "a mensagem tem que dizer o formato esperado, mas foi: " + e.getMessage());
    }

    @Test
    void rejectsNull() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> DurationParser.parse(null));
        assertTrue(e.getMessage().contains("vazia"),
                "a mensagem tem que dizer que a duração está vazia, mas foi: " + e.getMessage());
        assertTrue(e.getMessage().contains(DurationParser.FORMAT_HELP),
                "a mensagem tem que dizer o formato esperado, mas foi: " + e.getMessage());
    }

    @Test
    void formatRemainingOmitsZeroParts() {
        assertEquals("1d 2h 3m", DurationParser.formatRemaining(
                Duration.ofDays(1).plusHours(2).plusMinutes(3)));
        assertEquals("2h 5m", DurationParser.formatRemaining(
                Duration.ofHours(2).plusMinutes(5)));
        assertEquals("30m", DurationParser.formatRemaining(Duration.ofMinutes(30)));
        assertEquals("45s", DurationParser.formatRemaining(Duration.ofSeconds(45)));
        assertEquals("0s", DurationParser.formatRemaining(Duration.ZERO));
    }
}
