package com.sitelock.core;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Leitura/escrita do bloco gerenciado dentro do /etc/hosts.
 *
 * <pre>
 * # SITELOCK-START
 * 127.0.0.1 tiktok.com
 * 127.0.0.1 www.tiktok.com
 * ::1 tiktok.com
 * ::1 www.tiktok.com
 * # SITELOCK-END
 * </pre>
 *
 * Bloco vazio não é mantido: se não sobrar nenhuma entrada, os marcadores
 * também são removidos. A escrita é atômica (temp + move) e nunca escreve
 * por cima do original.
 */
public final class HostsFileManager {

    public static final String START_MARKER = "# SITELOCK-START";
    public static final String END_MARKER = "# SITELOCK-END";
    public static final String REDIRECT_IP = "127.0.0.1";
    public static final String REDIRECT_IPV6 = "::1";

    private HostsFileManager() {
    }

    /** As quatro linhas gerenciadas para um domínio (IPv4 + IPv6, com e sem www). */
    public static List<String> managedLines(String domain) {
        String d = domain.toLowerCase(Locale.ROOT);
        return List.of(
                REDIRECT_IP + " " + d,
                REDIRECT_IP + " www." + d,
                REDIRECT_IPV6 + " " + d,
                REDIRECT_IPV6 + " www." + d);
    }

    /** Os dois hostnames gerenciados para um domínio. */
    public static List<String> managedHosts(String domain) {
        String d = domain.toLowerCase(Locale.ROOT);
        return List.of(d, "www." + d);
    }

