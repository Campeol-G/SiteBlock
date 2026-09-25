package com.sitelock.cli;

import com.sitelock.core.DurationParser;
import com.sitelock.dns.DnsDaemonState;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Scanner;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Processo filho do {@code dns start}: roda o servidor em foreground até
 * {@code dns stop} (SIGTERM), Ctrl+C ou o fim do {@code --for}.
 * Comando interno (oculto da ajuda); não chame diretamente.
 */
@Command(name = "__run-daemon", hidden = true,
        description = "Executa o servidor DNS em foreground (uso interno do 'dns start').")
public class DnsRunDaemonCommand implements Callable<Integer> {

    @Option(names = "--port", defaultValue = "53") int port;
    @Option(names = "--upstream", defaultValue = "1.1.1.1") String upstream;
    @Option(names = "--for") String forDuration;
    @Option(names = "--state-file", hidden = true) String stateFileOverride;

    public DnsRunDaemonCommand() {
    }

    @Override
    public Integer call() {
        final java.net.InetAddress upstreamAddr;
        try {
            upstreamAddr = java.net.InetAddress.getByName(upstream);
        } catch (Exception e) {
            System.err.println("[sitelock-dns] upstream inválido '" + upstream + "': " + e.getMessage());
            return 2;
        }

        final Duration duration;
        try {
            duration = forDuration == null ? null : DurationParser.parse(forDuration);
        } catch (IllegalArgumentException e) {
            System.err.println("[sitelock-dns] " + e.getMessage());
            return 2;
        }

        Path stateDir = DnsDaemonState.stateDirFor(stateFileOverride);
        Path stateFile = stateFileOverride != null && !stateFileOverride.isBlank()
                ? Path.of(stateFileOverride)
                : stateDir.resolve("blocks.json");

        com.sitelock.dns.DnsServer server = new com.sitelock.dns.DnsServer(
                port, upstreamAddr, new com.sitelock.core.BlockStore(stateFile), Clock.systemUTC());
        server.installShutdownHook();

        java.util.concurrent.ScheduledExecutorService scheduler = null;
        if (duration != null) {
            scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "sitelock-dns-expiry");
                t.setDaemon(true);
                return t;
            });
            scheduler.schedule(() -> {
                System.out.println("[sitelock-dns] tempo de --for ("
                        + DurationParser.formatRemaining(duration) + ") esgotado; encerrando.");
                server.stop();
            }, duration.toMillis(), TimeUnit.MILLISECONDS);
        }

        try {
            com.sitelock.dns.NetworkUtils.printStartupInfo(port, upstreamAddr.getHostAddress());
            server.runWithWatchdog();
        } catch (com.sitelock.dns.DnsServerException e) {
            // Falha no bind inicial (porta em uso/sem permissão): sai para que o
            // `dns start` detecte o filho morto e mostre o motivo do log.
            System.err.println("[sitelock-dns] " + e.getMessage());
            return 1;
        } finally {
            if (scheduler != null) {
                scheduler.shutdownNow();
            }
            try {
                DnsDaemonState.delete(stateDir);
            } catch (IOException ignored) {
            }
        }
        return 0;
    }

    /** Ponto de entrada alternativo (não usado pela CLI; o daemon nasce via SiteLockApplication). */
    public static int run(int port, String upstream, String forDuration, String stateFileOverride) {
        DnsRunDaemonCommand cmd = new DnsRunDaemonCommand();
        cmd.port = port;
        cmd.upstream = upstream;
        cmd.forDuration = forDuration;
        cmd.stateFileOverride = stateFileOverride;
        return cmd.call();
    }
}
