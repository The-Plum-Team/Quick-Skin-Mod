package com.quickskin.mod.client.services;

import com.quickskin.mod.common.util.SafeImageReader;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The catalog scan rewrites a resized skin or cape in place, which may be the player's only copy. */
class CatalogPngReplaceTest {
    @TempDir Path directory;

    @Test
    void aResizedImageAtomicallyReplacesTheFileAndLeavesNoTemporaryFile() throws Exception {
        Path skin = directory.resolve("skin.png");
        ImageIO.write(new BufferedImage(70, 70, BufferedImage.TYPE_INT_ARGB), "PNG", skin.toFile());

        BufferedImage resized = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        resized.setRGB(3, 5, 0x80123456);
        LocalAssetManager.replaceWithPng(skin, resized);

        BufferedImage stored = SafeImageReader.readPng(skin);
        assertEquals(64, stored.getWidth());
        assertEquals(0x80123456, stored.getRGB(3, 5));
        try (Stream<Path> files = Files.list(directory)) {
            assertEquals(1, files.count());
        }
    }

    @Test
    void aFailedReplacementKeepsTheOriginal() throws Exception {
        Path skin = directory.resolve("skin.png");
        ImageIO.write(new BufferedImage(70, 70, BufferedImage.TYPE_INT_ARGB), "PNG", skin.toFile());
        byte[] original = Files.readAllBytes(skin);
        Path link = directory.resolve("link.png");
        try {
            Files.createSymbolicLink(link, skin);
        } catch (UnsupportedOperationException | java.io.IOException unsupported) {
            Assumptions.abort("symbolic links are unavailable here");
        }

        assertThrows(java.io.IOException.class, () -> LocalAssetManager.replaceWithPng(
                link, new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB)));
        assertArrayEquals(original, Files.readAllBytes(skin));
    }
}
