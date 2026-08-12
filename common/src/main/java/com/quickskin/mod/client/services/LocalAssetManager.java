package com.quickskin.mod.client.services;

import com.quickskin.mod.QuickSkin;
import com.quickskin.mod.common.data.AnimationMetadata;
import com.quickskin.mod.common.data.AssetMetadata;
import com.quickskin.mod.common.data.ContentId;
import com.quickskin.mod.common.data.LegacyContentAliasIndex;
import com.quickskin.mod.common.data.SkinPreferences;
import com.quickskin.mod.common.data.SkinResolution;
import com.quickskin.mod.common.data.SkinSortMode;
import com.quickskin.mod.common.data.TextureQuality;
import com.quickskin.mod.common.util.HashUtil;
import com.quickskin.mod.common.util.BoundedFileReader;
import com.quickskin.mod.common.util.CapeElytraSilhouette;
import com.quickskin.mod.common.util.HDTextureProcessor;
import com.quickskin.mod.common.util.SkinModelDetector;
import com.quickskin.mod.common.util.SafeImageReader;
import com.quickskin.mod.config.ClientConfig;
import com.quickskin.mod.networking.NetworkSecurity;
import com.quickskin.mod.platform.PlatformHelper;
//? if <1.21 {
import com.quickskin.mod.platform.MinecraftCompat;
//?} else if <1.21.11 {
//?} else {
import com.quickskin.mod.platform.MinecraftCompat;
//?}
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
//? if <1.21.11 {
import net.minecraft.resources.ResourceLocation;
//?} else {
import net.minecraft.resources.Identifier;
//?}
import org.jetbrains.annotations.Nullable;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
//? if <1.21 {
//?} else {
import java.lang.ref.SoftReference;
//?}
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Manages local skin and cape assets
 * Singleton service that scans filesystem and maintains metadata cache
 */
@Environment(EnvType.CLIENT)
public class LocalAssetManager {

    private static final int MAX_ASSET_BYTES = (int) SafeImageReader.MAX_ENCODED_BYTES;
    private static final int MAX_ANIMATION_FRAMES = 256;
    private static final int MAX_SCAN_CANDIDATES = 4096;
    private static final int MAX_SCAN_DEPTH = 32;
    private static final int MAX_LEGACY_ALIASES_PER_ASSET = 4;

    private static LocalAssetManager instance;

    // Asset discovery. Readers observe one immutable catalog generation instead of the transient
    // clear-and-repopulate states produced by a rescan.
    private volatile CatalogSnapshot catalog = CatalogSnapshot.empty();

    // Invalidates directory fingerprints captured before a catalog mutation or lifecycle reset.
    // Access is guarded by this manager's monitor.
    private long scanEpoch;

    // Texture registration
//? if <1.21.11 {
    private final Map<String, Map<TextureQuality, ResourceLocation>> textureRegistry = new ConcurrentHashMap<>();
//?} else {
    private final Map<String, Map<TextureQuality, Identifier>> textureRegistry = new ConcurrentHashMap<>();
//?}

//? if <1.21 {
//?} else if <1.21.11 {
    // F8: GC-friendly cache of decoded source BufferedImages
    private final Map<String, SoftReference<BufferedImage>> sourceImageCache = new ConcurrentHashMap<>();

//?} else {
    // F8: GC-friendly cache of decoded source BufferedImages
    private final Map<String, SoftReference<BufferedImage>> sourceImageCache = new ConcurrentHashMap<>();
//?}

    // Directory paths
    private Path skinsDirectory;
    private Path capesDirectory;
    private Path cacheDirectory;

    // Folder fingerprint as of the last completed scan; drives token-checked refreshes.
    private volatile long lastScanFingerprint;

    // Per-skin preferences
    private SkinPreferences skinPreferences;
    private Path preferencesFile;

    /**
     * Result enum for rename operations
     */
    public enum RenameResult {
        SUCCESS,
        NAME_TAKEN,
        INVALID_NAME,
        IO_ERROR,
        NOT_FOUND
    }

    /**
     * Immutable input captured before a metadata-only folder walk leaves the render thread.
     * The owner and epoch are intentionally opaque: callers can carry the request back, but cannot
     * make an old result current again.
     */
    public static final class ScanRequest {
        private final LocalAssetManager owner;
        private final long epoch;
        private final boolean initialized;
        private final List<Path> directories;

        ScanRequest(
                LocalAssetManager owner,
                long epoch,
                boolean initialized,
                List<Path> directories
        ) {
            this.owner = Objects.requireNonNull(owner, "owner");
            this.epoch = epoch;
            this.initialized = initialized;
            this.directories = List.copyOf(directories);
        }

        public List<Path> directories() {
            return directories;
        }
    }

    /** One atomically published, internally immutable view of the local asset catalog. */
    static final class CatalogSnapshot {
        private static final CatalogSnapshot EMPTY =
                new CatalogSnapshot(Map.of(), Map.of(), Map.of());

        private final Map<String, AssetMetadata> metadata;
        private final Map<String, Path> sourcePaths;
        private final Map<String, String> legacyAliases;

        private CatalogSnapshot(
                Map<String, AssetMetadata> metadata,
                Map<String, Path> sourcePaths,
                Map<String, String> legacyAliases
        ) {
            Map<String, AssetMetadata> metadataCopy = Map.copyOf(metadata);
            Map<String, Path> sourcePathCopy = Map.copyOf(sourcePaths);
            Map<String, String> aliasCopy = Map.copyOf(legacyAliases);
            if (!metadataCopy.keySet().equals(sourcePathCopy.keySet())) {
                throw new IllegalArgumentException("Local asset catalog indexes disagree");
            }
            for (String primary : metadataCopy.keySet()) {
                ContentId parsed = ContentId.parse(primary);
                if (parsed == null || parsed.algorithm() != ContentId.Algorithm.SHA256) {
                    throw new IllegalArgumentException("Local catalog primary is not SHA-256");
                }
            }
            for (Map.Entry<String, String> alias : aliasCopy.entrySet()) {
                ContentId legacy = ContentId.parse(alias.getKey());
                ContentId strong = ContentId.parse(alias.getValue());
                if (legacy == null || legacy.algorithm() != ContentId.Algorithm.SHA1
                        || strong == null || strong.algorithm() != ContentId.Algorithm.SHA256
                        || !metadataCopy.containsKey(alias.getValue())) {
                    throw new IllegalArgumentException("Invalid local content alias");
                }
            }
            this.metadata = metadataCopy;
            this.sourcePaths = sourcePathCopy;
            this.legacyAliases = aliasCopy;
        }

        static CatalogSnapshot empty() {
            return EMPTY;
        }

        static CatalogSnapshot copyOf(
                Map<String, AssetMetadata> metadata,
                Map<String, Path> sourcePaths
        ) {
            return copyOf(metadata, sourcePaths, Map.of());
        }

        static CatalogSnapshot copyOf(
                Map<String, AssetMetadata> metadata,
                Map<String, Path> sourcePaths,
                Map<String, String> legacyAliases
        ) {
            return metadata.isEmpty() && sourcePaths.isEmpty() && legacyAliases.isEmpty()
                    ? EMPTY
                    : new CatalogSnapshot(metadata, sourcePaths, legacyAliases);
        }

        Map<String, AssetMetadata> metadata() {
            return metadata;
        }

        Map<String, Path> sourcePaths() {
            return sourcePaths;
        }

        Map<String, String> legacyAliases() {
            return legacyAliases;
        }

        String resolve(String contentId) {
            ContentId parsed = ContentId.parse(contentId);
            if (parsed == null) return null;
            if (parsed.algorithm() == ContentId.Algorithm.SHA256) {
                return metadata.containsKey(contentId) ? contentId : null;
            }
            return legacyAliases.get(contentId);
        }

        CatalogSnapshot with(String hash, AssetMetadata assetMetadata, Path sourcePath) {
            Map<String, AssetMetadata> nextMetadata = new HashMap<>(metadata);
            Map<String, Path> nextSourcePaths = new HashMap<>(sourcePaths);
            nextMetadata.put(hash, assetMetadata);
            nextSourcePaths.put(hash, sourcePath);
            return copyOf(nextMetadata, nextSourcePaths, legacyAliases);
        }

        CatalogSnapshot without(String hash) {
            Map<String, AssetMetadata> nextMetadata = new HashMap<>(metadata);
            Map<String, Path> nextSourcePaths = new HashMap<>(sourcePaths);
            nextMetadata.remove(hash);
            nextSourcePaths.remove(hash);
            Map<String, String> nextAliases = new HashMap<>(legacyAliases);
            nextAliases.entrySet().removeIf(entry -> hash.equals(entry.getValue()));
            return copyOf(nextMetadata, nextSourcePaths, nextAliases);
        }
    }

    /** Mutable state owned by one synchronous scan and frozen exactly once at commit. */
    private static final class CatalogBuilder {
        private final Map<String, AssetMetadata> metadata = new HashMap<>();
        private final Map<String, Path> sourcePaths = new HashMap<>();
        private final LegacyContentAliasIndex aliases = new LegacyContentAliasIndex(
                MAX_SCAN_CANDIDATES * 3, MAX_LEGACY_ALIASES_PER_ASSET);
        private final LegacyContentAliasIndex capePreflightAliases = new LegacyContentAliasIndex(
                MAX_SCAN_CANDIDATES, 2);

        private void put(AssetMetadata assetMetadata, Path sourcePath) {
            if (!aliases.register(assetMetadata.hash(), List.of())) return;
            metadata.put(assetMetadata.hash(), assetMetadata);
            sourcePaths.put(assetMetadata.hash(), sourcePath);
        }

        private void registerAliases(String primary, String... legacyAliases) {
            aliases.register(primary, Arrays.asList(legacyAliases));
        }

        private void registerCapePreflight(String primary, String... legacyAliases) {
            capePreflightAliases.register(primary, Arrays.asList(legacyAliases));
        }

        private boolean isAuthorizedCapeAlias(String alias, String initialPrimary) {
            return capePreflightAliases.resolvesTo(alias, initialPrimary);
        }

