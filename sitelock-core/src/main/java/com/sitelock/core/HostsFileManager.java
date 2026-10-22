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
        String base = baseDomain(domain);
        return List.of(
                REDIRECT_IP + " " + base,
                REDIRECT_IP + " www." + base,
                REDIRECT_IPV6 + " " + base,
                REDIRECT_IPV6 + " www." + base);
    }

    /** Os dois hostnames gerenciados para um domínio. */
    public static List<String> managedHosts(String domain) {
        String base = baseDomain(domain);
        return List.of(base, "www." + base);
    }

    // POR QUE: o bloqueio é o mesmo com ou sem o prefixo "www", e "www." +
    // "www.x.com" seria "www.www.x.com" — um host que não existe.
    private static String baseDomain(String domain) {
        String d = domain.toLowerCase(Locale.ROOT);
        while (d.startsWith("www.") && d.length() > "www.".length()) {
            d = d.substring("www.".length());
        }
        return d;
    }

    /** Insere o domínio no bloco. Idempotente, nunca duplica linhas nem marcadores. */
    public static String addDomain(String hostsContent, String domain) {
        String content = hostsContent == null ? "" : hostsContent;
        List<String> lines = managedLines(domain);
        List<String> hosts = managedHosts(domain);

        List<String> all = splitLines(content);
        int start = indexOfMarker(all, START_MARKER, 0);
        if (start < 0) {
            return joinLines(appendBlock(all, lines), true);
        }
        int end = indexOfMarker(all, END_MARKER, start + 1);

        List<String> body = blockBody(all, start, end);
        body.addAll(missingLines(body, lines, hosts));
        // Bloco sem marcador final é fechado aqui: o arquivo passa a terminar em "\n".
        boolean terminaComQuebra = end >= 0 ? endsWithNewline(content) : true;
        return joinLines(rebuild(all, start, end, body), terminaComQuebra);
    }

    /** Remove o domínio do bloco. Bloco sem entradas é removido por inteiro. */
    public static String removeDomain(String hostsContent, String domain) {
        String content = hostsContent == null ? "" : hostsContent;
        List<String> hosts = managedHosts(domain);

        List<String> all = splitLines(content);
        int start = indexOfMarker(all, START_MARKER, 0);
        if (start < 0) {
            // POR QUE: o SiteBlock só reescreve o bloco que ele mesmo escreveu.
            // Linha do usuário fora dele é intocável, mesmo que cite o domínio.
            return content;
        }
        int end = indexOfMarker(all, END_MARKER, start + 1);

        List<String> kept = new ArrayList<>();
        for (String line : blockBody(all, start, end)) {
            String stripped = stripHostnames(line, hosts);
            if (stripped == null) {
                continue;
            }
            if (stripped.equals(line)) {
                kept.add(line);
            } else if (!stripped.isEmpty()) {
                kept.add(stripped);
            }
        }
        while (!kept.isEmpty() && kept.get(kept.size() - 1).isEmpty()) {
            kept.remove(kept.size() - 1);
        }

        if (!kept.stream().anyMatch(HostsFileManager::isEntryLine)) {
            // Bloco sem entradas: some inteiro, e só ele.
            int regionEnd = end >= 0 ? end : all.size() - 1;
            List<String> out = new ArrayList<>(all.subList(0, start));
            out.addAll(all.subList(regionEnd + 1, all.size()));
            return joinLines(out, endsWithNewline(content));
        }
        // Bloco sem marcador final é fechado aqui: o arquivo passa a terminar em "\n".
        boolean terminaComQuebra = end >= 0 ? endsWithNewline(content) : true;
        return joinLines(rebuild(all, start, end, kept), terminaComQuebra);
    }

    // POR QUE: marcador repetido dentro da região é descartado — um arquivo
    // com dois blocos deixa o segundo invisível para a remoção.
    private static List<String> blockBody(List<String> all, int start, int end) {
        int to = end >= 0 ? end : all.size();
        List<String> body = new ArrayList<>();
        for (int i = start + 1; i < to; i++) {
            String line = all.get(i);
            if (line.contains(START_MARKER) || line.contains(END_MARKER)) {
                continue;
            }
            body.add(line);
        }
        return body;
    }

    // POR QUE: o que está fora do bloco é copiado como está — é conteúdo do usuário.
    private static List<String> rebuild(List<String> all, int start, int end, List<String> body) {
        List<String> out = new ArrayList<>(all.subList(0, start + 1));
        out.addAll(body);
        if (end >= 0) {
            out.add(all.get(end));
            out.addAll(all.subList(end + 1, all.size()));
        } else {
            // Bloco sem marcador final: fecha o bloco para o arquivo ficar bem formado.
            out.add(END_MARKER);
        }
        return out;
    }

    // Bloco novo entra no fim do arquivo — só é usado quando ainda não existe um.
    private static List<String> appendBlock(List<String> all, List<String> lines) {
        List<String> out = new ArrayList<>(all);
        out.add(START_MARKER);
        out.addAll(lines);
        out.add(END_MARKER);
        return out;
    }

    // Linhas que faltam para o domínio ficar completo no bloco — reparo automático.
    private static List<String> missingLines(List<String> body, List<String> lines, List<String> hosts) {
        String block = String.join("\n", body);
        List<String> insert = new ArrayList<>();
        if (!containsHostWithIp(block, REDIRECT_IP, hosts.get(0))) {
            insert.add(lines.get(0));
        }
        if (!containsHostWithIp(block, REDIRECT_IP, hosts.get(1))) {
            insert.add(lines.get(1));
        }
        if (!containsHostWithIp(block, REDIRECT_IPV6, hosts.get(0))) {
            insert.add(lines.get(2));
        }
        if (!containsHostWithIp(block, REDIRECT_IPV6, hosts.get(1))) {
            insert.add(lines.get(3));
        }
        return insert;
    }

    // Linhas do arquivo sem o terminador final: quem remontar decide se ele volta.
    private static List<String> splitLines(String content) {
        if (content.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> lines = new ArrayList<>(Arrays.asList(content.split("\n", -1)));
        if (lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        return lines;
    }

    private static String joinLines(List<String> lines, boolean endsWithNewline) {
        if (lines.isEmpty()) {
            return "";
        }
        return String.join("\n", lines) + (endsWithNewline ? "\n" : "");
    }

    private static boolean endsWithNewline(String content) {
        return !content.isEmpty() && content.endsWith("\n");
    }

    private static int indexOfMarker(List<String> lines, String marker, int from) {
        for (int i = from; i < lines.size(); i++) {
            if (lines.get(i).contains(marker)) {
                return i;
            }
        }
        return -1;
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

    // ATENÇÃO: null não é "não mudou" — significa "apague esta linha" (sobrou
    // só o IP). Nos outros casos, devolve a linha reescrita.
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
        boolean remainingHost = false;
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
                remainingHost = true;
            }
        }
        if (!removedAny) {
            return line;
        }
        // Sem host sobrando, o comentário era da entrada removida: a linha
        // inteira sai. Deixá-la como "127.0.0.1 # note" manteria o bloco vivo.
        if (!remainingHost) {
            return null;
        }
        return String.join(" ", remaining);
    }

    private static boolean isEntryLine(String line) {
        String t = line.strip();
        if (t.isEmpty() || t.startsWith("#")) {
            return false;
        }
        String[] parts = t.split("\\s+");
        for (int i = 1; i < parts.length; i++) {
            if (parts[i].startsWith("#")) {
                return false;
            }
            if (!parts[i].isBlank()) {
                return true;
            }
        }
        return false;
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
        blockInFile(hostsPath, backupPath, lockFile, List.of(domain));
    }

    // POR QUE: por domínio são 3 locks e 3 leituras, e a checagem de concorrência compara o hosts
    // com o que foi lido milissegundos atrás — num apply de 40 domínios, um passo veria o arquivo
    // mudado e a operação morreria no meio: daí o lote compondo tudo em memória antes do lock.
    /**
     * Aplica o bloqueio de vários domínios numa única leitura e uma única escrita.
     */
    public static void blockInFile(Path hostsPath, Path backupPath, Path lockFile, List<String> domains)
            throws IOException {
        if (domains == null || domains.isEmpty()) {
            return;
        }
        String current = readHostsLocked(hostsPath, lockFile);
        String updated = current;
        for (String domain : domains) {
            updated = addDomain(updated, domain);
        }
        modifyHosts(hostsPath, backupPath, lockFile, updated, current);
    }

    /** Remove bloqueio no arquivo. */
    public static void unblockInFile(Path hostsPath, Path backupPath, Path lockFile, String domain)
            throws IOException {
        unblockInFile(hostsPath, backupPath, lockFile, List.of(domain));
    }

    /** Remove vários domínios numa única leitura e uma única escrita. */
    public static void unblockInFile(Path hostsPath, Path backupPath, Path lockFile, List<String> domains)
            throws IOException {
        if (domains == null || domains.isEmpty()) {
            return;
        }
        String current = readHostsLocked(hostsPath, lockFile);
        String updated = current;
        for (String domain : domains) {
            updated = removeDomain(updated, domain);
        }
        modifyHosts(hostsPath, backupPath, lockFile, updated, current);
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

    // ATENÇÃO: a escrita preserva dono e permissões do alvo; alvo chamado "hosts"
    // sem leitura para outros ganha leitura de grupo/outros — é o reparo da
    // permissão 600 quebrada.
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
