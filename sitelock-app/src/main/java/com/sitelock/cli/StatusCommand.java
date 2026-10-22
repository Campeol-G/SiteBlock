package com.sitelock.cli;

import com.sitelock.core.BlockEntry;
import com.sitelock.core.BlockService;
import com.sitelock.core.CorruptedStateException;
import com.sitelock.core.DomainUtils;
import com.sitelock.core.DurationParser;
import com.sitelock.core.HostsFileManager;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;

/**
 * siteblock status — lista bloqueios. Limpa expirados só se puder escrever no hosts.
 */
@Command(name = "status", description = "Lista dominios bloqueados e tempo restante.",
        mixinStandardHelpOptions = true)
public class StatusCommand implements Callable<Integer> {

    @Option(names = "--hosts-file", hidden = true) String hostsFileOverride;
    @Option(names = "--state-file", hidden = true) String stateFileOverride;

    public StatusCommand() {
    }

    StatusCommand(String hostsFileOverride, String stateFileOverride) {
        this.hostsFileOverride = hostsFileOverride;
        this.stateFileOverride = stateFileOverride;
    }

    @Override
    public Integer call() {
        BlockService service = CliSupport.newService(hostsFileOverride, stateFileOverride);

        final Map<String, BlockEntry> state;
        try {
            if (service.isHostsWritable()) {
                BlockService.SweepResult sweep = service.sweepExpired();
                state = sweep.state();
                if (!sweep.removed().isEmpty()) {
                    System.out.println("Limpeza: bloqueios expirados removidos: "
                            + String.join(", ", sweep.removed()));
                }
            } else {
                state = service.getStore().load();
                long pending = state.values().stream()
                        .filter(e -> e.isExpired(Instant.now(service.getClock())))
                        .count();
                if (pending > 0) {
                    System.out.println("Aviso: há " + pending + " bloqueio(s) expirado(s) pendentes de limpeza.");
                    System.out.println(CliSupport.elevationHint("status")
                            + " para concluir a limpeza no hosts.");
                }
            }
        } catch (CorruptedStateException e) {
            System.err.println("Erro: estado corrompido (" + e.getMessage() + ").");
            System.err.println("Nada foi alterado. Para recriar do zero, apague o arquivo manualmente ou rode: siteblock reset --yes");
            return 1;
        } catch (IOException e) {
            System.err.println("Erro ao ler estado: " + e.getMessage());
            return 1;
        }

        Instant now = Instant.now(service.getClock());

        if (state.isEmpty()) {
            System.out.println("Nenhum domínio bloqueado.");
            conferirDivergencia(service, state, now);
            return 0;
        }

        List<String> lines = new ArrayList<>();
        for (BlockEntry e : state.values()) {
            String remaining;
            if (e.getExpiresAt() == null) {
                remaining = "permanente";
            } else if (!now.isBefore(e.getExpiresAt())) {
                remaining = "expirado (pendente limpeza)";
            } else {
                remaining = DurationParser.formatRemaining(Duration.between(now, e.getExpiresAt()));
            }
            lines.add(e.getDomain() + " — " + remaining);
        }
        System.out.println("Domínios bloqueados (" + lines.size() + "):");
        lines.forEach(l -> System.out.println("  " + l));

        conferirDivergencia(service, state, now);

        // Só fala quando há temporário ativo: bloqueio permanente não tem prazo
        // para vencer, então não há o que avisar.
        boolean temAtivo = state.values().stream().anyMatch(e -> e.isTemporaryAndActive(now));
        if (temAtivo && !CliSupport.temDaemonVivo(stateFileOverride)) {
            System.out.println("Há bloqueio com prazo e nenhum daemon rodando: o prazo só será "
                    + "aplicado quando você rodar outro comando do siteblock.");
            System.out.println(CliSupport.elevatedCommand("daemon enable")
                    + " para ele vencer sozinho.");
        }
        return 0;
    }

