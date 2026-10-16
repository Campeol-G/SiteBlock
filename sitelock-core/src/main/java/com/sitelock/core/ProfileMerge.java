package com.sitelock.core;

import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;

/**
 * Regra de mesclagem de um perfil sobre o estado atual.
 *
 * <p>Formalmente, tratando "permanente" como infinito, o resultado é
 * {@code max(quanto resta agora, o que o perfil oferece)}. O perfil só pode
 * <b>aumentar</b> o prazo.
 *
 * <pre>
 * | estado atual          | perfil oferece | ação    |
 * |-----------------------|----------------|---------|
 * | ausente               | 8h             | ADDED   |
 * | ausente               | permanente     | ADDED   |
 * | temporário, 30min     | 8h             | RAISED  |
 * | temporário, 10h       | 8h             | KEPT    |
 * | temporário, 8h        | 8h             | KEPT    |
 * | temporário, 30min     | permanente     | RAISED  |
 * | permanente            | 8h             | KEPT    |
 * | permanente            | permanente     | KEPT    |
 * </pre>
 *
 * <p>Tres consequências que tornam a operação segura de repetir: aplicar o
 * mesmo perfil N vezes só produz {@code KEPT}; aplicar um perfil mais fraco
 * sobre um setup mais forte não faz estrago; e um {@code KEPT} <b>não</b> é um
 * no-op interno — devolve a mesma instância do {@link BlockEntry}, com o mesmo
 * {@code createdAt} e o mesmo {@code expiresAt}. Reescrever o mapa do zero faria
 * um bloqueio de 10h virar "10h a partir de agora", exatamente o encurtamento
 * que a regra proíbe.
 *
 * <p>Puro: sem I/O e sem {@code Clock}. O instante de referência entra no
 * método, que é o padrão já usado no resto do core.
 */
public final class ProfileMerge {

    /** O que aconteceu com um domínio. */
    public enum Action {
        /** Não estava bloqueado; o perfil criou o bloqueio. */
        ADDED,
        /** Estava bloqueado com prazo menor; o perfil estendeu. */
        RAISED,
        /** O estado atual já era mais forte; nada muda. */
        KEPT
    }

    /** Uma decisão por domínio do perfil. */
    public record Decision(String domain, Action action, BlockEntry entry) {
    }

    private ProfileMerge() {
    }

    /**
     * @param state  estado atual de bloqueio (não é modificado)
     * @param profile perfil a aplicar
     * @param now    instante de referência
     * @return uma decisão por domínio do perfil, inclusive os {@code KEPT}, em
     *         ordem alfabética
     */
    public static Map<String, Decision> merge(Map<String, BlockEntry> state,
            Profile profile, Instant now) {
        Map<String, Decision> decisions = new TreeMap<>();
        if (profile == null || profile.getEntries() == null) {
            return decisions;
        }
        for (Map.Entry<String, ProfileEntry> pe : profile.getEntries().entrySet()) {
            String domain = pe.getKey();
            Long offeredMillis = pe.getValue() == null ? null : pe.getValue().getDurationMillis();
            BlockEntry current = state == null ? null : state.get(domain);

            // Entrada expirada que sobrou (o sweep roda antes, mas sem permissão
            // no hosts ele não limpa nada) conta como ausente: re-bloquear a partir
            // de um prazo morto não renasce o prazo, inventa um novo.
            if (current != null && current.isExpired(now)) {
                current = null;
            }

            if (current == null) {
                decisions.put(domain, new Decision(domain, Action.ADDED,
                        new BlockEntry(domain, now, offeredMillis == null ? null : now.plusMillis(offeredMillis))));
                continue;
            }
            if (current.getExpiresAt() == null) {
                // Já permanente: nada é mais forte que infinito.
                decisions.put(domain, new Decision(domain, Action.KEPT, current));
                continue;
            }
            long remaining = current.getExpiresAt().toEpochMilli() - now.toEpochMilli();
            if (offeredMillis == null) {
                decisions.put(domain, new Decision(domain, Action.RAISED,
                        new BlockEntry(domain, current.getCreatedAt(), null)));
            } else if (offeredMillis > remaining) {
                decisions.put(domain, new Decision(domain, Action.RAISED,
                        new BlockEntry(domain, current.getCreatedAt(), now.plusMillis(offeredMillis))));
            } else {
                decisions.put(domain, new Decision(domain, Action.KEPT, current));
            }
        }
        return decisions;
    }
}