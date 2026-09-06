package com.quickskin.mod.client.gui.util;

import com.quickskin.mod.common.data.AnimationMetadata;
import com.quickskin.mod.common.util.CapeElytraSilhouette;
import com.quickskin.mod.common.util.HashUtil;
import com.quickskin.mod.common.util.BoundedFileReader;
import com.quickskin.mod.common.util.SafeImageReader;
import com.quickskin.mod.common.util.GifDecoder;
import com.quickskin.mod.networking.NetworkSecurity;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * File and image-processing boundary for cape imports.
 *
 * <p>The GUI owns only user interaction. This class owns validation, decoding, compositing,
 * collision-free filenames, persistence, and animation metadata.</p>
 */
@Environment(EnvType.CLIENT)
public final class CapeImportProcessor {
    private static final long MAX_SOURCE_BYTES = 32L * 1024L * 1024L;
    private static final int MAX_STATIC_DIMENSION = 4096;
    private static final long MAX_ATLAS_PIXELS = 64L * 1024L * 1024L / 4L;

    private CapeImportProcessor() {
    }

    public record PreparedCape(
            Path source,
            BufferedImage atlas,
            int frameCount,
            AnimationMetadata animationMetadata,
            boolean gif,
            boolean standardFormat
    ) {
    }

    public static boolean isSupported(Path path) {
        if (path == null || path.getFileName() == null) {
            return false;
        }
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".png") || name.endsWith(".gif");
    }

    public static PreparedCape prepare(Path source, GifDecoder gifDecoder) throws IOException {
        if (source == null || !Files.isRegularFile(source) || !Files.isReadable(source)) {
            throw new IOException("Cape file does not exist or is not readable");
        }
        if (!isSupported(source)) {
            throw new IOException("Only PNG and GIF cape files are supported");
        }

        long sourceBytes = Files.size(source);
        if (sourceBytes <= 0 || sourceBytes > MAX_SOURCE_BYTES) {
            throw new IOException("Cape file must be between 1 byte and 32 MB");
        }

        boolean gif = source.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".gif");
        if (gif) {
            return prepareGif(source, gifDecoder);
        }

        BufferedImage image = SafeImageReader.readPng(source);
        validateAtlas(image.getWidth(), image.getHeight(), 1);
        return new PreparedCape(source, image, 1, null, false,
                image.getWidth() == 64 && image.getHeight() == 32);
    }

    private static PreparedCape prepareGif(Path source, GifDecoder gifDecoder) throws IOException {
        if (gifDecoder == null) throw new IOException("GIF decoder is required for animated capes");
        try (InputStream input = Files.newInputStream(source)) {
            GifDecoder.DecodedGif gif = gifDecoder.decode(input);
            if (gif == null || gif.atlas() == null || gif.frameCount() < 1 || gif.metadata() == null
                    || gif.atlas().getHeight() % gif.frameCount() != 0) {
                throw new IOException("Invalid GIF image");
            }
            int width = gif.atlas().getWidth();
            int height = gif.atlas().getHeight() / gif.frameCount();
            validateAtlas(width, height, gif.frameCount());
            return new PreparedCape(source, gif.atlas(), gif.frameCount(), gif.metadata(), true,
                    width == 64 && height == 32);
        }
    }

    public static Path saveStandard(
            PreparedCape prepared,
            Path targetDirectory,
            Path metadataDirectory,
            BufferedImage vanillaElytra
    ) throws IOException {
        requireDirectory(targetDirectory);
        if (!prepared.standardFormat()) {
            throw new IOException("Cape requires adjustment before it can be saved");
        }

        if (prepared.gif()) {
            return copyUniqueAtomically(prepared.source(), targetDirectory, ".gif");
        }

        BufferedImage finalAtlas = compositeElytraIfNeeded(
                prepared.atlas(), prepared.frameCount(), vanillaElytra);
        return savePngUnique(prepared.source(), finalAtlas, targetDirectory,
                metadataDirectory, prepared.animationMetadata());
    }

    public static Path saveAdjusted(
            PreparedCape prepared,
            BufferedImage adjustedAtlas,
            Path targetDirectory,
            Path metadataDirectory,
            BufferedImage vanillaElytra
    ) throws IOException {
        if (adjustedAtlas == null) {
            throw new IOException("Cape adjustment produced no image");
        }
        requireDirectory(targetDirectory);

        int frameHeight = adjustedAtlas.getWidth() / 2;
        if (frameHeight <= 0 || adjustedAtlas.getHeight() % frameHeight != 0) {
            throw new IOException("Adjusted cape must contain complete 2:1 frames");
        }
        int frameCount = adjustedAtlas.getHeight() / frameHeight;
        validateAtlas(adjustedAtlas.getWidth(), frameHeight, frameCount);

        BufferedImage finalAtlas = compositeElytraIfNeeded(adjustedAtlas, frameCount, vanillaElytra);
        return savePngUnique(prepared.source(), finalAtlas, targetDirectory,
                metadataDirectory, frameCount > 1 ? prepared.animationMetadata() : null);
    }

    public static BufferedImage compositeElytraIfNeeded(
            BufferedImage atlas,
            int frameCount,
            BufferedImage vanillaElytra
    ) throws IOException {
        if (atlas == null) {
            return atlas;
        }

        int frameHeight = atlas.getWidth() / 2;
        if (frameHeight <= 0 || frameCount < 1
                || (long) frameHeight * frameCount != atlas.getHeight()) {
            throw new IOException("Invalid cape atlas layout");
        }

        boolean[] needsElytra = new boolean[frameCount];
        boolean anyFrameNeedsElytra = false;
        for (int frameIndex = 0; frameIndex < frameCount; frameIndex++) {
            int y = frameIndex * frameHeight;
            needsElytra[frameIndex] = isElytraAreaTransparent(
                    atlas.getSubimage(0, y, atlas.getWidth(), frameHeight));
            anyFrameNeedsElytra |= needsElytra[frameIndex];
        }
        BufferedImage composite = atlas;
        if (anyFrameNeedsElytra && vanillaElytra != null) {
            composite = new BufferedImage(
                    atlas.getWidth(), atlas.getHeight(), BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = composite.createGraphics();
            try {
                graphics.setRenderingHint(
                        RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                graphics.setComposite(AlphaComposite.Clear);
                graphics.fillRect(0, 0, composite.getWidth(), composite.getHeight());
                graphics.setComposite(AlphaComposite.SrcOver);
                for (int frameIndex = 0; frameIndex < frameCount; frameIndex++) {
                    int y = frameIndex * frameHeight;
                    if (needsElytra[frameIndex]) {
                        graphics.drawImage(vanillaElytra, 0, y, atlas.getWidth(), y + frameHeight,
                                0, 0, vanillaElytra.getWidth(), vanillaElytra.getHeight(), null);
                    }
                    graphics.drawImage(
                            atlas.getSubimage(0, y, atlas.getWidth(), frameHeight), 0, y, null);
                }
            } finally {
                graphics.dispose();
            }
        }
        return CapeElytraSilhouette.maskedCopy(composite, frameCount);
    }

    public static boolean isElytraAreaTransparent(BufferedImage image) {
        return CapeElytraSilhouette.isElytraAreaTransparent(image);
    }

    public static Path uniqueTarget(Path source, Path targetDirectory, String extension)
            throws IOException {
        requireDirectory(targetDirectory);
        String fileName = source.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        String base = dot > 0 ? fileName.substring(0, dot) : fileName;
        if (base.isBlank()) {
            base = "cape";
        }

        Path root = targetDirectory.toAbsolutePath().normalize();
        Path candidate = root.resolve(base + extension).normalize();
        int counter = 1;
        while (Files.exists(candidate)) {
            candidate = root.resolve(base + "_" + counter++ + extension).normalize();
        }
        if (!candidate.startsWith(root)) {
            throw new IOException("Cape destination escaped its target directory");
        }
        return candidate;
    }

    public static void savePng(BufferedImage image, Path output) throws IOException {
        if (output == null || output.getParent() == null) {
            throw new IOException("PNG destination is invalid");
        }
        Files.createDirectories(output.getParent());
        Path temporary = Files.createTempFile(output.getParent(), ".quickskin-cape-", ".png.tmp");
        try {
            writePng(image, temporary);
            SafeImageReader.readPng(temporary);
            atomicReplace(temporary, output);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void writePng(BufferedImage image, Path output) throws IOException {
        if (image == null) {
            throw new IOException("PNG image is missing");
        }
        BufferedImage argb = image;
        if (image.getType() != BufferedImage.TYPE_INT_ARGB) {
            argb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = argb.createGraphics();
            try {
                graphics.setComposite(AlphaComposite.Src);
                graphics.drawImage(image, 0, 0, null);
            } finally {
                graphics.dispose();
            }
        }
        if (!ImageIO.write(argb, "png", output.toFile())) {
            throw new IOException("No PNG writer is available");
        }
    }

    private static Path savePngUnique(
            Path source,
            BufferedImage image,
            Path targetDirectory,
            Path metadataDirectory,
            AnimationMetadata metadata
    ) throws IOException {
        requireDirectory(targetDirectory);
        Path root = targetDirectory.toAbsolutePath().normalize();
        Path temporary = Files.createTempFile(root, ".quickskin-cape-", ".png.tmp");
        Path savedCape = null;
        try {
            writePng(image, temporary);
            SafeImageReader.readPng(temporary);
            savedCape = moveUnique(temporary, source, root, ".png");
            try {
                saveMetadata(metadataDirectory, savedCape, metadata);
            } catch (IOException metadataError) {
                try {
                    Files.deleteIfExists(savedCape);
                } catch (IOException cleanupError) {
                    metadataError.addSuppressed(cleanupError);
                }
                throw metadataError;
            }
            return savedCape;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static Path copyUniqueAtomically(
            Path source, Path targetDirectory, String extension) throws IOException {
        requireDirectory(targetDirectory);
        Path root = targetDirectory.toAbsolutePath().normalize();
        Path temporary = Files.createTempFile(root, ".quickskin-cape-", ".copy.tmp");
        try {
            Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
            return moveUnique(temporary, source, root, extension);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static Path moveUnique(
            Path temporary, Path source, Path root, String extension) throws IOException {
        for (int attempts = 0; attempts < 10_000; attempts++) {
            Path target = uniqueTarget(source, root, extension);
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
                if (!committed) {
                    Files.deleteIfExists(target);
                }
            }
        }
        throw new IOException("Could not allocate a unique cape filename");
    }

    private static void saveMetadata(Path metadataDirectory, Path savedCape, AnimationMetadata metadata)
            throws IOException {
        if (metadata == null) {
            return;
        }
        String metadataJson = metadata.toJson();
        if (!NetworkSecurity.isValidAnimationMetadata(metadataJson)) {
            throw new IOException("Cape animation metadata is invalid");
        }
        requireDirectory(metadataDirectory);
        String hash = HashUtil.computeAssetContentId(
                BoundedFileReader.readBytes(savedCape, (int) MAX_SOURCE_BYTES), "cape");
        if (!NetworkSecurity.isValidStrongContentId(hash)) {
            throw new IOException("Could not compute cape content hash");
        }
        Path root = metadataDirectory.toAbsolutePath().normalize();
        Path metadataPath = root.resolve(hash + ".json").normalize();
        if (!metadataPath.startsWith(root)) {
            throw new IOException("Cape metadata destination escaped its cache directory");
        }
        Path temporary = Files.createTempFile(root, "." + hash + "-", ".json.tmp");
        try {
            Files.writeString(temporary, metadataJson);
            atomicReplace(temporary, metadataPath);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void atomicReplace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void requireDirectory(Path directory) throws IOException {
        if (directory == null) {
            throw new IOException("Cape destination is not initialized");
        }
        Files.createDirectories(directory);
    }

    private static void validateAtlas(int width, int frameHeight, int frameCount) throws IOException {
        if (width < 1 || frameHeight < 1 || frameCount < 1) {
            throw new IOException("Cape has invalid dimensions");
        }
        if (width > MAX_STATIC_DIMENSION || frameHeight > MAX_STATIC_DIMENSION) {
            throw new IOException("Cape dimensions exceed 4096 pixels");
        }
        long pixels = (long) width * frameHeight * frameCount;
        if (pixels > MAX_ATLAS_PIXELS) {
            throw new IOException("Cape requires more than 64 MB of decoded pixels");
        }
    }
}
