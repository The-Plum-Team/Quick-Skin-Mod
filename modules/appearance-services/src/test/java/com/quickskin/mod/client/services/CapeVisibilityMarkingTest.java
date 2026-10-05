package com.quickskin.mod.client.services;

import com.quickskin.mod.client.storage.NetworkTextureCache;
import com.quickskin.mod.common.data.AssetMetadata;
import com.quickskin.mod.common.data.SkinResolution;
import com.quickskin.mod.networking.TextureRequestCoordinator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives {@link CapeAnimationHelper#markCapeVisible} against the real animation manager, network
 * texture cache and local catalogue, and checks when a cape counts as marked for the current
 * visibility epoch.
 *
 * <p>Marking stamps the animation slot and the working-set entry with the current client tick.
 * A cape that stayed marked across a tick would keep its old stamps: a visible network cape would
 * lose working-set protection after the 40-tick lease and its animation slot after the
 * 20-tick visibility grace. Both client ticks must therefore make every cape mark again, and a
 * network cape whose texture is not resident must never be recorded as marked.</p>
 */
class CapeVisibilityMarkingTest {

    /** The player's own catalogued cape, which the network cache does not hold. */
    private static final String OWN_CAPE = "sha256-" + "c".repeat(64);
    /** A network cape whose texture has not arrived (or was evicted), and is not catalogued. */
    private static final String PENDING_CAPE = "sha256-" + "d".repeat(64);
    /** A catalogued skin: the catalogue holds the ID, but not as a cape. */
    private static final String CATALOGUED_SKIN = "sha256-" + "e".repeat(64);

    private final CapeRenderKeys keys = CapeRenderKeys.shared();
    private Object previousCatalog;

    @BeforeEach
    void installCatalogue() throws ReflectiveOperationException {
        Path capePath = Path.of("own-cape.png");
        Path skinPath = Path.of("skin.png");
        AssetMetadata cape = AssetMetadata.forCape(
                OWN_CAPE, "own cape", capePath, SkinResolution.STANDARD, 128L, 1L);
        AssetMetadata skin = AssetMetadata.forSkin(
                CATALOGUED_SKIN, "skin", skinPath, SkinResolution.STANDARD, 128L, "classic", 1L);
        Field catalog = catalogField();
        previousCatalog = catalog.get(LocalAssetManager.getInstance());
        catalog.set(LocalAssetManager.getInstance(), LocalAssetManager.CatalogSnapshot.copyOf(
                Map.of(OWN_CAPE, cape, CATALOGUED_SKIN, skin),
                Map.of(OWN_CAPE, capePath, CATALOGUED_SKIN, skinPath)));
    }

    @AfterEach
    void restoreCatalogue() throws ReflectiveOperationException {
        catalogField().set(LocalAssetManager.getInstance(), previousCatalog);
    }

    @Test
    void aVisibleCapeIsMarkedAgainAfterTheAnimationTick() {
        CapeRenderKey cape = markedKnownCape();

        AnimatedTextureManager.getInstance().tick();
        assertFalse(isMarked(cape), "the animation tick stamps slot visibility per tick");

        CapeAnimationHelper.markCapeVisible(cape.capeId());
        assertTrue(isMarked(cape));
    }

    @Test
    void aVisibleCapeIsMarkedAgainAfterTheWorkingSetTick() {
        CapeRenderKey cape = markedKnownCape();

        NetworkTextureCache.getInstance().tickWorkingSet();
        assertFalse(isMarked(cape), "the working-set tick stamps render use per tick");

        CapeAnimationHelper.markCapeVisible(cape.capeId());
        assertTrue(isMarked(cape));
    }

    @Test
    void aNetworkCapeThatIsNotResidentIsCheckedOnEveryFrame() {
        CapeRenderKey cape = key("local_cape:" + PENDING_CAPE);

        for (int frame = 0; frame < 3; frame++) {
            CapeAnimationHelper.markCapeVisible(cape.capeId());
            assertFalse(isMarked(cape),
                    "a cape not in the network cache must look for its texture again next frame");
        }
    }

    @Test
    void theOwnCataloguedCapeIsMarkedOncePerTick() {
        CapeRenderKey cape = key("local_cape:" + OWN_CAPE);

        CapeAnimationHelper.markCapeVisible(cape.capeId());
        assertTrue(isMarked(cape), "a catalogued cape is drawn from the catalogue");

        AnimatedTextureManager.getInstance().tick();
        assertFalse(isMarked(cape));
        CapeAnimationHelper.markCapeVisible(cape.capeId());
        assertTrue(isMarked(cape));

        NetworkTextureCache.getInstance().tickWorkingSet();
        assertFalse(isMarked(cape));
        CapeAnimationHelper.markCapeVisible(cape.capeId());
        assertTrue(isMarked(cape));
    }

    @Test
    void aCataloguedSkinIsNoCape() {
        CapeRenderKey cape = key("local_cape:" + CATALOGUED_SKIN);

        CapeAnimationHelper.markCapeVisible(cape.capeId());

        assertFalse(isMarked(cape), "CapeService never draws a catalogued skin as a cape");
    }

    @Test
    void removingMarkedStateMakesEveryCapeMarkAgain() {
        configureDisconnectedTextureRequests();
        CapeRenderKey cape = markedKnownCape();
        NetworkTextureCache.getInstance().clear();
        assertFalse(isMarked(cape), "clearing the network cache drops working-set marks");

        cape = markedKnownCape();
        AnimatedTextureManager.getInstance().clearAnimations();
        assertFalse(isMarked(cape), "clearing animations drops slot visibility");

        cape = markedKnownCape();
        AnimatedTextureManager.getInstance().unregisterAnimation(cape.animationId());
        assertFalse(isMarked(cape), "unregistering an animation forgets its slot visibility");
    }

    private CapeRenderKey markedKnownCape() {
        CapeRenderKey cape = key("known:minecon2016");
        CapeAnimationHelper.markCapeVisible(cape.capeId());
        assertTrue(isMarked(cape), "a bundled cape is marked on its first frame");
        return cape;
    }

    private CapeRenderKey key(String capeId) {
        CapeRenderKey key = keys.of(capeId);
        assertNotNull(key);
        return key;
    }

    private boolean isMarked(CapeRenderKey cape) {
        return cape.isVisibilityMarkedIn(keys.visibilityEpoch());
    }

    /** {@link NetworkTextureCache#clear()} also resets texture requests, which need a session. */
    private static void configureDisconnectedTextureRequests() {
        try {
            TextureRequestCoordinator.configureConnection(() -> null);
        } catch (IllegalStateException alreadyConfigured) {
            // Another test in this JVM installed it first.
        }
    }

    private static Field catalogField() throws ReflectiveOperationException {
        Field field = LocalAssetManager.class.getDeclaredField("catalog");
        field.setAccessible(true);
        return field;
    }
}
