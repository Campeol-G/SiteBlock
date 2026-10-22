package com.sitelock.dns;

import com.sitelock.core.BlockEntry;
import com.sitelock.core.BlockStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Alias por CNAME: o nome consultado é livre, mas aponta para um nome bloqueado.
// O defeito: o upstream devolvia a cadeia de CNAMEs e o IP real do alvo na mesma
// resposta, e o servidor repassava tudo — o cliente conectava no IP bloqueado.
class DnsAliasCnameTest {

    // IP que o upstream entrega para o alvo bloqueado (não pode vazar).
    private static final byte[] IP_REAL = {1, 2, 3, 4};

    @TempDir
    Path tmp;

    // Sobe o servidor apontado para o upstream falso, com os bloqueios dados.
    private DnsServer subir(FakeUpstream upstream, String... bloqueados) throws Exception {
        Path estado = DnsTestSupport.arquivoDeBloqueios(tmp, bloqueados);
        return subir(upstream, new BlockStore(estado), bloqueados[0]);
    }

    // Sobe o servidor com um BlockStore próprio (para contar leituras).
    private DnsServer subir(FakeUpstream upstream, BlockStore store, String bloqueado)
            throws Exception {
        int porta = DnsTestSupport.portaLivre();
        DnsServer server = new DnsServer(porta, upstream.endereco(), upstream.porta(), 2000,
                store, Clock.systemUTC(), List.of());
        Thread t = new Thread(server::serveForever, "servidor-de-teste");
        t.setDaemon(true);
        t.start();
        DnsTestSupport.esperaEscuta(porta, bloqueado, 5000);
        return server;
    }

    // Upstream que responde com a cadeia de aliases informada.
    private static FakeUpstream upstreamComCadeia(String[] cadeia, int tipoFinal,
                                                  byte[] rdataFinal) throws Exception {
        FakeUpstream upstream = new FakeUpstream();
        upstream.respondeCom(consulta ->
                DnsWire.respostaEmCadeia(consulta, cadeia, tipoFinal, rdataFinal));
        upstream.start();
        return upstream;
    }

    // true se o IP aparece em qualquer byte da resposta.
    private static boolean contemIp(byte[] resposta, byte[] ip) {
        for (int i = 0; i + ip.length <= resposta.length; i++) {
            boolean igual = true;
            for (int j = 0; j < ip.length; j++) {
                if (resposta[i + j] != ip[j]) {
                    igual = false;
                    break;
                }
            }
            if (igual) {
                return true;
            }
        }
        return false;
    }

    // O defeito original: "www.microsoft.com" livre, com CNAME para
    // "www.microsoft.com-c-3.edgekey.net" bloqueado. O cliente recebia o IP real.
    @Test
    void aliasDeNomeBloqueadoRecebeSinkhole() throws Exception {
        FakeUpstream upstream = upstreamComCadeia(
                new String[]{"alias.test", "alvo.test"}, DnsQuery.TYPE_A, IP_REAL);
        DnsServer server = subir(upstream, "alvo.test");
        try {
            byte[] bruto = DnsTestSupport.consultar(server.getPort(),
                    DnsWire.query(0x1234, "alias.test", DnsQuery.TYPE_A), 3000);
            assertNotNull(bruto, "o servidor não respondeu");
            DnsWire.Resposta r = DnsWire.parse(bruto);
            assertEquals(0, r.rcode(), "o alias bloqueado não pode dar SERVFAIL");
            assertEquals(1, r.anCount(), "o alias bloqueado leva o sinkhole");
            assertArrayEquals(new byte[]{0, 0, 0, 0}, r.primeiroRdata(),
                    "sinkhole IPv4 é 0.0.0.0");
            assertFalse(contemIp(bruto, IP_REAL),
                    "o IP real do nome bloqueado não pode chegar ao cliente");
        } finally {
            server.stop();
            upstream.close();
        }
    }

    // O nome livre sem alias continua sendo encaminhado: a correção do alias não
    // pode estragar o caminho normal.
    @Test
    void nomeLivreSemCnameContinuaSendoEncaminhado() throws Exception {
        FakeUpstream upstream = new FakeUpstream();
        upstream.start();
        DnsServer server = subir(upstream, "alvo.test");
        try {
            byte[] bruto = DnsTestSupport.consultar(server.getPort(),
                    DnsWire.query(0x2222, "livre.test", DnsQuery.TYPE_A), 3000);
            assertNotNull(bruto, "o servidor não respondeu");
            DnsWire.Resposta r = DnsWire.parse(bruto);
            assertEquals(0x2222, r.id(), "a resposta precisa ecoar o ID do cliente");
            assertEquals(0, r.rcode());
            assertEquals(1, r.anCount(), "a resposta do upstream tem que ser repassada");
            assertArrayEquals(new byte[]{(byte) 93, (byte) 184, (byte) 216, (byte) 34},
                    r.primeiroRdata(), "a resposta do upstream tem que chegar intacta");
        } finally {
            server.stop();
            upstream.close();
        }
    }