    // POR QUE: os dois podem discordar, e cada desfecho é ruim de um jeito diferente.
    // Estado sem linha no hosts: o site está livre e o status diz que está bloqueado.
    // Linha no hosts sem registro: preso para sempre, porque o sweep varre o JSON.
    private void conferirDivergencia(BlockService service, Map<String, BlockEntry> state, Instant now) {
        final String conteudo;
        try {
            conteudo = service.readHostsContent();
        } catch (IOException e) {
            // O status roda sem elevação: ler o hosts pode falhar por
            // permissão. Não é erro — é falta de conferência.
            System.out.println("Aviso: não consegui ler o hosts (" + service.getHostsPath()
                    + ") para conferir a divergência: " + e.getMessage());
            return;
        }

        Set<String> semEfeito = new TreeSet<>();
        Set<String> noEstado = new TreeSet<>();
        for (BlockEntry e : state.values()) {
            noEstado.add(baseDe(e.getDomain()));
            if (e.isExpired(now)) {
                // Expirado não está ativo: a limpeza pendente é assunto do sweep.
                continue;
            }
            if (!temLinhaGerenciada(conteudo, e.getDomain())) {
                semEfeito.add(e.getDomain());
            }
        }

        Set<String> presoSemRegistro = new TreeSet<>();
        for (String dominio : dominiosNoBloco(conteudo)) {
            if (!noEstado.contains(dominio)) {
                presoSemRegistro.add(dominio);
            }
        }

        if (semEfeito.isEmpty() && presoSemRegistro.isEmpty()) {
            return;
        }
        System.out.println("Divergência entre o estado e o hosts ("
                + (semEfeito.size() + presoSemRegistro.size()) + "):");
        for (String dominio : semEfeito) {
            System.out.println("  " + dominio
                    + " — sem efeito: o estado diz bloqueado, mas o hosts não tem a linha"
                    + " (o site está livre).");
        }
        for (String dominio : presoSemRegistro) {
            System.out.println("  " + dominio
                    + " — preso sem registro: o hosts tem a linha, mas o estado não tem"
                    + " o registro (o site fica bloqueado para sempre).");
            // O unblock só sabe tirar o que está no estado: sem registro, ele
            // diria que o domínio não está bloqueado e não tocaria no hosts.
            System.out.println("    Resolva com: " + CliSupport.elevatedCommand("block " + dominio)
                    + " e depois " + CliSupport.elevatedCommand("unblock " + dominio));
        }
    }

    // O domínio sem o prefixo "www." — o bloqueio é o mesmo com ou sem ele.
    private static String baseDe(String dominio) {
        return HostsFileManager.managedHosts(dominio).get(0);
    }

    private static boolean temLinhaGerenciada(String conteudo, String dominio) {
        for (String gerenciada : HostsFileManager.managedLines(dominio)) {
            String[] partes = gerenciada.strip().split("\\s+");
            if (linhaExiste(conteudo, partes[0], partes[1])) {
                return true;
            }
        }
        return false;
    }

    private static boolean linhaExiste(String conteudo, String ip, String host) {
        for (String linha : conteudo.split("\n", -1)) {
            String t = linha.strip();
            if (t.isEmpty() || t.startsWith("#")) {
                continue;
            }
            String[] partes = t.split("\\s+");
            if (partes.length < 2 || !partes[0].equalsIgnoreCase(ip)) {
                continue;
            }
            for (int i = 1; i < partes.length; i++) {
                if (partes[i].equalsIgnoreCase(host)) {
                    return true;
                }
            }
        }
        return false;
    }

    // Os domínios que o SiteLock tem no bloco gerenciado do hosts.
    private static Set<String> dominiosNoBloco(String conteudo) {
        Set<String> dominios = new TreeSet<>();
        for (String linha : linhasDoBloco(conteudo)) {
            String[] partes = linha.split("\\s+");
            if (partes.length < 2 || !ehDominioBloqueavel(partes[1])) {
                continue;
            }
            String dominio = baseDe(partes[1]);
            for (String gerenciada : HostsFileManager.managedLines(dominio)) {
                if (gerenciada.equals(partes[0] + " " + partes[1])) {
                    dominios.add(dominio);
                    break;
                }
            }
        }
        return dominios;
    }

    // As linhas dentro do bloco que o SiteLock gerencia.
    private static List<String> linhasDoBloco(String conteudo) {
        List<String> linhas = new ArrayList<>();
        boolean dentro = false;
        for (String linha : conteudo.split("\n", -1)) {
            String t = linha.strip();
            if (t.contains(HostsFileManager.START_MARKER)) {
                dentro = true;
                continue;
            }
            if (t.contains(HostsFileManager.END_MARKER)) {
                dentro = false;
                continue;
            }
            if (dentro) {
                linhas.add(t);
            }
        }
        return linhas;
    }

    // POR QUE: o "localhost" e afins do /etc/hosts são conteúdo do sistema, e o que o
    // SiteLock pode bloquear é o que o normalize() aceita como domínio.
    private static boolean ehDominioBloqueavel(String host) {
        try {
            DomainUtils.normalize(host);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
