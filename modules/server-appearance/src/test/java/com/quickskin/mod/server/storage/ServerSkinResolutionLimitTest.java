package com.quickskin.mod.server.storage;

import com.quickskin.mod.common.data.ContentAliases;
import com.quickskin.mod.networking.TextureTransferLimits;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** maxSkinResolution: a server that takes no skin wider than it, while capes stay unlimited. */
class ServerSkinResolutionLimitTest {
    @TempDir
    Path directory;

    private final ServerTextureCache textures = ServerTextureCache.getInstance();

    @AfterEach
    void release() {
        textures.clear();
        textures.configureMaxSkinResolution(0);
        textures.configureUploadLimit(TextureTransferLimits.DEFAULT_SERVER_UPLOAD_BYTES);
    }

    @Test
    void aSkinWiderThanTheLimitIsRefusedAndOneWithinItIsKept() throws IOException {
        assertEquals(64, textures.configureMaxSkinResolution(64));
        textures.init(directory.resolve("world"));
        byte[] hd = noise(128, 128);
        byte[] standard = noise(64, 64);
        assertNull(textures.prepareTexture(ContentAliases.forBytes(hd).sha256(), UUID.randomUUID(), "skin", hd));
        assertTrue(textures.storeTexture(ContentAliases.forBytes(standard).sha1(), UUID.randomUUID(), "skin", standard));
    }

    @Test
    void capesAreNotLimited() throws IOException {
        textures.configureMaxSkinResolution(64);
        textures.init(directory.resolve("world"));
        byte[] hdCape = noise(128, 64);
        try (ServerTextureCache.PreparedTexture prepared = textures.prepareTexture(
                ContentAliases.forBytes(hdCape).sha256(), UUID.randomUUID(), "cape", hdCape)) {
            assertNotNull(prepared);
        }
    }

    @Test
    void withoutALimitHdSkinsAreAccepted() throws IOException {
        textures.init(directory.resolve("world"));
        byte[] hd = noise(256, 256);
        assertTrue(textures.storeTexture(ContentAliases.forBytes(hd).sha1(), UUID.randomUUID(), "skin", hd));
    }

    @Test
    void aStoredHdSkinIsKeptOnDiskButNotLoadedUntilTheLimitAllowsItAgain() throws IOException {
        Path world = directory.resolve("world");
        textures.init(world);
        byte[] hd = noise(128, 128);
        byte[] hdCape = noise(256, 128);
        ContentAliases skin = ContentAliases.forBytes(hd);
        ContentAliases cape = ContentAliases.forBytes(hdCape);
        assertTrue(textures.storeTexture(skin.sha1(), UUID.randomUUID(), "skin", hd));
        assertTrue(textures.storeTexture(cape.sha1(), UUID.randomUUID(), "cape", hdCape));
        textures.clear();

        textures.configureMaxSkinResolution(64);
        textures.init(world);
        assertFalse(textures.isRequestable(skin.sha256(), "skin"));
        assertTrue(textures.isRetainedOverLimit(skin.sha256()));
        assertTrue(textures.isRequestable(cape.sha256(), "cape"), "a 2:1 cape is never taken for a skin");

        textures.clear();
        textures.configureMaxSkinResolution(128);
        textures.init(world);
        assertTrue(textures.isRequestable(skin.sha256(), "skin"));
    }

    private static byte[] noise(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Random random = new Random(31L * width + height);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, 0xFF000000 | random.nextInt(0x1000000));
            }
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "png", output));
        return output.toByteArray();
    }
}
