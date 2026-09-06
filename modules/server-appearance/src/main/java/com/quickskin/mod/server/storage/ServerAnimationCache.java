package com.quickskin.mod.server.storage;

import com.quickskin.mod.common.data.ContentId;
import com.quickskin.mod.networking.NetworkSecurity;
import com.quickskin.mod.networking.TextureTransferLimits;
import com.quickskin.mod.common.util.BoundedFileReader;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.UUID;
import java.util.stream.Stream;

/** Bounded persistent cache for validated animation metadata. */
public class ServerAnimationCache {
    private static final Logger LOGGER = LoggerFactory.getLogger(ServerAnimationCache.class);
    private static final int MAX_ENTRIES = TextureTransferLimits.MAX_SERVER_CACHE_ENTRIES;
    private static final long MAX_BYTES = 16L * 1024 * 1024;
    private static ServerAnimationCache instance;

    private final LinkedHashMap<String, String> metadataCache =
            new LinkedHashMap<>(16, 0.75f, true);
    private final Map<String, UUID> metadataAuthorities = new LinkedHashMap<>();
    /**
     * Immutable first-write identities. Unlike the delivery cache, these live for as long as the
     * backing texture so evicting a JSON payload can never reopen a legacy cape to retiming.
     */
    private final Map<String, String> metadataIdentities = new LinkedHashMap<>();
    private long cachedBytes;
    private Path storageDirectory;
    private volatile long generation;

    private ServerAnimationCache() {
    }

    public static ServerAnimationCache getInstance() {
        if (instance == null) instance = new ServerAnimationCache();
        return instance;
    }

    public synchronized void init(Path worldPath) {
        generation++;
        metadataCache.clear();
        metadataAuthorities.clear();
        metadataIdentities.clear();
        cachedBytes = 0;
        storageDirectory = worldPath.resolve("quickskin").resolve("animations").toAbsolutePath().normalize();
        try {
            Files.createDirectories(storageDirectory);
            cleanStaleFiles();
        } catch (IOException e) {
            LOGGER.error("Unable to create QuickSkin animation cache directory {}", storageDirectory, e);
            storageDirectory = null;
            return;
        }
        loadCachedMetadata();
    }

    public synchronized boolean storeMetadata(String hash, String metadataJson) {
        return storeMetadata(hash, metadataJson, null);
    }

    /** Enforces first-writer authority while allowing identical metadata from co-owners. */
    public synchronized boolean storeMetadata(
            String hash, String metadataJson, @Nullable UUID writerId) {
        if (!NetworkSecurity.isValidContentId(hash)
                || !NetworkSecurity.isValidAnimationMetadata(metadataJson)) {
            LOGGER.warn("Rejected invalid animation metadata for {}", hash);
            return false;
        }
        String primary = ServerTextureCache.getInstance().resolveContentId(hash);
        if (primary == null) {
            LOGGER.warn("Rejected animation metadata without a uniquely authenticated texture for {}", hash);
            return false;
        }
        hash = primary;
        String existing = metadataCache.get(hash);
        String candidateIdentity = metadataIdentity(metadataJson);
        String claimedIdentity = metadataIdentities.get(hash);
        if (claimedIdentity != null && !claimedIdentity.equals(candidateIdentity)) {
            LOGGER.warn("Rejected animation metadata identity change for {}", hash);
            return false;
        }
        UUID authority = metadataAuthorities.get(hash);
        if (existing != null && existing.equals(metadataJson)) {
            if (claimedIdentity == null) {
                if (!saveIdentityToDisk(hash, candidateIdentity)) return false;
                metadataIdentities.put(hash, candidateIdentity);
            }
            if (authority == null && writerId != null) {
                if (!saveAuthorityToDisk(hash, writerId)) return false;
                metadataAuthorities.put(hash, writerId);
            }
            return true;
        }
        // Metadata participates in the content identity for current clients. Legacy hashes use
        // first-write immutability so a co-owner can never retime another player's cape.
        if (existing != null || (claimedIdentity == null && writerId == null)) {
            LOGGER.warn("Rejected animation metadata authority change for {}", hash);
            return false;
        }
        boolean newIdentity = claimedIdentity == null;
        if (newIdentity && !saveIdentityToDisk(hash, candidateIdentity)) return false;
        boolean newAuthority = authority == null;
        if (newAuthority && writerId != null && !saveAuthorityToDisk(hash, writerId)) {
            if (newIdentity) deleteFile(safeIdentityFile(hash));
            return false;
        }

        int bytes = utf8Length(metadataJson);
        String previous = metadataCache.put(hash, metadataJson);
        if (previous != null) cachedBytes -= utf8Length(previous);
        cachedBytes += bytes;
        if (!saveMetadataToDisk(hash, metadataJson)) {
            metadataCache.remove(hash);
            cachedBytes -= bytes;
            if (previous != null) {
                metadataCache.put(hash, previous);
                cachedBytes += utf8Length(previous);
            }
            if (newAuthority) deleteFile(safeAuthorityFile(hash));
            if (newIdentity) deleteFile(safeIdentityFile(hash));
            return false;
        }
        metadataIdentities.put(hash, candidateIdentity);
        if (writerId != null) metadataAuthorities.put(hash, writerId);
        evictToLimits();
        return true;
    }