        private CatalogSnapshot freeze() {
            Map<String, String> filteredAliases = new HashMap<>(aliases.uniqueAliases());
            filteredAliases.entrySet().removeIf(
                    entry -> !metadata.containsKey(entry.getValue()));
            return CatalogSnapshot.copyOf(metadata, sourcePaths, filteredAliases);
        }
    }

    private LocalAssetManager() {
        // Private constructor for singleton
    }

    public static LocalAssetManager getInstance() {
        if (instance == null) {
            instance = new LocalAssetManager();
        }
        return instance;
    }

    /**
     * Initialize asset manager and discover assets
     */
    public synchronized void init() {
        // Get directories from platform helper
        skinsDirectory = PlatformHelper.getSkinsDirectory();
        capesDirectory = PlatformHelper.getCapesDirectory();
        cacheDirectory = PlatformHelper.getCacheDirectory();

        // Create directories if they don't exist
        try {
            Files.createDirectories(skinsDirectory);
            Files.createDirectories(capesDirectory);
            Files.createDirectories(cacheDirectory);
        } catch (IOException e) {
            QuickSkin.LOGGER.error("Unable to create QuickSkin local asset directories", e);
        }

        // Load skin preferences
        preferencesFile = PlatformHelper.getConfigDirectory().resolve("skin-preferences.json");
        skinPreferences = SkinPreferences.load(preferencesFile);

        // Discover assets
        discoverLocalAssets();
    }

    /**
     * Scan filesystem for skins and capes, build metadata cache
     */
    public synchronized void discoverLocalAssets() {
        if (skinsDirectory == null || capesDirectory == null || cacheDirectory == null) {
            return;
        }
        invalidatePendingScans();
        CatalogBuilder scanned = new CatalogBuilder();

        // Scan skins directory
        scanDirectory(skinsDirectory, "skin", scanned);

        // Scan capes directory
        scanDirectory(capesDirectory, "cape", scanned);

        // CPM is optional: do not even expose model files when the mod is absent.
        if (com.quickskin.mod.client.compat.CPMCompatIntegration.isAvailable()) {
            com.quickskin.mod.client.compat.CpmModelWorkflow.reconcilePendingSkinModeReset();
            scanCpmModels(scanned);
        } else {
            com.quickskin.mod.client.compat.CpmModelWorkflow.sanitizeUnavailableState();
        }

        // Publish both indexes together. Readers keep seeing the previous complete catalog until
        // this single volatile write, even if the disk scan is slow or skips an invalid entry.
        CatalogSnapshot completed = scanned.freeze();
        catalog = completed;
        com.quickskin.mod.client.compat.CpmModelWorkflow.sanitizeMissingActiveModel();
        migrateLegacyCacheFiles(completed);
        LocalContentIdMigration.migrate(
                aliasesForType(completed, "skin"),
                aliasesForType(completed, "cape"),
                aliasesForType(completed, "cpmmodel"),
                skinPreferences,
                preferencesFile);

        // Recorded last: processPngAsset may rewrite oversized files, which changes their own
        // size/mtime. Fingerprinting before the scan would leave the folder permanently "dirty".
        lastScanFingerprint = LocalAssetFolderWatch.fingerprint(getScannedDirectories());
//? if <1.21.11 {
    }

    /**
     * Scan CPM's player_models directory for .cpmmodel files
     */
    private void scanCpmModels(CatalogBuilder scanned) {
        Path modelsDir = com.quickskin.mod.client.compat.CPMCompatIntegration.getCPMModelsDirectory();
        if (!Files.exists(modelsDir)) return;

        try (Stream<Path> paths = Files.walk(modelsDir, MAX_SCAN_DEPTH)) {
            List<Path> candidates = paths.limit(MAX_SCAN_CANDIDATES)
                    .filter(Files::isRegularFile).toList();
            if (candidates.size() == MAX_SCAN_CANDIDATES) {
                QuickSkin.LOGGER.warn("CPM model scan reached the {} file cap in {}", MAX_SCAN_CANDIDATES, modelsDir);
            }
            for (Path path : candidates) {
                String fileName = path.getFileName().toString();
                if (!fileName.toLowerCase(Locale.ROOT).endsWith(".cpmmodel")) continue;

                try {
                    byte[] modelBytes = BoundedFileReader.readBytes(path, MAX_ASSET_BYTES);
                    String legacyHash = HashUtil.computeHash(modelBytes);
                    String hash = HashUtil.computeContentId(modelBytes);
                    if (hash == null || legacyHash == null) continue;

                    // Parse the .cpmmodel to get its name
                    var info = com.quickskin.mod.client.compat.CPMCompatIntegration.parseCpmModelInfo(path);
                    String friendlyName = info != null ? info.name : fileName.substring(0, fileName.length() - 9);

                    long fileSize = modelBytes.length;
                    long lastModifiedTime = Files.getLastModifiedTime(path).toMillis();

                    AssetMetadata metadata = AssetMetadata.forCpmModel(hash, friendlyName, path, fileSize, lastModifiedTime);
                    scanned.registerAliases(hash, legacyHash);
                    scanned.put(metadata, path);

                    // Cache icon PNG bytes if available
                    if (info != null && info.iconPngBytes != null && info.iconPngBytes.length <= MAX_ASSET_BYTES
                            && SafeImageReader.readPng(info.iconPngBytes) != null) {
                        writeVerifiedPngCache(
                                cacheDirectory.resolve("cpm_icons"), hash, info.iconPngBytes);
                    }
                } catch (Exception e) {
                    // Skip invalid files
                    QuickSkin.LOGGER.debug("Skipping invalid CPM model {}", path, e);
                }
            }
        } catch (IOException e) {
            // Directory walk failed
            QuickSkin.LOGGER.warn("Unable to scan CPM model directory {}", modelsDir, e);
        }
//?} else {
//?}
    }

    /**
     * Scan directory for PNG and GIF files and process them
     * @return Number of assets found
     */
    private int scanDirectory(Path directory, String type, CatalogBuilder scanned) {
        if (!Files.exists(directory)) {
            return 0;
        }

        int count = 0;

        try (Stream<Path> paths = Files.walk(directory, MAX_SCAN_DEPTH)) {
            List<Path> candidates = paths.limit(MAX_SCAN_CANDIDATES)
                    .filter(Files::isRegularFile).toList();
            if (candidates.size() == MAX_SCAN_CANDIDATES) {
                QuickSkin.LOGGER.warn("Local asset scan reached the {} file cap in {}", MAX_SCAN_CANDIDATES, directory);
            }
            if ("cape".equals(type)) {
                preflightCapeAliases(candidates, scanned);
            }
            for (Path path : candidates) {
                String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);

                // Process PNG files
                if (fileName.endsWith(".png")) {
                    AssetMetadata metadata = processPngAsset(path, type, scanned);
                    if (metadata != null) {
                        scanned.put(metadata, path);
                        count++;
                    }
                }
                // Process GIF files (animated capes only)
                else if (fileName.endsWith(".gif") && "cape".equals(type)) {
                    AssetMetadata metadata = processGifAsset(path, scanned);
                    if (metadata != null) {
//? if <1.21 {
                        scanned.put(metadata, path);
//?} else {
                        // Point to the cached PNG atlas so loadTexture/getSourceImage get the
                        // full multi-frame image. ImageIO.read of a .gif only returns the first
                        // frame, so the atlas path is what downstream consumers actually need.
                        // Fall back to the .gif path if the atlas wasn't written (shouldn't happen).
                        Path cachedAtlas = cacheDirectory.resolve("animated_capes").resolve(metadata.hash() + ".png");
                        scanned.put(metadata, Files.exists(cachedAtlas) ? cachedAtlas : path);
//?}
                        count++;
                    }
                }
            }
        } catch (IOException e) {
            QuickSkin.LOGGER.warn("Unable to scan QuickSkin asset directory {}", directory, e);
        }

