package com.sitelock.cli;

import com.sitelock.blocker.HostsFileBlocker;
import com.sitelock.blocker.SiteBlocker;
import com.sitelock.core.BlockService;
import com.sitelock.core.BlockStore;
import com.sitelock.core.DnsCacheFlusher;
import com.sitelock.core.ExpirationDaemonState;
import com.sitelock.core.PermissionAdvisor;
import com.sitelock.core.SiteLockPaths;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;

/** Helpers compartilhados pelos comandos CLI. */
final class CliSupport {

    static final String PROFILES_FILE_NAME = "profiles.json";

    private CliSupport() {
    }

    static Path resolveHostsPath(String override) {
        if (override != null && !override.isBlank()) {
            return Paths.get(override);
        }
        return SiteLockPaths.getHostsPath();
    }

    static Path resolveStateFile(String override) {
        if (override != null && !override.isBlank()) {
            return Paths.get(override);
        }
        return SiteLockPaths.getStateFile();
    }

    static Path resolveBackupFile(String stateFileOverride) {
        if (stateFileOverride != null && !stateFileOverride.isBlank()) {
            Path sf = Paths.get(stateFileOverride);
            return sf.resolveSibling("hosts.backup");
        }
        return SiteLockPaths.getBackupFile();
    }

    /**
     * Diretório de estado considerando o override de {@code --state-file}.
     *
     * <p>Sem isto, um comando leria o {@code daemon.json} do {@code ~/.sitelock}
     * real enquanto operava no arquivo de teste — ou, pior, na máquina de quem
     * está testando. Mesmo raciocínio de {@link DnsDaemonState#stateDirFor(String)}.
     */
    static java.nio.file.Path stateDirFor(String stateFileOverride) {
        if (stateFileOverride != null && !stateFileOverride.isBlank()) {
            java.nio.file.Path sf = Paths.get(stateFileOverride);
            java.nio.file.Path parent = sf.getParent();
            return parent != null ? parent : Paths.get(".");
        }
        return SiteLockPaths.getStateDir();
    }

    /**
     * Onde mora o {@code profiles.json}: irmão do {@code blocks.json}, pelo mesmo
     * motivo do {@code hosts.backup} e do {@code hosts.lock} — o {@code --state-file}
     * dos testes aponta o diretório inteiro, sem precisar de uma flag nova.
     */
    static Path resolveProfilesFile(String stateFileOverride) {
        if (stateFileOverride != null && !stateFileOverride.isBlank()) {
            return Paths.get(stateFileOverride).resolveSibling(PROFILES_FILE_NAME);
        }
        return SiteLockPaths.getStateDir().resolve(PROFILES_FILE_NAME);
    }

    static BlockService newService(String hostsOverride, String stateOverride) {
        Path hostsPath = resolveHostsPath(hostsOverride);
        Path stateFile = resolveStateFile(stateOverride);
        Path backupFile = resolveBackupFile(stateOverride);
        Path hostsLock = stateFile.resolveSibling("hosts.lock");
        BlockStore store = new BlockStore(stateFile);
        HostsFileBlocker blocker = new HostsFileBlocker(hostsPath, backupFile, hostsLock);
        return new BlockService(store, blocker, Clock.systemUTC(), hostsPath);
    }

    /**
     * Com o blocker injetado, para os testes que precisam que a escrita no hosts
     * falhe — é o único jeito de provar que o rollback do {@code profile apply}
     * acontece, já que ninguém vai tornar o {@code /etc/hosts} ilegível no teste.
     */
    static BlockService newService(String hostsOverride, String stateOverride, SiteBlocker blocker) {
        Path stateFile = resolveStateFile(stateOverride);
        return new BlockService(new BlockStore(stateFile), blocker, Clock.systemUTC(),
                resolveHostsPath(hostsOverride));
    }

    /**
     * Dica de elevação específica por SO (componente central: PermissionAdvisor).
     * No Windows instrui "Executar como administrador"; no Unix, "sudo".
     */
    static String elevationHint(String subcommand) {
        return new PermissionAdvisor().elevationHint(SiteLockPaths.getHostsPath(), subcommand);
    }

