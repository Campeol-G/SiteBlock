package com.sitelock.cli;

import com.sitelock.blocker.SiteBlocker;
import com.sitelock.core.BlockEntry;
import com.sitelock.core.BlockStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * O {@code profile unblock} grava o estado dentro de uma transação.
 *
 * <p>Ele solta vários domínios de uma vez, e o estado é o lugar que diz o que
 * está bloqueado. Se o {@code load} e o {@code save} ficarem fora da mesma
 * trava, o bloqueio que outro terminal fez no meio do caminho é apagado do
 * JSON — e o domínio continua no {@code /etc/hosts}, sem registro em lugar
 * nenhum. O sweep varre o JSON, então nunca limpa.
 *
 * <p>Os dois testes são determinísticos: um pergunta à trava do arquivo de
 * estado se ela está retida no momento em que o comando escreve no hosts, e o
 * outro faz o outro terminal gravar no instante em que a pessoa digita o SIM.
 */
class ProfileUnblockTransacaoTest {

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

    private String blocks() throws Exception {
        return Files.readString(Path.of(state()), StandardCharsets.UTF_8);
    }

    @Test
    void aEscritaNoHostsAconteceDentroDaTransacaoDoEstado() throws Exception {
        assertEquals(0, new BlockCommand(List.of("tiktok.com"), null,
                hosts().toString(), state()).call());
        assertEquals(0, new ProfileSaveCommand("trabalho", false,
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
            }

            @Override
            public void unblockAll(List<String> domains) {
                retida[0] = travaDoEstadoEstaRetida(state());
            }
        };

        int exit = new ProfileUnblockCommand("trabalho", true, hosts().toString(),
                state(), "SIM", espiao).call();

        assertEquals(0, exit);
        assertTrue(retida[0],
                "a escrita no hosts tem que acontecer dentro da transação, com a trava retida");
    }

    @Test
    void oBloqueioFeitoEmOutroTerminalNaoPodeSumir() throws Exception {
        assertEquals(0, new BlockCommand(List.of("tiktok.com"), null,
                hosts().toString(), state()).call());
        assertEquals(0, new ProfileSaveCommand("trabalho", false,
                hosts().toString(), state()).call());

        // A pessoa vai digitar o SIM. No instante em que o comando lê a
        // resposta, outro terminal bloqueia novo.com: é a janela em que o
        // estado é carregado e ainda não foi gravado.
        InputStream original = System.in;
        System.setIn(new StdinQueGrava(() -> {
            try {
                bloqueiaComoOutroTerminal("novo.com");
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, "SIM\n"));

        int exit;
        try {
            exit = new ProfileUnblockCommand("trabalho", true, hosts().toString(),
                    state(), null).call();
        } finally {
            System.setIn(original);
        }

        assertEquals(0, exit);
        String estado = blocks();
        assertFalse(estado.contains("tiktok.com"), estado);
        assertTrue(estado.contains("novo.com"),
                "o bloqueio de outro terminal não pode ser apagado: " + estado);
    }

    /** Simula outro terminal rodando {@code siteblock block <dominio>}. */
    private void bloqueiaComoOutroTerminal(String dominio) throws IOException {
        BlockStore store = new BlockStore(Path.of(state()));
        Map<String, BlockEntry> atual = store.load();
        atual.put(dominio, new BlockEntry(dominio, Instant.now(), null));
        store.save(atual);
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

    /**
     * Um stdin que dispara uma ação na primeira leitura.
     *
     * <p>É o gancho determinístico do teste: a leitura da confirmação é o
     * ponto em que o comando está com o estado carregado e ainda não gravado.
     */
    private static final class StdinQueGrava extends InputStream {

        private final Runnable aoLer;
        private final byte[] conteudo;
        private int pos;
        private boolean disparou;

        StdinQueGrava(Runnable aoLer, String conteudo) {
            this.aoLer = aoLer;
            this.conteudo = conteudo.getBytes(StandardCharsets.UTF_8);
        }

        private void dispara() {
            if (!disparou) {
                disparou = true;
                aoLer.run();
            }
        }

        @Override
        public int read() {
            dispara();
            return pos < conteudo.length ? conteudo[pos++] & 0xFF : -1;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            dispara();
            if (pos >= conteudo.length) {
                return -1;
            }
            int n = Math.min(len, conteudo.length - pos);
            System.arraycopy(conteudo, pos, b, off, n);
            pos += n;
            return n;
        }
    }
}
