package com.sitelock.dns;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Random;

/**
 * Fuzz do parser: pacote nenhum pode derrubar o servidor.
 *
 * <p>O parse de consulta já era auditado (400 mil pacotes aleatórios, zero
 * AIOOBE). O leitor da section answer — que segue ponteiro de compressão, coisa
 * que o parse de consulta rejeita de propósito — precisa da mesma prova: o
 * upstream é a rede, e a resposta dele não é confiável.
 */
class DnsPacketParserFuzzTest {

    /** Pacotes por rodada (a auditoria do parse de consulta usou 400 mil). */
    private static final int PACOTES = 400_000;

    /** Semente fixa: uma falha aqui tem que ser reproduzível. */
    private static final long SEMENTE = 20261021L;

    @Test
    void pacoteAleatorioNuncaQuebraOParserDeConsulta() {
        Random r = new Random(SEMENTE);
        for (int i = 0; i < PACOTES; i++) {
            byte[] pacote = new byte[1 + r.nextInt(600)];
            r.nextBytes(pacote);
            consome(pacote);
        }
    }

    /**
     * Resposta válida (cadeia de CNAMEs, nomes comprimidos) com bytes trocados
     * e cortes: é o caminho que o leitor da section answer percorre de verdade.
     */
    @Test
    void respostaMutadaNuncaQuebraOLetorDaAnswer() {
        Random r = new Random(SEMENTE + 1);
        byte[] base = respostaValida();
        for (int i = 0; i < PACOTES; i++) {
            byte[] pacote = base.clone();
            int mutacoes = 1 + r.nextInt(4);
            for (int m = 0; m < mutacoes; m++) {
                int pos = r.nextInt(pacote.length);
                if (r.nextInt(3) == 0) {
                    // ponteiro de compressão apontando para o nada
                    pacote[pos] = (byte) 0xC0;
                    if (pos + 1 < pacote.length) {
                        pacote[pos + 1] = (byte) r.nextInt(256);
                    }
                } else {
                    pacote[pos] = (byte) r.nextInt(256);
                }
            }
            if (r.nextInt(4) == 0) {
                pacote = Arrays.copyOf(pacote, r.nextInt(pacote.length + 1));
            }
            consome(pacote);
        }
    }

    /**
     * Passa o pacote pelos dois leitores: o de consulta e o da section answer.
     * Nenhum dos dois pode lançar exceção de runtime (AIOOBE, NPE, loop).
     */
    private static void consome(byte[] pacote) {
        try {
            DnsPacketParser.parse(pacote, pacote.length);
        } catch (DnsParseException e) {
            // pacote malformado: resultado esperado
        } catch (RuntimeException e) {
            throw new AssertionError("o parser de consulta explodiu: " + e, e);
        }
        try {
            DnsPacketParser.nomesDaAnswer(pacote, pacote.length);
        } catch (RuntimeException e) {
            throw new AssertionError("o leitor da section answer explodiu: " + e, e);
        }
    }

    /** Resposta com cadeia de CNAMEs, montada com nomes comprimidos. */
    private static byte[] respostaValida() {
        byte[] consulta = DnsWire.query(0x1234, "alias.test", DnsQuery.TYPE_A);
        return DnsWire.respostaEmCadeia(consulta,
                new String[]{"alias.test", "meio.test", "alvo.test"},
                DnsQuery.TYPE_A, new byte[]{1, 2, 3, 4});
    }
}