    /** Insere o domínio no bloco. Idempotente, nunca duplica linhas nem marcadores. */
    public static String addDomain(String hostsContent, String domain) {
        String content = hostsContent == null ? "" : hostsContent;
        List<String> lines = managedLines(domain);
        List<String> hosts = managedHosts(domain);

        int start = content.indexOf(START_MARKER);
        int end = content.indexOf(END_MARKER);

        if (start >= 0 && end > start) {
            String before = content.substring(0, end);
            String after = content.substring(end);
            String block = content.substring(start, end);
            StringBuilder insert = new StringBuilder();
            if (!containsHostWithIp(block, REDIRECT_IP, hosts.get(0))) {
                insert.append(lines.get(0)).append("\n");
            }
            if (!containsHostWithIp(block, REDIRECT_IPV6, hosts.get(0))) {
                insert.append(lines.get(2)).append("\n");
            }
            if (!containsHostWithIp(block, REDIRECT_IP, hosts.get(1))) {
                insert.append(lines.get(1)).append("\n");
            }
            if (!containsHostWithIp(block, REDIRECT_IPV6, hosts.get(1))) {
                insert.append(lines.get(3)).append("\n");
            }
            if (insert.length() == 0) {
                return content;
            }
            return before + insert + after;
        }

        StringBuilder sb = new StringBuilder(content);
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') {
            sb.append('\n');
        }
        sb.append(START_MARKER).append('\n');
        for (String line : lines) {
            sb.append(line).append('\n');
        }
        sb.append(END_MARKER).append('\n');
        return sb.toString();
    }

    /** Remove o domínio do bloco. Bloco sem entradas é removido por inteiro. */
    public static String removeDomain(String hostsContent, String domain) {
        String content = hostsContent == null ? "" : hostsContent;
        List<String> hosts = managedHosts(domain);

        int start = content.indexOf(START_MARKER);
        int end = content.indexOf(END_MARKER);

        if (start >= 0 && end > start) {
            String head = content.substring(0, start);
            String block = content.substring(start, end);
            String tail = content.substring(end + END_MARKER.length());

            List<String> blockLines = new ArrayList<>(Arrays.asList(block.split("\n", -1)));
            List<String> kept = new ArrayList<>();
            for (String line : blockLines) {
                if (line.contains(START_MARKER)) {
                    kept.add(line);
                    continue;
                }
                String stripped = stripHostnames(line, hosts);
                if (stripped != null) {
                    if (!stripped.isEmpty() || isCommentOrBlank(line)) {
                        kept.add(line);
                    } else if (!stripped.equals(line)) {
                        if (!stripped.isBlank()) {
                            kept.add(stripped);
                        }
                    } else {
                        kept.add(line);
                    }
                }
            }

            while (!kept.isEmpty() && kept.get(kept.size() - 1).isEmpty()) {
                kept.remove(kept.size() - 1);
            }

            boolean hasEntry = kept.stream().anyMatch(HostsFileManager::isEntryLine);
            String newBlock;
            if (!hasEntry) {
                newBlock = "";
            } else {
                newBlock = String.join("\n", kept) + "\n" + END_MARKER;
            }

            String result = head + newBlock + tail;
            result = removeOrphanLines(result, hosts);
            return tidyBlankLines(result);
        }

        return tidyBlankLines(removeOrphanLines(content, hosts));
    }

    private static boolean containsHostWithIp(String block, String ip, String host) {
        for (String line : block.split("\n")) {
            String t = line.strip();
            if (t.isEmpty() || t.startsWith("#")) {
                continue;
            }
            String[] parts = t.split("\\s+");
            if (parts.length < 2 || !parts[0].equalsIgnoreCase(ip)) {
                continue;
            }
            for (int i = 1; i < parts.length; i++) {
                if (parts[i].equalsIgnoreCase(host)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Remove os hostnames alvo de uma linha.
     * @return null se a linha inteira deve sumir, senão a linha reescrita.
     */
    private static String stripHostnames(String line, List<String> hosts) {
        String trimmed = line.strip();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
            return line;
        }
        String[] parts = trimmed.split("\\s+");
        if (parts.length < 2) {
            return line;
        }
        List<String> remaining = new ArrayList<>();
        remaining.add(parts[0]);
        boolean removedAny = false;
        for (int i = 1; i < parts.length; i++) {
            if (parts[i].startsWith("#")) {
                remaining.add(parts[i]);
                for (int j = i + 1; j < parts.length; j++) {
                    remaining.add(parts[j]);
                }
                break;
            }
            boolean target = false;
            for (String h : hosts) {
                if (parts[i].equalsIgnoreCase(h)) {
                    target = true;
                    removedAny = true;
                    break;
                }
            }
            if (!target) {
                remaining.add(parts[i]);
            }
        }
        if (!removedAny) {
            return line;
        }
        if (remaining.size() <= 1) {
            return null;
        }
        return String.join(" ", remaining);
    }

    private static boolean isCommentOrBlank(String line) {
        String t = line.strip();
        return t.isEmpty() || t.startsWith("#");
    }

    private static boolean isEntryLine(String line) {
        String t = line.strip();
        if (t.isEmpty() || t.startsWith("#")) {
            return false;
        }
        return t.split("\\s+").length >= 2;
    }

    private static String removeOrphanLines(String content, List<String> hosts) {
        String[] lines = content.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String rewritten = stripHostnames(lines[i], hosts);
            if (rewritten == null) {
                continue;
            }
            sb.append(rewritten);
            if (i < lines.length - 1) {
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    private static String tidyBlankLines(String s) {
        return s.replaceAll("\n{3,}", "\n\n");
    }

    public static boolean isWritable(Path hostsPath) {
        return Files.isWritable(hostsPath);
    }

    /** Cria o backup do hosts original uma única vez (nunca sobrescreve). */
    public static void ensureBackup(Path hostsPath, Path backupPath) throws IOException {
        if (Files.exists(backupPath)) {
            return;
        }
        Path parent = backupPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.copy(hostsPath, backupPath);
        SiteLockPaths.chownToRealUserIfSudo(backupPath);
    }

    public static String readHosts(Path hostsPath) throws IOException {
        if (!Files.exists(hostsPath)) {
            return "";
        }
        return Files.readString(hostsPath, StandardCharsets.UTF_8);
    }

    /** Aplica bloqueio no arquivo com backup, lock e escrita atômica. */
    public static void blockInFile(Path hostsPath, Path backupPath, Path lockFile, String domain)
            throws IOException {
        modifyHosts(hostsPath, backupPath, lockFile, addDomain(readHostsLocked(hostsPath, lockFile), domain),
                readHostsLocked(hostsPath, lockFile));
    }

    /** Remove bloqueio no arquivo. */
    public static void unblockInFile(Path hostsPath, Path backupPath, Path lockFile, String domain)
            throws IOException {
        String current = readHostsLocked(hostsPath, lockFile);
        modifyHosts(hostsPath, backupPath, lockFile, removeDomain(current, domain), current);
    }

    private static String readHostsLocked(Path hostsPath, Path lockFile) throws IOException {
        ensureLockParent(lockFile);
        try (FileChannel channel = FileChannel.open(lockFile,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            SiteLockPaths.chownToRealUserIfSudo(lockFile);
            return readHosts(hostsPath);
        }
    }

    private static void modifyHosts(Path hostsPath, Path backupPath, Path lockFile,
                                    String newContent, String originalContent) throws IOException {
        ensureLockParent(lockFile);
        try (FileChannel channel = FileChannel.open(lockFile,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            SiteLockPaths.chownToRealUserIfSudo(lockFile);
            String current = readHosts(hostsPath);
            if (!current.equals(originalContent)) {
                throw new IOException("O arquivo hosts (" + hostsPath
                        + ") foi modificado concorrentemente. Tente novamente.");
            }
            if (current.equals(newContent)) {
                return;
            }
            ensureBackup(hostsPath, backupPath);
            writeAtomically(hostsPath, newContent);
        } catch (IOException e) {
            throw new IOException("Falha ao atualizar " + hostsPath + ": " + e.getMessage()
                    + " (nenhuma alteração parcial foi aplicada)", e);
        }
    }

    /**
     * Escrita atômica preservando dono/permissões do alvo.
     * Se o arquivo se chama "hosts" e está sem leitura para outros,
     * adiciona leitura de grupo/outros (repara permissão 600 quebrada).
     */
    static void writeAtomically(Path target, String content) throws IOException {
        boolean targetExists = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
        Set<PosixFilePermission> origPerms = null;
        UserPrincipal origOwner = null;
        GroupPrincipal origGroup = null;
        if (targetExists) {
            try {
                origPerms = Files.getPosixFilePermissions(target, LinkOption.NOFOLLOW_LINKS);
            } catch (UnsupportedOperationException | IOException ignored) {
            }
            try {
                origOwner = Files.getOwner(target, LinkOption.NOFOLLOW_LINKS);
            } catch (UnsupportedOperationException | IOException ignored) {
            }
            try {
                PosixFileAttributes attrs =
                        Files.readAttributes(target, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                origGroup = attrs.group();
            } catch (UnsupportedOperationException | IOException ignored) {
            }
        }
        Path dir = target.getParent() != null ? target.getParent() : Path.of(".");
        Path tmp = Files.createTempFile(dir, ".sitelock-", ".tmp");
        try {
            Files.writeString(tmp, content, StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            applyTargetAttributes(tmp, target, origPerms, origOwner, origGroup);
            try {
                Files.move(tmp, target,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            // Alguns FSs podem não preservar attrs no rename: garante no alvo final.
            // O hosts NUNCA recebe chown (deve continuar root); só perms.
            applyTargetAttributes(target, target, origPerms, null, null);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static void applyTargetAttributes(Path file, Path target,
                                              Set<PosixFilePermission> origPerms,
                                              UserPrincipal origOwner,
                                              GroupPrincipal origGroup) {
        boolean isHosts = target.getFileName() != null
                && target.getFileName().toString().equals("hosts");
        if (origPerms != null) {
            try {
                Set<PosixFilePermission> toApply = EnumSet.copyOf(origPerms);
                if (isHosts && !toApply.contains(PosixFilePermission.OTHERS_READ)) {
                    // Auto-reparo do estrago 600: hosts precisa ser legível por todos.
                    toApply.add(PosixFilePermission.GROUP_READ);
                    toApply.add(PosixFilePermission.OTHERS_READ);
                }
                Files.setPosixFilePermissions(file, toApply);
            } catch (UnsupportedOperationException | IOException ignored) {
            }
        } else if (isHosts) {
            try {
                Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
            } catch (UnsupportedOperationException | IOException ignored) {
            }
        }
        if (origOwner != null) {
            try {
                Files.setOwner(file, origOwner);
            } catch (UnsupportedOperationException | IOException ignored) {
            }
        }
        if (origGroup != null) {
            try {
                PosixFileAttributeView view =
                        Files.getFileAttributeView(file, PosixFileAttributeView.class);
                if (view != null) {
                    view.setGroup(origGroup);
                }
            } catch (UnsupportedOperationException | IOException ignored) {
            }
        }
    }

    private static void ensureLockParent(Path lockFile) throws IOException {
        Path parent = lockFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }
}