    // Cadeia com dois elos e o bloqueio no segundo elo: o IP real não pode vazar.
    @Test
    void cadeiaDeDoisElosComBloqueioNoSegundoEloRecebeSinkhole() throws Exception {
        FakeUpstream upstream = upstreamComCadeia(
                new String[]{"alias.test", "meio.test", "alvo.test"}, DnsQuery.TYPE_A, IP_REAL);
        DnsServer server = subir(upstream, "alvo.test");
        try {
            byte[] bruto = DnsTestSupport.consultar(server.getPort(),
                    DnsWire.query(0x3456, "alias.test", DnsQuery.TYPE_A), 3000);
            assertNotNull(bruto, "o servidor não respondeu");
            DnsWire.Resposta r = DnsWire.parse(bruto);
            assertEquals(0, r.rcode());
            assertEquals(1, r.anCount(), "a cadeia com elo bloqueado leva o sinkhole");
            assertArrayEquals(new byte[]{0, 0, 0, 0}, r.primeiroRdata(),
                    "sinkhole IPv4 é 0.0.0.0");
            assertFalse(contemIp(bruto, IP_REAL),
                    "o IP real do nome bloqueado não pode chegar ao cliente");
        } finally {
            server.stop();
            upstream.close();
        }
    }

    // Cadeia com dois elos, nenhum bloqueado: a resposta passa direto.
    @Test
    void cadeiaDeDoisElosSemNenhumBloqueadoPassaDireto() throws Exception {
        FakeUpstream upstream = upstreamComCadeia(
                new String[]{"alias.test", "meio.test", "alvo.test"}, DnsQuery.TYPE_A, IP_REAL);
        DnsServer server = subir(upstream, "outro.test");
        try {
            byte[] bruto = DnsTestSupport.consultar(server.getPort(),
                    DnsWire.query(0x4321, "alias.test", DnsQuery.TYPE_A), 3000);
            assertNotNull(bruto, "o servidor não respondeu");
            DnsWire.Resposta r = DnsWire.parse(bruto);
            assertEquals(0, r.rcode());
            assertEquals(3, r.anCount(), "a cadeia inteira tem que ser repassada");
            assertArrayEquals(IP_REAL, r.answers().get(2).rdata(),
                    "o registro final tem que chegar intacto");
        } finally {
            server.stop();
            upstream.close();
        }
    }

    // Resposta sem section answer (NXDOMAIN): nada a inspecionar, repassa.
    @Test
    void respostaSemSectionAnswerEhRepassada() throws Exception {
        FakeUpstream upstream = new FakeUpstream();
        upstream.modo(FakeUpstream.Modo.SEM_ANSWER);
        upstream.start();
        DnsServer server = subir(upstream, "alvo.test");
        try {
            byte[] bruto = DnsTestSupport.consultar(server.getPort(),
                    DnsWire.query(0x3333, "livre.test", DnsQuery.TYPE_A), 3000);
            assertNotNull(bruto, "o servidor não respondeu");
            DnsWire.Resposta r = DnsWire.parse(bruto);
            assertEquals(0x3333, r.id(), "a resposta precisa ecoar o ID do cliente");
            assertEquals(3, r.rcode(), "o NXDOMAIN do upstream tem que ser repassado");
            assertEquals(0, r.anCount());
        } finally {
            server.stop();
            upstream.close();
        }
    }

    // O bloqueio de verdade continua valendo: nome bloqueado não vai ao upstream.
    @Test
    void upstreamNaoRecebeConsultaDeNomeBloqueado() throws Exception {
        FakeUpstream upstream = new FakeUpstream();
        upstream.start();
        DnsServer server = subir(upstream, "alvo.test");
        try {
            byte[] bruto = DnsTestSupport.consultar(server.getPort(),
                    DnsWire.query(0x4444, "alvo.test", DnsQuery.TYPE_A), 3000);
            assertNotNull(bruto, "o servidor não respondeu");
            DnsWire.Resposta r = DnsWire.parse(bruto);
            assertEquals(1, r.anCount(), "o nome bloqueado leva o sinkhole");
            assertArrayEquals(new byte[]{0, 0, 0, 0}, r.primeiroRdata(),
                    "sinkhole IPv4 é 0.0.0.0");
            Thread.sleep(300); // dá tempo de o upstream receber, caso o servidor envie
            assertTrue(upstream.consultasRecebidas().isEmpty(),
                    "o nome bloqueado não pode ser consultado no upstream");
        } finally {
            server.stop();
            upstream.close();
        }
    }

