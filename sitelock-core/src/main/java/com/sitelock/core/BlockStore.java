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

    /**
 * Executa uma modifica��ão do estado inteiro sob a trava do arquivo.
 *
 * <p>Existe porque {@code load → transforma → save} solto é um
 * read-modify-write sem proteção: o {@code save} trava, mas o {@code load}
 * acontece antes e fora. Com o daemon rodando a cada minuto, para sempre, a
 * janela entre os dois não é "enquanto alguém digita" — é permanentemente
 * aberta. O caso concreto: o daemon lê o estado, e nesse intervalo outro
 * terminal roda {@code block reddit.com}; o daemon grava o estado que tinha
 * lido, e o novo bloqueio simplesmente desaparece do JSON enquanto continua no
 * {@code /etc/hosts}. O sweep varre o JSON — nunca vai limpá-lo — e o
 * {@code status} diz que não está bloqueado. Um site preso sem registro em
 * lugar nenhum.
 *
 * <p>É a mesma verificação otimista que o {@code HostsFileManager.modifyHosts}
 * já faz contra o hosts, finalmente aplicada ao JSON.
 *
 * @param body recebe acesso ao estado já carregado e um {@code save} que não
 *             trava de novo
 */
public <R> R inTransaction(TransactionBody<R> body) throws IOException {
        ensureParentExists(stateFile);
        ensureParentExists(lockFile);
        SiteLockPaths.chownToUserIfConfigured(stateFile.getParent());
        try (FileChannel channel = FileChannel.open(lockFile,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            SiteLockPaths.chownToUserIfConfigured(lockFile);
            return body.apply(new Transaction() {
                @Override
                public Map<String, BlockEntry> load() throws IOException {
                    // Mesma guarda de load(): arquivo inexistente é estado vazio,
                    // não erro. Sem ela o primeiro `block` de uma máquina nova
                    // falharia, porque ainda não há arquivo para ler.
                    if (!Files.exists(stateFile)) {
                        return new TreeMap<>();
                    }
                    return readAndParse();
                }

                @Override
                public void save(Map<String, BlockEntry> blocks) throws IOException {
                    writeAtomically(blocks);
                }
            });
        } catch (OverlappingFileLockException e) {
            // Outra thread da MESMA JVM. Não é erro de dado: o chamador decide.
            throw new IOException("Outro processo/thread está escrevendo o estado agora; "
                    + "tente de novo.", e);
        }
    }

    /** O que se pode fazer dentro de uma transação. */
    public interface Transaction {
        Map<String, BlockEntry> load() throws IOException;

        void save(Map<String, BlockEntry> blocks) throws IOException;
    }

    @FunctionalInterface
    public interface TransactionBody<R> {
        R apply(Transaction tx) throws IOException;
    }

    /** Escrita atômica sem travar de novo — só válida dentro de uma transação. */
    private void writeAtomically(Map<String, BlockEntry> blocks) throws IOException {
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
        SiteLockPaths.chownToUserIfConfigured(stateFile);
        SiteLockPaths.chownToUserIfConfigured(lockFile);
    }

    /** Salva o estado de forma atômica. */
    public void save(Map<String, BlockEntry> blocks) throws IOException {
        ensureParentExists(stateFile);
        ensureParentExists(lockFile);
        SiteLockPaths.chownToUserIfConfigured(stateFile.getParent());
        try (FileChannel channel = FileChannel.open(lockFile,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            writeAtomically(blocks);
        } catch (OverlappingFileLockException e) {
            throw new IOException("Outro processo/thread está escrevendo o estado agora; "
                    + "tente de novo.", e);
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
