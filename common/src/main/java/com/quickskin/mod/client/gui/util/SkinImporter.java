package com.quickskin.mod.client.gui.util;

import com.quickskin.mod.platform.QuickSkinInfo;
import com.quickskin.mod.client.services.LocalAssetManager;
import com.quickskin.mod.common.data.AssetMetadata;
import com.quickskin.mod.common.data.SkinResolution;
import com.quickskin.mod.common.util.HashUtil;
import com.quickskin.mod.common.util.HDTextureProcessor;
import com.quickskin.mod.common.util.SafeImageReader;
import com.quickskin.mod.common.util.BoundedFileReader;
import com.quickskin.mod.config.ClientConfig;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Utility for importing skin files into the QuickSkin directory
 */
@Environment(EnvType.CLIENT)
public class SkinImporter {
    private static final int MAX_CPM_MODEL_BYTES = 16 * 1024 * 1024;
    private static final int MAX_BATCH_FILES = 64;
    private static final int MAX_NAME_LENGTH = 128;

    /**
     * Import a skin file
     * @param sourcePath Source file path
     * @return The imported asset metadata, or null on failure
     */
    public static AssetMetadata importSkin(Path sourcePath) {
        if (sourcePath == null || sourcePath.getFileName() == null
                || !Files.isRegularFile(sourcePath)) {
            return null;
        }

        // Validate it's a supported image file (PNG, WebP, or JPG)
        String fileName = sourcePath.getFileName().toString();
        String lowerName = fileName.toLowerCase(Locale.ROOT);
        if (!lowerName.endsWith(".png") && !lowerName.endsWith(".webp")
                && !lowerName.endsWith(".jpg")) {
            return null;
        }

        try {
            // Process the skin using the new HDTextureProcessor
            // Allow transparency unless disabled in config (client or server)
            boolean allowTransparency = !ClientConfig.getInstance().shouldDisableSkinTransparency();
            BufferedImage sourceImage = SafeImageReader.readSkin(sourcePath);
            byte[] processedImageBytes = HDTextureProcessor.processHDSkin(
                    sourceImage, allowTransparency);

            if (processedImageBytes == null) {
                return null;
            }

            // Copy file to skins directory (always save as PNG since content is converted to PNG)
            LocalAssetManager assetManager = LocalAssetManager.getInstance();
            String nameWithoutExt = sanitizeBaseName(
                    fileName.substring(0, fileName.lastIndexOf('.')), "skin");
            writeUnique(assetManager.getSkinsDirectory(), nameWithoutExt, ".png", processedImageBytes);

            // Reload assets to pick up the new file
            assetManager.reload();

            // Get the metadata for the imported file
            String hash = HashUtil.computeAssetContentId(processedImageBytes, "skin");
            if (hash != null) {
                return assetManager.getMetadata(hash);
            }

        } catch (IOException | RuntimeException e) {
            QuickSkinInfo.LOGGER.warn("Unable to import skin {}", sourcePath, e);
        }
        return null;
    }
    public static AssetMetadata importCpmModel(Path sourcePath) {
        if (sourcePath == null || sourcePath.getFileName() == null
                || !Files.isRegularFile(sourcePath)) {
            return null;
        }
        if (!com.quickskin.mod.client.compat.CPMCompatIntegration.isAvailable()) {
            return null;
        }
        String fileName = sourcePath.getFileName().toString();
        if (!fileName.toLowerCase(Locale.ROOT).endsWith(".cpmmodel")) {
            return null;
        }
        try {
            byte[] modelBytes = BoundedFileReader.readBytes(sourcePath, MAX_CPM_MODEL_BYTES);
            Path modelsDir = com.quickskin.mod.client.compat.CPMCompatIntegration.getCPMModelsDirectory();
            Files.createDirectories(modelsDir);
            String nameWithoutExt = sanitizeBaseName(
                    fileName.substring(0, fileName.length() - 9), "model");
            writeUnique(modelsDir, nameWithoutExt, ".cpmmodel", modelBytes);
            LocalAssetManager assetManager = LocalAssetManager.getInstance();
            assetManager.reload();
            String hash = HashUtil.computeContentId(modelBytes);
            if (hash != null) {
                return assetManager.getMetadata(hash);
            }
        } catch (IOException | RuntimeException e) {
            QuickSkinInfo.LOGGER.warn("Unable to import CPM model {}", sourcePath, e);
        }

        return null;
    }

