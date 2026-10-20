package com.sitelock.core;

import java.util.ArrayList;
import java.util.List;

/**
 * A invocação da JVM que sobe o daemon de expiração.
 *
 * <p>Fonte única para os quatro lugares que sobem o daemon: a unit do systemd,
 * a LaunchDaemon do macOS, a tarefa do Windows e o fork do {@code daemon
 * start}. Quando a linha de comando da JVM era montada em cada um deles, cada
 * um tinha a sua própria forma — e cada forma tinha o seu próprio defeito.
 *
 * <p><b>A regra que a JVM impõe:</b> com {@code -jar <jar>} a JVM lê o
 * {@code Main-Class} do manifest e <b>para de interpretar opções</b> — tudo
 * que vier depois do caminho do jar é argumento do programa. Duas consequências,
 * e as duas já quebraram o daemon de verdade:
 *
 * <ul>
 *   <li>o nome da classe depois do {@code -jar} sobra como argumento órfão, e
 *       o picocli recusa a invocação inteira com "Unmatched argument at
 *       index 0" e código de saída 2;</li>
 *   <li>uma {@code -D} depois do {@code -jar} deixa de ser property e vira
 *       argumento — "Unknown options", código 2 de novo.</li>
 * </ul>
 *
 * <p>Sem {@code -jar} não há manifest: aí a JVM precisa do nome da classe, e
 * as {@code -D} podem vir antes ou depois do {@code -cp}, porque o classpath é
 * uma opção que consome um argumento só. É por isso que o nome da classe é
 * acrescentado só no caminho do classpath.
 *
 * <p>Ordem final: {@code java} → properties → {@code -jar} <i>ou</i>
 * {@code -cp} + classe → argumentos do programa.
 */
public final class DaemonCommandLine {

    /** Classe principal; é o {@code Main-Class} do manifest do fat-jar. */
    public static final String MAIN_CLASS = "com.sitelock.SiteLockApplication";

    private DaemonCommandLine() {
    }

    /**
     * @param javaBin      executável da JVM
     * @param jarPath      caminho do fat-jar; null ou vazio = usar o classpath
     * @param classpath    classpath de reserva, usado só quando não há jar
     * @param jvmProperties properties {@code -D...}, já no formato final
     * @param appArgs      argumentos do programa (o que vem depois da classe)
     */
    public static List<String> build(String javaBin, String jarPath, String classpath,
                                     List<String> jvmProperties, List<String> appArgs) {
        List<String> cmd = new ArrayList<>();
        cmd.add(javaBin);
        if (jvmProperties != null) {
            cmd.addAll(jvmProperties);
        }
        if (jarPath != null && !jarPath.isBlank()) {
            cmd.add("-jar");
            cmd.add(jarPath);
            // Sem o nome da classe: com -jar, o manifest já diz qual é.
        } else {
            cmd.add("-cp");
            cmd.add(classpath == null ? "." : classpath);
            cmd.add(MAIN_CLASS);
        }
        if (appArgs != null) {
            cmd.addAll(appArgs);
        }
        return cmd;
    }
}
