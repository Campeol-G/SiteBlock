package com.sitelock.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Comando de desinstalação: restaura hosts, remove .sitelock, remove repo e binário.
 * POR QUE: o usuário pediu padronizar como `siteblock uninstall`; ao invés de
 * duplicar a lógica em Java, delega ao script existente (uninstall.sh / .bat)
 * que já trata hosts, prefs, DNS, repo e binário de forma segura.
 */
@Command(name = "uninstall", description = "Desinstala o SiteBlock (hosts, prefs, repo, binário).")
public class UninstallCommand implements Runnable {

  @CommandLine.Option(names = {"-y", "--yes"}, description = "Confirma sem perguntar.")
  boolean yes;

  @Override
  public void run() {
    // Localiza o script relativo ao jar (se executado de dentro do repo ou via install)
    Path script = locateScript();
    if (script == null) {
      System.err.println("uninstall: script uninstall.sh / uninstall.bat nao encontrado.");
      System.exit(1);
      return;
    }

    ProcessBuilder pb = new ProcessBuilder();
    pb.inheritIO();

    if (isWindows()) {
      pb.command("cmd", "/c", script.toString());
    } else {
      pb.command("bash", script.toString());
    }

    if (yes) {
      pb.environment().put("UNINSTALL_YES", "1");
    }

    try {
      Process p = pb.start();
      int exit = p.waitFor();
      System.exit(exit);
    } catch (Exception e) {
      System.err.println("uninstall: falha ao executar script: " + e.getMessage());
      System.exit(2);
    }
  }

  private Path locateScript() {
    // Primeiro: proximo ao jar (se o jar for copiado para outro lugar com o script)
    try {
      File jarDir = new File(getClass().getProtectionDomain().getCodeSource().getLocation().toURI()).getParentFile();
      if (jarDir != null) {
        Path sibling = jarDir.toPath().resolve("uninstall.sh");
        if (Files.exists(sibling)) return sibling;
        Path bat = jarDir.toPath().resolve("uninstall.bat");
        if (Files.exists(bat)) return bat;
      }
    } catch (Exception ignored) {}

    // Segundo: diretorio de trabalho atual (usuario rodan do repo)
    Path cwd = Paths.get("").toAbsolutePath();
    Path sh = cwd.resolve("uninstall.sh");
    if (sh.toFile().exists()) return sh;
    Path bat = cwd.resolve("uninstall.bat");
    if (bat.toFile().exists()) return bat;

    // Terceiro: busca na home (se usuario copiou para algum lugar conhecido)
    Path home = Paths.get(System.getProperty("user.home"));
    Path shHome = home.resolve("SiteBlock/uninstall.sh");
    if (shHome.toFile().exists()) return shHome;

    return null;
  }

  private boolean isWindows() {
    return System.getProperty("os.name").toLowerCase().contains("win");
  }
}