    /** True when no legacy identity has been claimed yet, or the JSON is the exact first value. */
    public synchronized boolean isMetadataIdentityCompatible(String hash, String metadataJson) {
        if (!NetworkSecurity.isValidContentId(hash)
                || !NetworkSecurity.isValidAnimationMetadata(metadataJson)) return false;
        String primary = ServerTextureCache.getInstance().resolveContentId(hash);
        if (primary == null) return false;
        String identity = metadataIdentities.get(primary);
        return identity == null || identity.equals(metadataIdentity(metadataJson));
    }

    @Nullable
    public synchronized String getMetadata(String hash) {
        if (!NetworkSecurity.isValidContentId(hash)) return null;
        String primary = ServerTextureCache.getInstance().resolveContentId(hash);
        return primary == null ? null : metadataCache.get(primary);
    }

    /** Removes metadata whose backing cape blob was evicted or rejected. */
    public synchronized void removeMetadata(String hash) {
        removeMetadataEntry(hash);
    }

    /** Rejects a cleanup handoff that outlived the animation-cache server generation. */
    synchronized void removeMetadata(String hash, long expectedGeneration) {
        if (generation != expectedGeneration) return;
        removeMetadataEntry(hash);
    }

    private void removeMetadataEntry(String hash) {
        if (!NetworkSecurity.isValidContentId(hash)) return;
        ContentId parsed = ContentId.parse(hash);
        String primary = parsed != null && parsed.algorithm() == ContentId.Algorithm.SHA256
                ? hash
                : ServerTextureCache.getInstance().resolveContentId(hash);
        if (primary == null) return;
        String removed = metadataCache.remove(primary);
        if (removed != null) cachedBytes -= utf8Length(removed);
        metadataAuthorities.remove(primary);
        metadataIdentities.remove(primary);
        deleteEntryFiles(primary);
    }

    public synchronized void clear() {
        generation++;
        metadataCache.clear();
        metadataAuthorities.clear();
        metadataIdentities.clear();
        cachedBytes = 0;
        storageDirectory = null;
    }

    /** Lock-free identity capture for bounded filesystem cleanup handoffs. */
    long generation() {
        return generation;
    }

