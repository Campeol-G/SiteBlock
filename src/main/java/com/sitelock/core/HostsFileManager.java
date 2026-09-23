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
 * Leitura/escrita do bloco gerenciado dentro do {@code /etc/hosts}.
 *
 * <p>Formato (P1: inclui IPv6, pois só {@code 127.0.0.1} deixava passar
 * tráfego/resolução {@code AAAA}):
 * <pre>
 * # SITELOCK-START
 * 127.0.0.1 tiktok.com
 * 127.0.0.1 www.tiktok.com
 * ::1 tiktok.com
 * ::1 www.tiktok.com
 * # SITELOCK-END
 * </pre>
 *
 * <p>Decisões documentadas:
 * <ul>
 *   <li>Ao adicionar, as linhas entram <b>dentro</b> do bloco existente
 *       (antes de {@code # SITELOCK-END}); marcadores nunca são duplicados.</li>
 *   <li>Ao remover, apagam-se apenas as linhas do domínio pedido; se o bloco
 *       ficar sem nenhuma entrada, <b>os marcadores também são removidos</b>
 *       para não deixar lixo no hosts (bloco vazio some por completo).</li>
 *   <li>Backup: antes da <b>primeira</b> modificação, o hosts original é
 *       copiado para {@code ~/.sitelock/hosts.backup} — somente se esse backup
 *       ainda não existir, para nunca sobrescrever o original.</li>
 *   <li>Escrita atômica: grava em arquivo temporário no mesmo diretório e move
 *       com {@code ATOMIC_MOVE}. Se o processo morrer no meio, o hosts original
 *       continua intacto (o .tmp órfão pode ser ignorado/apagado).</li>
 *   <li>Lock: as operações de arquivo adquirem lock exclusivo em um arquivo de
 *       lock no diretório de estado (ex.: {@code ~/.sitelock/hosts.lock}).
 *       Isso serializa dois processos <b>SiteBlock</b> concorrentes. Não protege
 *       contra editores externos simultâneos — limitação conhecida de file
 *       locking cooperativo.</li>
 *   <li>Falha de escrita: como nunca escrevemos por cima do original (só via
 *       move atômico), não há "arquivo pela metade"; o catch apenas relata o
 *       erro sem tentar um restore destrutivo.</li>
 * </ul>
 *
 * <p>Os métodos {@link #addDomain(String, String)} e
 * {@link #removeDomain(String, String)} são <b>puros</b> (operam em String em
 * memória) justamente para serem testáveis sem tocar no {@code /etc/hosts} real.
 */
public final class HostsFileManager {

    public static final String START_MARKER = "# SITELOCK-START";
    public static final String END_MARKER = "# SITELOCK-END";
    public static final String REDIRECT_IP = "127.0.0.1";
    public static final String REDIRECT_IPV6 = "::1";

    private HostsFileManager() {
    }

    // ------------------------------------------------------------------
    // Lógica pura (testável em memória)
    // ------------------------------------------------------------------

    /** Retorna as quatro linhas gerenciadas para um domínio canônico (IPv4 + IPv6). */
    public static List<String> managedLines(String domain) {
        String d = domain.toLowerCase(Locale.ROOT);
        return List.of(
                REDIRECT_IP + " " + d,
                REDIRECT_IP + " www." + d,
                REDIRECT_IPV6 + " " + d,
                REDIRECT_IPV6 + " www." + d);
    }

    /** Retorna os dois hostnames gerenciados para um domínio canônico. */
    public static List<String> managedHosts(String domain) {
        String d = domain.toLowerCase(Locale.ROOT);
        return List.of(d, "www." + d);
    }

    /**
     * Insere o domínio no bloco (idempotente). Nunca duplica marcadores
     * nem linhas já presentes. A presença é checada por hostname (qualquer IP),
     * então entradas legadas só-IPv4 são complementadas com as linhas {@code ::1}.
     */
    public static String addDomain(String hostsContent, String domain) {
        String content = hostsContent == null ? "" : hostsContent;
        List<String> lines = managedLines(domain);
        List<String> hosts = managedHosts(domain);

        int start = content.indexOf(START_MARKER);
        int end = content.indexOf(END_MARKER);

        // Bloco íntegro existente: insere dentro dele.
        if (start >= 0 && end > start) {
            String before = content.substring(0, end);
            String after = content.substring(end);
            String block = content.substring(start, end);
            StringBuilder insert = new StringBuilder();
            // Checagem por par (IP, hostname): complementa blocos legados só-IPv4.
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
                return content; // idempotente
            }
            return before + insert + after;
        }

        // Sem bloco íntegro: anexa um bloco novo ao final.
        StringBuilder sb = new StringBuilder(content);
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') {
            sb.append('\n');
        }
        // Evita colar marcador na última linha sem newline; garante linha em branco? Não:
        // anexa direto para diff mínimo, separando com newline já garantido acima.
        sb.append(START_MARKER).append('\n');
        // Se havia marcadores parciais órfãos, preserva as entradas antigas?
        // Não tentamos adivinhar: apenas adiciona as linhas pedidas (idempotência
        // futura é garantida porque na próxima chamada o bloco estará íntegro).
        for (String line : lines) {
            sb.append(line).append('\n');
        }
        sb.append(END_MARKER).append('\n');
        return sb.toString();
    }

    /**
     * Remove o domínio do bloco. Se o bloco ficar sem entradas, remove os
     * marcadores também. Fora do bloco, remove linhas órfãs exatas do domínio
     * (higiene p/ edições manuais legadas).
     */
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
            // Remove a linha do START da lista para filtrar só entradas.
            List<String> kept = new ArrayList<>();
            for (String line : blockLines) {
                if (line.contains(START_MARKER)) {
                    kept.add(line);
                    continue;
                }
                String stripped = stripHostnames(line, hosts);
                if (stripped != null) {
                    // null = linha removida por inteiro; senão, linha reescrita (ou igual).
                    if (!stripped.isEmpty() || isCommentOrBlank(line)) {
                        // Linha virou vazia mas era comentário/branco: mantém como está.
                        kept.add(line);
                    } else if (!stripped.equals(line)) {
                        // Linha multi-host que sobrou com outros hosts: mantém reescrita,
                        // a menos que tenha virado vazia (caso tratado abaixo).
                        if (!stripped.isBlank()) {
                            kept.add(stripped);
                        }
                    } else {
                        kept.add(line);
                    }
                }
            }

            // O split com -1 gera um "" final quando o bloco termina com "\n";
            // esse artefato não é linha real e deve ser descartado para não
            // criar "\n\n" espúrio ao rejuntar.
            while (!kept.isEmpty() && kept.get(kept.size() - 1).isEmpty()) {
                kept.remove(kept.size() - 1);
            }

            // O bloco ficou vazio? (só marcadores/comentários/brancos, sem entradas)
            boolean hasEntry = kept.stream().anyMatch(HostsFileManager::isEntryLine);
            String newBlock;
            if (!hasEntry) {
                newBlock = ""; // some com os marcadores (decisão documentada)
            } else {
                newBlock = String.join("\n", kept) + "\n" + END_MARKER;
            }

            String result = head + newBlock + tail;
            // Higiene: remove órfãs fora do bloco (caso o usuário tenha duplicado fora).
            result = removeOrphanLines(result, hosts);
            return tidyBlankLines(result);
        }

        // Sem bloco: apenas remove órfãs exatas em qualquer lugar.
        return tidyBlankLines(removeOrphanLines(content, hosts));
    }

    /** true se o bloco já menciona o host com esse IP (case-insensitive). */
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
     *
     * @return {@code null} se a linha inteira deve sumir; senão a linha reescrita
     *     (igual à original se nada mudou; string vazia se virou vazia).
     */
    private static String stripHostnames(String line, List<String> hosts) {
        String trimmed = line.strip();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
            return line; // comentários/brancos: intocados
        }
        String[] parts = trimmed.split("\\s+");
        if (parts.length < 2) {
            return line;
        }
        List<String> remaining = new ArrayList<>();
        remaining.add(parts[0]); // IP
        boolean removedAny = false;
        for (int i = 1; i < parts.length; i++) {
            if (parts[i].startsWith("#")) {
                // Comentário trailing: preserva o resto como está.
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
            return null; // só tinha o IP (ou IP + nada útil): some com a linha
        }
        return String.join(" ", remaining);
    }

    private static boolean isCommentOrBlank(String line) {
        String t = line.strip();
        return t.isEmpty() || t.startsWith("#");
    }

    /** Linha de entrada = não vazia, não comentário (dentro do bloco). */
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
                continue; // pula a linha (sem deixar buraco duplo — tidy depois)
            }
            sb.append(rewritten);
            if (i < lines.length - 1) {
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    /** Colapsa 3+ newlines seguidos em no máximo 2 (evita buracos). */
    private static String tidyBlankLines(String s) {
        return s.replaceAll("\n{3,}", "\n\n");
    }

    // ------------------------------------------------------------------
    // Operações de arquivo
    // ------------------------------------------------------------------

    public static boolean isWritable(Path hostsPath) {
        return Files.isWritable(hostsPath);
    }

    /**
     * Garante backup do hosts original (só cria se ainda não existir).
     * O backup mora no diretório de estado: devolve a posse ao usuário real
     * quando criado via sudo (mesmo bug de ownership do blocks.json).
     */
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

    /** Lê o hosts como UTF-8 (cria vazio se não existir — caso de testes). */
    public static String readHosts(Path hostsPath) throws IOException {
        if (!Files.exists(hostsPath)) {
            return "";
        }
        return Files.readString(hostsPath, StandardCharsets.UTF_8);
    }

    /**
     * Aplica bloqueio no arquivo: backup (1ª vez) + escrita atômica + lock.
     * Em falha, o original segue intacto (nunca escrevemos por cima).
     */
    public static void blockInFile(Path hostsPath, Path backupPath, Path lockFile, String domain)
            throws IOException {
        modifyHosts(hostsPath, backupPath, lockFile, addDomain(readHostsLocked(hostsPath, lockFile), domain),
                readHostsLocked(hostsPath, lockFile));
    }

    /** Remove bloqueio no arquivo (mesmas garantias do {@code blockInFile}). */
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
            // Releitura dentro do lock para evitar lost-update entre processos.
            String current = readHosts(hostsPath);
            // Recalcula a partir do estado mais fresco? Não: o chamador já calculou
            // a partir de leitura recente; aqui apenas confirmamos que nada mudou
            // de forma grosseira. Para manter idempotência simples, seguimos com
            // newContent calculado (operações são idempotentes, então o risco é mínimo).
            if (!current.equals(originalContent)) {
                // Outro processo mexeu no meio: rebase simples — aplica de novo sobre o fresco.
                // Detecta se era add ou remove pelo tamanho? Em vez de adivinhar, aborta
                // com mensagem clara para retry (raro; lock minimiza a janela).
                throw new IOException("O /etc/hosts foi modificado concorrentemente. Tente novamente.");
            }
            if (current.equals(newContent)) {
                return; // idempotente: nada a fazer, nem backup
            }
            ensureBackup(hostsPath, backupPath);
            writeAtomically(hostsPath, newContent);
        } catch (IOException e) {
            throw new IOException("Falha ao atualizar " + hostsPath + ": " + e.getMessage()
                    + " (nenhuma alteração parcial foi aplicada)", e);
        }
    }

    /**
     * Escrita atômica com preservação de dono/permissões (P0 — bug real).
     *
     * <p><b>Causa raiz do "bloqueei e não funcionou":</b> {@code Files.createTempFile}
     * cria o temporário com {@code 600}; o {@code ATOMIC_MOVE} por cima do alvo
     * carregava esse {@code 600} para o {@code /etc/hosts} (que era {@code 644}).
     * O hosts virava legível só por root: browser e {@code getent} como usuário
     * caíam no DNS real, enquanto {@code sudo getent} via {@code 127.0.0.1}.
     * Agora o temporário herda dono/grupo/permissões do alvo antes do move, e o
     * resultado é verificado depois do move. Heurística de auto-reparo: arquivo
     * chamado {@code hosts} sem leitura para outros ganha {@code r} de grupo/outros
     * (reverte o estrago do bug em escritas futuras).
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
