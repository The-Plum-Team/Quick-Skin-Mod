package com.quickskin.mod.client.importing;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A skin Mojang serves is recognised as a default one by its pixels alone. */
class MinecraftDefaultSkinsTest {
    private static BufferedImage skin(int seed, int hiddenColour) {
        BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                // The bottom half is transparent, with whatever colour hides behind alpha 0
                int argb = y < 32 ? 0xFF000000 | ((x * 4 + seed) & 0xFF) << 16 | (y * 8) << 8 | seed : hiddenColour & 0x00FFFFFF;
                image.setRGB(x, y, argb);
            }
        }
        return image;
    }

    @Test
    void theSamePixelsMatchWhateverHidesBehindTransparentOnes() {
        List<int[]> defaults = List.of(MinecraftDefaultSkins.pixels(skin(3, 0x000000)));
        assertTrue(MinecraftDefaultSkins.matches(skin(3, 0xABCDEF), defaults));
    }

    @Test
    void anotherSkinDoesNotMatch() {
        List<int[]> defaults = List.of(MinecraftDefaultSkins.pixels(skin(3, 0)));
        assertFalse(MinecraftDefaultSkins.matches(skin(4, 0), defaults));
    }

    @Test
    void onlyA64By64SkinCanMatch() {
        List<int[]> defaults = List.of(MinecraftDefaultSkins.pixels(skin(3, 0)));
        assertFalse(MinecraftDefaultSkins.matches(new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB), defaults));
        assertFalse(MinecraftDefaultSkins.matches(null, defaults));
    }
}
