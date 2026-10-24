package com.quickskin.mod.server.data;

import com.quickskin.mod.common.data.ContentAliases;
import com.quickskin.mod.common.data.SkinResolution;
import com.quickskin.mod.common.util.BoundedFileReader;
import com.quickskin.mod.common.util.SkinModelDetector;
import com.quickskin.mod.networking.PoolSkinEntry;
import com.quickskin.mod.networking.TextureTransferLimits;
import com.quickskin.mod.platform.QuickSkinInfo;
import com.quickskin.mod.server.storage.ServerTextureCache;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The PNG skins a server owner puts in {@code config/quickskin-skins/}, which join the world's
 * skin pool when the server starts and when an admin reloads the pool. A skin is named after its
 * file, without {@code .png} ({@link PoolSkinEntry#nameFrom}); a name ending in {@code _slim} or
 * {@code _classic} chooses the model, and otherwise it is read from the arms
 * ({@link SkinModelDetector}). A file replaces the pool skin of its name. Only regular files are
 * read, at most {@link TextureTransferLimits#MAX_POOL_SKINS} of them, each within the server's
 * upload limit, 64x64 or an HD skin size, and checked like an upload.
 */
public final class SkinPoolFolder {
    public static final String DIRECTORY_NAME = "quickskin-skins";
    /** Who the texture cache records as the uploader of the folder's skins. */
    public static final UUID OWNER = UUID.nameUUIDFromBytes("quickskin:skin-folder".getBytes(StandardCharsets.UTF_8));

    /** A skin read from the folder and staged in the texture cache, not stored yet. */
    public record Found(String name, String model, ServerTextureCache.PreparedTexture texture) {
    }

    private SkinPoolFolder() {
    }

    /** Creates the folder when it is missing, so server owners find where skins go. */
    public static void createIfMissing(Path directory) {
        try {
            if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) Files.createDirectories(directory);
        } catch (IOException e) {
            QuickSkinInfo.LOGGER.warn("Unable to create the skin pool folder {}", directory, e);
        }
    }

    /**
     * Reads and checks the folder's skins and stages them in the texture cache; slow, so off the
     * server thread once players are on. Each found skin must be passed to {@link #commit}.
     */
    public static List<Found> prepare(Path directory) {
        List<Found> found = new ArrayList<>();
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return found;
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory, "*.{png,PNG}")) {
            for (Path file : entries) {
                if (files.size() >= TextureTransferLimits.MAX_POOL_SKINS) break;
                if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) files.add(file);
            }
        } catch (IOException e) {
            QuickSkinInfo.LOGGER.warn("Unable to list the skin pool folder {}", directory, e);
            return found;
        }
        files.sort(null);
        ServerTextureCache cache = ServerTextureCache.getInstance();
        for (Path file : files) {
            String fileName = file.getFileName().toString();
            String base = fileName.substring(0, fileName.length() - ".png".length());
            String lower = base.toLowerCase(Locale.ROOT);
            String model = lower.endsWith("_slim") ? "slim" : lower.endsWith("_classic") ? "classic" : null;
            String name = PoolSkinEntry.nameFrom(base, "skin");
            try {
                byte[] data = BoundedFileReader.readBytes(file, cache.uploadLimitBytes());
                if (!isSkinLayout(data)) {
                    QuickSkinInfo.LOGGER.warn("Skipped {}: a skin is 64x64 or an HD size such as 128x128", file);
                    continue;
                }
                ServerTextureCache.PreparedTexture texture = cache.prepareTexture(
                        ContentAliases.forBytes(data).sha256(), OWNER, "skin", data);
                if (texture == null) {
                    QuickSkinInfo.LOGGER.warn("Skipped {}: it is not a skin this server accepts", file);
                    continue;
                }
                found.add(new Found(name, model != null ? model : SkinModelDetector.detectSkinModel(data), texture));
            } catch (IOException | RuntimeException e) {
                QuickSkinInfo.LOGGER.warn("Skipped {}: it could not be read", file, e);
            }
        }
        return found;
    }

    /** Whether a PNG has the size of a skin Quick Skin draws as it is: 64x64 or one of the HD sizes. */
    static boolean isSkinLayout(byte[] png) {
        if (png.length < 24 || png[12] != 'I' || png[13] != 'H' || png[14] != 'D' || png[15] != 'R') return false;
        SkinResolution resolution = SkinResolution.fromDimensions(readInt(png, 16), readInt(png, 20));
        return resolution != null && resolution != SkinResolution.LEGACY;
    }

    private static int readInt(byte[] data, int offset) {
        return ((data[offset] & 0xff) << 24) | ((data[offset + 1] & 0xff) << 16)
                | ((data[offset + 2] & 0xff) << 8) | (data[offset + 3] & 0xff);
    }

    /**
     * Server thread: stores the found skins in the texture cache and puts each in the pool under
     * its name, replacing a pool skin of that name.
     *
     * @return the names of the skins the pool took
     */
    public static List<String> commit(List<Found> found, ServerSkinAssignmentStore store) {
        List<String> added = new ArrayList<>();
        ServerTextureCache cache = ServerTextureCache.getInstance();
        for (Found skin : found) {
            try (ServerTextureCache.PreparedTexture texture = skin.texture()) {
                if (!cache.storePreparedTexture(texture)) continue;
                String skinId = "local_skin:" + texture.hash();
                PoolSkinEntry existing = store.poolSkin(skin.name());
                if (existing != null && existing.skinId().equals(skinId) && existing.model().equals(skin.model())) {
                    added.add(existing.name());
                    continue;
                }
                if (existing != null) store.removeFromPool(existing.name());
                ServerSkinAssignmentStore.PoolAddResult result = store.addToPool(skin.name(), skinId, skin.model());
                if (result == ServerSkinAssignmentStore.PoolAddResult.ADDED) {
                    added.add(skin.name());
                } else {
                    QuickSkinInfo.LOGGER.warn("The skin pool did not take {} ({})", skin.name(), result);
                }
            }
        }
        return added;
    }
}