    /**
     * Import multiple skin files
     * @param sourcePaths Array of source file paths
     * @return List of successfully imported assets
     */
    public static List<AssetMetadata> importSkins(Path[] sourcePaths) {
        List<AssetMetadata> imported = new ArrayList<>();

        if (sourcePaths == null) return imported;
        int fileCount = Math.min(sourcePaths.length, MAX_BATCH_FILES);
        for (int index = 0; index < fileCount; index++) {
            Path path = sourcePaths[index];
            AssetMetadata metadata = importSkin(path);
            if (metadata != null) {
                imported.add(metadata);
            }
        }

        return imported;
    }

    /**
     * Save a BufferedImage as a skin file
     * @param image The image to save
     * @param username The username (used for filename)
     * @return The path to the saved file, or null on failure
     */
    public static Path saveSkinImage(BufferedImage image, String username) {
        if (image == null || username == null) {
            return null;
        }

        try {
            // Check dimensions, resize to nearest valid if needed
            int width = image.getWidth();
            int height = image.getHeight();
            SkinResolution resolution = SkinResolution.fromDimensions(width, height);
            if (resolution == null) {
                resolution = SkinResolution.findNearest(width, height);
                if (resolution == null) {
                    return null;
                }
                image = HDTextureProcessor.resizeToResolution(image, resolution);
            }

            // Convert legacy 64x32 skins to modern 64x64 format
            if (resolution == SkinResolution.LEGACY) {
                image = HDTextureProcessor.convertLegacyToModern(image);
            }

            // Apply transparency settings if needed
            boolean disableTransparency = ClientConfig.getInstance().shouldDisableSkinTransparency();
            if (disableTransparency) {
                image = HDTextureProcessor.removeTransparency(image);
            }

            // Create filename from username
            LocalAssetManager assetManager = LocalAssetManager.getInstance();
            byte[] encoded = HDTextureProcessor.imageToPng(image);
            if (encoded == null) return null;
            return writeUnique(assetManager.getSkinsDirectory(),
                    sanitizeBaseName(username, "skin"), ".png", encoded);

        } catch (IOException | RuntimeException e) {
            QuickSkinInfo.LOGGER.warn("Unable to save downloaded skin for {}", username, e);
            return null;
        }
    }

    private static Path writeUnique(
            Path directory, String baseName, String extension, byte[] content) throws IOException {
        if (directory == null || content == null || content.length == 0
                || content.length > SafeImageReader.MAX_ENCODED_BYTES) {
            throw new IOException("Skin destination or content is invalid");
        }
        Files.createDirectories(directory);
        Path root = directory.toAbsolutePath().normalize();
        Path temporary = Files.createTempFile(root, ".quickskin-skin-", ".tmp");
        try {
            Files.write(temporary, content);
            if (".png".equals(extension)) SafeImageReader.readPng(temporary);
            for (int counter = 0; counter < 10_000; counter++) {
                String suffix = counter == 0 ? "" : "_" + counter;
                Path target = root.resolve(baseName + suffix + extension).normalize();
                if (!target.startsWith(root)) throw new IOException("Skin destination escaped its directory");
                try {
                    Files.createFile(target);
                } catch (FileAlreadyExistsException ignored) {
                    continue;
                }
                boolean committed = false;
                try {
                    atomicReplace(temporary, target);
                    committed = true;
                    return target;
                } finally {
                    if (!committed) Files.deleteIfExists(target);
                }
            }
            throw new IOException("Could not allocate a unique skin filename");
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String sanitizeBaseName(String value, String fallback) {
        if (value == null) return fallback;
        String sanitized = value.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (sanitized.isEmpty() || ".".equals(sanitized) || "..".equals(sanitized)) {
            return fallback;
        }
        return sanitized.length() <= MAX_NAME_LENGTH
                ? sanitized : sanitized.substring(0, MAX_NAME_LENGTH);
    }

    private static void atomicReplace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

}
