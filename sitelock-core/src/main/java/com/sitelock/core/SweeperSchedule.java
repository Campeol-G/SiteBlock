package com.sitelock.core;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Quanto o daemon de expiração dorme entre uma varredura e outra.
 *
 * <p>Três regras brigam entre si, e o desenho é o equilíbrio delas:
 *
 * <ul>
 *   <li><b>Piso de 1s</b> — um prazo que vence em 500ms não pode virar
 *       sono de 500ms: o SO arredonda para baixo e o daemon acordaria antes.
 *       Também nunca gera sono zero ou negativo.</li>
 *   <li><b>Teto ({@code --interval}, 60s)</b> — é o que faz um bloqueio criado
 *       em outro terminal ser notado em no máximo um minuto, sem precisar de
 *       watcher de arquivo.</li>
 *   <li><b>Intervalo ocioso</b> — sem nenhum temporário ativo, dormir 1s é
 *       reler o disco a cada segundo a noite inteira para não descobrir nada.</li>
 * </ul>
 *
 * <p>Puro: sem I/O e sem {@code Clock}. O instante de referência entra no
 * método.
 */
public final class SweeperSchedule {

    /** Piso do sono: nunca menos que isto, para não girar embouçado. */
    public static final Duration MIN_SLEEP = Duration.ofSeconds(1);

    private SweeperSchedule() {
    }

    /**
     * @param state       bloqueio atual (não é modificado)
     * @param now         instante de referência
     * @param maxInterval teto do sono ({@code --interval})
     * @param idleInterval sono quando não há temporário ativo
     * @return quanto dormir antes da próxima varredura
     */
    public static Duration nextSleep(Map<String, BlockEntry> state, Instant now,
            Duration maxInterval, Duration idleInterval) {
        Instant proxima = proximaExpiracaoAtiva(state, now);
        if (proxima == null) {
            return idleInterval;
        }
        Duration restante = Duration.between(now, proxima);
        if (restante.compareTo(MIN_SLEEP) < 0) {
            return MIN_SLEEP;
        }
        if (restante.compareTo(maxInterval) > 0) {
            return maxInterval;
        }
        return restante;
    }

    /**
     * O menor {@code expiresAt} ainda no futuro.
     *
     * <p>Entradas vencidas são ignoradas de propósito: o {@code sweepExpired()}
     * roda antes do sono, então uma que sobrou é porque o hosts não pôde ser
     * escrito — e nesse caso o próprio daemon trata isso dormindo o intervalo
     * ocioso. Sem este filtro, ela puxaria o sono para o piso de 1s e o daemon
     * passaria o dia relendo um arquivo em que não consegue escrever.
     */
    static Instant proximaExpiracaoAtiva(Map<String, BlockEntry> state, Instant now) {
        if (state == null || state.isEmpty()) {
            return null;
        }
        Instant melhor = null;
        for (BlockEntry entry : state.values()) {
            if (entry == null || entry.getExpiresAt() == null || entry.isExpired(now)) {
                continue;
            }
            if (melhor == null || entry.getExpiresAt().isBefore(melhor)) {
                melhor = entry.getExpiresAt();
            }
        }
        return melhor;
    }
}