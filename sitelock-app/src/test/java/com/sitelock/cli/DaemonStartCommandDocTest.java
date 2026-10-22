package com.sitelock.cli;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * O javadoc do {@code daemon start} tem que descrever o que o código faz.
 *
 * <p>A checagem de identidade do daemon deixou de ser só "o PID está vivo":
 * o registro guarda o instante de início do processo, e é ele que prova que
 * o PID vivo ainda é o nosso. Um javadoc que afirma o contrário não é
 * documento: é mentira, e a próxima pessoa que mexer no arquivo confia
 * nela.
 */
class DaemonStartCommandDocTest {

    @Test
    void javadocDoRegistroOrfaoDescreveAChecagemAtual() throws Exception {
        String fonte = Files.readString(fonteDoComando(), StandardCharsets.UTF_8);
        String javadoc = javadocDe(fonte, "descartarRegistroOrfao");

        assertFalse(javadoc.contains("a checagem é só"),
                "a checagem não é só o PID: " + javadoc);
        assertTrue(javadoc.contains("instante de início"),
                "o javadoc precisa falar do instante de início do processo: " + javadoc);
        assertTrue(javadoc.contains("PID"),
                "o javadoc precisa continuar falando do PID: " + javadoc);
    }

    // O arquivo-fonte do comando, subindo a árvore até achar.
    private static Path fonteDoComando() {
        Path relativo = Path.of("src/main/java/com/sitelock/cli/DaemonStartCommand.java");
        Path atual = Path.of("").toAbsolutePath();
        while (atual != null) {
            Path candidato = atual.resolve(relativo);
            if (Files.exists(candidato)) {
                return candidato;
            }
            atual = atual.getParent();
        }
        throw new IllegalStateException("fonte do DaemonStartCommand não encontrada");
    }

    // O bloco de javadoc que precede o método.
    private static String javadocDe(String fonte, String metodo) {
        int declaracao = fonte.lastIndexOf(metodo);
        int inicio = fonte.lastIndexOf("/**", declaracao);
        int fim = fonte.indexOf("*/", inicio);
        return fonte.substring(inicio, fim + 2);
    }
}
