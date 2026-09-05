package com.quickskin.mod.server.storage;

import com.quickskin.mod.common.data.ContentAliases;
import com.quickskin.mod.common.data.PlayerAppearance;
import com.quickskin.mod.server.data.ServerPlayerAppearanceRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldStorageIsolationTest {
    @TempDir
    Path directory;

    private final ServerTextureCache textures = ServerTextureCache.getInstance();
    private final ServerAnimationCache animations = ServerAnimationCache.getInstance();
    private final ServerAppearanceStorage appearances = ServerAppearanceStorage.getInstance();
    private final ServerPlayerAppearanceRepository players = ServerPlayerAppearanceRepository.getInstance();

    @AfterEach
    void releaseWorld() {
        players.clear();
        animations.clear();
        textures.clear();
        appearances.clear();
    }

    @Test
    void switchingWorldPathsKeepsPersistedTexturesAndAppearancesIsolated() throws Exception {
        Path firstWorld = directory.resolve("first-world");
        Path secondWorld = directory.resolve("second-world");
        UUID player = UUID.randomUUID();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB), "png", output));
        byte[] png = output.toByteArray();
        ContentAliases aliases = ContentAliases.forBytes(png);
        String skinId = "local_skin:" + aliases.sha256();

        openWorld(firstWorld);
        assertTrue(textures.storeTexture(aliases.sha1(), player, "skin", png));
        assertTrue(players.trySetAppearance(new PlayerAppearance(player, skinId, "", "classic")));
        appearances.savePlayerAppearance(player);
        assertTrue(Files.isRegularFile(firstWorld.resolve("quickskin/appearances/" + player + ".json")));

        releaseWorld();
        openWorld(secondWorld);
        assertNull(textures.getTexture(aliases.sha1()));
        assertNull(appearances.loadPlayerAppearance(player));
        assertNull(players.getAppearance(player));

        releaseWorld();
        openWorld(firstWorld);
        assertArrayEquals(png, textures.getTexture(aliases.sha1()));
        assertEquals(skinId, appearances.loadPlayerAppearance(player).getSkinId());
    }

    private void openWorld(Path world) {
        textures.init(world);
        animations.init(world);
        appearances.init(world);
    }
}