    // O sinkhole do alias tem que respeitar o tipo pedido (AAAA → ::).
    @Test
    void aliasBloqueadoComAaaaRecebeSinkholeIpv6() throws Exception {
        FakeUpstream upstream = upstreamComCadeia(
                new String[]{"alias.test", "alvo.test"}, DnsQuery.TYPE_AAAA, new byte[16]);
        DnsServer server = subir(upstream, "alvo.test");
        try {
            byte[] bruto = DnsTestSupport.consultar(server.getPort(),
                    DnsWire.query(0x5555, "alias.test", DnsQuery.TYPE_AAAA), 3000);
            assertNotNull(bruto, "o servidor não respondeu");
            DnsWire.Resposta r = DnsWire.parse(bruto);
            assertEquals(0, r.rcode());
            assertEquals(1, r.anCount());
            assertArrayEquals(new byte[16], r.primeiroRdata(), "sinkhole IPv6 é ::");
        } finally {
            server.stop();
            upstream.close();
        }
    }

    // Tipo ≠ A/AAAA no alias bloqueado: NODATA, como num nome bloqueado.
    @Test
    void aliasBloqueadoComTipoNaoAaaaRespondeNodata() throws Exception {
        FakeUpstream upstream = upstreamComCadeia(
                new String[]{"alias.test", "alvo.test"}, 0, new byte[0]);
        DnsServer server = subir(upstream, "alvo.test");
        try {
            byte[] bruto = DnsTestSupport.consultar(server.getPort(),
                    DnsWire.query(0x6666, "alias.test", 16 /*TXT*/), 3000);
            assertNotNull(bruto, "o servidor não respondeu");
            DnsWire.Resposta r = DnsWire.parse(bruto);
            assertEquals(0, r.rcode(), "NODATA não é SERVFAIL");
            assertEquals(0, r.anCount(), "NODATA: nenhum answer para tipo diferente de A/AAAA");
        } finally {
            server.stop();
            upstream.close();
        }
    }

    // Subdomínio do nome bloqueado na cadeia: bloqueia igual (sufixo com ponto).
    @Test
    void aliasParaSubdominioDoBloqueadoRecebeSinkhole() throws Exception {
        FakeUpstream upstream = upstreamComCadeia(
                new String[]{"alias.test", "cdn.alvo.test"}, DnsQuery.TYPE_A, IP_REAL);
        DnsServer server = subir(upstream, "alvo.test");
        try {
            byte[] bruto = DnsTestSupport.consultar(server.getPort(),
                    DnsWire.query(0x7777, "alias.test", DnsQuery.TYPE_A), 3000);
            assertNotNull(bruto, "o servidor não respondeu");
            DnsWire.Resposta r = DnsWire.parse(bruto);
            assertEquals(1, r.anCount(), "o subdomínio do bloqueado leva o sinkhole");
            assertArrayEquals(new byte[]{0, 0, 0, 0}, r.primeiroRdata());
            assertFalse(contemIp(bruto, IP_REAL),
                    "o IP real do nome bloqueado não pode chegar ao cliente");
        } finally {
            server.stop();
            upstream.close();
        }
    }

    // A checagem do alias não pode custar uma releitura do blocks.json: a
    // consulta já leu a lista de bloqueios, e a cadeia é checada com ela.
    @Test
    void checagemDoAliasNaoReleOBlocksJsonDeNovo() throws Exception {
        FakeUpstream upstream = upstreamComCadeia(
                new String[]{"alias.test", "alvo.test"}, DnsQuery.TYPE_A, IP_REAL);
        Path estado = DnsTestSupport.arquivoDeBloqueios(tmp, "alvo.test");
        ContadorDeLeituras store = new ContadorDeLeituras(estado);
        DnsServer server = subir(upstream, store, "alvo.test");
        try {
            store.zerar();
            byte[] bruto = DnsTestSupport.consultar(server.getPort(),
                    DnsWire.query(0x9999, "alias.test", DnsQuery.TYPE_A), 3000);
            assertNotNull(bruto, "o servidor não respondeu");
            assertEquals(1, store.leituras(),
                    "o blocks.json pode ser lido uma vez por consulta — a checagem do alias "
                            + "tem que usar a mesma lista");
        } finally {
            server.stop();
            upstream.close();
        }
    }

    // BlockStore que conta as leituras do arquivo de estado.
    private static final class ContadorDeLeituras extends BlockStore {

        private final AtomicInteger leituras = new AtomicInteger();

        ContadorDeLeituras(Path arquivo) {
            super(arquivo);
        }

        @Override
        public Map<String, BlockEntry> load() throws IOException {
            leituras.incrementAndGet();
            return super.load();
        }

        int leituras() {
            return leituras.get();
        }

        void zerar() {
            leituras.set(0);
        }
    }
}
