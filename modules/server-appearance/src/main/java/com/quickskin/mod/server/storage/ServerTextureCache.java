package com.quickskin.mod.server.storage;

import com.quickskin.mod.networking.NetworkSecurity;
import com.quickskin.mod.networking.TextureTransferLimits;
import com.quickskin.mod.common.data.ContentAliasIndex;
import com.quickskin.mod.common.data.ContentAliases;
import com.quickskin.mod.common.data.ContentId;
import com.quickskin.mod.common.util.BoundedFileReader;
import com.quickskin.mod.common.util.PngAnimationIdentity;
import com.quickskin.mod.server.concurrent.ServerCacheIoExecutor;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/** Bounded server-side texture cache with strict content-addressed disk paths. */
public class ServerTextureCache {
    private static final Logger LOGGER = LoggerFactory.getLogger(ServerTextureCache.class);
    private static final int MAX_OWNERS_PER_TEXTURE = 256;
    private static final int MAX_PENDING_DELETIONS = 8192;
    private static ServerTextureCache instance;

    private final LinkedHashMap<String, CacheEntry> textureCache =
            new LinkedHashMap<>(16, 0.75f, true);
    private final ContentAliasIndex contentAliases =
            new ContentAliasIndex(TextureTransferLimits.MAX_SERVER_CACHE_ENTRIES);
    private final PinnedTextureBudget pinnedTextures =
            new PinnedTextureBudget(TextureTransferLimits.MAX_SERVER_PINNED_BYTES);
    private final LinkedHashMap<String, EntryDeletion> pendingDeletions =
            new LinkedHashMap<>();
    private long cachedBytes;
    private Path storageDirectory;
    private long generation;

    private ServerTextureCache() {
    }

    public static synchronized ServerTextureCache getInstance() {
        if (instance == null) {
            instance = new ServerTextureCache();
        }
        return instance;
    }

    public synchronized void init(Path worldPath) {
        generation++;
        cancelPendingDeletions();
        textureCache.clear();
        contentAliases.clear();
        cachedBytes = 0;
        pinnedTextures.clear();
        storageDirectory = worldPath.resolve("quickskin").resolve("textures").toAbsolutePath().normalize();
        try {
            Files.createDirectories(storageDirectory);
            deleteStagedFiles(storageDirectory);
        } catch (IOException e) {
            LOGGER.error("Unable to create QuickSkin texture cache directory {}", storageDirectory, e);
            storageDirectory = null;
            return;
        }
        loadCachedTextures();
    }

    /**
     * Compatibility entry point for non-network callers. Network ingress should prepare on the
     * bounded worker and invoke {@link #storePreparedTexture(PreparedTexture)} on the server
     * thread so the image is decoded exactly once.
     */
    public boolean storeTexture(String hash, UUID ownerId, String textureType, byte[] textureData) {
        try (PreparedTexture prepared = prepareTexture(
                hash, ownerId, textureType, textureData)) {
            return prepared != null && storePreparedTexture(prepared);
        }
    }

