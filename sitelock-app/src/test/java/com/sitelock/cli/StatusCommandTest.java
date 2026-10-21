package com.sitelock.cli;

import com.sitelock.core.BlockEntry;
import com.sitelock.core.BlockStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * O status conferindo o estado contra o hosts.
 *
 * <p>Há dois desfechos opostos quando os dois discordam. Estado sem linha no
 * hosts: o site está livre e o status afirma que está bloqueado. Linha no
 * hosts sem registro no estado: o site está preso para sempre, porque o
 * sweep varre o JSON e nunca o hosts — e a recuperação seria editar o
 * /etc/hosts à mão, que é o que este programa existe para dispensar.
 */
class StatusCommandTest {

    @TempDir
    Path tmp;

    private Path hosts;

    /** O hosts de teste; criado uma vez só, para não apagar o bloco escrito. */
    private Path hosts() throws Exception {
        if (hosts == null) {
            hosts = tmp.resolve("hosts");
            Files.writeString(hosts, "127.0.0.1 localhost\n", StandardCharsets.UTF_8);
        }
        return hosts;
    }

    private String state() {
        return tmp.resolve("estado/blocks.json").toString();
    }

    /** Grava um estado com um único domínio, sem passar pelo block. */
    private void gravarEstado(String dominio, Instant expiresAt) throws Exception {
        Map<String, BlockEntry> estado = new TreeMap<>();
        estado.put(dominio, new BlockEntry(dominio, Instant.now(), expiresAt));
        Files.createDirectories(tmp.resolve("estado"));
        new BlockStore(Path.of(state())).save(estado);
    }

    private Resultado rodar(Chamavel c) throws Exception {
        PrintStream outOriginal = System.out;
        PrintStream errOriginal = System.err;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream fake = new PrintStream(buffer, true, StandardCharsets.UTF_8);
        System.setOut(fake);
        System.setErr(fake);
        AtomicInteger codigo = new AtomicInteger(-1);
        try {
            codigo.set(c.chamar());
        } finally {
            System.setOut(outOriginal);
            System.setErr(errOriginal);
        }
        return new Resultado(codigo.get(), buffer.toString(StandardCharsets.UTF_8));
    }

    private record Resultado(int codigo, String texto) {
    }

    @FunctionalInterface
    private interface Chamavel {
        int chamar() throws Exception;
    }

    @Test
    void estadoEmDiaComOHostsNaoSinalizaNada() throws Exception {
        assertEquals(0, new BlockCommand(List.of("tiktok.com"), null,
                hosts().toString(), state()).call());

        Resultado r = rodar(() -> new StatusCommand(hosts().toString(), state()).call());

        assertEquals(0, r.codigo());
        assertFalse(r.texto().contains("Divergência"),
                "estado e hosts em dia não têm divergência: " + r.texto());
    }

    @Test
    void estadoSemLinhaNoHostsSinalizaSemEfeito() throws Exception {
        gravarEstado("tiktok.com", null);

        Resultado r = rodar(() -> new StatusCommand(hosts().toString(), state()).call());

        assertEquals(0, r.codigo());
        assertTrue(r.texto().contains("sem efeito"), r.texto());
        assertTrue(r.texto().contains("tiktok.com"), r.texto());
        assertTrue(r.texto().contains("Domínios bloqueados (1):"),
                "a lista de bloqueados não muda: " + r.texto());
    }

    @Test
    void linhaNoHostsSemRegistroSinalizaPresoSemRegistro() throws Exception {
        assertEquals(0, new BlockCommand(List.of("tiktok.com"), null,
                hosts().toString(), state()).call());
        // O registro se perde (state apagado, sobrescrito ou corrompido): o hosts
        // continua com a linha e o site fica preso para sempre.
        Files.delete(Path.of(state()));

        Resultado r = rodar(() -> new StatusCommand(hosts().toString(), state()).call());

        assertEquals(0, r.codigo());
        assertTrue(r.texto().contains("preso sem registro"), r.texto());
        assertTrue(r.texto().contains("tiktok.com"), r.texto());
        assertTrue(r.texto().contains("siteblock block tiktok.com"),
                "a saída precisa dizer como resolver: " + r.texto());
        assertTrue(r.texto().contains("siteblock unblock tiktok.com"), r.texto());
        assertFalse(r.texto().contains("Domínios bloqueados"),
                "o status não pode inventar bloqueio que não está no estado: " + r.texto());
    }

    @Test
    void estadoComWwwNaoViraPresoSemRegistro() throws Exception {
        // O bloqueio do www e do dominio é o mesmo: as linhas são as mesmas.
        gravarEstado("www.tiktok.com", null);
        Files.writeString(hosts(), """
                127.0.0.1 localhost
                # SITELOCK-START
                127.0.0.1 tiktok.com
                127.0.0.1 www.tiktok.com
                ::1 tiktok.com
                ::1 www.tiktok.com
                # SITELOCK-END
                """, StandardCharsets.UTF_8);

        Resultado r = rodar(() -> new StatusCommand(hosts().toString(), state()).call());

        assertFalse(r.texto().contains("preso sem registro"),
                "o www e o dominio são o mesmo bloqueio: " + r.texto());
    }

    /**
     * Sem permissão de leitura no hosts, o status avisa e não vira erro.
     *
     * <p>ATENÇÃO: o teste depende de permissão POSIX — no Windows o
     * {@code setPosixFilePermissions} não existe, o arquivo continua legível e
     * a asserção quebra. Sem {@code @EnabledOnOs}, a perna Windows da CI fica
     * VERMELHA.
     */
    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void hostsSemLeituraNaoViraErro() throws Exception {
        gravarEstado("tiktok.com", null);
        Path hostsSemLeitura = tmp.resolve("hosts-sem-leitura");
        Files.writeString(hostsSemLeitura, "127.0.0.1 localhost\n", StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(hostsSemLeitura, Set.of(PosixFilePermission.OWNER_WRITE));

        Resultado r = rodar(() -> new StatusCommand(hostsSemLeitura.toString(), state()).call());

        assertEquals(0, r.codigo(), "ler o hosts pode falhar, mas o status não vira erro: " + r.texto());
        assertTrue(r.texto().contains("não consegui ler"), r.texto());
        assertFalse(r.texto().contains("Divergência"),
                "sem conseguir ler o hosts não há o que afirmar: " + r.texto());
        assertTrue(r.texto().contains("Domínios bloqueados (1):"),
                "a lista de bloqueados continua: " + r.texto());
    }
}
