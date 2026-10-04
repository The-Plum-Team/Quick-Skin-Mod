package com.quickskin.mod.client.compat;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real Camera matches bind targets by substring, so the alias has to carry the vanilla skin marker
 * for Quick Skin skins only: a cape offered to the head target would be clipped by its head rule.
 */
class RealCameraCompatTest {

    private static final String VANILLA = RealCameraCompat.VANILLA_SKIN_PREFIX;
    private static final String CONTENT_ID = "sha256-" + "a".repeat(64);
    private static final String LOCAL_ID = "quickskin:local/" + CONTENT_ID + "_full";
    private static final String NETWORK_SKIN_ID = "quickskin:network/skin/" + "b".repeat(40);

    private static final Predicate<String> IS_SKIN = contentId -> true;
    private static final Predicate<String> NOT_A_SKIN = contentId -> false;
    private static final Predicate<String> CATALOG_NOT_ASKED = contentId -> {
        throw new AssertionError("the catalog must not be asked about " + contentId);
    };

    @Test
    void networkSkinIsAliasedWithoutAskingTheCatalog() {
        assertEquals(VANILLA + NETWORK_SKIN_ID,
                RealCameraCompat.aliasSkinTextureId(NETWORK_SKIN_ID, CATALOG_NOT_ASKED));
    }

    @Test
    void localIdIsAliasedOnlyWhenTheCatalogKnowsItAsASkin() {
        List<String> asked = new ArrayList<>();

        assertEquals(VANILLA + LOCAL_ID,
                RealCameraCompat.aliasSkinTextureId(LOCAL_ID, asked::add));
        assertEquals(List.of(CONTENT_ID), asked);
        // local/ also holds capes and CPM icons.
        assertEquals(LOCAL_ID, RealCameraCompat.aliasSkinTextureId(LOCAL_ID, NOT_A_SKIN));
    }

    @Test
    void anIdInsideAWholeRenderTypeStringIsStillFound() {
        String renderType = "RenderType[entity_translucent:CompositeState[texture[Optional["
                + LOCAL_ID + "], blur=false, mipmap=false]]]";
        List<String> asked = new ArrayList<>();

        assertEquals(VANILLA + renderType,
                RealCameraCompat.aliasSkinTextureId(renderType, asked::add));
        assertEquals(List.of(CONTENT_ID), asked);
    }

    @Test
    void everythingElseIsReturnedUnchanged() {
        for (String textureId : List.of(
                "quickskin:network/cape/" + "b".repeat(40),
                "quickskin:textures/capes/bmo.png",
                "quickskin:cpm_bridge/" + "b".repeat(40),
                "quickskin:local/",
                "quickskin:local/" + CONTENT_ID,
                "quickskin:local/_full",
                "minecraft:skins/" + "c".repeat(40),
                "minecraft:textures/entity/player/wide/steve.png")) {
            assertEquals(textureId,
                    RealCameraCompat.aliasSkinTextureId(textureId, CATALOG_NOT_ASKED));
        }
        assertNull(RealCameraCompat.aliasSkinTextureId(null, CATALOG_NOT_ASKED));
    }

    @Test
    void theAliasKeepsTheOriginalIdAndIsNotAppliedTwice() {
        String alias = RealCameraCompat.aliasSkinTextureId(LOCAL_ID, IS_SKIN);

        assertTrue(alias.contains(VANILLA));
        assertTrue(alias.contains(LOCAL_ID), "a target made for the quickskin: id must keep matching");
        assertEquals(alias, RealCameraCompat.aliasSkinTextureId(alias, CATALOG_NOT_ASKED));
    }
}
