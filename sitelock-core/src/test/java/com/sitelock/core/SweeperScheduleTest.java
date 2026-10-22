package com.sitelock.core;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Quanto o daemon dorme entre uma varredura e outra.
 *
 * <p>O teto ({@code --interval}, 60s por padrão) é o que faz um bloqueio criado
 * em outro terminal ser notado em no máximo um minuto, sem watcher de arquivo.
 * O piso de 1s evita que o daemon fique girando à espera de um prazo que está a
 * 500ms. E o intervalo ocioso é o que impede um daemon de passar a madrugada
 * relendo o disco a cada segundo sem nada vencendo.
 */
class SweeperScheduleTest {

    private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");
    private static final Duration MAX = Duration.ofSeconds(60);
    private static final Duration IDLE = Duration.ofMinutes(5);

    private static Duration sleep(Map<String, BlockEntry> state) {
        return SweeperSchedule.nextSleep(state, NOW, MAX, IDLE);
    }

    // Estado com um temporário que vence dentro de "segundos" segundos.
    private static Map<String, BlockEntry> expiringIn(long segundos) {
        return expiringInMillis(segundos * 1000L);
    }

    // Estado com um temporário que vence dentro de "emMillis" milissegundos.
    private static Map<String, BlockEntry> expiringInMillis(long emMillis) {
        Map<String, BlockEntry> state = new TreeMap<>();
        state.put("tiktok.com", new BlockEntry("tiktok.com", NOW.minusSeconds(60),
                NOW.plusMillis(emMillis)));
        return state;
    }

    @Test
    void noTemporariesSleepsTheIdleInterval() {
        assertEquals(IDLE, sleep(new TreeMap<>()));
    }

    @Test
    void permanentOnlySleepsTheIdleInterval() {
        Map<String, BlockEntry> state = new TreeMap<>();
        state.put("tiktok.com", new BlockEntry("tiktok.com", NOW, null));
        assertEquals(IDLE, sleep(state));
    }

    @Test
    void expiringSoonSleepsUntilThen() {
        assertEquals(Duration.ofSeconds(30), sleep(expiringIn(30)));
    }

    @Test
    void farExpiryCapsAtMaxInterval() {
        assertEquals(MAX, sleep(expiringIn(600)));
    }

    @Test
    void nearExpiryFloorsAtOneSecond() {
        assertEquals(Duration.ofSeconds(1), sleep(expiringInMillis(500)),
                "500ms de sono arredondariam para baixo e o daemon acordaria antes da hora");
    }

    @Test
    void expiryExactlyNowCountsAsExpired() {
        // isExpired(agora) é verdade quando o prazo é igual a agora: o sweep
        // desta iteração já o resolveu, então não há temporário ativo.
        assertEquals(IDLE, sleep(expiringInMillis(0)));
    }

    @Test
    void aFiredDeadlineNeverProducesZeroOrNegativeSleep() {
        assertEquals(IDLE, sleep(expiringInMillis(-5000)));
    }

    @Test
    void exactMaxIntervalIsUnchanged() {
        assertEquals(MAX, sleep(expiringIn(60)));
    }

    @Test
    void picksTheEarliestOfSeveral() {
        Map<String, BlockEntry> state = expiringIn(45);
        state.put("youtube.com", new BlockEntry("youtube.com", NOW, NOW.plusSeconds(20)));
        assertEquals(Duration.ofSeconds(20), sleep(state));
    }

    @Test
    void alreadyExpiredEntriesDoNotShortenTheSleep() {
        Map<String, BlockEntry> state = expiringIn(600);
        state.put("vencido.com", new BlockEntry("vencido.com", NOW.minusSeconds(7200),
                NOW.minusSeconds(3600)));
        assertEquals(MAX, sleep(state),
                "o sweep roda antes e remove os vencidos; nenhum deles pode puxar o sono para 1s");
    }

    @Test
    void customIdleIntervalIsHonoured() {
        assertEquals(Duration.ofMinutes(30),
                SweeperSchedule.nextSleep(new TreeMap<>(), NOW, MAX, Duration.ofMinutes(30)));
    }
}