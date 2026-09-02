package com.quickskin.mod.common.util;

import com.quickskin.mod.common.data.SkinResolution;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Model detection reads the alpha of the two outer arm columns, so it is the one skin path that
 * a deliberately opaque fixture can never exercise. The packaged scenarios build exactly such
 * fixtures on purpose, to keep slim/classic deterministic, which leaves this heuristic to be
 * pinned here across every supported resolution.
 */
class SkinModelDetectorTest {
    @TempDir
    Path temporaryDirectory;

    /** The opaque base colour. Deliberately not pure black, which the detector counts as empty. */
    private static final Color OPAQUE_BASE = new Color(0x3A, 0x7C, 0xC8);

    private static BufferedImage skin(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(OPAQUE_BASE);
        graphics.fillRect(0, 0, size, size);
        graphics.dispose();
        return image;
    }

    /** Clear the outer arm columns the detector samples, scaled exactly like the detector does. */
    private static void clearArmColumns(BufferedImage image, int scale, int argb) {
        fill(image, 54 * scale, 56 * scale, 20 * scale, 32 * scale, argb);
        fill(image, 46 * scale, 48 * scale, 52 * scale, 64 * scale, argb);
    }

    private static void fill(
            BufferedImage image, int startX, int endX, int startY, int endY, int argb) {
        for (int y = startY; y < endY; y++) {
            for (int x = startX; x < endX; x++) {
                image.setRGB(x, y, argb);
            }
        }
    }

    private static byte[] png(BufferedImage image) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        ImageIO.write(image, "png", buffer);
        return buffer.toByteArray();
    }

    @Test
    void detectsSlimWhenBothOuterArmColumnsAreTransparent() {
        BufferedImage image = skin(64);
        clearArmColumns(image, 1, 0x00000000);

        assertEquals("slim", SkinModelDetector.detectSkinModel(image));
    }

    @Test
    void detectsClassicWhenTheArmColumnsAreFullyPainted() {
        assertEquals("classic", SkinModelDetector.detectSkinModel(skin(64)));
    }

    @Test
    void treatsOpaquePureBlackAsTheSlimMaskItUsuallyIs() {
        BufferedImage image = skin(64);
        clearArmColumns(image, 1, 0xFF000000);

        // Fully opaque, yet the documented heuristic reads pure black as an unused arm column.
        assertEquals("slim", SkinModelDetector.detectSkinModel(image));
    }

    @Test
    void requiresStrictlyMoreThanHalfTheSampledColumnToBeEmpty() {
        BufferedImage image = skin(64);
        // Exactly one of the two equally sized columns, so the ratio lands on 0.5 and must not
        // tip the strict "> 0.5" threshold.
        fill(image, 54, 56, 20, 32, 0x00000000);

        assertEquals("classic", SkinModelDetector.detectSkinModel(image));

        fill(image, 46, 48, 52, 53, 0x00000000);
        assertEquals("slim", SkinModelDetector.detectSkinModel(image));
    }

    @Test
    void scalesTheSampledColumnsForEverySupportedHdResolution() {
        for (SkinResolution resolution : new SkinResolution[] {
            SkinResolution.HD_128, SkinResolution.HD_256, SkinResolution.HD_512
        }) {
            int size = resolution.getWidth();
            int scale = resolution.getScale();

            BufferedImage classic = skin(size);
            assertEquals(
                    "classic",
                    SkinModelDetector.detectSkinModel(classic),
                    resolution + " opaque skin must stay classic");

            BufferedImage slim = skin(size);
            clearArmColumns(slim, scale, 0x00000000);
            assertEquals(
                    "slim",
                    SkinModelDetector.detectSkinModel(slim),
                    resolution + " transparent arm columns must detect slim");
        }
    }

    @Test
    void hdDetectionIgnoresTransparencyOutsideTheScaledArmColumns() {
        // A 2x skin whose *1x* arm coordinates are cleared proves the scale is applied: reading
        // the unscaled columns would see an empty arm and wrongly answer slim.
        BufferedImage image = skin(128);
        clearArmColumns(image, 1, 0x00000000);

        assertEquals("classic", SkinModelDetector.detectSkinModel(image));
    }

    @Test
    void legacySkinsAreAlwaysClassicEvenWhenFullyTransparent() {
        BufferedImage image = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB);

        assertEquals(SkinResolution.LEGACY, SkinResolution.fromDimensions(64, 32));
        assertEquals("classic", SkinModelDetector.detectSkinModel(image));
    }

    @Test
    void detectsSlimThroughEveryDecodedEntryPoint() throws IOException {
        BufferedImage image = skin(64);
        clearArmColumns(image, 1, 0x00000000);
        byte[] encoded = png(image);
        Path file = temporaryDirectory.resolve("slim.png");
        Files.write(file, encoded);

        assertEquals("slim", SkinModelDetector.detectSkinModel(encoded));
        assertEquals("slim", SkinModelDetector.detectSkinModel(file.toFile()));
        assertEquals("slim", SkinModelDetector.detectSkinModel(new ByteArrayInputStream(encoded)));
    }

    @Test
    void everyUnreadableInputFallsBackToClassicInsteadOfThrowing() {
        assertEquals("classic", SkinModelDetector.detectSkinModel((BufferedImage) null));
        assertEquals("classic", SkinModelDetector.detectSkinModel((java.io.File) null));
        assertEquals("classic", SkinModelDetector.detectSkinModel(new byte[] {1, 2, 3}));
        assertEquals("classic", SkinModelDetector.detectSkinModel(new byte[0]));
        assertEquals(
                "classic",
                SkinModelDetector.detectSkinModel(new ByteArrayInputStream(new byte[] {9, 9})));
        assertEquals("classic", SkinModelDetector.detectSkinModel((java.io.InputStream) null));
    }
}
