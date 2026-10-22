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
                // Lock inacessível (root-owned sem sudo), FS sem lock, ou outra thread
                // da mesma JVM com lock (locks são por JVM: tryLock concorrente lança
                // OverlappingFileLockException). Ler o snapshot atômico direto é seguro.
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

    // POR QUE: load → transforma → save solto é read-modify-write sem proteção
    // (o save trava, o load é antes e fora): com o daemon a cada minuto, a
    // janela entre os dois está sempre aberta.

    // O caso: o daemon lê o estado, outro terminal roda block, e o daemon grava
    // o estado antigo — o bloqueio some do JSON e continua no hosts, que o
    // sweep (que varre o JSON) nunca limpa, e o status diz que não está bloqueado.

    // A gravação é um delta sobre o disco, não cega: quem não espera a trava
    // (outra versão da ferramenta, script, edição manual) não é apagado. É a
    // mesma verificação otimista que o HostsFileManager.modifyHosts já faz.
    /**
     * Executa uma modificação do estado inteiro sob a trava do arquivo.
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
            return body.apply(new LockedState());
        } catch (OverlappingFileLockException e) {
            // Outra thread da MESMA JVM. Não é erro de dado: o chamador decide.
            throw new IOException("Outro processo/thread está escrevendo o estado agora; "
                    + "tente de novo.", e);
        }
    }

    // POR QUE: o save de dentro da transação não pode ser uma escrita cega — o
    // que está no disco pode ter mudado desde o load (por outro escritor que não
    // espera a trava), e apagar isso é o lost update que a transação impede.
    private final class LockedState implements Transaction {

        // O que foi gravado por último: a base do delta da próxima gravação.
        private Map<String, BlockEntry> lastWritten = new TreeMap<>();

        @Override
        public Map<String, BlockEntry> load() throws IOException {
            // Mesma guarda de load(): arquivo inexistente é estado vazio,
            // não erro. Sem ela o primeiro `block` de uma máquina nova
            // falharia, porque ainda não há arquivo para ler.
            Map<String, BlockEntry> state = Files.exists(stateFile)
                    ? readAndParse()
                    : new TreeMap<>();
            lastWritten = new TreeMap<>(state);
            return state;
        }

        @Override
        public void save(Map<String, BlockEntry> blocks) throws IOException {
            writeAtomically(mergeIntoDisk(blocks));
            lastWritten = new TreeMap<>(blocks);
        }

        // Aplica o delta entre o que foi gravado por último e o que se quer
        // gravar sobre o que está no disco agora.
        private Map<String, BlockEntry> mergeIntoDisk(Map<String, BlockEntry> blocks)
                throws IOException {
            Map<String, BlockEntry> onDisk = Files.exists(stateFile)
                    ? readAndParse()
                    : new TreeMap<>();
            Map<String, BlockEntry> merged = new TreeMap<>(onDisk);
            for (Map.Entry<String, BlockEntry> e : lastWritten.entrySet()) {
                BlockEntry wanted = blocks.get(e.getKey());
                if (wanted == null || !wanted.equals(e.getValue())) {
                    merged.remove(e.getKey());
                }
            }
            for (Map.Entry<String, BlockEntry> e : blocks.entrySet()) {
                BlockEntry before = lastWritten.get(e.getKey());
                if (before == null || !before.equals(e.getValue())) {
                    merged.put(e.getKey(), e.getValue());
                }
            }
            return merged;
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

    // Escrita atômica sem travar de novo — só válida dentro de uma transação.
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
