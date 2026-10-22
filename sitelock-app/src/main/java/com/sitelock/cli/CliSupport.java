package com.sitelock.cli;

import com.sitelock.blocker.HostsFileBlocker;
import com.sitelock.blocker.SiteBlocker;
import com.sitelock.core.BlockService;
import com.sitelock.core.BlockStore;
import com.sitelock.core.DnsCacheFlusher;
import com.sitelock.core.ExpirationDaemonState;
import com.sitelock.core.OsType;
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

// Helpers compartilhados pelos comandos CLI.
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

    // Diretório de estado considerando o override de --state-file.
    // POR QUE: sem isto, um comando leria o daemon.json do ~/.sitelock real
    // enquanto operava no arquivo de teste — mesmo raciocínio de DnsDaemonState.stateDirFor.
    static java.nio.file.Path stateDirFor(String stateFileOverride) {
        if (stateFileOverride != null && !stateFileOverride.isBlank()) {
            java.nio.file.Path sf = Paths.get(stateFileOverride);
            java.nio.file.Path parent = sf.getParent();
            return parent != null ? parent : Paths.get(".");
        }
        return SiteLockPaths.getStateDir();
    }

    // Onde mora o profiles.json: irmão do blocks.json, pelo mesmo motivo do
    // hosts.backup e do hosts.lock — o --state-file dos testes aponta o
    // diretório inteiro, sem precisar de uma flag nova.
    static Path resolveProfilesFile(String stateFileOverride) {
        if (stateFileOverride != null && !stateFileOverride.isBlank()) {
            return Paths.get(stateFileOverride).resolveSibling(PROFILES_FILE_NAME);
        }
        return SiteLockPaths.getStateDir().resolve(PROFILES_FILE_NAME);
    }

    // POR QUE: o resto do projeto injeta Clock em vez de chamar Instant.now(),
    // para que os testes possam usar um tempo controlado — o comando que grava
    // um instante tem que entrar na mesma.
    static Clock clock() {
        return Clock.systemUTC();
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

    // Com o blocker injetado, para os testes que precisam que a escrita no hosts
    // falhe — é o único jeito de provar que o rollback do profile apply
    // acontece, já que ninguém vai tornar o /etc/hosts ilegível no teste.
    static BlockService newService(String hostsOverride, String stateOverride, SiteBlocker blocker) {
        Path stateFile = resolveStateFile(stateOverride);
        return new BlockService(new BlockStore(stateFile), blocker, Clock.systemUTC(),
                resolveHostsPath(hostsOverride));
    }

    // Dica de elevação específica por SO (vem do PermissionAdvisor). No Windows
    // instrui "Executar como administrador"; no Unix, "sudo".
    static String elevationHint(String subcommand) {
        return new PermissionAdvisor(osType()).elevationHint(SiteLockPaths.getHostsPath(), subcommand);
    }

    // O comando de elevação, no vocabulário de cada SO: no Unix, sudo com o
    // caminho real do binário (o ~/.local/bin fica fora do secure_path); no
    // Windows quem eleva é o terminal, e o texto vem do PermissionAdvisor.
    static String elevatedCommand(String subcommand) {
        if (osType().isWindows()) {
            return elevationHint(subcommand);
        }
        return sudoCommand(subcommand);
    }

    // O SO dos textos de elevação.
    // ATENÇÃO: o binário é o mesmo nos três SOs, e o texto muda — um "sudo
    // siteblock daemon enable" no Windows não existe na máquina de quem lê.
    static OsType osType() {
        return OsType.detect();
    }

    // POR QUE: mantido só por compatibilidade com testes/código existente —
    // delega para elevationHint.
    @Deprecated
    static String sudoHint(String subcommand) {
        return elevationHint(subcommand);
    }

    // Monta a dica com sudo usando o caminho real do binário, porque o
    // ~/.local/bin costuma ficar fora do secure_path do sudo. No Windows o
    // prefixo "sudo" não se aplica, mas o método fica por compatibilidade.
    static String sudoCommand(String subcommand) {
        String bin = System.getProperty("siteblock.bin", "siteblock");
        String display = "/usr/local/bin/siteblock".equals(bin) ? "siteblock" : bin;
        if (display.contains(" ")) {
            display = "\"" + display + "\"";
        }
        String args = subcommand == null || subcommand.isBlank() ? "" : " " + subcommand;
        return "sudo " + display + args;
    }

    // Avisa quando um bloqueio com prazo não tem daemon para cumpri-lo: sem ele
    // a feature continua invisível e o prazo só vence quando alguém abre o
    // terminal. POR QUE: sai em stdout com "AVISO:" porque não é erro.

    // ATENÇÃO: o gatilho é só "não há daemon vivo" — o autostart não é verificado aqui.
    static void warnIfNoDaemon(String stateFileOverride) {
        if (temDaemonVivo(stateFileOverride)) {
            return;
        }
        System.out.println("AVISO: este bloqueio tem prazo, mas não há daemon de expiração rodando.");
        System.out.println("       O prazo só vai ser aplicado quando você rodar outro comando do "
                + "siteblock (ex.: 'siteblock status').");
        System.out.println("       Para o daemon passar a rodar sozinho, inclusive depois de reiniciar:");
        System.out.println("       " + elevatedCommand("daemon enable"));
    }

    // true se há um daemon registrado e vivo. Sem elevação: é só leitura.
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

    // POR QUE: um leitor para o processo todo, e não um por pergunta — o
    // Scanner antigo engolia as respostas seguintes no buffer de 1 KB: a
    // segunda confirmação vinha null e o comando dizia que não desbloqueou.

    // ATENÇÃO: o leitor é refeito quando o System.in muda — é o que permite ao
    // teste trocar o stdin sem que uma leitura velha responda por outra.
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

    // O leitor de stdin do processo.
    private static BufferedReader stdin;

    // De onde veio o leitor: se o System.in trocar, o leitor troca junto.
    private static InputStream stdinStream;

    // Flush do cache DNS após modificação no hosts — best-effort: nunca lança
    // exceção nem muda o exit code do comando; só avisa.
    static void flushDnsBestEffort() {
        try {
            new DnsCacheFlusher().flushBestEffort();
        } catch (Exception ignored) {
        }
    }
}
