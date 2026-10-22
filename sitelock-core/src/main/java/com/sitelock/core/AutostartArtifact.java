package com.sitelock.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Gera o texto do artefato de autostart de cada SO.
 *
 * <p>Puro: nenhum caminho é resolvido aqui dentro. Tudo chega já absoluto pelo
 * {@link AutostartPaths} — resolver no gerador seria resolver no momento
 * errado, que é o daemon rodando como root sem {@code SUDO_USER}.
 */
public final class AutostartArtifact {

    /** Nome da tarefa no Windows. */
    public static final String TASK_NAME = "sitelock-sweeper";
    /** Label da LaunchDaemon no macOS. */
    public static final String LABEL = "com.sitelock.sweeper";
    /** Nome da unit no Linux. */
    public static final String UNIT_NAME = "sitelock-sweeper.service";

    private static final int DEFAULT_INTERVAL_SECONDS = 60;

    private AutostartArtifact() {
    }

    /**
     * O comando do daemon, como tokens.
     *
     * <p>Compartilhado pelos três artefatos — é a lista que vira o ExecStart, o
     * ProgramArguments ou o {@code /tr}. Manter uma só fonte é o que impede o
     * caso em que o macOS recebe o caminho certo e o Linux recebe o errado.
     *
     * <p>A forma da linha é montada por {@link DaemonCommandLine}, que é também
     * o que o fork do {@code daemon start} usa: a unit e o fork precisam
     * Produzir a <b>mesma</b> invocação, senão um caminho sobe e o outro não.
     */
    public static List<String> commandLine(AutostartPaths p) {
        List<String> properties = new ArrayList<>();
        properties.add("-Dsitelock.stateDir=" + p.stateDir().toAbsolutePath());
        properties.add("-Dsitelock.hostsFile=" + p.hostsFile().toAbsolutePath());
        if (p.realUserName() != null && !p.realUserName().isBlank()) {
            properties.add("-Dsitelock.user=" + p.realUserName());
        }
        return DaemonCommandLine.build(p.javaBin(), p.jarPath(), p.classpath(), properties,
                List.of("daemon", "__run-daemon", "--interval",
                        String.valueOf(DEFAULT_INTERVAL_SECONDS)));
    }

    /** Unit do systemd, para {@code /etc/systemd/system/sitelock-sweeper.service}. */
    public static String systemdUnit(AutostartPaths p) {
        StringBuilder sb = new StringBuilder();
        sb.append("[Unit]\n");
        sb.append("Description=SiteBlock: libera sozinho os bloqueios expirados\n");
        sb.append("Documentation=https://github.com/Campeol-G/SiteBlock\n");
        sb.append("\n[Service]\n");
        sb.append("Type=simple\n");
        sb.append("ExecStart=").append(execStart(p)).append('\n');
        sb.append("Restart=always\n");
        sb.append("RestartSec=5\n");
        // Unit de sistema de propósito: sem User=, porque sem elevação o daemon
        // não consegue tirar nada do hosts — que é a única coisa que ele faz.
        sb.append("\n[Install]\n");
        sb.append("WantedBy=multi-user.target\n");
        return sb.toString();
    }

    /** Plist da LaunchDaemon, para {@code /Library/LaunchDaemons}. */
    public static String launchdPlist(AutostartPaths p) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" "
                + "\"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n");
        sb.append("<plist version=\"1.0\">\n<dict>\n");
        sb.append("  <key>Label</key>\n  <string>").append(LABEL).append("</string>\n");
        sb.append("  <key>ProgramArguments</key>\n  <array>\n");
        for (String arg : commandLine(p)) {
            sb.append("    <string>").append(escapeXml(arg)).append("</string>\n");
        }
        sb.append("  </array>\n");
        sb.append("  <key>RunAtLoad</key>\n  <true/>\n");
        sb.append("  <key>KeepAlive</key>\n  <true/>\n");
        sb.append("</dict>\n</plist>\n");
        return sb.toString();
    }

    /**
     * Comando {@code schtasks} que instala a tarefa.
     *
     * <p>Dispara em <b>ONLOGON</b>, não em ONSTART: {@code /ru SYSTEM} trocaria
     * a identidade do processo, e todo arquivo do diretório de estado nasceria
     * do SYSTEM.
     *
     * <p>{@code ONLOGON} com {@code HIGHEST} dá o root que o daemon precisa sem
     * esse custo. Perde-se só o caso "ligada, ninguém logado" — e sem sessão não
     * há navegador para estar bloqueado.
     */
    public static String schtasksCommand(AutostartPaths p) {
        return String.join(" ", schtasksArgs(p)) + " /f";
    }

    /** O mesmo comando em lista de tokens, para o instalador executar direto. */
    public static List<String> schtasksArgs(AutostartPaths p) {
        List<String> args = new ArrayList<>();
        args.add("schtasks");
        args.add("/create");
        args.add("/sc");
        args.add("ONLOGON");
        args.add("/rl");
        args.add("HIGHEST");
        args.add("/tn");
        args.add(TASK_NAME);
        args.add("/tr");
        args.add(quotedTaskCommand(p));
        return args;
    }

    /** Comando do Windows para remover a tarefa. */
    public static List<String> schtasksDeleteArgs() {
        return List.of("schtasks", "/delete", "/tn", TASK_NAME, "/f");
    }

    private static String quotedTaskCommand(AutostartPaths p) {
        // O /tr é um único argumento: aspas por fora, e aspas internas
        // duplicadas — é a regra do cmd.exe, não a do shell Unix.
        StringBuilder sb = new StringBuilder("\"");
        for (String arg : commandLine(p)) {
            sb.append(arg.contains(" ") ? "\\\"\"" + arg + "\\\"\" " : arg).append(' ');
        }
        sb.append('"');
        return sb.toString().trim();
    }

    private static String execStart(AutostartPaths p) {
        StringBuilder sb = new StringBuilder();
        for (String arg : commandLine(p)) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            // O systemd não faz split de shell: o espaço quebraria a diretiva.
            sb.append(arg.contains(" ") ? "\"" + arg + "\"" : arg);
        }
        return sb.toString();
    }

    private static String escapeXml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}