    private boolean saveMetadataToDisk(String hash, String metadataJson) {
        Path file = safeFile(hash);
        Path tempFile = NetworkSecurity.resolveContained(
                storageDirectory, hash, ".json." + UUID.randomUUID() + ".tmp");
        if (file == null || tempFile == null || Files.isSymbolicLink(file)
                || Files.isSymbolicLink(tempFile)) {
            LOGGER.warn("Refusing unsafe animation cache path for {}", hash);
            return false;
        }
        try {
            Files.write(tempFile, metadataJson.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            try {
                Files.move(tempFile, file,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tempFile, file, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException e) {
            LOGGER.error("Unable to persist QuickSkin animation metadata {}", hash, e);
            return false;
        } finally {
            deleteTempFile(tempFile);
        }
    }

    private boolean saveAuthorityToDisk(String hash, UUID authority) {
        Path file = safeAuthorityFile(hash);
        Path tempFile = NetworkSecurity.resolveContained(
                storageDirectory, hash, ".authority." + UUID.randomUUID() + ".tmp");
        if (file == null || tempFile == null || Files.isSymbolicLink(file)
                || Files.isSymbolicLink(tempFile)) return false;
        try {
            Files.writeString(tempFile, authority.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            try {
                Files.move(tempFile, file,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(tempFile, file, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException exception) {
            LOGGER.warn("Unable to persist animation metadata authority {}", hash, exception);
            return false;
        } finally {
            deleteTempFile(tempFile);
        }
    }

    private boolean saveIdentityToDisk(String hash, String identity) {
        Path file = safeIdentityFile(hash);
        Path tempFile = NetworkSecurity.resolveContained(
                storageDirectory, hash, ".identity." + UUID.randomUUID() + ".tmp");
        if (file == null || tempFile == null || Files.isSymbolicLink(file)
                || Files.isSymbolicLink(tempFile)) return false;
        try {
            Files.writeString(tempFile, identity, StandardCharsets.US_ASCII,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            try {
                Files.move(tempFile, file,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(tempFile, file, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException exception) {
            LOGGER.warn("Unable to persist animation metadata identity {}", hash, exception);
            return false;
        } finally {
            deleteTempFile(tempFile);
        }
    }

    private void deleteTempFile(Path file) {
        if (file == null || Files.isSymbolicLink(file)) return;
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            LOGGER.debug("Unable to remove QuickSkin animation temp file {}", file, e);
        }
    }

    private void loadCachedMetadata() {
        if (storageDirectory == null || !Files.isDirectory(storageDirectory, LinkOption.NOFOLLOW_LINKS)) return;
        Map<String, String> legacyMigrations = new LinkedHashMap<>();
        Map<String, Boolean> identityFromStrongSource = new LinkedHashMap<>();
        loadMetadataIdentities(legacyMigrations, identityFromStrongSource);
        PriorityQueue<Path> newestFiles = new PriorityQueue<>(
                Comparator.comparingLong(this::lastModified));
        try (Stream<Path> paths = Files.list(storageDirectory)) {
            paths.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .forEach(path -> {
                        newestFiles.add(path);
                        if (newestFiles.size() > MAX_ENTRIES) {
                            Path expired = newestFiles.remove();
                            // This is only delivery-cache eviction. The immutable identity and
                            // authority remain bound to the still-cached backing texture.
                            deleteFile(expired);
                        }
                    });
        } catch (IOException e) {
            LOGGER.error("Unable to list QuickSkin animation cache {}", storageDirectory, e);
            return;
        }
        List<Path> files = new ArrayList<>(newestFiles);
        files.sort(Comparator.comparingLong(this::lastModified));
        files.forEach(path -> loadMetadataFile(path, legacyMigrations));
        migrateLegacyMetadataEntries(legacyMigrations);
    }

    private void loadMetadataFile(
            Path path, Map<String, String> legacyMigrations) {
        String fileName = path.getFileName().toString();
        String hash = fileName.substring(0, fileName.length() - ".json".length());
        if (!NetworkSecurity.isValidContentId(hash) || safeFile(hash) == null) {
            deleteFile(path);
            return;
        }
        String primary = ServerTextureCache.getInstance().resolveContentId(hash);
        if (primary == null) {
            LOGGER.warn("Ignoring animation metadata without a unique texture alias {}", hash);
            deleteEntryFiles(hash);
            return;
        }
        try {
            long size = Files.size(path);
            if (size <= 0 || size > TextureTransferLimits.MAX_JSON_BYTES) {
                deleteDeliveryMetadata(hash);
                return;
            }
            String metadata = BoundedFileReader.readUtf8(
                    path, TextureTransferLimits.MAX_JSON_BYTES);
            if (!NetworkSecurity.isValidAnimationMetadata(metadata)) {
                LOGGER.warn("Ignoring invalid animation metadata cache entry {}", hash);
                deleteDeliveryMetadata(hash);
                return;
            }
            if (!ServerTextureCache.getInstance().isRequestable(primary, "cape")) {
                LOGGER.warn("Ignoring orphaned animation metadata cache entry {}", hash);
                deleteEntryFiles(hash);
                return;
            }
            if (!ServerTextureCache.getInstance()
                    .isAnimationMetadataCompatible(primary, metadata)) {
                LOGGER.warn("Ignoring incompatible animation metadata cache entry {}", hash);
                deleteDeliveryMetadata(hash);
                return;
            }
            String identity = metadataIdentity(metadata);
            String claimedIdentity = metadataIdentities.get(primary);
            if (claimedIdentity != null && !claimedIdentity.equals(identity)) {
                LOGGER.warn("Ignoring animation metadata that changed identity for {}", hash);
                deleteFile(path);
                return;
            }
            if (claimedIdentity == null) {
                if (!saveIdentityToDisk(primary, identity)) return;
                metadataIdentities.put(primary, identity);
            }
            String previous = metadataCache.put(primary, metadata);
            if (previous != null) cachedBytes -= utf8Length(previous);
            cachedBytes += utf8Length(metadata);
            UUID authority = readAuthority(hash);
            if (authority != null) {
                if (primary.equals(hash)) metadataAuthorities.put(primary, authority);
                else metadataAuthorities.putIfAbsent(primary, authority);
            }
            if (!primary.equals(hash)) legacyMigrations.put(hash, primary);
            evictToLimits();
        } catch (IOException e) {
            LOGGER.warn("Unable to load QuickSkin animation metadata {}", path, e);
        }
    }

    private void loadMetadataIdentities(
            Map<String, String> legacyMigrations,
            Map<String, Boolean> identityFromStrongSource) {
        try (Stream<Path> paths = Files.list(storageDirectory)) {
            paths.filter(path -> path.getFileName().toString().endsWith(".identity"))
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .forEach(path -> {
                        String name = path.getFileName().toString();
                        String hash = name.substring(0, name.length() - ".identity".length());
                        String primary = NetworkSecurity.isValidContentId(hash)
                                ? ServerTextureCache.getInstance().resolveContentId(hash)
                                : null;
                        if (primary == null
                                || !ServerTextureCache.getInstance().isRequestable(primary, "cape")) {
                            deleteEntryFiles(hash);
                            return;
                        }
                        try {
                            if (Files.size(path) != 64) {
                                deleteFile(path);
                                return;
                            }
                            String identity = BoundedFileReader.readUtf8(path, 64);
                            if (!identity.matches("[0-9a-f]{64}")) {
                                deleteFile(path);
                                return;
                            }
                            boolean strongSource = primary.equals(hash);
                            String existing = metadataIdentities.get(primary);
                            boolean existingFromStrong = identityFromStrongSource
                                    .getOrDefault(primary, false);
                            if (existing != null && !existing.equals(identity)
                                    && existingFromStrong && !strongSource) {
                                LOGGER.warn("Ignoring conflicting legacy animation identity {}", hash);
                                deleteEntryFiles(hash);
                                return;
                            }
                            if (existing == null || strongSource || !existingFromStrong) {
                                metadataIdentities.put(primary, identity);
                            }
                            if (strongSource) identityFromStrongSource.put(primary, true);
                            UUID authority = readAuthority(hash);
                            if (authority != null) {
                                if (strongSource) metadataAuthorities.put(primary, authority);
                                else metadataAuthorities.putIfAbsent(primary, authority);
                            }
                            if (!strongSource) legacyMigrations.put(hash, primary);
                        } catch (IOException exception) {
                            LOGGER.warn("Unable to load animation metadata identity {}", hash,
                                    exception);
                        }
                    });
        } catch (IOException exception) {
            LOGGER.error("Unable to list QuickSkin animation identities {}", storageDirectory,
                    exception);
        }
    }

    /**
     * Copies a uniquely authenticated legacy metadata bundle to the SHA-256 primary and verifies
     * every present component before deleting the SHA-1 files. Ambiguous aliases never enter this
     * map, so their metadata cannot be attached to either colliding strong identity.
     */
    private void migrateLegacyMetadataEntries(Map<String, String> migrations) {
        for (Map.Entry<String, String> migration : migrations.entrySet()) {
            String legacy = migration.getKey();
            String primary = migration.getValue();
            String identity = metadataIdentities.get(primary);
            if (identity == null || !saveIdentityToDisk(primary, identity)
                    || !isPersistedText(safeIdentityFile(primary), identity, 64)) {
                LOGGER.warn("Retaining legacy animation cache entry {} because identity migration "
                        + "to {} could not be verified", legacy, primary);
                continue;
            }

            UUID authority = metadataAuthorities.get(primary);
            if (authority != null && (!saveAuthorityToDisk(primary, authority)
                    || !isPersistedText(
                            safeAuthorityFile(primary), authority.toString(), 64))) {
                LOGGER.warn("Retaining legacy animation cache entry {} because authority migration "
                        + "to {} could not be verified", legacy, primary);
                continue;
            }

            String metadata = metadataCache.get(primary);
            if (metadata != null && (!saveMetadataToDisk(primary, metadata)
                    || !isPersistedText(
                            safeFile(primary), metadata,
                            TextureTransferLimits.MAX_JSON_BYTES))) {
                LOGGER.warn("Retaining legacy animation cache entry {} because metadata migration "
                        + "to {} could not be verified", legacy, primary);
                continue;
            }
            deleteEntryFiles(legacy);
        }
    }

    private boolean isPersistedText(@Nullable Path path, String expected, int maximumBytes) {
        if (path == null || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return false;
        try {
            byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);
            if (Files.size(path) != expectedBytes.length) return false;
            return java.util.Arrays.equals(
                    expectedBytes, BoundedFileReader.readBytes(path, maximumBytes));
        } catch (IOException exception) {
            LOGGER.warn("Unable to verify migrated animation cache file {}", path, exception);
            return false;
        }
    }

    private void cleanStaleFiles() throws IOException {
        if (storageDirectory == null) return;
        try (Stream<Path> paths = Files.list(storageDirectory)) {
            paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .forEach(path -> {
                        String name = path.getFileName().toString();
                        if (name.endsWith(".tmp")) {
                            deleteFile(path);
                            return;
                        }
                        String suffix;
                        if (name.endsWith(".authority")) suffix = ".authority";
                        else if (name.endsWith(".identity")) suffix = ".identity";
                        else return;
                        String hash = name.substring(0, name.length() - suffix.length());
                        if (!NetworkSecurity.isValidContentId(hash)
                                || !ServerTextureCache.getInstance().isRequestable(hash, "cape")) {
                            deleteFile(path);
                        }
                    });
        }
    }

    private void deleteEntryFiles(String hash) {
        deleteFile(safeFile(hash));
        deleteFile(safeAuthorityFile(hash));
        deleteFile(safeIdentityFile(hash));
    }

    /** Discards a reconstructible delivery payload without dropping its first-write identity. */
    private void deleteDeliveryMetadata(String hash) {
        deleteFile(safeFile(hash));
        if (!metadataIdentities.containsKey(hash)) {
            metadataAuthorities.remove(hash);
            deleteFile(safeAuthorityFile(hash));
        }
    }

    private void evictToLimits() {
        Iterator<Map.Entry<String, String>> iterator = metadataCache.entrySet().iterator();
        while ((metadataCache.size() > MAX_ENTRIES || cachedBytes > MAX_BYTES) && iterator.hasNext()) {
            Map.Entry<String, String> eldest = iterator.next();
            iterator.remove();
            cachedBytes -= utf8Length(eldest.getValue());
            deleteFile(safeFile(eldest.getKey()));
        }
    }

    @Nullable
    private Path safeFile(String hash) {
        return NetworkSecurity.resolveContained(storageDirectory, hash, ".json");
    }

    @Nullable
    private Path safeAuthorityFile(String hash) {
        return NetworkSecurity.resolveContained(storageDirectory, hash, ".authority");
    }

    @Nullable
    private Path safeIdentityFile(String hash) {
        return NetworkSecurity.resolveContained(storageDirectory, hash, ".identity");
    }

    @Nullable
    private UUID readAuthority(String hash) {
        Path file = safeAuthorityFile(hash);
        if (file == null || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return null;
        try {
            if (Files.size(file) > 64) return null;
            return UUID.fromString(BoundedFileReader.readUtf8(file, 64).trim());
        } catch (IOException | IllegalArgumentException exception) {
            LOGGER.warn("Ignoring invalid animation metadata authority for {}", hash, exception);
            deleteFile(file);
            return null;
        }
    }

    private void deleteFile(@Nullable Path file) {
        if (file == null || Files.isSymbolicLink(file)) return;
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            LOGGER.warn("Unable to evict QuickSkin animation cache file {}", file, e);
        }
    }

    private int utf8Length(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    private String metadataIdentity(String metadataJson) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(metadataJson.getBytes(StandardCharsets.UTF_8));
            StringBuilder identity = new StringBuilder(digest.length * 2);
            for (byte value : digest) identity.append(String.format("%02x", value & 0xff));
            return identity.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
        }
    }

    private long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis();
        } catch (IOException e) {
            return Long.MIN_VALUE;
        }
    }
}
