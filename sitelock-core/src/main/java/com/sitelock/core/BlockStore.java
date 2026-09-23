package com.sitelock.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.TreeMap;

/**
 * Persistência do blocks.json com escrita atômica e lock entre processos.
 * JSON ilegível vira {@link CorruptedStateException} sem apagar nada.
 */
public class BlockStore {

    private static final TypeReference<Map<String, BlockEntry>> MAP_TYPE =
            new TypeReference<>() {
            };

    private final Path stateFile;
    private final Path lockFile;
    private final ObjectMapper mapper;

    public BlockStore(Path stateFile) {
        this.stateFile = stateFile;
        this.lockFile = stateFile.resolveSibling(stateFile.getFileName() + ".lock");
        this.mapper = defaultMapper();
    }

    /** Construtor padrão usando {@link SiteLockPaths}. */
    public BlockStore() {
        this(SiteLockPaths.getStateFile());
    }

    static ObjectMapper defaultMapper() {
        ObjectMapper om = new ObjectMapper();
        om.registerModule(new JavaTimeModule());
        om.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        om.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        return om;
    }

    public Path getStateFile() {
        return stateFile;
    }

    /** Arquivo inexistente vira mapa vazio. Cai para leitura direta se o lock falhar. */
    public Map<String, BlockEntry> load() throws IOException {
        if (!Files.exists(stateFile)) {
            return new TreeMap<>();
        }
        // Tenta lock compartilhado somente-leitura; cai para leitura direta.
        if (Files.exists(lockFile)) {
            try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.READ);
                 FileLock ignored = channel.tryLock(0L, Long.MAX_VALUE, true)) {
                return readAndParse();
            } catch (IOException | UnsupportedOperationException | OverlappingFileLockException e) {
                // Lock inacessível (ex.: root-owned sem sudo), FS sem lock, ou outra
                // thread DA MESMA JVM já mantém lock sobre a região (locks de arquivo
                // são por JVM, não por canal: tryLock concorrente lança
                // OverlappingFileLockException, unchecked). Em todos os casos a
                // leitura direta do snapshot atômico é segura.
                return readAndParse();
            }
        }
        return readAndParse();
    }

    private Map<String, BlockEntry> readAndParse() throws IOException {
            byte[] bytes;
            try {
                bytes = Files.readAllBytes(stateFile);
            } catch (IOException e) {
                throw new IOException("Falha ao ler " + stateFile + ": " + e.getMessage(), e);
            }
            String text = new String(bytes, StandardCharsets.UTF_8).trim();
            if (text.isEmpty()) {
                throw new CorruptedStateException(
                        "Arquivo de estado vazio/corrompido: " + stateFile, null);
            }
            final Map<String, BlockEntry> parsed;
            try {
                parsed = mapper.readValue(text, MAP_TYPE);
            } catch (JsonProcessingException e) {
                throw new CorruptedStateException(
                        "Arquivo de estado corrompido: " + stateFile, e);
            }
            if (parsed == null) {
                throw new CorruptedStateException(
                        "Arquivo de estado corrompido (conteúdo nulo): " + stateFile, null);
            }
            Map<String, BlockEntry> result = new TreeMap<>();
            for (Map.Entry<String, BlockEntry> e : parsed.entrySet()) {
                BlockEntry entry = e.getValue();
                if (entry == null || entry.getDomain() == null || entry.getDomain().isBlank()) {
                    throw new CorruptedStateException(
                            "Arquivo de estado corrompido (entrada sem domínio): " + stateFile, null);
                }
                result.put(entry.getDomain(), entry);
            }
            return result;
    }

    /** Salva o estado de forma atômica. */
    public void save(Map<String, BlockEntry> blocks) throws IOException {
        ensureParentExists(stateFile);
        ensureParentExists(lockFile);
        SiteLockPaths.chownToRealUserIfSudo(stateFile.getParent());
        try (FileChannel channel = FileChannel.open(lockFile,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            Path tmp = stateFile.resolveSibling(stateFile.getFileName() + ".tmp");
            String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(blocks);
            Files.writeString(tmp, json, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            try {
                Files.move(tmp, stateFile,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, stateFile, StandardCopyOption.REPLACE_EXISTING);
            }
            SiteLockPaths.chownToRealUserIfSudo(stateFile);
            SiteLockPaths.chownToRealUserIfSudo(lockFile);
        }
    }

    /** Apaga o estado. Não toca no /etc/hosts. */
    public void clear() throws IOException {
        save(new TreeMap<>());
    }

    private static void ensureParentExists(Path file) throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }
}
