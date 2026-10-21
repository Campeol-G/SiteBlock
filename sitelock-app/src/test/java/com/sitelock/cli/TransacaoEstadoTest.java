package com.sitelock.cli;

import com.sitelock.blocker.SiteBlocker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Os comandos que escrevem estado têm que fazer isso <b>dentro</b> de uma
 * transação, com a trava do estado retida do começo ao fim.
 *
 * <p>Por fora dela, o {@code load} e o {@code save} de um mesmo comando ficam
 * em janelas separadas: um {@code block} feito em outro terminal nesse intervalo
 * é apagado do JSON — e o domínio continua no {@code /etc/hosts}, sem registro
 * em lugar nenhum. O sweep varre o JSON, então nunca limpa.
 *
 * <p>O teste é determinístico: o espião pergunta à trava do arquivo de estado
 * se ela está retida no momento em que o comando escreve no hosts.
 */
class TransacaoEstadoTest {

    @TempDir
    Path tmp;

    private Path hosts;

    private Path hosts() throws Exception {
        if (hosts == null) {
            hosts = tmp.resolve("hosts");
            Files.writeString(hosts, "127.0.0.1 localhost\n", StandardCharsets.UTF_8);
        }
        return hosts;
    }

    private String state() {
        return tmp.resolve("state/blocks.json").toString();
    }

    /**
     * true se a trava do arquivo de estado está retida agora.
     *
     * <p>Na mesma JVM, um {@code tryLock} sobre um arquivo já travado por ela
     * estoura {@link OverlappingFileLockException} — é assim que se descobre
     * que a transação está com a trava.
     */
    private static boolean travaDoEstadoEstaRetida(String stateFile) {
        Path lock = Path.of(stateFile).resolveSibling("blocks.json.lock");
        try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.WRITE)) {
            FileLock peguei = channel.tryLock();
            if (peguei == null) {
                // Outra JVM tem a trava: também conta como retida.
                return true;
            }
            peguei.release();
            return false;
        } catch (OverlappingFileLockException e) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    @Test
    void applyMantemATravaDoEstadoRetidaEnquantoEscreveNoHosts() throws Exception {
        assertEquals(0, new BlockCommand(List.of("tiktok.com"), "4h",
                hosts().toString(), state()).call());
        assertEquals(0, new ProfileSaveCommand("trabalho", false,
                hosts().toString(), state()).call());
        // O estado agora é mais fraco que a receita: o apply tem o que mudar.
        assertEquals(0, new BlockCommand(List.of("tiktok.com"), "30m",
                hosts().toString(), state()).call());

        boolean[] retida = {false};
        SiteBlocker espiao = new SiteBlocker() {
            @Override
            public void block(String domain) {
            }

            @Override
            public void unblock(String domain) {
            }

            @Override
            public void blockAll(List<String> domains) {
                retida[0] = travaDoEstadoEstaRetida(state());
            }

            @Override
            public void unblockAll(List<String> domains) {
            }
        };

        int exit = new ProfileApplyCommand("trabalho", hosts().toString(), state(), espiao).call();

        assertEquals(0, exit);
        assertTrue(retida[0],
                "a escrita no hosts tem que acontecer dentro da transação, com a trava retida");
    }
}
