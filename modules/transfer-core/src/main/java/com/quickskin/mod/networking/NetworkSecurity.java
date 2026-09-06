package com.quickskin.mod.networking;

import com.quickskin.mod.common.data.AnimationMetadata;
import com.quickskin.mod.common.data.ContentId;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/** Pure validation helpers for values and files crossing the network boundary. */
public final class NetworkSecurity {
    private static final byte[] PNG_SIGNATURE = new byte[] {
            (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
    };

    private NetworkSecurity() {
    }

    public static boolean isValidContentId(String contentId) {
        return ContentId.parse(contentId) != null;
    }

    public static boolean isValidStrongContentId(String contentId) {
        ContentId parsed = ContentId.parse(contentId);
        return parsed != null && parsed.algorithm() == ContentId.Algorithm.SHA256;
    }

    public static boolean isValidLegacyContentId(String contentId) {
        ContentId parsed = ContentId.parse(contentId);
        return parsed != null && parsed.algorithm() == ContentId.Algorithm.SHA1;
    }

    public static boolean isValidTextureType(String textureType) {
        return "skin".equals(textureType) || "cape".equals(textureType);
    }

    public static boolean isValidModel(String model) {
        return "classic".equals(model) || "slim".equals(model) || "auto".equals(model);
    }

    public static boolean isValidLocalAppearanceId(String appearanceId, String textureType) {
        if (!isValidTextureType(textureType)) {
            return false;
        }
        if (appearanceId == null || appearanceId.isEmpty()) {
            return true;
        }
        if (appearanceId.getBytes(StandardCharsets.UTF_8).length
                        > TextureTransferLimits.MAX_APPEARANCE_ID_BYTES) {
            return false;
        }
        for (int i = 0; i < appearanceId.length(); i++) {
            if (Character.isISOControl(appearanceId.charAt(i))) return false;
        }
        String prefix = "skin".equals(textureType) ? "local_skin:" : "local_cape:";
        if (appearanceId.startsWith("local_skin:") || appearanceId.startsWith("local_cape:")) {
            return appearanceId.startsWith(prefix)
                    && isValidContentId(appearanceId.substring(prefix.length()));
        }
        return true;
    }

    /** V2 keeps non-local appearance names but requires strong IDs for local content. */
    public static boolean isValidV2AppearanceId(String appearanceId, String textureType) {
        if (!isValidLocalAppearanceId(appearanceId, textureType)) return false;
        if (appearanceId == null || appearanceId.isEmpty()) return true;
        String prefix = "skin".equals(textureType) ? "local_skin:" : "local_cape:";
        return !appearanceId.startsWith(prefix)
                || isValidStrongContentId(appearanceId.substring(prefix.length()));
    }

    public static boolean isValidLegacyAppearanceId(String appearanceId, String textureType) {
        if (!isValidLocalAppearanceId(appearanceId, textureType)) return false;
        if (appearanceId == null || appearanceId.isEmpty()) return true;
        String prefix = "skin".equals(textureType) ? "local_skin:" : "local_cape:";
        return !appearanceId.startsWith(prefix)
                || isValidLegacyContentId(appearanceId.substring(prefix.length()));
    }

    /**
     * Resolve a content-addressed file and verify lexical containment. The strict
     * identifier check also prevents separator, drive-letter, and dot-segment input.
     */
    public static Path resolveContained(Path directory, String contentId, String suffix) {
        if (directory == null || !isValidContentId(contentId) || suffix == null) {
            return null;
        }
        Path root = directory.toAbsolutePath().normalize();
        Path result = root.resolve(contentId + suffix).normalize();
        return result.startsWith(root) ? result : null;
    }

    public static boolean isValidTextureData(byte[] data, String textureType) {
        long pixels = getTexturePixelCount(data, textureType);
        if (pixels < 1) return false;
        int width = readInt(data, 16);
        int height = readInt(data, 20);

        // Force one complete decode after checking dimensions. This rejects truncated
        // or otherwise undecodable images without allowing an unbounded pixel allocation.
        try (ImageInputStream input =
                     new MemoryCacheImageInputStream(new ByteArrayInputStream(data))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                return false;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                if (reader.getWidth(0) != width || reader.getHeight(0) != height) {
                    return false;
                }
                BufferedImage decoded = reader.read(0);
                return decoded != null && decoded.getWidth() == width && decoded.getHeight() == height;
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** Cheap preflight for rate accounting before a full ImageIO decode. */
    public static long getTexturePixelCount(byte[] data, String textureType) {
        if (data == null || data.length == 0 || data.length > TextureTransferLimits.MAX_TEXTURE_BYTES
                || (textureType != null && !isValidTextureType(textureType))
                || !hasPngSignature(data) || data.length < 33 || readInt(data, 8) != 13
                || data[12] != 'I' || data[13] != 'H' || data[14] != 'D' || data[15] != 'R') {
            return -1;
        }
        int width = readInt(data, 16);
        int height = readInt(data, 20);
        return validDimensions(width, height, textureType) ? (long) width * height : -1;
    }

    public static boolean isValidAnimationMetadata(String metadataJson) {
        return parseAnimationMetadata(metadataJson) != null;
    }

    /** Parses and validates once so packet handlers do not repeat bounded JSON work. */
    public static AnimationMetadata parseAnimationMetadata(String metadataJson) {
        if (metadataJson == null || metadataJson.isEmpty()
                || metadataJson.getBytes(StandardCharsets.UTF_8).length > TextureTransferLimits.MAX_JSON_BYTES) {
            return null;
        }
        try {
            AnimationMetadata metadata = AnimationMetadata.fromJson(metadataJson);
            if (metadata == null || metadata.frames() == null || metadata.frameCount() < 1
                    || metadata.frameCount() > 256
                    || metadata.frames().size() != metadata.frameCount()) {
                return null;
            }
            Set<Integer> indexes = new HashSet<>();
            for (AnimationMetadata.FrameData frame : metadata.frames()) {
                if (frame == null || frame.delay() < 20 || frame.delay() > 60_000
                        || frame.index() < 0 || frame.index() >= metadata.frameCount()
                        || !indexes.add(frame.index())) {
                    return null;
                }
            }
            return metadata;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean hasPngSignature(byte[] data) {
        if (data.length < PNG_SIGNATURE.length) {
            return false;
        }
        for (int i = 0; i < PNG_SIGNATURE.length; i++) {
            if (data[i] != PNG_SIGNATURE[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean validDimensions(int width, int height, String textureType) {
        if (width <= 0 || height <= 0 || width > TextureTransferLimits.MAX_IMAGE_WIDTH
                || height > TextureTransferLimits.MAX_IMAGE_HEIGHT
                || (long) width * height > TextureTransferLimits.MAX_IMAGE_PIXELS) {
            return false;
        }
        // A skin is never an animation atlas, so cap both axes to the supported HD limit.
        return !"skin".equals(textureType) || height <= TextureTransferLimits.MAX_IMAGE_WIDTH;
    }

    private static int readInt(byte[] data, int offset) {
        return ((data[offset] & 0xff) << 24)
                | ((data[offset + 1] & 0xff) << 16)
                | ((data[offset + 2] & 0xff) << 8)
                | (data[offset + 3] & 0xff);
    }
}
