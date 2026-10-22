package com.quickskin.mod.client.services;

import com.quickskin.mod.common.data.AssetMetadata;
import com.quickskin.mod.common.data.SkinResolution;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The skin list leaves out a hidden own skin, but never runs empty because of it. */
class HiddenOwnSkinTest {
    private static final AssetMetadata OWN = skin('1', "own");
    private static final AssetMetadata OTHER = skin('2', "other");

    @Test
    void aHiddenOwnSkinLeavesTheListWhileAnotherSkinIsListed() {
        assertEquals(List.of(OTHER), LocalAssetManager.withoutOwnSkin(List.of(OWN, OTHER), OWN.hash()));
    }

    @Test
    void aHiddenOwnSkinStaysListedWhenItIsTheOnlySkin() {
        assertEquals(List.of(OWN), LocalAssetManager.withoutOwnSkin(List.of(OWN), OWN.hash()));
    }

    @Test
    void withoutAnOwnSkinEverySkinIsListed() {
        assertEquals(List.of(OWN, OTHER), LocalAssetManager.withoutOwnSkin(List.of(OWN, OTHER), null));
    }

    private static AssetMetadata skin(char digit, String name) {
        return AssetMetadata.forSkin("sha256-" + String.valueOf(digit).repeat(64), name,
                Path.of(name + ".png"), SkinResolution.STANDARD, 128L, "classic", 1L);
    }
}
