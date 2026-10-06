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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerTextureUploadLimitTest {
    private static final int LIMIT = TextureTransferLimits.MIN_SERVER_UPLOAD_BYTES;

    @TempDir
    Path directory;

    private final ServerTextureCache textures = ServerTextureCache.getInstance();

    @AfterEach
    void release() {
        textures.clear();
        textures.configureUploadLimit(TextureTransferLimits.DEFAULT_SERVER_UPLOAD_BYTES);
    }

    @Test
    void aTextureOverTheConfiguredLimitIsNeitherStagedNorServed() throws IOException {
        byte[] large = noiseSkin(256);
        assertTrue(large.length > LIMIT, "fixture must exceed the limit");
        ContentAliases aliases = ContentAliases.forBytes(large);

        assertEquals(LIMIT, textures.configureUploadLimit(LIMIT));
        textures.init(directory.resolve("world"));

        assertNull(textures.prepareTexture(aliases.sha256(), UUID.randomUUID(), "skin", large));
        assertFalse(textures.storeTexture(aliases.sha1(), UUID.randomUUID(), "skin", large));
        assertFalse(textures.isRequestable(aliases.sha256(), "skin"));
        assertNull(textures.getTexture(aliases.sha256()));
    }

    @Test
    void aTextureWithinTheLimitIsStillAccepted() throws IOException {
        byte[] small = noiseSkin(64);
        assertTrue(small.length <= LIMIT);
        ContentAliases aliases = ContentAliases.forBytes(small);
        textures.configureUploadLimit(LIMIT);
        textures.init(directory.resolve("world"));

        assertTrue(textures.storeTexture(aliases.sha1(), UUID.randomUUID(), "skin", small));
        assertTrue(textures.isRequestable(aliases.sha256(), "skin"));
        assertArrayEquals(small, textures.getTexture(aliases.sha256()));
    }

    @Test
    void aStoredTextureOverALoweredLimitIsKeptOnDiskButNotLoaded() throws IOException {
        byte[] large = noiseSkin(256);
        ContentAliases aliases = ContentAliases.forBytes(large);
        Path world = directory.resolve("world");
        textures.init(world);
        assertTrue(textures.storeTexture(aliases.sha1(), UUID.randomUUID(), "skin", large));
        textures.clear();

        textures.configureUploadLimit(LIMIT);
        textures.init(world);

        assertFalse(textures.isRequestable(aliases.sha256(), "skin"));
        assertEquals(-1, textures.getTextureSize(aliases.sha256()));
        try (var files = Files.list(world.resolve("quickskin").resolve("textures"))) {
            assertTrue(files.anyMatch(path -> path.getFileName().toString().endsWith(".png")));
        }

        textures.clear();
        textures.configureUploadLimit(TextureTransferLimits.MAX_TEXTURE_BYTES);
        textures.init(world);
        assertTrue(textures.isRequestable(aliases.sha256(), "skin"));
    }

    private static byte[] noiseSkin(int size) throws IOException {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Random random = new Random(size);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                image.setRGB(x, y, 0xFF000000 | random.nextInt(0x1000000));
            }
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "png", output));
        return output.toByteArray();
    }
}