        return count;
//? if <1.21.11 {
//?} else {
    }

    /** Scan CPM's recursive player_models tree for standalone model files. */
    private void scanCpmModels(CatalogBuilder scanned) {
        Path modelsDirectory = com.quickskin.mod.client.compat.CPMCompatIntegration
                .getCPMModelsDirectory();
        if (!Files.isDirectory(modelsDirectory)) {
            return;
        }

        try (Stream<Path> paths = Files.walk(modelsDirectory, MAX_SCAN_DEPTH)) {
            List<Path> candidates = paths.limit(MAX_SCAN_CANDIDATES)
                    .filter(Files::isRegularFile).toList();
            if (candidates.size() == MAX_SCAN_CANDIDATES) {
                QuickSkin.LOGGER.warn("CPM model scan reached the {} file cap in {}", MAX_SCAN_CANDIDATES, modelsDirectory);
            }
            for (Path path : candidates) {
                String fileName = path.getFileName().toString();
                if (!fileName.toLowerCase(Locale.ROOT).endsWith(".cpmmodel")) {
                    continue;
                }
                try {
                    scanCpmModel(path, fileName, scanned);
                } catch (IOException | RuntimeException ignored) {
                    // Skip only the unreadable candidate and continue the recursive scan.
                    QuickSkin.LOGGER.debug("Skipping invalid CPM model {}", path, ignored);
                }
            }
        } catch (IOException ignored) {
            // An unreadable optional directory must not affect normal skins/capes.
            QuickSkin.LOGGER.warn("Unable to scan CPM model directory {}", modelsDirectory, ignored);
        }
    }

    private void scanCpmModel(Path path, String fileName, CatalogBuilder scanned) throws IOException {
        byte[] modelBytes = BoundedFileReader.readBytes(path, MAX_ASSET_BYTES);
        String legacyHash = HashUtil.computeHash(modelBytes);
        String hash = HashUtil.computeContentId(modelBytes);
        if (hash == null || legacyHash == null) {
            return;
        }

        com.quickskin.mod.client.compat.CPMCompatIntegration.CpmModelInfo info =
                com.quickskin.mod.client.compat.CPMCompatIntegration.parseCpmModelInfo(path);
        String fallbackName = fileName.substring(0, fileName.length() - 9);
        String friendlyName = info != null && info.name != null && !info.name.isBlank()
                ? info.name
                : fallbackName;
        AssetMetadata metadata = AssetMetadata.forCpmModel(
                hash,
                friendlyName,
                path,
                modelBytes.length,
                Files.getLastModifiedTime(path).toMillis()
        );
        scanned.registerAliases(hash, legacyHash);
        scanned.put(metadata, path);

        if (info != null && info.iconPngBytes != null && info.iconPngBytes.length <= MAX_ASSET_BYTES
                && SafeImageReader.readPng(info.iconPngBytes) != null) {
            writeVerifiedPngCache(
                    cacheDirectory.resolve("cpm_icons"), hash, info.iconPngBytes);
        }
//?}
    }

    /**
     * Authenticates every historical cape name before any candidate is allowed to consume a
     * SHA-1-keyed metadata sidecar. This first pass is what makes collision rejection independent
     * of filesystem iteration order.
     */
    private void preflightCapeAliases(List<Path> candidates, CatalogBuilder scanned) {
        for (Path path : candidates) {
            String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
            if (!fileName.endsWith(".png") && !fileName.endsWith(".gif")) continue;
            try {
                byte[] sourceBytes = BoundedFileReader.readBytes(path, MAX_ASSET_BYTES);
                String primary = HashUtil.computeAssetContentId(sourceBytes, "cape");
                String rawLegacy = HashUtil.computeHash(sourceBytes);
                String roleLegacy = HashUtil.computeAssetHash(sourceBytes, "cape");
                if (primary != null && rawLegacy != null && roleLegacy != null) {
                    scanned.registerCapePreflight(primary, rawLegacy, roleLegacy);
                }
            } catch (IOException | RuntimeException ignored) {
                // The normal scan will reject the unreadable candidate as well.
            }
        }
    }

    /**
     * Process PNG asset and create metadata
     */
    private AssetMetadata processPngAsset(Path path, String type, CatalogBuilder scanned) {
        try {
            byte[] sourceBytes = BoundedFileReader.readBytes(path, MAX_ASSET_BYTES);
            String rawLegacyHash = HashUtil.computeHash(sourceBytes);
            String roleLegacyHash = HashUtil.computeAssetHash(sourceBytes, type);
            String hash = HashUtil.computeAssetContentId(sourceBytes, type);
            if (hash == null || rawLegacyHash == null || roleLegacyHash == null) {
                return null;
            }
            String initialStrongHash = hash;

            // F9: read image once upfront, use for both metadata synthesis and dimension check.
            BufferedImage image = SafeImageReader.readPng(sourceBytes);
            if (image == null) {
                return null;
            }

            AnimationMetadata animMeta = null;
            if ("cape".equals(type)) {
                animMeta = readAnimationMetadataFile(hash);
                if (animMeta == null
                        && scanned.isAuthorizedCapeAlias(roleLegacyHash, initialStrongHash)) {
                    animMeta = readAnimationMetadataFile(roleLegacyHash);
                }
                if (animMeta == null && !roleLegacyHash.equals(rawLegacyHash)
                        && scanned.isAuthorizedCapeAlias(rawLegacyHash, initialStrongHash)) {
                    animMeta = readAnimationMetadataFile(rawLegacyHash);
                }
                if (animMeta == null) {
//? if <1.21 {
                    int candidateWidth = image.getWidth();
                    int candidateHeight = image.getHeight();
                    int candidateFrameHeight = candidateWidth / 2;
                    if (candidateWidth > 0 && candidateFrameHeight > 0
                            && candidateHeight > candidateFrameHeight
                            && candidateHeight % candidateFrameHeight == 0) {
                        int candidateFrames = candidateHeight / candidateFrameHeight;
                        if (candidateFrames > 1 && candidateFrames <= MAX_ANIMATION_FRAMES) {
//?} else {
                    int width = image.getWidth();
                    int height = image.getHeight();
                    int frameHeight = width / 2; // Cape frames have a 2:1 aspect ratio.

                    if (width > 0 && frameHeight > 0 && height > frameHeight && height % frameHeight == 0) {
                        int fc = height / frameHeight;
                        if (fc > 1 && fc <= MAX_ANIMATION_FRAMES) {
//?}
                            List<AnimationMetadata.FrameData> frames = new ArrayList<>();
//? if <1.21 {
                            for (int i = 0; i < candidateFrames; i++) {
//?} else {
                            for (int i = 0; i < fc; i++) {
                                // Use 50ms per frame (20 FPS) as a sensible default.
//?}
                                frames.add(new AnimationMetadata.FrameData(50, i));
                            }
//? if <1.21 {
                            animMeta = new AnimationMetadata(frames, candidateFrames);
//?} else {
                            animMeta = new AnimationMetadata(frames, fc);
//?}
                        }
                    }
                }
            }

            int width = image.getWidth();
            int height = image.getHeight();

            SkinResolution resolution;
            String skinModel = null;
            boolean isAnimated = false;
            int frameCount = 1;

            if (animMeta != null) {
                // This is an animated asset (cape) identified by its metadata file.
                isAnimated = true;
                frameCount = animMeta.frameCount();
                if (frameCount < 1 || frameCount > MAX_ANIMATION_FRAMES
                        || height % frameCount != 0) return null;
                int frameHeight = (frameCount > 0) ? height / frameCount : height;
                resolution = SkinResolution.fromDimensions(width, frameHeight);
                if (resolution == null) {
                    resolution = SkinResolution.findNearest(width, frameHeight);
                    if (resolution == null) {
                        return null;
                    }
                    image = HDTextureProcessor.resizeAnimationStrip(image, resolution.getWidth());
                    if (!ImageIO.write(image, "PNG", path.toFile())) return null;
                    width = image.getWidth();
                    height = image.getHeight();
                }
            } else {
                // This is a static asset or a PNG animation strip without metadata.
                if ("skin".equals(type)) {
                    resolution = SkinResolution.fromDimensions(width, height);
                    if (resolution == null) {
                        resolution = SkinResolution.findNearest(width, height);
                        if (resolution == null) {
                            return null;
                        }
                        // Resize the image and overwrite the file so loadTexture works correctly
                        image = HDTextureProcessor.resizeToResolution(image, resolution);
                        if (!ImageIO.write(image, "PNG", path.toFile())) return null;
                        width = image.getWidth();
                        height = image.getHeight();
                    }
                    skinModel = SkinModelDetector.detectSkinModel(image);
                } else { // Cape logic for static capes or PNG strips
                    int frameHeight = width / 2;
                    if (width > 0 && frameHeight > 0 && height % frameHeight == 0) {
                        frameCount = height / frameHeight;
                        if (frameCount < 1 || frameCount > MAX_ANIMATION_FRAMES) return null;
                        isAnimated = frameCount > 1;
                        resolution = SkinResolution.fromDimensions(width, frameHeight);
                        if (resolution == null) {
                            resolution = SkinResolution.findNearest(width, frameHeight);
                            if (resolution == null) {
                                return null;
                            }
                            // Resize the cape to valid dimensions and overwrite the file
                            if (isAnimated) {
                                image = HDTextureProcessor.resizeAnimationStrip(image, resolution.getWidth());
                            } else {
                                image = HDTextureProcessor.resizeToResolution(image, resolution);
                            }
                            if (!ImageIO.write(image, "PNG", path.toFile())) return null;
                            width = image.getWidth();
                            height = image.getHeight();
                        }
                    } else {
                        return null;
                    }
                }
            }

            byte[] finalBytes = BoundedFileReader.readBytes(path, MAX_ASSET_BYTES);
            String finalRawLegacyHash = HashUtil.computeHash(finalBytes);
            String finalRoleLegacyHash = HashUtil.computeAssetHash(finalBytes, type);
            hash = HashUtil.computeAssetContentId(finalBytes, type);
            ContentId parsedStrong = ContentId.parse(hash);
            if (parsedStrong == null || parsedStrong.algorithm() != ContentId.Algorithm.SHA256
                    || !NetworkSecurity.isValidLegacyContentId(finalRawLegacyHash)
                    || !NetworkSecurity.isValidLegacyContentId(finalRoleLegacyHash)) return null;
            scanned.registerAliases(hash,
                    rawLegacyHash, roleLegacyHash, finalRawLegacyHash, finalRoleLegacyHash);
            if (animMeta != null) {
                writeAnimationMetadataFile(hash, animMeta);
                if (!initialStrongHash.equals(hash)) {
                    migrateVerifiedCacheFile(
                            cacheDirectory,
                            initialStrongHash,
                            hash,
                            ".json",
                            com.quickskin.mod.networking.TextureTransferLimits.MAX_JSON_BYTES,
                            LocalAssetManager::isValidAnimationMetadataBytes);
                }
            }

            // Get friendly name (filename without extension)
            String friendlyName = path.getFileName().toString();
            int dotIndex = friendlyName.lastIndexOf('.');
            if (dotIndex > 0) {
                friendlyName = friendlyName.substring(0, dotIndex);
            }

            // Get file size and modification time
            long fileSize = finalBytes.length;
            long lastModifiedTime = Files.getLastModifiedTime(path).toMillis();

            // Create metadata
            if ("skin".equals(type)) {
                return AssetMetadata.forSkin(hash, friendlyName, path, resolution, fileSize, skinModel, lastModifiedTime);
            } else {
                if (isAnimated) {
                    return AssetMetadata.forAnimatedCape(hash, friendlyName, path, resolution, fileSize, frameCount, lastModifiedTime);
                } else {
                    return AssetMetadata.forCape(hash, friendlyName, path, resolution, fileSize, lastModifiedTime);
                }
            }

        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Process GIF asset (animated cape) and create metadata
     * Loads GIF frames directly using STB Image
     */
    private AssetMetadata processGifAsset(Path path, CatalogBuilder scanned) {
        com.quickskin.mod.common.util.StbGifLoader.GifLoadResult result = null;
        try {
            byte[] sourceBytes = BoundedFileReader.readBytes(path, MAX_ASSET_BYTES);
//? if <1.21 {
            // Load GIF using STB Image
            try (var inputStream = new ByteArrayInputStream(sourceBytes)) {
                result = com.quickskin.mod.common.util.StbGifLoader.loadGif(inputStream);
            }

            // Compute hash of original GIF
//?} else {
//?}
            String rawLegacyHash = HashUtil.computeHash(sourceBytes);
            String roleLegacyHash = HashUtil.computeAssetHash(sourceBytes, "cape");
            String hash = HashUtil.computeAssetContentId(sourceBytes, "cape");
            if (hash == null || rawLegacyHash == null || roleLegacyHash == null) {
                return null;
            }
            scanned.registerAliases(hash, rawLegacyHash, roleLegacyHash);
//? if <1.21 {
//?} else {

            // F7: cache-hit fast path. Skip STB decode if cached atlas + metadata
            // exist and are newer than the source GIF.
            Path cachedAtlasFast = NetworkSecurity.resolveContained(
                    cacheDirectory.resolve("animated_capes"), hash, ".png");
            AnimationMetadata cachedMeta = readAnimationMetadataFile(hash);
            if (cachedAtlasFast != null && !Files.isSymbolicLink(cachedAtlasFast)
                    && Files.exists(cachedAtlasFast) && cachedMeta != null) {
                try {
                    long srcMtime = Files.getLastModifiedTime(path).toMillis();
                    long atlasMtime = Files.getLastModifiedTime(cachedAtlasFast).toMillis();
                    Path cachedMetaFast = NetworkSecurity.resolveContained(cacheDirectory, hash, ".json");
                    long metaMtime = Files.getLastModifiedTime(cachedMetaFast).toMillis();
                    if (atlasMtime >= srcMtime && metaMtime >= srcMtime) {
                        BufferedImage atlasImg = SafeImageReader.readPng(cachedAtlasFast);
                        if (atlasImg != null && cachedMeta.frameCount() > 0
                                && cachedMeta.frameCount() <= MAX_ANIMATION_FRAMES
                                && atlasImg.getHeight() % cachedMeta.frameCount() == 0) {
                            int cw = atlasImg.getWidth();
                            int ch = atlasImg.getHeight() / cachedMeta.frameCount();
                            SkinResolution res = SkinResolution.fromDimensions(cw, ch);
                            if (res == null) res = SkinResolution.STANDARD;
                            String fn = path.getFileName().toString();
                            int di = fn.lastIndexOf('.');
                            if (di > 0) fn = fn.substring(0, di);
                            return AssetMetadata.forAnimatedCape(
                                hash, fn, path, res,
                                sourceBytes.length, cachedMeta.frameCount(), srcMtime);
                        }
                    }
                } catch (IOException ignored) {
                    // Fall through to full decode on any cache read failure.
                }
            }

            // Load GIF using STB Image
            try (var inputStream = new ByteArrayInputStream(sourceBytes)) {
                result = com.quickskin.mod.common.util.StbGifLoader.loadGif(inputStream);
            }
//?}

            // Create PNG atlas from frames (stack vertically)
            int width = result.frameWidth();
            int height = result.frameHeight();
            int frameCount = result.frames().length;
            int atlasHeight = height * frameCount;

            NativeImage atlas = new NativeImage(width, atlasHeight, false);
            Path atlasPath;
            try {
                // Copy each frame into the atlas
                for (int i = 0; i < frameCount; i++) {
                    NativeImage frame = result.frames()[i];
                    for (int y = 0; y < height; y++) {
                        for (int x = 0; x < width; x++) {
//? if <1.21 {
                            MinecraftCompat.INSTANCE.setPixel(
                                    atlas, x, i * height + y, MinecraftCompat.INSTANCE.getPixel(frame, x, y));
//?} else if <1.21.11 {
                            PlatformHelper.setPixel(atlas, x, i * height + y, PlatformHelper.getPixel(frame, x, y));
//?} else {
                            MinecraftCompat.INSTANCE.setPixel(atlas, x, i * height + y, MinecraftCompat.INSTANCE.getPixel(frame, x, y));
//?}
                        }
                    }
                }

                // Save PNG atlas to cache
                Path cacheDir = cacheDirectory.resolve("animated_capes");
                Files.createDirectories(cacheDir);
                atlasPath = NetworkSecurity.resolveContained(cacheDir, hash, ".png");
                if (atlasPath == null || Files.isSymbolicLink(atlasPath)) return null;
                atlas.writeToFile(atlasPath);
            } finally {
                atlas.close();
            }
//? if <1.21 {
//?} else {

            // Composite vanilla elytra on cache atlas if elytra area is transparent
            compositeElytraOnAtlasIfNeeded(atlasPath, frameCount);
//?}

            // Save animation metadata to cache
            writeAnimationMetadataFile(hash, result.metadata());

            // Get friendly name
            String friendlyName = path.getFileName().toString();
            int dotIndex = friendlyName.lastIndexOf('.');
            if (dotIndex > 0) {
                friendlyName = friendlyName.substring(0, dotIndex);
            }

            // Get file size and modification time
            long fileSize = sourceBytes.length;
            long lastModifiedTime = Files.getLastModifiedTime(path).toMillis();

            // Get resolution from first frame
            SkinResolution resolution = SkinResolution.fromDimensions(width, height);
            if (resolution == null) {
//? if <1.21 {
                resolution = SkinResolution.findNearest(width, height);
                if (resolution == null) {
                    return null;
                }
                // Resize the cached atlas frames to match valid cape dimensions
                BufferedImage atlasImage = SafeImageReader.readPng(atlasPath);
                if (atlasImage != null) {
                    int targetW = resolution.getWidth();
                    int targetH = resolution.getHeight();
                    BufferedImage resizedAtlas = new BufferedImage(
                            targetW, targetH * frameCount, BufferedImage.TYPE_INT_ARGB);
                    java.awt.Graphics2D g = resizedAtlas.createGraphics();
                    g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                            java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                    for (int i = 0; i < frameCount; i++) {
                        g.drawImage(atlasImage,
                                0, i * targetH, targetW, (i + 1) * targetH,
                                0, i * height, width, (i + 1) * height,
                                null);
                    }
                    g.dispose();
                    ImageIO.write(resizedAtlas, "PNG", atlasPath.toFile());
                }
//?} else {
                resolution = SkinResolution.STANDARD;
//?}
            }
//? if <1.21 {

            // Composite vanilla elytra on cache atlas if elytra area is transparent
            compositeElytraOnAtlasIfNeeded(atlasPath, frameCount);
//?} else {
//?}

            // Create metadata for animated cape
            return AssetMetadata.forAnimatedCape(
                    hash,
                    friendlyName,
                    path,
                    resolution,
                    fileSize,
                    frameCount,
                    lastModifiedTime
            );

        } catch (Exception e) {
            return null;
        } finally {
            // Clean up frames
            if (result != null) {
                result.close();
            }
        }
    }

    /**
     * Get animation metadata for a texture hash
     * @param hash The texture hash
     * @return The metadata, or null if not found or not animated
     */
    public AnimationMetadata getAnimationMetadata(String hash) {
        if (!NetworkSecurity.isValidContentId(hash)) return null;
        CatalogSnapshot snapshot = catalog;
        String primary = snapshot.resolve(hash);
        if (primary == null) return null;
        AssetMetadata assetMeta = snapshot.metadata().get(primary);
        if (assetMeta == null || !assetMeta.isAnimated()) {
            return null;
        }
        return readAnimationMetadataFile(primary);
    }

    @Nullable
    private AnimationMetadata readAnimationMetadataFile(String hash) {
        Path metadataPath = NetworkSecurity.resolveContained(cacheDirectory, hash, ".json");
        if (metadataPath == null || Files.isSymbolicLink(metadataPath) || !Files.exists(metadataPath)) return null;
        try {
            String json = BoundedFileReader.readUtf8(
                    metadataPath, com.quickskin.mod.networking.TextureTransferLimits.MAX_JSON_BYTES);
//? if <1.21.11 {
            return NetworkSecurity.isValidAnimationMetadata(json)
                    ? AnimationMetadata.fromJson(json) : null;
//?} else {
            return NetworkSecurity.parseAnimationMetadata(json);
//?}
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private void writeAnimationMetadataFile(String hash, AnimationMetadata metadata) throws IOException {
        String json = metadata.toJson();
        if (!NetworkSecurity.isValidAnimationMetadata(json)) throw new IOException("Invalid animation metadata");
        Path metadataPath = NetworkSecurity.resolveContained(cacheDirectory, hash, ".json");
        if (metadataPath == null || Files.isSymbolicLink(metadataPath)) throw new IOException("Unsafe metadata path");
        writeVerifiedCacheFile(
                cacheDirectory,
                metadataPath,
                json.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                com.quickskin.mod.networking.TextureTransferLimits.MAX_JSON_BYTES,
                LocalAssetManager::isValidAnimationMetadataBytes);
    }

    /** Migrates only aliases that survived the complete scan as globally unambiguous. */
    private void migrateLegacyCacheFiles(CatalogSnapshot snapshot) {
        for (Map.Entry<String, String> alias : snapshot.legacyAliases().entrySet()) {
            AssetMetadata metadata = snapshot.metadata().get(alias.getValue());
            if (metadata == null) continue;
            if (metadata.isCape()) {
                migrateVerifiedCacheFile(
                        cacheDirectory,
                        alias.getKey(),
                        alias.getValue(),
                        ".json",
                        com.quickskin.mod.networking.TextureTransferLimits.MAX_JSON_BYTES,
                        LocalAssetManager::isValidAnimationMetadataBytes);
                migrateVerifiedCacheFile(
                        cacheDirectory.resolve("animated_capes"),
                        alias.getKey(),
                        alias.getValue(),
                        ".png",
                        MAX_ASSET_BYTES,
                        LocalAssetManager::isValidPngBytes);
            } else if (metadata.isCpmModel()) {
                migrateVerifiedCacheFile(
                        cacheDirectory.resolve("cpm_icons"),
                        alias.getKey(),
                        alias.getValue(),
                        ".png",
                        MAX_ASSET_BYTES,
                        LocalAssetManager::isValidPngBytes);
            }
        }
    }

    private static Map<String, String> aliasesForType(
            CatalogSnapshot snapshot, String assetType) {
        Map<String, String> result = new HashMap<>();
        for (Map.Entry<String, String> alias : snapshot.legacyAliases().entrySet()) {
            AssetMetadata metadata = snapshot.metadata().get(alias.getValue());
            if (metadata != null && assetType.equals(metadata.type())) {
                result.put(alias.getKey(), alias.getValue());
            }
        }
        return Map.copyOf(result);
    }

    private void migrateVerifiedCacheFile(
            Path root,
            String legacyId,
            String strongId,
            String suffix,
            int maximumBytes,
            java.util.function.Predicate<byte[]> validator
    ) {
        Path legacyPath = NetworkSecurity.resolveContained(root, legacyId, suffix);
        Path strongPath = NetworkSecurity.resolveContained(root, strongId, suffix);
        if (legacyPath == null || strongPath == null || legacyPath.equals(strongPath)
                || Files.isSymbolicLink(legacyPath) || !Files.isRegularFile(legacyPath)) {
            return;
        }
        try {
            byte[] legacyBytes = BoundedFileReader.readBytes(legacyPath, maximumBytes);
            if (!validator.test(legacyBytes)) return;

            if (Files.exists(strongPath)) {
                if (Files.isSymbolicLink(strongPath) || !Files.isRegularFile(strongPath)) return;
                byte[] strongBytes = BoundedFileReader.readBytes(strongPath, maximumBytes);
                if (!validator.test(strongBytes) || !Arrays.equals(legacyBytes, strongBytes)) {
                    // Both files may carry user-relevant state. A mismatch is not evidence that
                    // either side is disposable, so retain the legacy file for manual recovery.
                    return;
                }
            } else {
                writeVerifiedCacheFile(
                        root, strongPath, legacyBytes, maximumBytes, validator);
                byte[] committed = BoundedFileReader.readBytes(strongPath, maximumBytes);
                if (!validator.test(committed) || !Arrays.equals(legacyBytes, committed)) return;
            }

            // The strong destination is now an independently verified byte-for-byte copy.
            Files.deleteIfExists(legacyPath);
        } catch (IOException | RuntimeException error) {
            QuickSkin.LOGGER.debug(
                    "Retaining legacy local cache entry {} after migration failure",
                    legacyPath, error);
        }
    }

    private void writeVerifiedPngCache(Path root, String contentId, byte[] pngBytes)
            throws IOException {
        Path target = NetworkSecurity.resolveContained(root, contentId, ".png");
        if (target == null) throw new IOException("Unsafe PNG cache path");
        writeVerifiedCacheFile(
                root, target, pngBytes, MAX_ASSET_BYTES, LocalAssetManager::isValidPngBytes);
    }

    private void writeVerifiedCacheFile(
            Path root,
            Path target,
            byte[] bytes,
            int maximumBytes,
            java.util.function.Predicate<byte[]> validator
    ) throws IOException {
        if (bytes == null || bytes.length > maximumBytes || !validator.test(bytes)) {
            throw new IOException("Invalid local cache content");
        }
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedTarget = target.toAbsolutePath().normalize();
        if (!normalizedTarget.startsWith(normalizedRoot) || Files.isSymbolicLink(normalizedRoot)
                || Files.isSymbolicLink(normalizedTarget)) {
            throw new IOException("Unsafe local cache destination");
        }
        Files.createDirectories(normalizedRoot);
        Path temporary = Files.createTempFile(normalizedRoot, ".quickskin-cache-", ".tmp");
        try {
            Files.write(temporary, bytes);
            byte[] verified = BoundedFileReader.readBytes(temporary, maximumBytes);
            if (!validator.test(verified) || !Arrays.equals(bytes, verified)) {
                throw new IOException("Local cache temp-file verification failed");
            }
            atomicReplaceCache(temporary, normalizedTarget);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static boolean isValidPngBytes(byte[] bytes) {
        if (bytes == null) return false;
        try {
            return SafeImageReader.readPng(bytes) != null;
        } catch (IOException ignored) {
            return false;
        }
    }

    private static boolean isValidAnimationMetadataBytes(byte[] bytes) {
        if (bytes == null) return false;
        String json = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        return NetworkSecurity.isValidAnimationMetadata(json);
    }

    private static void atomicReplaceCache(Path source, Path target) throws IOException {
        try {
            Files.move(source, target,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Get all assets of a specific type
     */
    public List<AssetMetadata> getAssetsByType(String type) {
        CatalogSnapshot snapshot = catalog;
        String playerOwnSkinHash = snapshot.resolve(ClientConfig.getInstance().playerOwnSkinHash);
        SkinSortMode sortMode = ClientConfig.getInstance().getSkinSortMode();

        return snapshot.metadata().values().stream()
                .filter(meta -> type.equals(meta.type()))
                .sorted(getSortComparator(sortMode, playerOwnSkinHash))
                .toList();
    }

    /**
     * Get comparator for sorting assets based on sort mode
     */
    private Comparator<AssetMetadata> getSortComparator(SkinSortMode mode, String playerSkinHash) {
        return switch (mode) {
            case LATEST_LAST -> Comparator
                    .comparing((AssetMetadata meta) -> !meta.hash().equals(playerSkinHash))
                    .thenComparing(AssetMetadata::friendlyName);

            case LATEST_FIRST -> Comparator
                    .comparing((AssetMetadata meta) -> !meta.hash().equals(playerSkinHash))
                    .thenComparing(Comparator.comparing(AssetMetadata::lastModifiedTime).reversed());

            case ALPHABETICAL -> Comparator
                    .comparing((AssetMetadata meta) -> !meta.hash().equals(playerSkinHash))
                    .thenComparing(AssetMetadata::friendlyName);
        };
    }

    /** Get all selectable skin entries, including optional CPM models. */
    public List<AssetMetadata> getAllSkins() {
        CatalogSnapshot snapshot = catalog;
        String playerOwnSkinHash = snapshot.resolve(ClientConfig.getInstance().playerOwnSkinHash);
        SkinSortMode sortMode = ClientConfig.getInstance().getSkinSortMode();
        return snapshot.metadata().values().stream()
//? if <1.21.11 {
                .filter(meta -> "skin".equals(meta.type()) || "cpmmodel".equals(meta.type()))
//?} else {
                .filter(metadata -> metadata.isSkin() || metadata.isCpmModel())
//?}
                .sorted(getSortComparator(sortMode, playerOwnSkinHash))
                .toList();
    }

    /**
     * Get metadata by hash
     */
    public AssetMetadata getMetadata(String hash) {
        if (!NetworkSecurity.isValidContentId(hash)) return null;
        CatalogSnapshot snapshot = catalog;
        String primary = snapshot.resolve(hash);
        return primary == null ? null : snapshot.metadata().get(primary);
    }

    /**
     * Get source file path by hash
     */
    public Path getSourcePath(String hash) {
        if (!NetworkSecurity.isValidContentId(hash)) return null;
        CatalogSnapshot snapshot = catalog;
        String primary = snapshot.resolve(hash);
        return primary == null ? null : snapshot.sourcePaths().get(primary);
    }

    /**
     * Load texture data for a specific quality level
     * Returns raw PNG bytes
     */
    public byte[] loadTexture(String hash, TextureQuality quality) {
        if (!NetworkSecurity.isValidContentId(hash) || quality == null) return null;
        CatalogSnapshot snapshot = catalog;
        String primary = snapshot.resolve(hash);
        if (primary == null) return null;
        Path sourcePath = snapshot.sourcePaths().get(primary);
        if (sourcePath == null || !Files.exists(sourcePath)) {
            return null;
        }

//? if <1.21 {
        // For GIF source files, use the cached PNG atlas (ImageIO only reads first GIF frame)
        Path readPath = sourcePath;
        if (sourcePath.toString().toLowerCase(Locale.ROOT).endsWith(".gif")) {
            Path cachedAtlas = NetworkSecurity.resolveContained(
                    cacheDirectory.resolve("animated_capes"), primary, ".png");
            if (cachedAtlas != null && !Files.isSymbolicLink(cachedAtlas) && Files.exists(cachedAtlas)) {
                readPath = cachedAtlas;
            }
        }

//?} else {
//?}
        try {
//? if <1.21 {
            byte[] sourceBytes = BoundedFileReader.readBytes(readPath, MAX_ASSET_BYTES);
            BufferedImage image = SafeImageReader.readPng(sourceBytes);
            if (image == null) {
                return null;
            }

            // Check if this is a skin and transparency should be disabled
//?} else if <1.21.11 {
            byte[] sourceBytes = BoundedFileReader.readBytes(sourcePath, MAX_ASSET_BYTES);
            BufferedImage image = SafeImageReader.readPng(sourceBytes);
            if (image == null) {
                return null;
            }

            // Check if this is a skin and transparency should be disabled
//?} else {
            byte[] sourceBytes = BoundedFileReader.readBytes(sourcePath, MAX_ASSET_BYTES);
//?}
            AssetMetadata metadata = snapshot.metadata().get(primary);
            boolean isSkin = metadata != null && "skin".equals(metadata.type());
            boolean isCape = metadata != null && metadata.isCape();
            boolean shouldRemoveTransparency = isSkin &&
                    com.quickskin.mod.config.ClientConfig.getInstance().shouldDisableSkinTransparency();
//? if <1.21.11 {
//?} else {

            // Canonical full-quality bytes need no decode/re-encode when presentation policy is off.
            if (quality == TextureQuality.FULL && !shouldRemoveTransparency && !isCape
                    && primary.equals(HashUtil.computeAssetContentId(
                            sourceBytes, metadata != null ? metadata.type() : null))) {
                return sourceBytes;
            }

            BufferedImage image = SafeImageReader.readPng(sourceBytes);
            if (image == null) {
                return null;
            }
//?}

            // Apply transparency removal if needed
            if (shouldRemoveTransparency) {
                image = HDTextureProcessor.removeTransparency(image);
            }
            boolean capePresentationChanged = false;
            if (isCape) {
                BufferedImage masked = CapeElytraSilhouette.maskedCopy(
                        image, Math.max(1, metadata.frameCount()));
                capePresentationChanged = masked != image;
                image = masked;
            }

            // Process based on quality
            return switch (quality) {
                case FULL -> {
                    // For FULL quality, we need to convert the image back to bytes
                    // since presentation policy may have modified it.
                    if (shouldRemoveTransparency || capePresentationChanged) {
                        yield HDTextureProcessor.imageToPng(image);
                    } else {
                        yield sourceBytes;
                    }
                }
                case PREVIEW -> HDTextureProcessor.createPreview(image);
                case THUMBNAIL -> HDTextureProcessor.createThumbnail(image);
                case NORMALIZED -> HDTextureProcessor.normalizeForVanilla(image);
            };

        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Loads immutable imported bytes for network transfer. Server presentation policy must never
     * destructively alter the content-addressed source that other clients receive.
     */
    public byte @Nullable [] loadCanonicalTexture(String hash, String textureType) {
        if (!NetworkSecurity.isValidContentId(hash)
                || !NetworkSecurity.isValidTextureType(textureType)) return null;
        CatalogSnapshot snapshot = catalog;
        String primary = snapshot.resolve(hash);
        if (primary == null) return null;
        AssetMetadata metadata = snapshot.metadata().get(primary);
        if (metadata == null || !textureType.equals(metadata.type())) return null;
        Path sourcePath = snapshot.sourcePaths().get(primary);
        if (sourcePath == null) return null;
//? if <1.21.11 {
        Path readPath = sourcePath;
        if (metadata.isAnimated()
                && sourcePath.toString().toLowerCase(Locale.ROOT).endsWith(".gif")) {
            readPath = NetworkSecurity.resolveContained(
                    cacheDirectory.resolve("animated_capes"), primary, ".png");
        }
        if (readPath == null || Files.isSymbolicLink(readPath)) return null;
//?} else {
//?}
        try {
//? if <1.21.11 {
            byte[] sourceBytes = BoundedFileReader.readBytes(readPath, MAX_ASSET_BYTES);
//?} else {
            byte[] sourceBytes = BoundedFileReader.readBytes(sourcePath, MAX_ASSET_BYTES);
//?}
            if ((!metadata.isAnimated() && !primary.equals(
                    HashUtil.computeAssetContentId(sourceBytes, textureType)))
                    || NetworkSecurity.getTexturePixelCount(sourceBytes, textureType) < 1) return null;
            // One full bounded decode is the authoritative on-disk tamper/integrity check.
            SafeImageReader.readPng(sourceBytes);
            if ("cape".equals(textureType) && metadata.isAnimated()) {
                AnimationMetadata animation = getAnimationMetadata(primary);
                if (animation == null) return null;
                sourceBytes = com.quickskin.mod.common.util.PngAnimationIdentity
                        .attach(sourceBytes, animation.toJson());
            }
            return sourceBytes;
        } catch (IOException | RuntimeException error) {
            QuickSkin.LOGGER.warn("Unable to load canonical {} texture {}", textureType, primary, error);
            return null;
        }
    }

//? if <1.21.11 {
//?} else {
    private Path getCpmIconPath(String hash) {
        return NetworkSecurity.resolveContained(cacheDirectory.resolve("cpm_icons"), hash, ".png");
    }

//?}
    private byte[] loadCpmModelIcon(String hash) {
//? if <1.21.11 {
        Path iconPath = NetworkSecurity.resolveContained(
                cacheDirectory.resolve("cpm_icons"), hash, ".png");
        if (iconPath != null && !Files.isSymbolicLink(iconPath) && Files.exists(iconPath)) {
            try {
                return BoundedFileReader.readBytes(iconPath, MAX_ASSET_BYTES);
            } catch (IOException e) {
                return null;
            }
//?} else {
        Path iconPath = getCpmIconPath(hash);
        if (iconPath == null || Files.isSymbolicLink(iconPath) || !Files.exists(iconPath)) {
            return null;
//?}
        }
//? if <1.21.11 {
        return null;
//?} else {
        try {
            return BoundedFileReader.readBytes(iconPath, MAX_ASSET_BYTES);
        } catch (IOException ignored) {
            return null;
        }
//?}
    }

    /**
     * Delete local asset
     */
    public synchronized boolean deleteAsset(String hash) {
        if (!NetworkSecurity.isValidContentId(hash)) return false;
        CatalogSnapshot snapshot = catalog;
        String primary = snapshot.resolve(hash);
        if (primary == null) return false;
        AssetMetadata metadata = snapshot.metadata().get(primary);
        if (metadata == null) {
            return false;
        }

        Path path = snapshot.sourcePaths().get(primary);
        if (path == null || !Files.exists(path)) {
            return false;
        }

        try {
            Files.delete(path);
            if (metadata.isSkin()) {
                com.quickskin.mod.client.compat.CPMCompatIntegration.evictHttpTextureCache(primary);
            }
            if (metadata.isCpmModel()) {
                com.quickskin.mod.client.compat.CpmModelWorkflow.onModelDeleted(metadata);
            }
            catalog = snapshot.without(primary);
            invalidatePendingScans();
//? if <1.21 {
//?} else {
            sourceImageCache.remove(primary);
//?}

//? if <1.21.11 {
            Map<TextureQuality, ResourceLocation> registeredTextures = textureRegistry.remove(primary);
//?} else {
            Map<TextureQuality, Identifier> registeredTextures = textureRegistry.remove(primary);
//?}
            if (registeredTextures != null) {
//? if <1.21.11 {
                for (ResourceLocation location : registeredTextures.values()) {
//?} else {
                for (Identifier location : registeredTextures.values()) {
//?}
                    try {
                        Minecraft.getInstance().getTextureManager().release(location);
                    } catch (RuntimeException ignored) {
                        QuickSkin.LOGGER.debug("Unable to release deleted local texture {}", location, ignored);
                    }
                }
            }
            if (metadata.isCpmModel()) {
                try {
//? if <1.21.11 {
                    Files.deleteIfExists(NetworkSecurity.resolveContained(
                            cacheDirectory.resolve("cpm_icons"), primary, ".png"));
//?} else {
                    Files.deleteIfExists(getCpmIconPath(primary));
//?}
                } catch (IOException ignored) {
                    QuickSkin.LOGGER.warn("Unable to delete CPM icon for {}", primary, ignored);
                }
            }

            // Also remove preferences for this skin
            if (skinPreferences != null) {
                skinPreferences.remove(primary);
                savePreferences();
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Rename a local asset file
     * @param hash The hash of the asset to rename
     * @param newFriendlyName The new friendly name (without extension)
     * @return RenameResult indicating success or failure reason
     */
    public synchronized RenameResult renameLocalAsset(String hash, String newFriendlyName) {
        if (!NetworkSecurity.isValidContentId(hash)) return RenameResult.NOT_FOUND;
        // Validate the new name
        if (newFriendlyName == null || newFriendlyName.trim().isEmpty()) {
            return RenameResult.INVALID_NAME;
        }

        // Check for invalid characters in filename
        String sanitizedName = newFriendlyName.trim();
        if (sanitizedName.matches(".*[<>:\"/\\\\|?*].*")) {
            return RenameResult.INVALID_NAME;
        }

        // Get the metadata for this asset
        CatalogSnapshot snapshot = catalog;
        String primary = snapshot.resolve(hash);
        if (primary == null) return RenameResult.NOT_FOUND;
        AssetMetadata metadata = snapshot.metadata().get(primary);
        if (metadata == null) {
            return RenameResult.NOT_FOUND;
        }

        // Get the current file path
        Path currentPath = snapshot.sourcePaths().get(primary);
        if (currentPath == null || !Files.exists(currentPath)) {
            return RenameResult.NOT_FOUND;
        }

        // Determine the parent directory and file extension
        Path parentDir = currentPath.getParent();
        String extension = currentPath.getFileName().toString();
        int dotIndex = extension.lastIndexOf('.');
        if (dotIndex > 0) {
            extension = extension.substring(dotIndex);
        } else {
            extension = ".png"; // Default to .png if no extension found
        }

        // Create the new file path
        Path newPath = parentDir.resolve(sanitizedName + extension);

        // Check if a file with the new name already exists
        if (Files.exists(newPath) && !newPath.equals(currentPath)) {
            return RenameResult.NAME_TAKEN;
        }

        try {
            // Rename the file
            Files.move(currentPath, newPath, StandardCopyOption.REPLACE_EXISTING);

            // Update the metadata cache with new friendly name and path
            AssetMetadata updatedMetadata;
            if (metadata.isCpmModel()) {
                updatedMetadata = AssetMetadata.forCpmModel(
                        metadata.hash(),
                        sanitizedName,
                        newPath,
                        metadata.fileSize(),
                        metadata.lastModifiedTime()
                );
            } else if ("skin".equals(metadata.type())) {
                updatedMetadata = AssetMetadata.forSkin(
                        metadata.hash(),
                        sanitizedName,
                        newPath,
                        metadata.resolution(),
                        metadata.fileSize(),
                        metadata.skinModel(),
                        metadata.lastModifiedTime()
                );
            } else if (metadata.isAnimated()) {
                updatedMetadata = AssetMetadata.forAnimatedCape(
                        metadata.hash(),
                        sanitizedName,
                        newPath,
                        metadata.resolution(),
                        metadata.fileSize(),
                        metadata.frameCount(),
                        metadata.lastModifiedTime()
                );
            } else {
                updatedMetadata = AssetMetadata.forCape(
                        metadata.hash(),
                        sanitizedName,
                        newPath,
                        metadata.resolution(),
                        metadata.fileSize(),
                        metadata.lastModifiedTime()
                );
            }

            catalog = snapshot.with(primary, updatedMetadata, newPath);
            invalidatePendingScans();
            if (updatedMetadata.isCpmModel()
                    && primary.equals(catalog.resolve(
                            ClientConfig.getInstance().activeCpmModelHash))) {
                com.quickskin.mod.client.compat.CpmModelWorkflow.activateModel(updatedMetadata);
            }

            // A rename moves the file without rescanning, and the file name feeds the folder
            // fingerprint. Keep them in step, or the next menu poll sees a phantom change and
            // pays for a full rescan the user never asked for.
            lastScanFingerprint = LocalAssetFolderWatch.fingerprint(getScannedDirectories());

            return RenameResult.SUCCESS;

        } catch (IOException e) {
            return RenameResult.IO_ERROR;
        }
    }

    /**
     * Clear all caches and rediscover assets
     */
    public synchronized void reload() {
        discoverLocalAssets();
    }

    /**
     * Folders whose contents feed {@link #discoverLocalAssets()}.
     *
     * <p>The returned list is immutable. Asynchronous callers should use
     * {@link #snapshotScanRequest()} so the matching epoch travels back with the fingerprint.
     */
    public synchronized List<Path> getScannedDirectories() {
        return scannedDirectoriesSnapshot();
    }

    /**
     * Captures the immutable directory list and catalog epoch for one asynchronous folder poll.
     */
    public synchronized ScanRequest snapshotScanRequest() {
        boolean initialized = skinsDirectory != null && capesDirectory != null && cacheDirectory != null;
        return new ScanRequest(this, scanEpoch, initialized, scannedDirectoriesSnapshot());
    }

    private List<Path> scannedDirectoriesSnapshot() {
        if (skinsDirectory == null) {
            // Before init() there is nothing to watch, and the optional CPM probe must not run.
            return List.of();
        }

        List<Path> directories = new ArrayList<>(3);
        directories.add(skinsDirectory);
        if (capesDirectory != null) directories.add(capesDirectory);
        if (com.quickskin.mod.client.compat.CPMCompatIntegration.isAvailable()) {
            Path modelsDirectory =
                    com.quickskin.mod.client.compat.CPMCompatIntegration.getCPMModelsDirectory();
            if (modelsDirectory != null) directories.add(modelsDirectory);
        }
        return List.copyOf(directories);
    }

    /**
     * Rebuild the catalog only when the upload folders changed since the last completed scan.
     *
     * <p>Lets a screen poll for files copied in from outside the game without paying for a full
     * rescan every time. Compute {@code observedFingerprint} from {@link ScanRequest#directories()}
     * off-thread, then carry the same request back to this method on the render thread. A request
     * captured before a newer scan, mutation, or lifecycle reset is rejected before it can mutate
     * the catalog. The rebuild itself stays where every other rescan already runs, so a concurrent
     * {@code getTextureLocation} never blocks on a background scan holding this monitor.
     *
     * @return {@code true} when a rescan ran and the caller should refresh its view
     */
    public synchronized boolean refreshIfChanged(
            ScanRequest request,
            long observedFingerprint
    ) {
        if (!isCurrentScanRequest(request)
                || !request.initialized
                || observedFingerprint == lastScanFingerprint) {
            return false;
        }
        discoverLocalAssets();
        return true;
    }

    /** Package-private regression seam for epoch behavior without touching Minecraft state. */
    synchronized boolean isCurrentScanRequest(ScanRequest request) {
        return request != null && request.owner == this && request.epoch == scanEpoch;
    }

    /** Invalidates folder-walk results captured before the current lifecycle/catalog state. */
    synchronized void invalidatePendingScans() {
        scanEpoch++;
    }

    /**
     * Clear texture cache to force re-registration with new settings
     * Call this when transparency settings change
     */
    public synchronized void clearTextureCache() {
        // ClientRuntime invokes this during every session reset. Invalidate folder walks before
        // touching Minecraft state so even a partial cleanup cannot accept an old async result.
        invalidatePendingScans();

        // Unregister all textures from Minecraft's texture manager
        Minecraft mc = Minecraft.getInstance();
//? if <1.21.11 {
        for (Map<TextureQuality, ResourceLocation> qualityMap : textureRegistry.values()) {
            for (ResourceLocation location : qualityMap.values()) {
//?} else {
        for (Map<TextureQuality, Identifier> qualityMap : textureRegistry.values()) {
            for (Identifier location : qualityMap.values()) {
//?}
                try {
                    mc.getTextureManager().release(location);
                } catch (Exception e) {
                    // Failed to release texture
                }
            }
        }

        // Clear our cache
        textureRegistry.clear();

        // Clear Ears features cache
        if (com.quickskin.mod.client.compat.EarsCompatIntegration.isAvailable()) {
            com.quickskin.mod.client.compat.EarsCompatIntegration.clearAllFeatures();
        }
    }

    /**
     * Clear only skin textures from cache (not capes)
     * Call this when skin transparency settings change
     */
    public void clearSkinTextureCache() {

        Minecraft mc = Minecraft.getInstance();
        List<String> hashesToClear = new ArrayList<>();

        // Find all skin hashes
        for (String hash : textureRegistry.keySet()) {
            AssetMetadata metadata = getMetadata(hash);
            if (metadata != null && "skin".equals(metadata.type())) {
                hashesToClear.add(hash);
            }
        }

        // Unregister and remove skin textures only
        for (String hash : hashesToClear) {
//? if <1.21.11 {
            Map<TextureQuality, ResourceLocation> qualityMap = textureRegistry.get(hash);
//?} else {
            Map<TextureQuality, Identifier> qualityMap = textureRegistry.get(hash);
//?}
            if (qualityMap != null) {
//? if <1.21.11 {
                for (ResourceLocation location : qualityMap.values()) {
//?} else {
                for (Identifier location : qualityMap.values()) {
//?}
                    try {
                        mc.getTextureManager().release(location);
                    } catch (Exception e) {
                        // Failed to release texture
                    }
                }
                textureRegistry.remove(hash);
            }
        }
    }

    /**
     * Get Identifier for a texture
     * Registers texture with Minecraft if not already registered
     */
//? if <1.21.11 {
    public synchronized ResourceLocation getTextureLocation(String hash, TextureQuality quality) {
//?} else {
    public synchronized Identifier getTextureLocation(String hash, TextureQuality quality) {
//?}
        if (!NetworkSecurity.isValidContentId(hash) || quality == null) return null;
        String primary = catalog.resolve(hash);
        if (primary == null) return null;
        // Check if already registered
//? if <1.21.11 {
        Map<TextureQuality, ResourceLocation> qualityMap = textureRegistry.get(primary);
//?} else {
        Map<TextureQuality, Identifier> qualityMap = textureRegistry.get(primary);
//?}
        if (qualityMap != null && qualityMap.containsKey(quality)) {
            return qualityMap.get(quality);
        }

//? if <1.21.11 {
        // For cpmmodel entries, load the cached icon PNG
        AssetMetadata meta = getMetadata(primary);
        byte[] textureData;
        if (meta != null && meta.isCpmModel()) {
            textureData = loadCpmModelIcon(primary);
        } else {
            textureData = loadTexture(primary, quality);
        }
//?} else {
        // CPM files are not images; their separately cached embedded icon is.
        AssetMetadata metadata = getMetadata(primary);
        byte[] textureData = metadata != null && metadata.isCpmModel()
                ? loadCpmModelIcon(primary)
                : loadTexture(primary, quality);
//?}
        if (textureData == null) {
            return null;
        }

        NativeImage nativeImage = null;
        DynamicTexture dynamicTexture = null;
//? if <1.21.11 {
        ResourceLocation location = null;
//?} else {
        Identifier location = null;
//?}
        boolean registered = false;
        boolean committed = false;
        try {
            // Load directly as NativeImage from PNG bytes (handles pixel format automatically)
            nativeImage = NativeImage.read(new ByteArrayInputStream(textureData));

            // For animated capes, only register the FIRST FRAME on GPU instead of the full atlas.
            // The animation system keeps the atlas in RAM and handles frame switching separately.
            // This prevents massive VRAM waste and fixes incorrect UV rendering when
            // the animation texture is used as a fallback.
//? if <1.21.11 {
//?} else {
            AssetMetadata meta = metadata;
//?}
            if (meta != null && meta.isAnimated() && meta.frameCount() > 1 && quality == TextureQuality.FULL) {
                int frameHeight = nativeImage.getHeight() / meta.frameCount();
                if (frameHeight > 0 && frameHeight < nativeImage.getHeight()) {
                    NativeImage firstFrame = new NativeImage(nativeImage.getWidth(), frameHeight, false);
                    boolean installed = false;
                    try {
                        for (int y = 0; y < frameHeight; y++) {
                            for (int x = 0; x < nativeImage.getWidth(); x++) {
//? if <1.21 {
                                MinecraftCompat.INSTANCE.setPixel(
                                        firstFrame, x, y, MinecraftCompat.INSTANCE.getPixel(nativeImage, x, y));
//?} else if <1.21.11 {
                                PlatformHelper.setPixel(firstFrame, x, y, PlatformHelper.getPixel(nativeImage, x, y));
//?} else {
                                MinecraftCompat.INSTANCE.setPixel(firstFrame, x, y, MinecraftCompat.INSTANCE.getPixel(nativeImage, x, y));
//?}
                            }
                        }
                        nativeImage.close();
                        nativeImage = firstFrame;
                        installed = true;
                    } finally {
                        if (!installed) firstFrame.close();
                    }
                }
            }

            // Create dynamic texture
//? if <1.21.9 {
            dynamicTexture = new DynamicTexture(nativeImage);
//?} else {
            dynamicTexture = new DynamicTexture(() -> "quickskin_local_" + primary, nativeImage);
//?}

            // Register with texture manager
//? if <1.21 {
            location = new ResourceLocation(
//?} else if <1.21.11 {
            location = ResourceLocation.fromNamespaceAndPath(
//?} else {
            location = Identifier.fromNamespaceAndPath(
//?}
                    QuickSkin.MOD_ID,
                    "local/" + primary + "_" + quality.name().toLowerCase(Locale.ROOT)
            );

            Minecraft.getInstance().getTextureManager().register(location, dynamicTexture);
            registered = true;

            // Cache transparency info for the first-person arm rendering mixin
            // DynamicTextures aren't accessible via resource manager, so we check here
            boolean hasAlpha = false;
            for (int y = 0; y < nativeImage.getHeight() && !hasAlpha; y += Math.max(1, nativeImage.getHeight() / 32)) {
                for (int x = 0; x < nativeImage.getWidth() && !hasAlpha; x += Math.max(1, nativeImage.getWidth() / 32)) {
//? if <1.21 {
                    int pixel = MinecraftCompat.INSTANCE.getPixel(nativeImage, x, y);
//?} else if <1.21.11 {
                    int pixel = PlatformHelper.getPixel(nativeImage, x, y);
//?} else {
                    int pixel = MinecraftCompat.INSTANCE.getPixel(nativeImage, x, y);
//?}
                    int alpha = (pixel >> 24) & 0xFF;
                    if (alpha < 255) hasAlpha = true;
                }
            }
            // Parse Ears features from the original unprocessed image (preserving alpha for Alfalfa data)
//? if <1.21.11 {
            AssetMetadata metadata = getMetadata(primary);
//?} else {
//?}
            if (metadata != null && "skin".equals(metadata.type())
                    && com.quickskin.mod.client.compat.EarsCompatIntegration.isAvailable()) {
                BufferedImage originalImage = getSourceImage(primary);
                if (originalImage != null) {
                    com.quickskin.mod.client.compat.EarsCompatIntegration.parseAndStoreFeatures(location, originalImage);
                }
            }

            // Cache in registry
            com.quickskin.mod.common.util.TextureAlphaDetector.cacheTransparencyResult(location, hasAlpha);
            qualityMap = textureRegistry.computeIfAbsent(primary, k -> new ConcurrentHashMap<>());
            qualityMap.put(quality, location);

            committed = true;
            return location;

        } catch (Exception e) {
            return null;
        } finally {
            if (!committed) {
                if (registered && location != null) {
                    try {
                        Minecraft.getInstance().getTextureManager().release(location);
                    } catch (RuntimeException ignored) {
                        QuickSkin.LOGGER.debug("Unable to release failed local texture {}", location, ignored);
                    }
                } else if (dynamicTexture != null) {
                    try {
                        dynamicTexture.close();
                    } catch (RuntimeException ignored) {
                        QuickSkin.LOGGER.debug("Unable to close failed local texture {}", primary, ignored);
                    }
                } else if (nativeImage != null) {
                    nativeImage.close();
                }
            }
        }
    }

    @Nullable
    public BufferedImage getSourceImage(String hash) {
        if (!NetworkSecurity.isValidContentId(hash)) return null;
        String primary = catalog.resolve(hash);
        if (primary == null) return null;
//? if <1.21 {
        Path sourcePath = getSourcePath(primary);

        // For GIF source files, always use the cached PNG atlas
        // (ImageIO.read on .gif only returns the first frame, not the full strip)
        if (sourcePath != null && sourcePath.toString().toLowerCase(Locale.ROOT).endsWith(".gif")) {
            Path cachedAtlas = NetworkSecurity.resolveContained(
                    cacheDirectory.resolve("animated_capes"), primary, ".png");
            if (cachedAtlas != null && !Files.isSymbolicLink(cachedAtlas) && Files.exists(cachedAtlas)) {
                sourcePath = cachedAtlas;
//?} else {
        // F8: SoftReference cache — GC reclaims under memory pressure.
        SoftReference<BufferedImage> ref = sourceImageCache.get(primary);
        if (ref != null) {
            BufferedImage cached = ref.get();
            if (cached != null) {
                return cached;
//?}
            }
//? if <1.21 {
//?} else {
            sourceImageCache.remove(primary, ref);
//?}
        }
//? if <1.21 {
//?} else {
        Path sourcePath = getSourcePath(primary);
//?}
        if (sourcePath == null) {
            // Also check cache for animated capes converted from GIFs
            Path cachedAtlas = NetworkSecurity.resolveContained(
                    cacheDirectory.resolve("animated_capes"), primary, ".png");
            if (cachedAtlas != null && !Files.isSymbolicLink(cachedAtlas) && Files.exists(cachedAtlas)) {
                sourcePath = cachedAtlas;
            } else {
                return null;
            }
        }
        try {
//? if <1.21 {
            return SafeImageReader.readPng(sourcePath);
//?} else {
            BufferedImage decoded = SafeImageReader.readPng(sourcePath);
            if (decoded != null) {
                sourceImageCache.put(primary, new SoftReference<>(decoded));
            }
            return decoded;
//?}
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Get the skins directory path
     */
    public Path getSkinsDirectory() {
        return skinsDirectory;
    }

    /**
     * Get the capes directory path
     */
    public Path getCapesDirectory() {
        return capesDirectory;
    }

    /**
     * Gets the cache directory for processed assets.
     */
    public Path getCacheDirectory() {
        return cacheDirectory;
    }

    /**
     * Convert BufferedImage to NativeImage for texture registration
     */
    private NativeImage convertToNativeImage(BufferedImage bufferedImage) {
        int width = bufferedImage.getWidth();
        int height = bufferedImage.getHeight();

        NativeImage nativeImage = new NativeImage(width, height, true);

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int argb = bufferedImage.getRGB(x, y);
                // NativeImage expects ABGR format
                int a = (argb >> 24) & 0xFF;
                int r = (argb >> 16) & 0xFF;
                int g = (argb >> 8) & 0xFF;
                int b = argb & 0xFF;
                int abgr = (a << 24) | (b << 16) | (g << 8) | r;
//? if <1.21 {
                MinecraftCompat.INSTANCE.setPixel(nativeImage, x, y, abgr);
//?} else if <1.21.11 {
                PlatformHelper.setPixel(nativeImage, x, y, abgr);
//?} else {
                MinecraftCompat.INSTANCE.setPixel(nativeImage, x, y, abgr);
//?}
            }
        }

        return nativeImage;
    }

    /**
     * Get model type preference for a skin
     * @param hash Skin hash
     * @return Model type preference ("auto", "classic", or "slim")
     */
    public String getSkinModelPreference(String hash) {
        if (!NetworkSecurity.isValidContentId(hash)) return "auto";
        String primary = catalog.resolve(hash);
        if (primary == null) return "auto";
        if (skinPreferences == null) {
            return "auto";
        }
        return skinPreferences.getModelType(primary);
    }

    /**
     * Set model type preference for a skin
     * @param hash Skin hash
     * @param modelType Model type ("auto", "classic", or "slim")
     */
    public void setSkinModelPreference(String hash, String modelType) {
        if (!NetworkSecurity.isValidContentId(hash)) return;
        String primary = catalog.resolve(hash);
        if (primary == null) return;
        if (skinPreferences != null) {
            skinPreferences.setModelType(primary, modelType);
            savePreferences();
        }
    }

    /**
     * Save skin preferences to disk
     */
    private void savePreferences() {
        if (skinPreferences != null && preferencesFile != null) {
            skinPreferences.save(preferencesFile);
        }
    }

    /**
     * Apply the shared Elytra import policy to a cached GIF atlas while keeping the source GIF
     * untouched: composite the vanilla fallback where required, then restore the tapered cutout.
     */
    private void compositeElytraOnAtlasIfNeeded(Path atlasPath, int frameCount) {
        try {
            BufferedImage atlas = SafeImageReader.readPng(atlasPath);
            if (atlas == null) return;

            int capeW = atlas.getWidth();
            int frameH = (frameCount > 0) ? atlas.getHeight() / frameCount : atlas.getHeight();
            if (frameCount < 1 || frameH * frameCount != atlas.getHeight()) return;

            boolean needsFallback = false;
            for (int frame = 0; frame < frameCount; frame++) {
                needsFallback |= CapeElytraSilhouette.isElytraAreaTransparent(
                        atlas.getSubimage(0, frame * frameH, capeW, frameH));
            }

            BufferedImage composited = atlas;
            if (needsFallback) {

                // Load vanilla elytra texture
                var resourceOpt = Minecraft.getInstance().getResourceManager()
//? if <1.21 {
                        .getResource(new ResourceLocation("minecraft", "textures/entity/elytra.png"));
//?} else if <1.21.11 {
                        .getResource(ResourceLocation.fromNamespaceAndPath("minecraft", "textures/entity/elytra.png"));
//?} else {
                        .getResource(Identifier.fromNamespaceAndPath("minecraft", "textures/entity/elytra.png"));
//?}
                if (resourceOpt.isEmpty()) return;
                BufferedImage elytra;
                try (var stream = resourceOpt.get().open()) {
//? if <1.21.11 {
                    elytra = SafeImageReader.readPng(stream);
//?} else {
                    byte[] encoded = com.quickskin.mod.common.util.BoundedFileReader.readBytes(
                            stream,
                            (int) com.quickskin.mod.common.util.SafeImageReader.MAX_ENCODED_BYTES);
                    elytra = com.quickskin.mod.common.util.SafeImageReader.readPng(encoded);
//?}
                }
                if (elytra == null) return;

                composited = new BufferedImage(
                        capeW, atlas.getHeight(), BufferedImage.TYPE_INT_ARGB);
                java.awt.Graphics2D g = composited.createGraphics();
                g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                        java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                for (int i = 0; i < frameCount; i++) {
                    int yOff = i * frameH;
                    BufferedImage frame = atlas.getSubimage(0, yOff, capeW, frameH);
                    if (CapeElytraSilhouette.isElytraAreaTransparent(frame)) {
                        g.drawImage(elytra, 0, yOff, capeW, yOff + frameH,
                                0, 0, elytra.getWidth(), elytra.getHeight(), null);
                    }
                    g.drawImage(frame, 0, yOff, null);
                }
                g.dispose();
            }

            BufferedImage masked = CapeElytraSilhouette.maskedCopy(composited, frameCount);
            if (composited != atlas || masked != atlas) {
                ImageIO.write(masked, "PNG", atlasPath.toFile());
            }
        } catch (Exception e) {
            // Non-critical — elytra just won't have the vanilla fallback
        }
    }
}
