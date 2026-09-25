package com.sitelock.cli;

import com.sitelock.core.BlockEntry;
import com.sitelock.core.BlockStore;
import com.sitelock.core.DurationParser;
import com.sitelock.dns.DnsDaemonState;
import com.sitelock.dns.NetworkUtils;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * siteblock dns status — mostra se o servidor está rodando (pid, porta, upstream,
 * tempo restante do --for), o IP local para configurar nos dispositivos e quantos
 * domínios estão bloqueados.
 */
@Command(name = "status",
        description = "Mostra o estado do servidor DNS local.",
        mixinStandardHelpOptions = true)
public class DnsStatusCommand implements Callable<Integer> {

    @Option(names = "--state-file", hidden = true) String stateFileOverride;

    public DnsStatusCommand() {
    }

    DnsStatusCommand(String stateFileOverride) {
        this.stateFileOverride = stateFileOverride;
    }

    @Override
    public Integer call() {
        Path stateDir = DnsDaemonState.stateDirFor(stateFileOverride);
        Path stateFile = stateFileOverride != null && !stateFileOverride.isBlank()
                ? Paths.get(stateFileOverride)
                : stateDir.resolve("blocks.json");

        boolean running = false;
        try {
            Optional<DnsDaemonState> existing = DnsDaemonState.read(stateDir);
            if (existing.isPresent() && DnsDaemonState.isAlive(existing.get())) {
                running = true;
                DnsDaemonState s = existing.get();
                System.out.println("Servidor DNS: RODANDO (pid " + s.getPid()
                        + ", porta " + s.getPort() + ", upstream " + s.getUpstream() + ").");
                if (s.getStartedAt() != null) {
                    System.out.println("  Iniciado em: " + s.getStartedAt());
                }
                if (s.getExpiresAt() != null) {
                    Instant now = Instant.now(Clock.systemUTC());
                    if (now.isBefore(s.getExpiresAt())) {
                        System.out.println("  Desliga sozinho em: "
                                + DurationParser.formatRemaining(
                                        Duration.between(now, s.getExpiresAt())));
                    } else {
                        System.out.println("  Prazo do --for esgotado (desligamento iminente).");
                    }
                } else {
                    System.out.println("  Sem expiração (roda até 'dns stop').");
                }
            } else {
                if (existing.isPresent()) {
                    DnsDaemonState.delete(stateDir);
                }
                System.out.println("Servidor DNS: PARADO.");
            }
        } catch (Exception e) {
            System.err.println("Erro ao ler estado do daemon: " + e.getMessage());
            return 1;
        }

        if (running) {
            System.out.println("IP local para configurar nos dispositivos: "
                    + String.join(", ", NetworkUtils.localIPv4Addresses()));
        }
        try {
            Map<String, BlockEntry> state = new BlockStore(stateFile).load();
            Instant now = Instant.now(Clock.systemUTC());
            long active = state.values().stream().filter(e -> !e.isExpired(now)).count();
            System.out.println("Domínios bloqueados ativos: " + active + ".");
        } catch (Exception e) {
            System.out.println("Domínios bloqueados: ilegível (" + e.getMessage() + ").");
        }
        return 0;
    }
}
