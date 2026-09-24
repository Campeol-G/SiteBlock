package com.sitelock.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Limpeza do cache de DNS após modificações no hosts — etapa best-effort.
 *
 * <p>Falha no flush <b>nunca</b> impede o bloqueio de ter sido salvo: só gera o
 * aviso {@link PermissionAdvisor#dnsFlushFailureWarning()}. Comandos executados
 * via {@link ProcessBuilder}, com exit code e stderr capturados e timeout de 10s.
 *
 * <ul>
 *   <li>Linux: tenta {@code systemd-resolve --flush-caches}; se falhar
 *       (comando ausente em distros sem systemd-resolved), tenta
 *       {@code resolvectl flush-caches}. Se ambos falharem, avisa que pode ser
 *       preciso reiniciar o navegador.</li>
 *   <li>macOS: {@code dscacheutil -flushcache} + {@code killall -HUP mDNSResponder}.</li>
 *   <li>Windows: {@code ipconfig /flushdns}.</li>
 * </ul>
 *
 * <p>Limitação conhecida: a execução real do flush não é testada no CI em todos
 * os SOs (exigiria runners Windows/macOS + permissões) — validar manualmente.
 * Os testes unitários cobrem apenas a <i>seleção</i> dos comandos por SO, com um
 * {@link CommandRunner} fake, sem executar processos reais.
 */
public final class DnsCacheFlusher {

    /** Executa um comando e devolve o exit code (injetável para testes). */
    public interface CommandRunner {
        int run(List<String> command) throws IOException;
    }

    public record FlushResult(boolean success, String detail) {
    }

    private static final long TIMEOUT_SECONDS = 10;

    private final OsType osType;
    private final CommandRunner runner;

    /** Usa o SO real e execução via {@link ProcessBuilder}. */
    public DnsCacheFlusher() {
        this(OsType.detect(), DnsCacheFlusher::runWithProcessBuilder);
    }

    /** Injeta o SO (produção com SO simulado / testes). */
    public DnsCacheFlusher(OsType osType) {
        this(osType, DnsCacheFlusher::runWithProcessBuilder);
    }

    /** Injeta o valor de {@code os.name} (para testes dos três ramos). */
    public DnsCacheFlusher(String osName) {
        this(OsType.detect(osName), DnsCacheFlusher::runWithProcessBuilder);
    }

    /** Injeta SO + runner (testes usam um fake, sem processo real). */
    DnsCacheFlusher(OsType osType, CommandRunner runner) {
        this.osType = osType == null ? OsType.UNKNOWN : osType;
        this.runner = runner == null ? DnsCacheFlusher::runWithProcessBuilder : runner;
    }

    /** Comandos tentados em ordem para o SO corrente (exposto para testes). */
    List<List<String>> commandsForCurrentOs() {
        return commandsFor(osType);
    }

    /** Seleção de comandos por SO — pura, sem executar nada. */
    static List<List<String>> commandsFor(OsType os) {
        if (os != null && os.isWindows()) {
            return List.of(List.of("ipconfig", "/flushdns"));
        }
        if (os != null && os.isMac()) {
            // Equivale a: dscacheutil -flushcache && killall -HUP mDNSResponder
            return List.of(List.of("sh", "-c", "dscacheutil -flushcache && killall -HUP mDNSResponder"));
        }
        // Linux e UNKNOWN: tenta systemd-resolve, depois resolvectl.
        return List.of(
                List.of("systemd-resolve", "--flush-caches"),
                List.of("resolvectl", "flush-caches"));
    }

    /**
     * Executa o flush. Nunca lança exceção para o chamador do CLI:
     * devolve {@link FlushResult} e quem chama decide logar o aviso.
     */
    public FlushResult flush() {
        List<List<String>> commands = commandsForCurrentOs();
        List<String> errors = new ArrayList<>();
        for (List<String> cmd : commands) {
            try {
                int exit = runner.run(cmd);
                if (exit == 0) {
                    return new FlushResult(true, "flush OK via: " + String.join(" ", cmd));
                }
                errors.add(String.join(" ", cmd) + " (exit " + exit + ")");
            } catch (IOException e) {
                // Comando ausente ou com falha (ex.: sem systemd-resolved) — tenta o próximo.
                errors.add(String.join(" ", cmd) + " (" + e.getMessage() + ")");
            } catch (Exception e) {
                errors.add(String.join(" ", cmd) + " (erro: " + e.getMessage() + ")");
            }
        }
        // No Linux, esgotar as duas variantes ainda é "aviso", não erro fatal.
        return new FlushResult(false, String.join("; ", errors));
    }

    /**
     * Atalho best-effort para os comandos CLI: executa e, se falhar, imprime o
     * aviso padrão no stderr sem lançar exceção e sem mudar o exit code do comando.
     *
     * @return true se o flush funcionou, false se só avisou.
     */
    public boolean flushBestEffort() {
        FlushResult r;
        try {
            r = flush();
        } catch (Exception e) {
            r = new FlushResult(false, String.valueOf(e.getMessage()));
        }
        if (!r.success()) {
            System.err.println("Aviso: " + PermissionAdvisor.dnsFlushFailureWarning());
            if (r.detail() != null && !r.detail().isBlank()) {
                System.err.println("(flush DNS: " + r.detail() + ")");
            } else if (osType == OsType.LINUX || osType == OsType.UNKNOWN) {
                System.err.println("(flush DNS: tente 'systemd-resolve --flush-caches' ou "
                        + "'resolvectl flush-caches'; se ausentes, reinicie o navegador.)");
            }
        }
        return r.success();
    }

    public OsType getOsType() {
        return osType;
    }

    private static int runWithProcessBuilder(List<String> command) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            throw new IOException("comando não encontrado: " + command.get(0).toLowerCase(Locale.ROOT), e);
        }
        boolean done;
        try {
            done = p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            throw new IOException("timeout aguardando flush de DNS", e);
        }
        if (!done) {
            p.destroyForcibly();
            throw new IOException("timeout (> " + TIMEOUT_SECONDS + "s) no flush de DNS");
        }
        // Drena a saída para não travar o pipe em comandos verbosos.
        String out = "";
        try (InputStream in = p.getInputStream()) {
            out = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
        int exit = p.exitValue();
        if (exit != 0 && !out.isBlank()) {
            throw new IOException("exit " + exit + ": " + out.strip().split("\n")[0]);
        }
        return exit;
    }
}
