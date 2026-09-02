package com.quickskin.mod.common.util;

import com.quickskin.mod.common.data.SkinResolution;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HD skins and skin transparency are both live production paths — the import snaps a source to a
 * supported resolution, and a user setting can flatten alpha — yet every packaged fixture is a
 * deliberately opaque 64x64. These pin the resolution and alpha behaviour those fixtures skip.
 */
class HdSkinTransparencyTest {

    private static final Color OPAQUE_BASE = new Color(0x3A, 0x7C, 0xC8);

    private static BufferedImage opaqueSkin(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(OPAQUE_BASE);
        graphics.fillRect(0, 0, size, size);
        graphics.dispose();
        return image;
    }

    private static void clearArmColumns(BufferedImage image, int scale) {
        fill(image, 54 * scale, 56 * scale, 20 * scale, 32 * scale);
        fill(image, 46 * scale, 48 * scale, 52 * scale, 64 * scale);
    }

    private static void fill(BufferedImage image, int startX, int endX, int startY, int endY) {
        for (int y = startY; y < endY; y++) {
            for (int x = startX; x < endX; x++) {
                image.setRGB(x, y, 0x00000000);
            }
        }
    }

    private static int alpha(BufferedImage image, int x, int y) {
        return (image.getRGB(x, y) >> 24) & 0xFF;
    }

    @Test
    void everySupportedHdSkinSizeIsAnExactResolutionSoImportKeepsItVerbatim() {
        int[][] sizes = {{128, 128}, {256, 256}, {512, 512}, {1024, 1024}, {2048, 2048}};
        for (int[] size : sizes) {
            SkinResolution resolution = SkinResolution.fromDimensions(size[0], size[1]);

            assertNotNull(resolution, size[0] + " must be a supported HD skin resolution");
            assertTrue(resolution.isHD(), resolution + " must report itself as HD");
            assertEquals(size[0] / 64, resolution.getScale(), resolution + " scale");
            // An exact match is what keeps the importer from rewriting the file at all.
            assertSame(resolution, SkinResolution.findNearest(size[0], size[1]));
        }
    }

    @Test
    void nonStandardSquareSizesSnapToTheNearestSupportedResolution() {
        assertNull(SkinResolution.fromDimensions(100, 100));
        assertSame(SkinResolution.HD_128, SkinResolution.findNearest(100, 100));
        assertSame(SkinResolution.HD_256, SkinResolution.findNearest(200, 200));
        assertSame(SkinResolution.STANDARD, SkinResolution.findNearest(70, 70));
    }

    @Test
    void resizingPreservesExactAlphaAndColourWithoutFringing() {
        BufferedImage source = opaqueSkin(64);
        clearArmColumns(source, 1);

        BufferedImage resized = HDTextureProcessor.resizeToResolution(source, SkinResolution.HD_128);

        assertEquals(128, resized.getWidth());
        assertEquals(128, resized.getHeight());
        assertEquals(BufferedImage.TYPE_INT_ARGB, resized.getType());
        // Nearest-neighbour must produce only the two source alphas, never an interpolated edge.
        for (int y = 0; y < 128; y++) {
            for (int x = 0; x < 128; x++) {
                int a = alpha(resized, x, y);
                assertTrue(a == 0 || a == 255, "unexpected intermediate alpha " + a + " at " + x + "," + y);
            }
        }
        assertEquals(0, alpha(resized, 54 * 2, 20 * 2));
        assertEquals(255, alpha(resized, 0, 0));
        assertEquals(OPAQUE_BASE.getRGB(), resized.getRGB(0, 0));
    }

    @Test
    void anUpscaledSlimSkinStillDetectsSlim() {
        BufferedImage source = opaqueSkin(64);
        clearArmColumns(source, 1);

        BufferedImage resized = HDTextureProcessor.resizeToResolution(source, SkinResolution.HD_256);

        assertEquals("slim", SkinModelDetector.detectSkinModel(resized));
    }

    @Test
    void processingKeepsAlphaWhenTransparencyIsAllowed() throws IOException {
        BufferedImage source = opaqueSkin(128);
        clearArmColumns(source, 2);

        BufferedImage processed = decode(HDTextureProcessor.processHDSkin(source, true));

        assertEquals(128, processed.getWidth());
        assertEquals(0, alpha(processed, 54 * 2, 20 * 2));
        assertEquals("slim", SkinModelDetector.detectSkinModel(processed));
    }

    @Test
    void disablingTransparencyFlattensTheBodyButPreservesOverlayLayers() throws IOException {
        BufferedImage source = opaqueSkin(64);
        // A hat-overlay hole, which must survive, and a base-layer hole, which must not.
        source.setRGB(40, 4, 0x00000000);
        source.setRGB(4, 20, 0x00000000);

        BufferedImage processed = decode(HDTextureProcessor.processHDSkin(source, false));

        assertEquals(0, alpha(processed, 40, 4), "head overlay must keep its transparency");
        assertEquals(255, alpha(processed, 4, 20), "base layer must be flattened");
        assertEquals(0xFF000000, processed.getRGB(4, 20), "a flattened hole becomes opaque black");
    }

    @Test
    void flatteningTransparencyStillLeavesASlimSkinDetectableAsSlim() throws IOException {
        BufferedImage source = opaqueSkin(64);
        clearArmColumns(source, 1);

        BufferedImage processed = decode(HDTextureProcessor.processHDSkin(source, false));

        // The sampled arm columns are outside every overlay region, so they are flattened to
        // opaque black. Detection survives only because it counts pure black as an empty column;
        // that is what keeps the "disable skin transparency" setting from silently switching a
        // slim skin to the classic model.
        assertEquals(255, alpha(processed, 54, 20));
        assertEquals(0xFF000000, processed.getRGB(54, 20));
        assertEquals("slim", SkinModelDetector.detectSkinModel(processed));
    }

    @Test
    void legacySkinsAreConvertedToTheModernSquareLayout() throws IOException {
        BufferedImage legacy = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = legacy.createGraphics();
        graphics.setColor(OPAQUE_BASE);
        graphics.fillRect(0, 0, 64, 32);
        graphics.dispose();

        BufferedImage processed = decode(HDTextureProcessor.processHDSkin(legacy, true));

        assertEquals(64, processed.getWidth());
        assertEquals(64, processed.getHeight());
    }

    @Test
    void processingRefusesInputBeyondTheHardPixelCap() {
        assertNull(HDTextureProcessor.processHDSkin(
                new BufferedImage(2049, 64, BufferedImage.TYPE_INT_ARGB), true));
        assertNull(HDTextureProcessor.processHDSkin(
                new BufferedImage(64, 2049, BufferedImage.TYPE_INT_ARGB), true));
        assertNull(HDTextureProcessor.processHDSkin((BufferedImage) null, true));
    }

    private static BufferedImage decode(byte[] png) throws IOException {
        assertNotNull(png, "processing must produce PNG bytes");
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        assertNotNull(image, "processed bytes must decode as a PNG");
        return image;
    }
}
