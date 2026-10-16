package com.sitelock.core;

import java.nio.file.Path;

/**
 * Tudo o que o artefato de autostart precisa, já resolvido e absoluto.
 *
 * <p>Estes valores são resolvidos no instante do {@code daemon enable} e
 * embutidos no artefato. O caminho não pode ser resolvido depois, dentro do
 * daemon: um unit de <b>sistema</b> (sem {@code User=}), uma LaunchDaemon e um
 * {@code schtasks /ru SYSTEM} rodam como root <b>sem {@code SUDO_USER}</b>, e
 * aí {@code user.home} é {@code /root} ou o profile do SYSTEM. O daemon leria
 * um {@code blocks.json} que não é o do {@code sudo siteblock block} — e a
 * feature inteira vira um no-op silencioso.
 *
 * @param stateDir      diretório de estado do usuário real
 * @param hostsFile     arquivo de hosts do sistema
 * @param javaBin       caminho absoluto do java
 * @param jarPath       caminho absoluto do fat-jar, ou null para usar o classpath
 * @param classpath     classpath de reserva, quando não há jar
 * @param realUserName  usuário a quem devolver a posse dos arquivos escritos;
 *                      null quando desconhecido
 * @param os            sistema operacional-alvo
 */
public record AutostartPaths(Path stateDir, Path hostsFile, String javaBin,
                            String jarPath, String classpath, String realUserName, OsType os) {
}