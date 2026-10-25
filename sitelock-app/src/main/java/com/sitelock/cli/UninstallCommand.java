package com.sitelock.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Uninstall command: restores hosts, removes .sitelock, removes repo and binary.
 * WHY: the user asked to standardize it as `siteblock uninstall`; instead of
 * duplicating the logic in Java, it delegates to the existing script (uninstall.sh / .bat)
 * which already handles hosts, prefs, DNS, repo and binary safely.
 */
@Command(name = "uninstall", description = "Uninstalls SiteBlock (hosts, prefs, repo, binary).")
public class UninstallCommand implements Runnable {

  @CommandLine.Option(names = {"-y", "--yes"}, description = "Confirms without asking.")
  boolean yes;

  @Override
  public void run() {
    // Locates the script relative to the jar (when run from inside the repo or via install)
    Path script = locateScript();
    if (script == null) {
      System.err.println("uninstall: script uninstall.sh / uninstall.bat not found.");
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
      System.err.println("uninstall: failed to run script: " + e.getMessage());
      System.exit(2);
    }
  }

  private Path locateScript() {
    // First: next to the jar (if the jar was copied elsewhere together with the script)
    try {
      File jarDir = new File(getClass().getProtectionDomain().getCodeSource().getLocation().toURI()).getParentFile();
      if (jarDir != null) {
        Path sibling = jarDir.toPath().resolve("uninstall.sh");
        if (Files.exists(sibling)) return sibling;
        Path bat = jarDir.toPath().resolve("uninstall.bat");
        if (Files.exists(bat)) return bat;
      }
    } catch (Exception ignored) {}

    // Second: current working directory (user running from the repo)
    Path cwd = Paths.get("").toAbsolutePath();
    Path sh = cwd.resolve("uninstall.sh");
    if (sh.toFile().exists()) return sh;
    Path bat = cwd.resolve("uninstall.bat");
    if (bat.toFile().exists()) return bat;

    // Third: search the home directory (if the user copied it somewhere known)
    Path home = Paths.get(System.getProperty("user.home"));
    Path shHome = home.resolve("SiteBlock/uninstall.sh");
    if (shHome.toFile().exists()) return shHome;

    return null;
  }

  private boolean isWindows() {
    return System.getProperty("os.name").toLowerCase().contains("win");
  }
}
