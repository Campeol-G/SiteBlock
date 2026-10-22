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

    /**
     * Resultado de enable/disable: sucesso e o detalhe para o usuário.
     *
     * <p>O caminho vem separado do texto: um para o olho da pessoa, outro para
     * o artefato. Misturar os dois gerou uma unit apontando para o nada:
     * {@code -jar "O jar já está no lugar: /opt/sitelock/siteblock.jar"}.
     *
     * @param detail a mensagem para o usuário
     * @param path   o caminho do jar ou do artefato; null quando não há
     */
    public record InstallResult(boolean success, String detail, Path path) {

        /** Resultado sem caminho: só a mensagem. */
        public InstallResult(boolean success, String detail) {
            this(success, detail, null);
        }
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
     * Onde o jar do daemon é instalado: um diretório do sistema, que só o root
     * escreve.
     *
     * <p><b>{@code mvn clean} não derruba mais o daemon.</b> A unit apontava
     * para o {@code target/}, que o clean apaga: o systemd entrava em loop de
     * crash e o prazo dos bloqueios parava de ser cumprido em silêncio.
     *
     * <p>O daemon também deixa de executar código que o usuário pode trocar:
     * o jar original vive num diretório gravável por construção, que era o
     * motivo do aviso de elevação. A cópia vive num diretório do root.
     *
     * @param systemRoot {@code /} em produção; raiz de teste nos testes
     */
    public static Path installDir(OsType os, Path systemRoot) {
        Path root = systemRoot == null ? Path.of("/") : systemRoot;
        if (os != null && os.isWindows()) {
            return root.resolve("ProgramData/SiteBlock");
        }
        if (os != null && os.isMac()) {
            return root.resolve("usr/local/lib/sitelock");
        }
        return root.resolve("opt/sitelock");
    }

    /** O caminho do jar instalado. */
    public static Path installJarPath(OsType os, Path systemRoot) {
        return installDir(os, systemRoot).resolve("siteblock.jar");
    }

    /**
     * Copia o jar para o local estável.
     *
     * <p>Sempre sobrescreve: reinstalar é como o daemon passa a rodar a versão
     * nova depois de um rebuild. Copiar o jar para cima de si mesmo é pulado —
     * o destino some no meio da cópia e o daemon fica sem executável.
     *
     * @param origem jar a instalar; null ou inexistente = erro
     */
    public static InstallResult installJar(OsType os, Path systemRoot, Path origem) {
        if (origem == null) {
            return new InstallResult(false,
                    "Não sei qual jar instalar. Rode a partir do siteblock empacotado, ou "
                            + "informe o caminho: siteblock daemon enable --jar <caminho>");
        }
        Path origemAbs = origem.toAbsolutePath();
        if (!Files.exists(origemAbs)) {
            return new InstallResult(false, "O jar " + origemAbs + " não existe. "
                    + "Rode 'mvn package' para gerar, ou informe outro caminho com --jar.");
        }
        Path destino = installJarPath(os, systemRoot);
        if (destino.toAbsolutePath().equals(origemAbs)) {
            return new InstallResult(true, "O jar já está no lugar: " + destino, destino);
        }
        try {
            Path parent = destino.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.copy(origemAbs, destino,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return new InstallResult(true, "Jar instalado em " + destino, destino);
        } catch (IOException e) {
            return new InstallResult(false,
                    "Falha ao instalar o jar em " + destino + ": " + e.getMessage());
        }
    }

    /**
     * Onde vive o artefato neste SO.
     *
     * <p>No Windows não existe arquivo: a definição vive na Tarefa do
     * Agendador, que não está em lugar nenhum que se possa apontar. Por isso
     * devolve {@code null} e {@link #install} não escreve nada.
     *
     * <p>Escrever criaria uma árvore {@code C:\etc\systemd\system\} de lixo com
     * uma unit Linux dentro, e ainda assim imprimiria "instalado".
     *
     * @param systemRoot {@code /} em produção; raiz de teste nos testes
     * @return o caminho do artefato, ou null se este SO não usa arquivo
     */
    public static Path artifactPath(OsType os, Path systemRoot) {
        if (os != null && os.isWindows()) {
            return null;
        }
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
        Path artifact = artifactPath(os, systemRoot);
        return artifact != null && Files.exists(artifact);
    }

    /** Escreve o artefato e liga o agendador. */
    public InstallResult install(AutostartPaths paths) {
        Path target = artifactPath();
        if (target == null) {
            // Windows: a definição vive na Tarefa, não em arquivo.
            List<String> comando = AutostartArtifact.schtasksArgs(paths);
            try {
                int exit = runner.run(comando);
                if (exit != 0) {
                    return new InstallResult(false,
                            String.join(" ", comando) + " falhou com código " + exit);
                }
            } catch (IOException e) {
                return new InstallResult(false, String.join(" ", comando) + ": " + e.getMessage());
            }
            return new InstallResult(true, String.join(" ", comando));
        }
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

        List<String> comando = schedulerEnableCommand(os, systemRoot);
        try {
            // daemon-reload antes de enable: sem ele o systemd pode responder com
            // a unit antiga em cache e ignorar a que acabamos de escrever.
            runner.run(List.of("systemctl", "daemon-reload"));
        } catch (IOException ignored) {
            // Sem systemd não há o que recarregar; o enable abaixo avisa.
        }
        try {
            int exit = runner.run(comando);
            if (exit != 0) {
                return new InstallResult(false, String.join(" ", comando) + " falhou com código " + exit);
            }
        } catch (IOException e) {
            return new InstallResult(false, String.join(" ", comando) + ": " + e.getMessage());
        }
        return new InstallResult(true, String.join(" ", comando), target);
    }

    /** Remove o artefato e desliga o agendador. */
    public InstallResult disable() {
        Path target = artifactPath();
        if (target != null) {
            try {
                Files.deleteIfExists(target);
            } catch (IOException e) {
                return new InstallResult(false, "Falha ao remover o artefato: " + e.getMessage());
            }
        }
        List<String> comando = os.isWindows() ? AutostartArtifact.schtasksDeleteArgs()
                : schedulerDisableCommand(os, systemRoot);
        try {
            runner.run(comando);
        } catch (IOException e) {
            // O artefato já foi removido; o agendador não existir é o menor problema.
            return new InstallResult(true, "Artefato removido; " + String.join(" ", comando)
                    + " não pôde ser executado (" + e.getMessage() + ").", target);
        }
        return new InstallResult(true, String.join(" ", comando), target);
    }

    static List<String> schedulerEnableCommand(OsType os, Path systemRoot) {
        if (os != null && os.isMac()) {
            return List.of("launchctl", "bootstrap", "system",
                    String.valueOf(artifactPath(os, systemRoot)));
        }
        return List.of("systemctl", "enable", "--now", AutostartArtifact.UNIT_NAME);
    }

    static List<String> schedulerDisableCommand(OsType os, Path systemRoot) {
        if (os != null && os.isMac()) {
            return List.of("launchctl", "bootout", "system",
                    String.valueOf(artifactPath(os, systemRoot)));
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