package com.sitelock.core;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/**
 * Um domínio dentro de um perfil.
 *
 * <p>{@code durationMillis} é um {@code Long} porque {@code null} significa
 * "permanente" — o mesmo contrato de {@link BlockEntry#expiresAt}. Guardar
 * string ISO de duração não resolveria: {@code Duration} serializa no Jackson
 * como segundos decimais ("28800.000000000"), que é ambíguo de ler.
 *
 * <p>É a <b>duração restante no momento do save</b>, nunca o {@code expiresAt}
 * original: um perfil é uma receita, não um histórico. Guardar o instante
 * transformaria um perfil de 6 meses em "já vencido".
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ProfileEntry {

    private Long durationMillis;

    /** Construtor para o Jackson. */
    @JsonCreator
    public ProfileEntry(@JsonProperty("durationMillis") Long durationMillis) {
        this.durationMillis = durationMillis;
    }

    public ProfileEntry() {
    }

    /** {@code null} = bloqueio permanente. */
    public Long getDurationMillis() {
        return durationMillis;
    }

    public void setDurationMillis(Long durationMillis) {
        this.durationMillis = durationMillis;
    }

    /** Espelha {@link BlockEntry#isTemporaryAndActive(Instant)}: true se tem prazo. */
    public boolean isTemporary() {
        return durationMillis != null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ProfileEntry other)) {
            return false;
        }
        return Objects.equals(durationMillis, other.durationMillis);
    }

    @Override
    public int hashCode() {
        return Objects.hash(durationMillis);
    }

    @Override
    public String toString() {
        return "ProfileEntry{durationMillis=" + durationMillis + '}';
    }
}