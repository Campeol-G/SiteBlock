package com.sitelock.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Instala e remove o artefato de autostart, e chama o agendador.
 *
 * <p>{@code systemRoot} é {@code /} em produção e um diretório temporário nos
 * testes; todo caminho do artefato sai de {@code systemRoot.resolve(...)}, e é
 * isso que torna o enable/disable testável sem root.
 */
public final class AutostartInstaller {

    /** Resultado de enable/disable: sucesso e o detalhe para o usuário. */
    public record InstallResult(boolean success, String detail) {
    }

    private static final int COMMAND_TIMEOUT_SECONDS = 15;

    private final OsType os;
    private final Path systemRoot;
    private final CommandRunner runner;

    public AutostartInstaller(OsType os, Path systemRoot, CommandRunner runner) {
        this.os = os == null ? OsType.UNKNOWN : os;
        this.systemRoot = systemRoot == null ? Path.of("/") : systemRoot;
        this.runner = runner == null ? AutostartInstaller::runWithProcessBuilder : runner;
    }

    /**
     * Onde vive o artefato neste SO.
     *
     * @param systemRoot {@code /} em produção; raiz de teste nos testes
     */
    public static Path artifactPath(OsType os, Path systemRoot) {
        Path root = systemRoot == null ? Path.of("/") : systemRoot;
        if (os != null && os.isMac()) {
            return root.resolve("Library/LaunchDaemons/" + AutostartArtifact.LABEL + ".plist");
        }
        return root.resolve("etc/systemd/system/" + AutostartArtifact.UNIT_NAME);
    }

    public Path artifactPath() {
        return artifactPath(os, systemRoot);
    }

    /**
     * O autostart está ligado?
     *
     * <p>Estático e sem {@code CommandRunner} de propósito: o
     * {@code daemon status} e o aviso do {@code block --for} consultam isto sem
     * precisar de elevação nem montar executor nenhum.
     *
     * <p>No Linux e no macOS é só teste de existência do arquivo. No Windows
     * não há arquivo, então é um {@code schtasks /query} — também sem elevação,
     * e também só leitura.
     */
    public static boolean isEnabled(OsType os, Path systemRoot) {
        if (os != null && os.isWindows()) {
            try {
                return runWithProcessBuilder(
                        List.of("schtasks", "/query", "/tn", AutostartArtifact.TASK_NAME)) == 0;
            } catch (Exception e) {
                return false;
            }
        }
        return Files.exists(artifactPath(os, systemRoot));
    }

    /** Escreve o artefato e liga o agendador. */
    public InstallResult install(AutostartPaths paths) {
        Path target = artifactPath();
        try {
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            String conteudo = os.isMac()
                    ? AutostartArtifact.launchdPlist(paths)
                    : AutostartArtifact.systemdUnit(paths);
            Files.writeString(target, conteudo, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return new InstallResult(false,
                    "Falha ao escrever " + target + ": " + e.getMessage());
        }

        List<String> comando = os.isWindows() ? AutostartArtifact.schtasksArgs(paths)
                : schedulerEnableCommand(os);
        try {
            int exit = runner.run(comando);
            if (exit != 0) {
                return new InstallResult(false, String.join(" ", comando) + " falhou com código " + exit);
            }
        } catch (IOException e) {
            return new InstallResult(false, String.join(" ", comando) + ": " + e.getMessage());
        }
        return new InstallResult(true, String.join(" ", comando));
    }

    /** Remove o artefato e desliga o agendador. */
    public InstallResult disable() {
        try {
            Files.deleteIfExists(artifactPath());
        } catch (IOException e) {
            return new InstallResult(false, "Falha ao remover o artefato: " + e.getMessage());
        }
        List<String> comando = os.isWindows() ? AutostartArtifact.schtasksDeleteArgs()
                : schedulerDisableCommand(os);
        try {
            runner.run(comando);
        } catch (IOException e) {
            // O artefato já foi removido; o agendador não existir é o menor problema.
            return new InstallResult(true, "Artefato removido; " + String.join(" ", comando)
                    + " não pôde ser executado (" + e.getMessage() + ").");
        }
        return new InstallResult(true, String.join(" ", comando));
    }

    static List<String> schedulerEnableCommand(OsType os) {
        if (os != null && os.isMac()) {
            return List.of("launchctl", "bootstrap", "system",
                    artifactPath(os, Path.of("/")).toString());
        }
        return List.of("systemctl", "enable", "--now", AutostartArtifact.UNIT_NAME);
    }

    static List<String> schedulerDisableCommand(OsType os) {
        if (os != null && os.isMac()) {
            return List.of("launchctl", "bootout", "system",
                    artifactPath(os, Path.of("/")).toString());
        }
        return List.of("systemctl", "disable", "--now", AutostartArtifact.UNIT_NAME);
    }

    private static int runWithProcessBuilder(List<String> command) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        boolean done;
        try {
            done = p.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            throw new IOException("interrompido aguardando " + command.get(0), e);
        }
        if (!done) {
            p.destroyForcibly();
            throw new IOException("timeout (" + COMMAND_TIMEOUT_SECONDS + "s) em "
                    + String.join(" ", command).toLowerCase(Locale.ROOT));
        }
        return p.exitValue();
    }
}