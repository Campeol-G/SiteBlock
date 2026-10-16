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
 * Persistência do {@code profiles.json} com escrita atômica e lock entre
 * processos — a mesma disciplina do {@link BlockStore}, pelas mesmas razões:
 * dois {@code profile save} simultâneos não podem deixar o arquivo pela
 * metade, e JSON ilegível vira {@link CorruptedStateException} sem apagar nada.
 *
 * <p>O caminho vem do construtor porque quem decide o {@code stateDir} é a
 * camada de CLI, que resolve o {@code --state-file} dos testes.
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
                // Lock inacessível (ex.: root-owned sem sudo), FS sem lock, ou outra
                // thread da MESMA JVM já o mantém (locks de arquivo são por JVM,
                // não por canal: tryLock concorrente lança OverlappingFileLockException,
                // unchecked). Ler o snapshot atômico direto é seguro em todos eles.
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
    }

    /** Remove um perfil. Nome ausente não é erro — apagar o que não existe é o estado desejado. */
    public void delete(String name) throws IOException {
        Map<String, Profile> current = load();
        if (current.remove(name) != null) {
            save(current);
        }
    }

    private static void ensureParentExists(Path file) throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }
}