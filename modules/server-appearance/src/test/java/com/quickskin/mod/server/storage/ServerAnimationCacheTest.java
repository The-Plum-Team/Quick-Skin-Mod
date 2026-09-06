package com.quickskin.mod.server.storage;

import com.quickskin.mod.common.data.ContentAliases;
import com.quickskin.mod.common.data.ContentId;
import com.quickskin.mod.common.util.HashUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerAnimationCacheTest {
    private static final UUID OWNER = UUID.fromString("6fb31f80-9a3e-4a4f-bdb8-79ef0e2c0c88");

    @TempDir
    Path tempDirectory;

    @AfterEach
    void clearSingletons() {
        ServerAnimationCache.getInstance().clear();
        ServerTextureCache.getInstance().clear();
    }

    @Test
    void legacyIdentitySurvivesDeliveryEvictionAndRestart() throws Exception {
        Path textures = Files.createDirectories(tempDirectory.resolve("textures"));
        Path animations = Files.createDirectories(tempDirectory.resolve("animations"));
        ServerTextureCache textureCache = ServerTextureCache.getInstance();
        ServerAnimationCache animationCache = ServerAnimationCache.getInstance();
        textureCache.clear();
        animationCache.clear();
        setField(textureCache, "storageDirectory", textures);
        setField(animationCache, "storageDirectory", animations);

        byte[] cape = legacyCapePng();
        String hash = HashUtil.computeHash(cape);
        String primary = ContentAliases.forBytes(cape).sha256();
        String original = metadata(50);
        String retimed = metadata(125);
        assertTrue(textureCache.storeTexture(hash, OWNER, "cape", cape));
        assertTrue(Files.isRegularFile(textures.resolve(primary + ".png")));
        assertTrue(Files.isRegularFile(textures.resolve(primary + ".owner")));
        assertFalse(Files.exists(textures.resolve(hash + ".png")));
        assertFalse(Files.exists(textures.resolve(hash + ".owner")));
        assertTrue(animationCache.storeMetadata(hash, original, OWNER));

        // Force the same delivery-only LRU path used when the bounded JSON cache fills.
        setField(animationCache, "cachedBytes", 16L * 1024 * 1024 + 1);
        invoke(animationCache, "evictToLimits");
        assertNull(animationCache.getMetadata(hash));
        assertTrue(Files.isRegularFile(animations.resolve(primary + ".identity")));
        assertFalse(animationCache.isMetadataIdentityCompatible(hash, retimed));
        assertFalse(animationCache.storeMetadata(hash, retimed, UUID.randomUUID()));

        // Restart with no delivery JSON: the independent identity must still load and reject a
        // different timing claim while the backing cape remains cached.
        animationCache.clear();
        setField(animationCache, "storageDirectory", animations);
        invoke(animationCache, "loadCachedMetadata");
        assertFalse(animationCache.isMetadataIdentityCompatible(hash, retimed));
        assertFalse(animationCache.storeMetadata(hash, retimed, UUID.randomUUID()));
        assertTrue(animationCache.storeMetadata(hash, original, UUID.randomUUID()));
    }

    @Test
    void migratesUniquelyAuthenticatedLegacyMetadataToSha256() throws Exception {
        Path textures = Files.createDirectories(tempDirectory.resolve("textures-migration"));
        Path animations = Files.createDirectories(tempDirectory.resolve("animations-migration"));
        ServerTextureCache textureCache = ServerTextureCache.getInstance();
        ServerAnimationCache animationCache = ServerAnimationCache.getInstance();
        textureCache.clear();
        animationCache.clear();
        setField(textureCache, "storageDirectory", textures);
        setField(animationCache, "storageDirectory", animations);

        byte[] cape = legacyCapePng();
        ContentAliases aliases = ContentAliases.forBytes(cape);
        String metadata = metadata(75);
        String identity = ContentId.hash(
                metadata.getBytes(StandardCharsets.UTF_8),
                ContentId.Algorithm.SHA256).digest();
        assertTrue(textureCache.storeTexture(aliases.sha1(), OWNER, "cape", cape));
        Files.writeString(animations.resolve(aliases.sha1() + ".json"), metadata);
        Files.writeString(animations.resolve(aliases.sha1() + ".identity"), identity);
        Files.writeString(animations.resolve(aliases.sha1() + ".authority"), OWNER.toString());

        invoke(animationCache, "loadCachedMetadata");

        assertTrue(metadata.equals(animationCache.getMetadata(aliases.sha1())));
        assertTrue(metadata.equals(Files.readString(
                animations.resolve(aliases.sha256() + ".json"))));
        assertTrue(identity.equals(Files.readString(
                animations.resolve(aliases.sha256() + ".identity"))));
        assertTrue(OWNER.toString().equals(Files.readString(
                animations.resolve(aliases.sha256() + ".authority"))));
        assertFalse(Files.exists(animations.resolve(aliases.sha1() + ".json")));
        assertFalse(Files.exists(animations.resolve(aliases.sha1() + ".identity")));
        assertFalse(Files.exists(animations.resolve(aliases.sha1() + ".authority")));
    }

    private static String metadata(int delay) {
        return "{\"frames\":[{\"delay\":" + delay
                + ",\"index\":0}],\"frameCount\":1}";
    }

    private static byte[] legacyCapePng() throws Exception {
        BufferedImage image = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0x7f336699);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "png", output));
        return output.toByteArray();
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void invoke(Object target, String name) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(target);
    }
}
