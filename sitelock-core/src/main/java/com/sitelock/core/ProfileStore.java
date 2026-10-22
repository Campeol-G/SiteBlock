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
 * Persistência do {@code profiles.json} com escrita atômica e lock entre processos.
 *
 * <p>Disciplina igual à do {@link BlockStore}: dois {@code profile save} simultâneos
 * não podem deixar o arquivo pela metade, e JSON ilegível vira
 * {@link CorruptedStateException} sem apagar nada. O caminho vem do construtor
 * porque quem decide o {@code stateDir} é a camada de CLI.
 */
public class ProfileStore {

    private static final TypeReference<Map<String, Profile>> MAP_TYPE =
            new TypeReference<>() {
            };

    private final Path profilesFile;
    private final Path lockFile;
    private final ObjectMapper mapper;

    public ProfileStore(Path profilesFile) {
        this.profilesFile = profilesFile;
        this.lockFile = profilesFile.resolveSibling(profilesFile.getFileName() + ".lock");
        this.mapper = defaultMapper();
    }

    static ObjectMapper defaultMapper() {
        ObjectMapper om = new ObjectMapper();
        om.registerModule(new JavaTimeModule());
        om.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        om.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        return om;
    }

    public Path getProfilesFile() {
        return profilesFile;
    }

    /** Arquivo inexistente vira mapa vazio. Lê o snapshot direto se o lock falhar. */
    public Map<String, Profile> load() throws IOException {
        if (!Files.exists(profilesFile)) {
            return new TreeMap<>();
        }
        if (Files.exists(lockFile)) {
            try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.READ);
                 FileLock ignored = channel.tryLock(0L, Long.MAX_VALUE, true)) {
                return readAndParse();
            } catch (IOException | UnsupportedOperationException | OverlappingFileLockException e) {
                // ATENÇÃO: lock inacessível (ex.: root-owned sem sudo), FS sem lock, ou outra
                // thread da MESMA JVM já o mantém: lock é por JVM, então tryLock concorrente
                // lança OverlappingFileLockException (unchecked). Ler o snapshot direto é seguro.
                return readAndParse();
            }
        }
        return readAndParse();
    }

    private Map<String, Profile> readAndParse() throws IOException {
        final String text;
        try {
            text = Files.readString(profilesFile, StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            throw new IOException("Falha ao ler " + profilesFile + ": " + e.getMessage(), e);
        }
        if (text.isEmpty()) {
            throw new CorruptedStateException(
                    "Arquivo de perfis vazio/corrompido: " + profilesFile, null);
        }
        final ProfileDocument doc;
        try {
            doc = mapper.readValue(text, ProfileDocument.class);
        } catch (JsonProcessingException e) {
            throw new CorruptedStateException(
                    "Arquivo de perfis corrompido: " + profilesFile, e);
        }
        if (doc == null || doc.getProfiles() == null) {
            throw new CorruptedStateException(
                    "Arquivo de perfis corrompido (conteúdo nulo): " + profilesFile, null);
        }
        return new TreeMap<>(doc.getProfiles());
    }

    /** Salva os perfis de forma atômica. */
    public void save(Map<String, Profile> profiles) throws IOException {
        Map<String, Profile> toSave = profiles != null ? new TreeMap<>(profiles) : new TreeMap<>();
        ensureParentExists(profilesFile);
        ensureParentExists(lockFile);
        try (FileChannel channel = FileChannel.open(lockFile,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            writeAtomically(toSave);
        } catch (OverlappingFileLockException e) {
            throw new IOException("Outro processo/thread está escrevendo as receitas agora; "
                    + "tente de novo.", e);
        }
    }

    /**
     * Executa uma modificação das receitas sob a trava do arquivo.
     *
     * <p>Dois {@code profile add} simultâneos não podem se perder: sem a trava, o
     * último a gravar apagava o domínio do outro. Receita é trabalho digitado;
     * perdê-lo é pior do que perder estado de bloqueio, que se reconstrói.
     *
     * <p>A gravação é um delta sobre o que está no disco: quem escreve sem esperar
     * a trava não é apagado.
     *
     * @param body recebe acesso às receitas já carregadas e um {@code save} que
     *             não trava de novo
     */
    public <R> R inTransaction(TransactionBody<R> body) throws IOException {
        ensureParentExists(profilesFile);
        ensureParentExists(lockFile);
        try (FileChannel channel = FileChannel.open(lockFile,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            return body.apply(new LockedProfiles());
        } catch (OverlappingFileLockException e) {
            throw new IOException("Outro processo/thread está escrevendo as receitas agora; "
                    + "tente de novo.", e);
        }
    }

    /** O que se pode fazer dentro de uma transação de receitas. */
    public interface Transaction {
        Map<String, Profile> load() throws IOException;

        void save(Map<String, Profile> profiles) throws IOException;
    }

    @FunctionalInterface
    public interface TransactionBody<R> {
        R apply(Transaction tx) throws IOException;
    }

    // A transação: as receitas carregadas e o que foi gravado por último.
    // POR QUE: o save de dentro dela não pode ser escrita cega — o que está no
    // disco pode ter mudado desde o load, e apagar isso é o lost update.
    private final class LockedProfiles implements Transaction {

        // O que foi gravado por último: a base do delta da próxima gravação.
        private Map<String, Profile> lastWritten = new TreeMap<>();

        @Override
        public Map<String, Profile> load() throws IOException {
            // O load público já sabe ler sem a trava — e a transação já tem a
            // trava, então ele cai no caminho direto, que é o que interessa.
            Map<String, Profile> perfis = ProfileStore.this.load();
            lastWritten = copia(perfis);
            return perfis;
        }

        @Override
        public void save(Map<String, Profile> perfis) throws IOException {
            writeAtomically(mergeIntoDisk(perfis));
            lastWritten = copia(perfis);
        }

        // Aplica o delta entre o que foi gravado por último e o que se quer
        // gravar sobre o que está no disco agora.
        private Map<String, Profile> mergeIntoDisk(Map<String, Profile> perfis)
                throws IOException {
            Map<String, Profile> onDisk = Files.exists(profilesFile)
                    ? readAndParse()
                    : new TreeMap<>();
            Map<String, Profile> merged = new TreeMap<>(onDisk);
            for (Map.Entry<String, Profile> e : lastWritten.entrySet()) {
                Profile wanted = perfis.get(e.getKey());
                if (wanted == null || !wanted.equals(e.getValue())) {
                    merged.remove(e.getKey());
                }
            }
            for (Map.Entry<String, Profile> e : perfis.entrySet()) {
                Profile before = lastWritten.get(e.getKey());
                if (before == null || !before.equals(e.getValue())) {
                    merged.put(e.getKey(), e.getValue());
                }
            }
            return merged;
        }
    }

    // POR QUE: a cópia de comparação tem que ser o estado como ele estava antes da
    // mudança — guardar os mesmos objetos carregados faria uma alteração no lugar
    // mudar a base junto, e o delta não veria mudança nenhuma.
    private static Map<String, Profile> copia(Map<String, Profile> perfis) {
        Map<String, Profile> copia = new TreeMap<>();
        for (Map.Entry<String, Profile> e : perfis.entrySet()) {
            Profile p = e.getValue();
            if (p == null) {
                copia.put(e.getKey(), null);
                continue;
            }
            Map<String, ProfileEntry> entradas = new TreeMap<>();
            for (Map.Entry<String, ProfileEntry> entrada : p.getEntries().entrySet()) {
                ProfileEntry valor = entrada.getValue();
                entradas.put(entrada.getKey(),
                        valor == null ? null : new ProfileEntry(valor.getDurationMillis()));
            }
            // Construtor sem argumentos e setters: é o jeito de copiar as
            // datas como elas são, inclusive quando o updatedAt é nulo.
            Profile novo = new Profile();
            novo.setCreatedAt(p.getCreatedAt());
            novo.setUpdatedAt(p.getUpdatedAt());
            novo.setEntries(entradas);
            copia.put(e.getKey(), novo);
        }
        return copia;
    }

    private void writeAtomically(Map<String, Profile> toSave) throws IOException {
        ensureParentExists(profilesFile);
        ensureParentExists(lockFile);
        Path tmp = profilesFile.resolveSibling(profilesFile.getFileName() + ".tmp");
        String json = mapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(new ProfileDocument(ProfileDocument.CURRENT_VERSION, toSave));
        Files.writeString(tmp, json, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
        try {
            Files.move(tmp, profilesFile,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, profilesFile, StandardCopyOption.REPLACE_EXISTING);
        }
        SiteLockPaths.chownToRealUserIfSudo(profilesFile);
        SiteLockPaths.chownToRealUserIfSudo(lockFile);
    }

    /** Remove um perfil. Nome ausente não é erro — apagar o que não existe é o estado desejado. */
    public void delete(String name) throws IOException {
        inTransaction(tx -> {
            Map<String, Profile> perfis = tx.load();
            if (perfis.remove(name) != null) {
                tx.save(perfis);
            }
            return null;
        });
    }

    private static void ensureParentExists(Path file) throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }
}