    /**
     * Fully validates and bulk-stages an upload away from the server thread.
     *
     * @param expectedHash a client-provided content ID for chunked uploads, or {@code null} for a
     *                     direct upload whose ID must be computed by the server
     */
    public @Nullable PreparedTexture prepareTexture(
            @Nullable String expectedHash,
            UUID ownerId,
            String textureType,
            byte[] textureData
    ) {
        if (ownerId == null || !NetworkSecurity.isValidTextureType(textureType)
                || textureData == null || textureData.length == 0
                || textureData.length > TextureTransferLimits.MAX_TEXTURE_BYTES
                || (expectedHash != null && !NetworkSecurity.isValidContentId(expectedHash))) {
            return null;
        }

        final long preparedGeneration;
        final Path preparedDirectory;
        synchronized (this) {
            if (storageDirectory == null) return null;
            preparedGeneration = generation;
            preparedDirectory = storageDirectory;
        }

        // Own a private immutable byte snapshot before hashing or decoding it.
        byte[] copy = Arrays.copyOf(textureData, textureData.length);
        ContentAliases computedAliases = ContentAliases.forBytes(copy);
        ContentId expected = expectedHash == null ? null : ContentId.parse(expectedHash);
        String computedHash = expected == null
                ? computedAliases.sha1()
                : computedAliases.forAlgorithm(expected.algorithm());
        if (!NetworkSecurity.isValidContentId(computedHash)
                || (expectedHash != null && !ContentId.matches(expectedHash, copy))
                || !NetworkSecurity.isValidTextureData(copy, textureType)) {
            LOGGER.warn("Rejected invalid texture upload (id={}, type={}, bytes={})",
                    expectedHash, textureType, copy.length);
            return null;
        }
        final String embeddedAnimationMetadata;
        try {
            embeddedAnimationMetadata = PngAnimationIdentity.extract(copy);
        } catch (IOException error) {
            LOGGER.warn("Rejected malformed animation identity in texture {}", computedHash);
            return null;
        }
        if (embeddedAnimationMetadata != null
                && NetworkSecurity.parseAnimationMetadata(embeddedAnimationMetadata) == null) {
            LOGGER.warn("Rejected invalid embedded animation metadata in texture {}", computedHash);
            return null;
        }

        Path stagedImage = NetworkSecurity.resolveContained(
                preparedDirectory,
                computedHash,
                ".png." + UUID.randomUUID() + ".ingress.tmp");
        if (stagedImage == null || Files.isSymbolicLink(stagedImage)) return null;

        synchronized (this) {
            if (preparedGeneration != generation
                    || !preparedDirectory.equals(storageDirectory)) return null;
        }
        try {
            Files.write(stagedImage, copy, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (IOException exception) {
            LOGGER.warn("Unable to stage QuickSkin texture {}", computedHash, exception);
            return null;
        }

        synchronized (this) {
            if (preparedGeneration == generation
                    && preparedDirectory.equals(storageDirectory)) {
                return new PreparedTexture(
                        preparedGeneration,
                        preparedDirectory,
                        stagedImage,
                        computedHash,
                        computedAliases,
                        ownerId,
                        textureType,
                        embeddedAnimationMetadata,
                        copy);
            }
        }
        deleteTempFile(stagedImage);
        return null;
    }

    /**
     * Commits ownership and cache state on the server thread after its caller revalidates the
     * player session. The large image write was already completed by {@link #prepareTexture}.
     */
    public synchronized boolean storePreparedTexture(PreparedTexture prepared) {
        if (prepared == null || !prepared.beginCommit()
                || prepared.generation != generation
                || storageDirectory == null
                || !storageDirectory.equals(prepared.storageDirectory)) return false;

        boolean aliasesWereNew = contentAliases.resolve(prepared.aliases.sha256()) == null;
        String primary = contentAliases.register(prepared.hash, prepared.aliases);
        if (primary == null) return false;
        CacheEntry previous = textureCache.get(primary);
        if (previous == null && !cancelPendingDeletion(primary)) {
            LOGGER.debug("Deferring texture {} while its old cache files are being evicted",
                    primary);
            if (aliasesWereNew) contentAliases.removePrimary(primary);
            return false;
        }
        OwnerBinding uploader = new OwnerBinding(prepared.ownerId, prepared.textureType);
        Set<OwnerBinding> owners = new LinkedHashSet<>();
        if (previous != null) {
            owners.addAll(previous.owners);
            if (!Arrays.equals(previous.data, prepared.data)) {
                LOGGER.warn("Rejected attempted takeover of texture {} by {}",
                        primary, prepared.ownerId);
                return false;
            }
            if (previous.owners.contains(uploader)) return true;
            if (previous.owners.size() >= MAX_OWNERS_PER_TEXTURE) return false;
        }
        owners.add(uploader);

        CacheEntry candidate = new CacheEntry(
                prepared.data, prepared.embeddedAnimationMetadata, owners);
        if (!commitPreparedFiles(prepared, primary, candidate, previous)) {
            if (aliasesWereNew) contentAliases.removePrimary(primary);
            return false;
        }

        previous = textureCache.put(primary, candidate);
        if (previous != null) cachedBytes -= previous.data.length;
        cachedBytes += candidate.data.length;
        evictToLimits();
        return textureCache.containsKey(primary);
    }

    public byte @Nullable [] getTexture(String hash) {
        if (!NetworkSecurity.isValidContentId(hash)) return null;
        CacheEntry entry;
        synchronized (this) {
            entry = textureCache.get(resolvePrimary(hash));
        }
        // Cache entries are immutable; perform the potentially 16 MiB copy after releasing the
        // cache monitor so unrelated ownership/size checks are not blocked by bulk memory work.
        return entry == null ? null : Arrays.copyOf(entry.data, entry.data.length);
    }

    public synchronized int getTextureSize(String hash) {
        if (!NetworkSecurity.isValidContentId(hash)) return -1;
        CacheEntry entry = textureCache.get(resolvePrimary(hash));
        return entry == null ? -1 : entry.data.length;
    }

    public synchronized boolean isOwnedBy(String hash, UUID ownerId, String textureType) {
        if (!NetworkSecurity.isValidContentId(hash) || ownerId == null
                || !NetworkSecurity.isValidTextureType(textureType)) {
            return false;
        }
        CacheEntry entry = textureCache.get(resolvePrimary(hash));
        return entry != null && entry.owners.contains(new OwnerBinding(ownerId, textureType));
    }

    /**
     * Atomically moves one connected player's active blob pins to a newly accepted appearance.
     * Missing, unowned, or globally over-budget local textures refuse the whole replacement.
     */
    public synchronized boolean tryReplaceAppearancePins(
            UUID playerId, String skinId, String capeId) {
        if (playerId == null) return false;
        LinkedHashMap<String, Integer> requested = new LinkedHashMap<>();
        if (!collectAppearancePin(requested, playerId, skinId, "local_skin:", "skin")
                || !collectAppearancePin(
                        requested, playerId, capeId, "local_cape:", "cape")) return false;
        return pinnedTextures.tryReplace(playerId, requested);
    }

    public void releaseAppearancePins(UUID playerId) {
        pinnedTextures.releasePlayer(playerId);
    }

    public boolean isPinned(String hash) {
        String primary = resolvePrimary(hash);
        return primary != null && pinnedTextures.isPinned(primary);
    }

    public long getPinnedBytes() {
        return pinnedTextures.pinnedBytes();
    }

    private boolean collectAppearancePin(
            Map<String, Integer> requested,
            UUID playerId,
            String appearanceId,
            String prefix,
            String textureType
    ) {
        if (appearanceId == null || appearanceId.isEmpty()
                || !appearanceId.startsWith(prefix)) return true;
        String hash = appearanceId.substring(prefix.length());
        if (!NetworkSecurity.isValidContentId(hash)) return false;
        String primary = resolvePrimary(hash);
        CacheEntry entry = primary == null ? null : textureCache.get(primary);
        if (entry == null
                || !entry.owners.contains(new OwnerBinding(playerId, textureType))) return false;
        Integer previous = requested.putIfAbsent(primary, entry.data.length);
        return previous == null || previous == entry.data.length;
    }

    /** A request may fetch another player's texture, but only an authenticated stored entry. */
    public synchronized boolean isRequestable(String hash, String textureType) {
        if (!NetworkSecurity.isValidContentId(hash) || !NetworkSecurity.isValidTextureType(textureType)) {
            return false;
        }
        CacheEntry entry = textureCache.get(resolvePrimary(hash));
        if (entry == null) return false;
        for (OwnerBinding owner : entry.owners) {
            if (textureType.equals(owner.textureType)) return true;
        }
        return false;
    }

    /** Returns the cache's stable storage key for either authenticated wire alias. */
    public synchronized @Nullable String resolveContentId(String contentId) {
        if (!NetworkSecurity.isValidContentId(contentId)) return null;
        String primary = contentAliases.resolve(contentId);
        return primary != null && textureCache.containsKey(primary) ? primary : null;
    }

    /** Translates only aliases proven to describe the same cached canonical PNG. */
    public synchronized @Nullable String contentIdFor(
            String contentId, ContentId.Algorithm algorithm) {
        if (algorithm == null) return null;
        String translated = contentAliases.alias(contentId, algorithm);
        return translated != null && resolveContentId(translated) != null ? translated : null;
    }

    private String resolvePrimary(String contentId) {
        return contentAliases.resolve(contentId);
    }

    /**
     * Current animated PNGs carry an exact identity. Legacy PNGs are permanently bound to their
     * first accepted metadata for the lifetime of the backing texture.
     */
    public boolean isAnimationMetadataCompatible(
            String hash, String metadataJson) {
        if (!NetworkSecurity.isValidContentId(hash) || metadataJson == null) return false;
        String embeddedIdentity;
        String primary;
        synchronized (this) {
            primary = resolvePrimary(hash);
            CacheEntry entry = textureCache.get(primary);
            if (entry == null) return false;
            embeddedIdentity = entry.embeddedAnimationMetadata;
        }
        return (embeddedIdentity == null || embeddedIdentity.equals(metadataJson))
                && ServerAnimationCache.getInstance()
                        .isMetadataIdentityCompatible(primary, metadataJson);
    }

    public void saveAll() {
        List<Map.Entry<String, CacheEntry>> snapshot;
        synchronized (this) {
            snapshot = new ArrayList<>(textureCache.entrySet());
        }
        int failed = 0;
        for (Map.Entry<String, CacheEntry> entry : snapshot) {
            if (!saveTextureToDisk(entry.getKey(), entry.getValue())) failed++;
        }
        if (failed > 0) {
            LOGGER.warn("Failed to persist {} QuickSkin texture cache entries", failed);
        }
    }

    public synchronized void clear() {
        generation++;
        textureCache.clear();
        contentAliases.clear();
        cachedBytes = 0;
        pinnedTextures.clear();
        deleteStagedFiles(storageDirectory);
        storageDirectory = null;
    }

    private boolean commitPreparedFiles(
            PreparedTexture prepared, String primary,
            CacheEntry candidate, @Nullable CacheEntry previous) {
        Path imageFile = safeFile(primary, ".png");
        Path ownerFile = safeFile(primary, ".owner");
        Path ownerTemp = safeFile(
                primary, ".owner." + UUID.randomUUID() + ".ingress.tmp");
        if (imageFile == null || ownerFile == null || ownerTemp == null
                || Files.isSymbolicLink(imageFile) || Files.isSymbolicLink(ownerFile)
                || Files.isSymbolicLink(ownerTemp) || Files.isSymbolicLink(prepared.stagedImage)) {
            LOGGER.warn("Refusing unsafe texture cache path for {}", primary);
            return false;
        }
        try {
            Files.write(ownerTemp, ownershipBytes(candidate.owners),
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            if (previous == null
                    || !Files.isRegularFile(imageFile, LinkOption.NOFOLLOW_LINKS)) {
                atomicReplace(prepared.stagedImage, imageFile);
            }
            atomicReplace(ownerTemp, ownerFile);
            return true;
        } catch (IOException exception) {
            LOGGER.error("Unable to commit QuickSkin texture {}", primary, exception);
            return false;
        } finally {
            deleteTempFile(ownerTemp);
        }
    }

    private byte[] ownershipBytes(Set<OwnerBinding> owners) {
        StringBuilder ownership = new StringBuilder();
        for (OwnerBinding owner : owners) {
            ownership.append(owner.ownerId).append(':').append(owner.textureType).append('\n');
        }
        return ownership.toString().getBytes(StandardCharsets.UTF_8);
    }

    private boolean saveTextureToDisk(String hash, CacheEntry entry) {
        Path imageFile = safeFile(hash, ".png");
        Path ownerFile = safeFile(hash, ".owner");
        String nonce = UUID.randomUUID().toString();
        Path imageTemp = safeFile(hash, ".png." + nonce + ".tmp");
        Path ownerTemp = safeFile(hash, ".owner." + nonce + ".tmp");
        if (imageFile == null || ownerFile == null || Files.isSymbolicLink(imageFile)
                || Files.isSymbolicLink(ownerFile) || imageTemp == null || ownerTemp == null
                || Files.isSymbolicLink(imageTemp) || Files.isSymbolicLink(ownerTemp)) {
            LOGGER.warn("Refusing unsafe texture cache path for {}", hash);
            return false;
        }
        try {
            Files.write(imageTemp, entry.data, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            Files.write(ownerTemp, ownershipBytes(entry.owners),
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            atomicReplace(imageTemp, imageFile);
            atomicReplace(ownerTemp, ownerFile);
            return true;
        } catch (IOException e) {
            LOGGER.error("Unable to persist QuickSkin texture {}", hash, e);
            return false;
        } finally {
            deleteTempFile(imageTemp);
            deleteTempFile(ownerTemp);
        }
    }

    private void atomicReplace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void deleteTempFile(Path file) {
        if (file == null || Files.isSymbolicLink(file)) return;
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            LOGGER.debug("Unable to remove QuickSkin cache temp file {}", file, e);
        }
    }

    private void deleteStagedFiles(@Nullable Path directory) {
        if (directory == null || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return;
        try (Stream<Path> paths = Files.list(directory)) {
            paths.filter(path -> path.getFileName().toString().endsWith(".ingress.tmp"))
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .forEach(this::deleteTempFile);
        } catch (IOException exception) {
            LOGGER.debug("Unable to clean staged QuickSkin texture files in {}", directory, exception);
        }
    }

    private void loadCachedTextures() {
        if (storageDirectory == null || !Files.isDirectory(storageDirectory, LinkOption.NOFOLLOW_LINKS)) return;

        Map<String, String> legacyMigrations = new LinkedHashMap<>();
        PriorityQueue<Path> newestFiles = new PriorityQueue<>(
                Comparator.comparingLong(this::lastModified));
        try (Stream<Path> paths = Files.list(storageDirectory)) {
            paths.filter(path -> path.getFileName().toString().endsWith(".png"))
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .forEach(path -> {
                        newestFiles.add(path);
                        if (newestFiles.size() > TextureTransferLimits.MAX_SERVER_CACHE_ENTRIES) {
                            Path expired = newestFiles.remove();
                            String name = expired.getFileName().toString();
                            scheduleEntryDeletion(
                                    name.substring(0, name.length() - ".png".length()));
                        }
                    });
        } catch (IOException e) {
            LOGGER.error("Unable to list QuickSkin texture cache {}", storageDirectory, e);
            return;
        }

        List<Path> files = new ArrayList<>(newestFiles);
        files.sort(Comparator.comparingLong(this::lastModified));
        for (Path path : files) {
            String fileName = path.getFileName().toString();
            String hash = fileName.substring(0, fileName.length() - ".png".length());
            if (!NetworkSecurity.isValidContentId(hash) || safeFile(hash, ".png") == null) continue;
            try {
                long size = Files.size(path);
                if (size <= 0 || size > TextureTransferLimits.MAX_TEXTURE_BYTES) {
                    scheduleEntryDeletion(hash);
                    continue;
                }
                Ownership ownership = readOwnership(hash);
                if (ownership == null) {
                    LOGGER.warn("Ignoring unowned legacy texture cache entry {}", hash);
                    scheduleEntryDeletion(hash);
                    continue;
                }
                byte[] data = BoundedFileReader.readBytes(
                        path, TextureTransferLimits.MAX_TEXTURE_BYTES);
                String embeddedAnimationMetadata = PngAnimationIdentity.extract(data);
                if (!ContentId.matches(hash, data)
                        || !isValidForOwnedTypes(data, ownership.owners)
                        || (embeddedAnimationMetadata != null
                                && NetworkSecurity.parseAnimationMetadata(
                                        embeddedAnimationMetadata) == null)) {
                    LOGGER.warn("Ignoring invalid texture cache entry {}", hash);
                    scheduleEntryDeletion(hash);
                    continue;
                }
                ContentAliases aliases = ContentAliases.forBytes(data);
                String primary = contentAliases.register(hash, aliases);
                if (primary == null) {
                    LOGGER.warn("Ignoring conflicting texture aliases for {}", hash);
                    scheduleEntryDeletion(hash);
                    continue;
                }
                CacheEntry entry = new CacheEntry(
                        data, embeddedAnimationMetadata, ownership.owners);
                CacheEntry previous = textureCache.get(primary);
                if (previous != null) {
                    if (!Arrays.equals(previous.data, data)) {
                        LOGGER.warn("Ignoring colliding texture cache entry {}", hash);
                        scheduleEntryDeletion(hash);
                        continue;
                    }
                    Set<OwnerBinding> merged = new LinkedHashSet<>(previous.owners);
                    if (merged.size() + ownership.owners.size() > MAX_OWNERS_PER_TEXTURE) {
                        scheduleEntryDeletion(hash);
                        continue;
                    }
                    merged.addAll(ownership.owners);
                    textureCache.put(primary, new CacheEntry(
                            previous.data, previous.embeddedAnimationMetadata, merged));
                } else {
                    textureCache.put(primary, entry);
                    cachedBytes += data.length;
                }
                if (!primary.equals(hash)) legacyMigrations.put(hash, primary);
                evictToLimits();
            } catch (IOException e) {
                LOGGER.warn("Unable to load QuickSkin texture cache entry {}", path, e);
            }
        }
        migrateLegacyCacheEntries(legacyMigrations);
    }

    /**
     * Migrates an authenticated SHA-1 filename only after its SHA-256 replacement and ownership
     * sidecar have been atomically written and read back. A failed migration deliberately retains
     * the legacy files so the next startup can retry without losing the cached blob.
     */
    private void migrateLegacyCacheEntries(Map<String, String> migrations) {
        if (migrations.isEmpty()) return;
        Map<String, Boolean> persistedPrimaries = new LinkedHashMap<>();
        for (Map.Entry<String, String> migration : migrations.entrySet()) {
            String legacy = migration.getKey();
            String primary = migration.getValue();
            CacheEntry entry = textureCache.get(primary);
            if (entry == null) {
                // The entry was intentionally evicted while restoring the bounded cache.
                scheduleEntryDeletion(legacy);
                continue;
            }
            boolean persisted = persistedPrimaries.computeIfAbsent(primary, ignored ->
                    saveTextureToDisk(primary, entry)
                            && isPersistedEntry(primary, entry));
            if (persisted) {
                scheduleLegacyAliasDeletion(legacy);
            } else {
                LOGGER.warn("Retaining legacy texture cache entry {} because migration to {} "
                        + "could not be verified", legacy, primary);
            }
        }
    }

    private boolean isPersistedEntry(String hash, CacheEntry expected) {
        Path imageFile = safeFile(hash, ".png");
        Path ownerFile = safeFile(hash, ".owner");
        if (imageFile == null || ownerFile == null
                || !Files.isRegularFile(imageFile, LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(ownerFile, LinkOption.NOFOLLOW_LINKS)) return false;
        try {
            if (Files.size(imageFile) != expected.data.length
                    || Files.size(ownerFile) > 16 * 1024) return false;
            byte[] persistedData = BoundedFileReader.readBytes(
                    imageFile, TextureTransferLimits.MAX_TEXTURE_BYTES);
            byte[] persistedOwners = BoundedFileReader.readBytes(ownerFile, 16 * 1024);
            return ContentId.matches(hash, persistedData)
                    && Arrays.equals(expected.data, persistedData)
                    && Arrays.equals(ownershipBytes(expected.owners), persistedOwners);
        } catch (IOException exception) {
            LOGGER.warn("Unable to verify migrated QuickSkin texture {}", hash, exception);
            return false;
        }
    }

    @Nullable
    private Ownership readOwnership(String hash) {
        Path ownerFile = safeFile(hash, ".owner");
        if (ownerFile == null || !Files.isRegularFile(ownerFile, LinkOption.NOFOLLOW_LINKS)) return null;
        try {
            if (Files.size(ownerFile) > 16 * 1024) return null;
            List<String> lines = Arrays.asList(
                    BoundedFileReader.readUtf8(ownerFile, 16 * 1024).split("\\R", -1));
            Set<OwnerBinding> owners = new LinkedHashSet<>();
            for (String line : lines) {
                if (line.isEmpty()) continue;
                int separator = line.lastIndexOf(':');
                if (separator <= 0) return null;
                String type = line.substring(separator + 1);
                if (!NetworkSecurity.isValidTextureType(type)) return null;
                owners.add(new OwnerBinding(UUID.fromString(line.substring(0, separator)), type));
                if (owners.size() > MAX_OWNERS_PER_TEXTURE) return null;
            }
            return owners.isEmpty() ? null : new Ownership(owners);
        } catch (IOException | IllegalArgumentException e) {
            LOGGER.warn("Unable to read ownership for QuickSkin texture {}", hash, e);
            return null;
        }
    }

    /** A shared content blob must satisfy every typed ownership claim restored from disk. */
    private boolean isValidForOwnedTypes(byte[] data, Set<OwnerBinding> owners) {
        Set<String> validatedTypes = new LinkedHashSet<>();
        for (OwnerBinding owner : owners) {
            if (validatedTypes.add(owner.textureType)
                    && !NetworkSecurity.isValidTextureData(data, owner.textureType)) return false;
        }
        return !validatedTypes.isEmpty();
    }

    private synchronized void evictToLimits() {
        List<String> evicted = new ArrayList<>();
        Iterator<Map.Entry<String, CacheEntry>> iterator = textureCache.entrySet().iterator();
        while ((textureCache.size() > TextureTransferLimits.MAX_SERVER_CACHE_ENTRIES
                || cachedBytes > TextureTransferLimits.MAX_SERVER_CACHE_BYTES) && iterator.hasNext()) {
            Map.Entry<String, CacheEntry> eldest = iterator.next();
            if (pinnedTextures.isPinned(eldest.getKey())) continue;
            iterator.remove();
            cachedBytes -= eldest.getValue().data.length;
            pinnedTextures.removeTexture(eldest.getKey());
            contentAliases.removePrimary(eldest.getKey());
            evicted.add(eldest.getKey());
        }
        scheduleEntryDeletions(evicted);
    }

    private void scheduleEntryDeletion(String hash) {
        scheduleEntryDeletions(List.of(hash), true);
    }

    /** Deletes only an obsolete texture/owner alias after its strong replacement is durable. */
    private void scheduleLegacyAliasDeletion(String hash) {
        scheduleEntryDeletions(List.of(hash), false);
    }

    /** Captures contained paths under the monitor; all actual deletes happen on the I/O worker. */
    private void scheduleEntryDeletions(List<String> hashes) {
        scheduleEntryDeletions(hashes, true);
    }

    private void scheduleEntryDeletions(
            List<String> hashes, boolean removeAnimationMetadata) {
        if (hashes == null || hashes.isEmpty()) return;
        List<EntryDeletion> accepted = new ArrayList<>();
        synchronized (this) {
            for (String hash : hashes) {
                if (!NetworkSecurity.isValidContentId(hash) || textureCache.containsKey(hash)
                        || pendingDeletions.containsKey(hash)) continue;
                if (pendingDeletions.size() >= MAX_PENDING_DELETIONS) {
                    LOGGER.warn("QuickSkin cache cleanup backlog reached its hard bound; retaining stale disk files");
                    break;
                }
                Path image = safeFile(hash, ".png");
                Path ownership = safeFile(hash, ".owner");
                if (image == null && ownership == null) continue;
                EntryDeletion deletion = new EntryDeletion(
                        hash, image, ownership,
                        ServerAnimationCache.getInstance().generation(),
                        removeAnimationMetadata);
                pendingDeletions.put(hash, deletion);
                pinnedTextures.removeTexture(hash);
                accepted.add(deletion);
            }
        }
        if (accepted.isEmpty()) return;
        if (ServerCacheIoExecutor.getInstance().submit(() -> {
            for (EntryDeletion deletion : accepted) {
                try {
                    deleteEntryFilesOffThread(deletion);
                } catch (RuntimeException | LinkageError error) {
                    LOGGER.warn("Unable to finish QuickSkin cache eviction for {}",
                            deletion.hash, error);
                }
            }
        })) return;
        synchronized (this) {
            for (EntryDeletion deletion : accepted) {
                deletion.cancelQueued();
                pendingDeletions.remove(deletion.hash, deletion);
            }
        }
        LOGGER.warn("QuickSkin cache cleanup worker is stopped or full; retaining {} stale disk entries",
                accepted.size());
    }

    private void deleteEntryFilesOffThread(EntryDeletion deletion) {
        if (!deletion.begin()) return;
        try {
            deleteFile(deletion.image);
            deleteFile(deletion.ownership);
            if (deletion.removeAnimationMetadata) {
                ServerAnimationCache.getInstance().removeMetadata(
                        deletion.hash, deletion.animationGeneration);
            }
        } finally {
            synchronized (this) {
                pendingDeletions.remove(deletion.hash, deletion);
            }
        }
    }

    /** Returns false only after the worker has claimed the old files for deletion. */
    private boolean cancelPendingDeletion(String hash) {
        EntryDeletion deletion = pendingDeletions.get(hash);
        if (deletion == null) return true;
        if (!deletion.cancelQueued()) return false;
        pendingDeletions.remove(hash, deletion);
        return true;
    }

    private void cancelPendingDeletions() {
        for (EntryDeletion deletion : pendingDeletions.values()) deletion.cancelQueued();
        pendingDeletions.clear();
    }

    private void deleteFile(@Nullable Path file) {
        if (file == null || Files.isSymbolicLink(file)) return;
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            LOGGER.warn("Unable to evict QuickSkin cache file {}", file, e);
        }
    }

    @Nullable
    private Path safeFile(String hash, String suffix) {
        return NetworkSecurity.resolveContained(storageDirectory, hash, suffix);
    }

    private long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis();
        } catch (IOException e) {
            return Long.MIN_VALUE;
        }
    }

    /**
     * Immutable validation result whose only mutable state is its one-shot commit/cleanup guard.
     * Callers must close rejected or stale results so their staged file is removed promptly.
     */
    public static final class PreparedTexture implements AutoCloseable {
        private final long generation;
        private final Path storageDirectory;
        private final Path stagedImage;
        private final String hash;
        private final ContentAliases aliases;
        private final UUID ownerId;
        private final String textureType;
        private final String embeddedAnimationMetadata;
        private final byte[] data;
        private boolean commitStarted;
        private boolean closed;

        private PreparedTexture(
                long generation,
                Path storageDirectory,
                Path stagedImage,
                String hash,
                ContentAliases aliases,
                UUID ownerId,
                String textureType,
                String embeddedAnimationMetadata,
                byte[] data
        ) {
            this.generation = generation;
            this.storageDirectory = storageDirectory;
            this.stagedImage = stagedImage;
            this.hash = hash;
            this.aliases = aliases;
            this.ownerId = ownerId;
            this.textureType = textureType;
            this.embeddedAnimationMetadata = embeddedAnimationMetadata;
            this.data = data;
        }

        public String hash() {
            return hash;
        }

        private synchronized boolean beginCommit() {
            if (closed || commitStarted) return false;
            commitStarted = true;
            return true;
        }

        @Override
        public void close() {
            synchronized (this) {
                if (closed) return;
                closed = true;
            }
            if (Files.isSymbolicLink(stagedImage)) return;
            try {
                Files.deleteIfExists(stagedImage);
            } catch (IOException exception) {
                LOGGER.debug("Unable to remove staged QuickSkin texture {}", stagedImage, exception);
            }
        }
    }

    private static final class CacheEntry {
        private final byte[] data;
        private final String embeddedAnimationMetadata;
        private final Set<OwnerBinding> owners;

        private CacheEntry(
                byte[] data, String embeddedAnimationMetadata,
                Set<OwnerBinding> owners) {
            this.data = data;
            this.embeddedAnimationMetadata = embeddedAnimationMetadata;
            this.owners = new LinkedHashSet<>(owners);
        }
    }

    private static final class Ownership {
        private final Set<OwnerBinding> owners;

        private Ownership(Set<OwnerBinding> owners) {
            this.owners = owners;
        }
    }

    private static final class OwnerBinding {
        private final UUID ownerId;
        private final String textureType;

        private OwnerBinding(UUID ownerId, String textureType) {
            this.ownerId = ownerId;
            this.textureType = textureType;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof OwnerBinding)) return false;
            OwnerBinding binding = (OwnerBinding) other;
            return ownerId.equals(binding.ownerId) && textureType.equals(binding.textureType);
        }

        @Override
        public int hashCode() {
            return 31 * ownerId.hashCode() + textureType.hashCode();
        }
    }

    private static final class EntryDeletion {
        private static final int QUEUED = 0;
        private static final int RUNNING = 1;
        private static final int CANCELED = 2;

        private final String hash;
        private final Path image;
        private final Path ownership;
        private final long animationGeneration;
        private final boolean removeAnimationMetadata;
        private final AtomicInteger state = new AtomicInteger(QUEUED);

        private EntryDeletion(
                String hash, Path image, Path ownership,
                long animationGeneration, boolean removeAnimationMetadata) {
            this.hash = hash;
            this.image = image;
            this.ownership = ownership;
            this.animationGeneration = animationGeneration;
            this.removeAnimationMetadata = removeAnimationMetadata;
        }

        private boolean begin() {
            return state.compareAndSet(QUEUED, RUNNING);
        }

        private boolean cancelQueued() {
            return state.compareAndSet(QUEUED, CANCELED);
        }
    }
}