    /**
     * @deprecated mantido para compatibilidade com testes/código existente;
     *             delega para {@link #elevationHint(String)}.
     */
    @Deprecated
    static String sudoHint(String subcommand) {
        return elevationHint(subcommand);
    }

    /**
     * Monta a dica com sudo usando o caminho real do binário, porque
     * ~/.local/bin costuma ficar fora do secure_path do sudo.
     * No Windows o prefixo "sudo" não se aplica (ver PermissionAdvisor),
     * mas este método é mantido para compatibilidade dos testes.
     */
    static String sudoCommand(String subcommand) {
        String bin = System.getProperty("siteblock.bin", "siteblock");
        String display = "/usr/local/bin/siteblock".equals(bin) ? "siteblock" : bin;
        if (display.contains(" ")) {
            display = "\"" + display + "\"";
        }
        String args = subcommand == null || subcommand.isBlank() ? "" : " " + subcommand;
        return "sudo " + display + args;
    }

    /**
     * Avisa quando um bloqueio com prazo não tem daemon para cumpri-lo.
     *
     * <p>É o aviso que fecha o ciclo. Sem ele, a feature continua invisível e o
     * comportamento antigo — o prazo vence, o site só sai quando alguém abre o
     * terminal — permanece sendo o que a pessoa acha que é o normal.
     *
     * <p>Gatilho: não há daemon vivo <b>ou</b> não há autostart instalado. Os
     * dois são defeito do ponto de vista de quem digitou {@code --for 4h}, e
     * mesmo com autostart instalado o daemon pode estar naquele boot atrasado.
     *
     * <p>Vai para stdout com {@code AVISO:} porque isto não é erro — o
     * bloqueio foi salvo e está funcionando. Erro vai para stderr.
     */
    static void warnIfNoDaemon(String stateFileOverride) {
        if (temDaemonVivo(stateFileOverride)) {
            return;
        }
        System.out.println("AVISO: este bloqueio tem prazo, mas não há daemon de expiração rodando.");
        System.out.println("       O prazo só vai ser aplicado quando você rodar outro comando do "
                + "siteblock (ex.: 'siteblock status').");
        System.out.println("       " + sudoCommand("daemon enable")
                + " para isso acontecer sozinho, inclusive depois de reiniciar.");
    }

    /** true se há um daemon registrado e vivo. Sem elevação: é só leitura. */
    static boolean temDaemonVivo(String stateFileOverride) {
        try {
            return ExpirationDaemonState.read(stateDirFor(stateFileOverride))
                    .map(ExpirationDaemonState::isAlive)
                    .orElse(false);
        } catch (IOException e) {
            // Não achou o registro é o mesmo que não ter daemon: o aviso serve.
            return false;
        }
    }

    /**
     * Lê uma linha do stdin.
     *
     * <p>UM leitor para o processo todo, e não um por pergunta. O
     * {@code Scanner} antigo lia o stdin num buffer de 1 KB e engolia as
     * respostas seguintes: na primeira confirmação de um {@code unblock} com
     * dois domínios, as duas linhas que a pessoa digitou sumiam no buffer, e a
     * segunda confirmação vinha {@code null} — o comando dizia que não
     * desbloqueou, com a intenção da pessoa bem clara.
     *
     * <p>O leitor é refeito quando o {@code System.in} muda — é o que permite
     * ao teste trocar o stdin sem que uma leitura velha responda por outra.
     */
    static String readLine() {
        try {
            InputStream atual = System.in;
            if (stdin == null || stdinStream != atual) {
                stdinStream = atual;
                stdin = new BufferedReader(new InputStreamReader(atual, StandardCharsets.UTF_8));
            }
            return stdin.readLine();
        } catch (IOException e) {
            return null;
        }
    }

    /** O leitor de stdin do processo. */
    private static BufferedReader stdin;

    /** De onde veio o leitor: se o {@code System.in} trocar, o leitor troca junto. */
    private static InputStream stdinStream;

    /**
     * Flush do cache DNS após modificação no hosts — best-effort.
     * Nunca lança exceção nem muda o exit code do comando; só avisa.
     */
    static void flushDnsBestEffort() {
        try {
            new DnsCacheFlusher().flushBestEffort();
        } catch (Exception ignored) {
        }
    }
}
