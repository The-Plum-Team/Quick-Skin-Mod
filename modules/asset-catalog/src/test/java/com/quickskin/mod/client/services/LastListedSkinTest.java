package com.quickskin.mod.client.services;

import com.quickskin.mod.common.data.AssetMetadata;
import com.quickskin.mod.common.data.SkinResolution;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The skin list always keeps one skin, whether that skin is the own skin or a custom one. */
class LastListedSkinTest {
    private static final AssetMetadata FIRST = skin('1', "first");
    private static final AssetMetadata SECOND = skin('2', "second");
    private static final AssetMetadata UNLISTED = skin('3', "unlisted");

    @Test
    void deletingOneOfTwoListedSkinsKeepsTheOther() {
        assertTrue(LocalAssetManager.keepsAListedSkin(List.of(FIRST, SECOND), Set.of(FIRST.hash())));
    }

    @Test
    void theOnlyListedSkinIsKept() {
        assertFalse(LocalAssetManager.keepsAListedSkin(List.of(FIRST), Set.of(FIRST.hash())));
    }

    @Test
    void deletingEveryListedSkinAtOnceIsRefused() {
        assertFalse(LocalAssetManager.keepsAListedSkin(List.of(FIRST, SECOND),
                Set.of(FIRST.hash(), SECOND.hash())));
    }

    @Test
    void aSkinMissingFromTheListDoesNotCountAsKept() {
        // A hidden own skin is catalogued but not listed: the last listed skin is still the last
        assertFalse(LocalAssetManager.keepsAListedSkin(List.of(FIRST), Set.of(FIRST.hash(), UNLISTED.hash())));
    }

    private static AssetMetadata skin(char digit, String name) {
        return AssetMetadata.forSkin("sha256-" + String.valueOf(digit).repeat(64), name,
                Path.of(name + ".png"), SkinResolution.STANDARD, 128L, "classic", 1L);
    }
}
