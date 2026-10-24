package com.quickskin.mod.server.data;

import com.quickskin.mod.networking.PoolSkinEntry;
import com.quickskin.mod.server.storage.ServerTextureCache;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The PNG skins a server owner drops in a folder join the world's pool, named after their files. */
class SkinPoolFolderTest {
    @TempDir
    Path world;
    @TempDir
    Path folder;

    private final ServerTextureCache textures = ServerTextureCache.getInstance();

    @BeforeEach
    void openWorld() {
        textures.init(world);
    }

    @AfterEach
    void releaseWorld() {
        textures.clear();
    }

    @Test
    void theFolderSkinsJoinThePoolUnderTheirFileNames() throws IOException {
        Files.write(folder.resolve("Guard_slim.png"), skin(1));
        Files.write(folder.resolve("Town crier.png"), skin(2));
        Files.writeString(folder.resolve("notes.png"), "not an image");
        Files.write(folder.resolve("odd.png"), image(50, 50, 3));
        ServerSkinAssignmentStore store = new ServerSkinAssignmentStore();
        store.init(world);

        List<String> added = SkinPoolFolder.commit(SkinPoolFolder.prepare(folder), store);

        assertEquals(List.of("Guard_slim", "Town_crier"), added);
        PoolSkinEntry guard = store.poolSkin("guard_slim");
        assertEquals("slim", guard.model());
        assertTrue(ServerSkinAssignmentStore.isValid(guard.skinId(), guard.model()));
        assertTrue(textures.isSkin(guard.skinId().substring("local_skin:".length())));
        assertEquals("classic", store.poolSkin("Town_crier").model());
    }

    @Test
    void aChangedFileReplacesThePoolSkinOfItsName() throws IOException {
        ServerSkinAssignmentStore store = new ServerSkinAssignmentStore();
        store.init(world);
        Files.write(folder.resolve("Guard.png"), skin(1));
        SkinPoolFolder.commit(SkinPoolFolder.prepare(folder), store);
        String first = store.poolSkin("Guard").skinId();

        Files.write(folder.resolve("Guard.png"), skin(5));
        SkinPoolFolder.commit(SkinPoolFolder.prepare(folder), store);

        assertEquals(1, store.pool().size());
        assertFalse(first.equals(store.poolSkin("Guard").skinId()));
    }

    @Test
    void assignedAndPoolSkinsArePinnedUnderHoldersOfTheirOwn() throws IOException {
        Files.write(folder.resolve("Guard.png"), skin(1));
        ServerSkinAssignmentStore store = new ServerSkinAssignmentStore();
        store.init(world);
        SkinPoolFolder.commit(SkinPoolFolder.prepare(folder), store);
        String hash = store.poolSkin("Guard").skinId().substring("local_skin:".length());
        UUID holder = UUID.randomUUID();

        assertTrue(textures.tryReplaceSkinPins(holder, List.of(hash)));
        assertTrue(textures.isPinned(hash));
        assertFalse(textures.tryReplaceSkinPins(holder, List.of("sha256-" + "c".repeat(64))),
                "a skin the cache does not hold cannot be pinned");
        assertTrue(textures.isPinned(hash), "a refused replacement keeps the old pins");

        textures.releaseAppearancePins(holder);
        assertFalse(textures.isPinned(hash));
    }

    private static byte[] skin(long seed) throws IOException {
        return image(64, 64, seed);
    }

    private static byte[] image(int width, int height, long seed) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Random random = new Random(seed);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, 0xFF000000 | random.nextInt(0x1000000));
            }
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }
}
