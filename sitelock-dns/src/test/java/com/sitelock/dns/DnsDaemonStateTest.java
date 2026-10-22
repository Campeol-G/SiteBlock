package com.sitelock.dns;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

// O registro do daemon DNS (dns-server.json). É lido pelo dns start, pelo dns
// status e pelo dns stop: um registro quebrado tranca a máquina, e um registro
// que aponta para o PID errado faz o stop mandar sinal num processo alheio.
class DnsDaemonStateTest {

    @TempDir
    Path tmp;

    private Path arquivo() {
        return DnsDaemonState.statePath(tmp);
    }

    @Test
    void gravacaoELeituraRoundTrip() throws Exception {
        Instant agora = Instant.parse("2026-01-01T12:00:00Z");
        DnsDaemonState.write(tmp, new DnsDaemonState(4242, 5353, "1.1.1.1", agora, null));

        DnsDaemonState lido = DnsDaemonState.read(tmp).orElseThrow();
        assertEquals(4242, lido.getPid());
        assertEquals(5353, lido.getPort());
        assertEquals("1.1.1.1", lido.getUpstream());
        assertEquals(agora, lido.getStartedAt());
    }

    // JSON pela metade não pode estourar: o registro viraria lixo órfão e o
    // usuário nunca mais conseguiria subir o servidor DNS sem apagar o
    // arquivo à mão.
    @Test
    void jsonCorrompidoDevolveRegistroVazio() throws Exception {
        Files.createDirectories(tmp);
        Files.writeString(arquivo(), "{ \"pid\": 12, \"port\":", StandardCharsets.UTF_8);

        assertTrue(DnsDaemonState.read(tmp).isEmpty(),
                "JSON inválido tem que virar registro órfão, não exceção");
    }

    // A escrita é atômica: o arquivo trocado de inode, nunca meio escrito.
    @Test
    void escritaEhAtomica() throws Exception {
        Instant agora = Instant.parse("2026-01-01T12:00:00Z");
        DnsDaemonState.write(tmp, new DnsDaemonState(1, 53, "1.1.1.1", agora, null));
        Object chaveAntes = Files.readAttributes(arquivo(), BasicFileAttributes.class).fileKey();
        assumeTrue(chaveAntes != null, "o FS não expõe fileKey");

        DnsDaemonState.write(tmp, new DnsDaemonState(2, 53, "1.1.1.1", agora, null));
        Object chaveDepois = Files.readAttributes(arquivo(), BasicFileAttributes.class).fileKey();

        assertNotEquals(chaveAntes, chaveDepois,
                "a escrita tem que passar por .tmp + rename (o inode muda)");
        assertFalse(Files.exists(tmp.resolve("dns-server.json.tmp")),
                "o arquivo temporário não pode sobrar");
    }

    // O SO recicla PID: o número existir não prova que o processo é o
    // daemon. O instante de início é a prova.
    @Test
    void pidRecicladoNaoPassaPorDaemonVivo() throws Exception {
        Files.createDirectories(tmp);
        Files.writeString(arquivo(), "{\"pid\":" + ProcessHandle.current().pid()
                + ",\"port\":53,\"upstream\":\"1.1.1.1\","
                + "\"startedAt\":\"2026-01-01T12:00:00Z\",\"expiresAt\":null,"
                + "\"processStartedAt\":\"2020-01-01T00:00:00Z\"}", StandardCharsets.UTF_8);

        DnsDaemonState state = DnsDaemonState.read(tmp).orElseThrow();
        assertFalse(DnsDaemonState.isAlive(state),
                "PID de outro processo não é o daemon: o stop mandaria sinal no processo errado");
    }

    // Registro escrito por uma versão antiga (sem o instante) vale pelo PID.
    @Test
    void registroAntigoSemInstanteDeInicioContinuaValendoPeloPid() throws Exception {
        Files.createDirectories(tmp);
        Files.writeString(arquivo(), "{\"pid\":" + ProcessHandle.current().pid()
                + ",\"port\":53,\"upstream\":\"1.1.1.1\","
                + "\"startedAt\":\"2026-01-01T12:00:00Z\",\"expiresAt\":null}", StandardCharsets.UTF_8);

        DnsDaemonState state = DnsDaemonState.read(tmp).orElseThrow();
        assertTrue(DnsDaemonState.isAlive(state),
                "registro antigo (sem processStartedAt) continua válido pelo PID");
    }

    // Quem escreve o registro guarda o instante de início do próprio processo.
    @Test
    void gravacaoGuardaOInstanteDeInicioDoProcesso() throws Exception {
        Instant agora = Instant.parse("2026-01-01T12:00:00Z");
        DnsDaemonState.write(tmp, new DnsDaemonState(
                ProcessHandle.current().pid(), 53, "1.1.1.1", agora, null));

        DnsDaemonState lido = DnsDaemonState.read(tmp).orElseThrow();
        assertTrue(DnsDaemonState.isAlive(lido),
                "o registro do processo atual tem que ser reconhecido como vivo");
    }

    @Test
    void deleteIfOwnedNaoApagaRegistroDeOutroPid() throws Exception {
        Instant agora = Instant.parse("2026-01-01T12:00:00Z");
        DnsDaemonState.write(tmp, new DnsDaemonState(111, 53, "1.1.1.1", agora, null));

        DnsDaemonState.deleteIfOwned(tmp, 222);

        assertTrue(DnsDaemonState.read(tmp).isPresent(),
                "o dns start que falhou não pode apagar o registro de outro daemon");
    }

    @Test
    void deleteIfOwnedApagaRegistroDoProprioPid() throws Exception {
        Instant agora = Instant.parse("2026-01-01T12:00:00Z");
        DnsDaemonState.write(tmp, new DnsDaemonState(111, 53, "1.1.1.1", agora, null));

        DnsDaemonState.deleteIfOwned(tmp, 111);

        assertTrue(DnsDaemonState.read(tmp).isEmpty(),
                "o dono do registro tem que conseguir limpá-lo");
    }
}
