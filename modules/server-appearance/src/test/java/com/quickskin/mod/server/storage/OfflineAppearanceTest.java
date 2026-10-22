package com.quickskin.mod.server.storage;

import com.quickskin.mod.common.data.ContentAliases;
import com.quickskin.mod.common.data.PlayerAppearance;
import com.quickskin.mod.server.data.ServerPlayerAppearanceRepository;
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
import java.nio.file.attribute.FileTime;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OfflineAppearanceTest {
    @TempDir
    Path world;

    private final ServerTextureCache textures = ServerTextureCache.getInstance();
    private final ServerAppearanceStorage appearances = ServerAppearanceStorage.getInstance();
    private final ServerPlayerAppearanceRepository players =
            ServerPlayerAppearanceRepository.getInstance();
    private final UUID player = UUID.randomUUID();

    @BeforeEach
    void openWorld() {
        textures.init(world);
        appearances.init(world);
    }

    @AfterEach
    void releaseWorld() {
        players.clear();
        appearances.clear();
        textures.clear();
    }

    @Test
    void aPlayerWhoLeavesIsStillSharedWithoutPinningTheirSkin() throws IOException {
        String skinId = storeSkin(player, 1);
        assertTrue(players.trySetAppearance(new PlayerAppearance(player, skinId, "", "slim")));
        assertTrue(textures.isPinned(hashOf(skinId)));

        players.playerLeft(player);

        assertFalse(players.hasAppearance(player));
        assertFalse(textures.isPinned(hashOf(skinId)));
        assertEquals(0, textures.getPinnedBytes());
        assertTrue(players.snapshotRoster().playerIds().contains(player));
        assertEquals(skinId, players.getAppearanceToShare(player).getSkinId());
        assertEquals("slim", players.getAppearance(player).getModel());
    }

    @Test
    void aPlayerWhoLeavesWithoutSkinOrCapeIsNotKept() {
        assertTrue(players.trySetAppearance(new PlayerAppearance(player, "", "", "classic")));
        long revision = players.snapshotRoster().revision();

        players.playerLeft(player);

        assertNull(players.getAppearance(player));
        assertFalse(players.snapshotRoster().playerIds().contains(player));
        assertTrue(players.snapshotRoster().revision() != revision);
    }

    @Test
    void savedAppearancesOfPlayersWhoAreNotConnectedAreLoadedAtStart() throws IOException {
        String skinId = storeSkin(player, 2);
        UUID plain = UUID.randomUUID();
        save(new PlayerAppearance(player, skinId, "", "slim"));
        save(new PlayerAppearance(plain, "", "", "classic"));
        Files.writeString(directory().resolve("not-a-player.json"), "{}");

        assertEquals(1, appearances.loadOfflineAppearances());

        assertEquals(skinId, players.getAppearance(player).getSkinId());
        assertFalse(players.hasAppearance(player));
        assertFalse(textures.isPinned(hashOf(skinId)));
        assertNull(players.getAppearance(plain));
    }

    @Test
    void aJoinReplacesTheOfflineAppearanceWithThePinnedOne() throws IOException {
        String skinId = storeSkin(player, 3);
        save(new PlayerAppearance(player, skinId, "", "slim"));
        appearances.loadOfflineAppearances();

        PlayerAppearance restored = appearances.loadPlayerAppearance(player);

        assertEquals(skinId, restored.getSkinId());
        assertTrue(players.hasAppearance(player));
        assertTrue(textures.isPinned(hashOf(skinId)));
        assertEquals(1, players.snapshotRoster().playerIds().size());
    }

    @Test
    void aJoinWithoutUsableSavedAppearanceDropsTheOfflineOne() throws IOException {
        String skinId = storeSkin(player, 4);
        assertTrue(players.keepOfflineAppearance(new PlayerAppearance(player, skinId, "", "slim")));

        // What the join does when the saved appearance cannot be restored
        players.updateAppearance(player, "", "", "classic");

        assertEquals("", players.getAppearanceToShare(player).getSkinId());
        assertTrue(players.hasAppearance(player));
    }

    @Test
    void anOfflineTextureTheCacheNoLongerHoldsIsSentEmpty() throws IOException {
        String skinId = storeSkin(player, 5);
        String capeId = "local_cape:" + ContentAliases.forBytes(noise(64, 32, 6)).sha256();
        assertTrue(players.keepOfflineAppearance(new PlayerAppearance(player, skinId, capeId, "slim")));

        PlayerAppearance shared = players.getAppearanceToShare(player);

        assertEquals(skinId, shared.getSkinId());
        assertEquals("", shared.getCapeId());
        assertEquals(capeId, players.getAppearance(player).getCapeId());
    }

    @Test
    void theAppearancesOfPlayersWhoLeftFirstAreDroppedPastTheBound() throws IOException {
        String skinId = storeSkin(player, 7);
        int bound = ServerPlayerAppearanceRepository.MAX_OFFLINE_APPEARANCES;
        UUID first = UUID.randomUUID();
        assertTrue(players.keepOfflineAppearance(new PlayerAppearance(first, skinId, "", "slim")));
        for (int index = 0; index < bound; index++) {
            assertTrue(players.keepOfflineAppearance(
                    new PlayerAppearance(UUID.randomUUID(), skinId, "", "slim")));
        }

        assertNull(players.getAppearance(first));
        assertEquals(bound, players.snapshotRoster().playerIds().size());
    }

    @Test
    void onlyTheMostRecentlySavedFilesAreLoadedAtStart() throws IOException {
        String skinId = storeSkin(player, 8);
        int bound = ServerPlayerAppearanceRepository.MAX_OFFLINE_APPEARANCES;
        UUID oldest = UUID.randomUUID();
        save(new PlayerAppearance(oldest, skinId, "", "slim"));
        Files.setLastModifiedTime(directory().resolve(oldest + ".json"), FileTime.fromMillis(0));
        for (int index = 0; index < bound; index++) {
            save(new PlayerAppearance(UUID.randomUUID(), skinId, "", "slim"));
        }

        assertEquals(bound, appearances.loadOfflineAppearances());
        assertNull(players.getAppearance(oldest));
    }

    @Test
    void aConnectedPlayerIsNeverReplacedByTheirSavedFile() throws IOException {
        String skinId = storeSkin(player, 9);
        assertTrue(players.trySetAppearance(new PlayerAppearance(player, skinId, "", "slim")));
        save(new PlayerAppearance(player, "", "", "classic"));

        assertFalse(players.keepOfflineAppearance(new PlayerAppearance(player, skinId, "", "classic")));
        appearances.loadOfflineAppearances();

        assertEquals("slim", players.getAppearance(player).getModel());
        assertTrue(textures.isPinned(hashOf(skinId)));
    }

    /** Stores a skin owned by the player and returns its appearance id. */
    private String storeSkin(UUID owner, long seed) throws IOException {
        byte[] skin = noise(64, 64, seed);
        ContentAliases aliases = ContentAliases.forBytes(skin);
        assertTrue(textures.storeTexture(aliases.sha1(), owner, "skin", skin));
        return "local_skin:" + aliases.sha256();
    }

    private void save(PlayerAppearance appearance) throws IOException {
        Files.createDirectories(directory());
        Files.writeString(directory().resolve(appearance.getPlayerId() + ".json"),
                "{\"playerId\": \"" + appearance.getPlayerId() + "\", \"skinId\": \""
                        + appearance.getSkinId() + "\", \"capeId\": \"" + appearance.getCapeId()
                        + "\", \"model\": \"" + appearance.getModel() + "\"}");
    }

    private Path directory() {
        return world.resolve("quickskin").resolve("appearances");
    }

    private static String hashOf(String appearanceId) {
        return appearanceId.substring(appearanceId.indexOf(':') + 1);
    }

    private static byte[] noise(int width, int height, long seed) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Random random = new Random(seed);
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
