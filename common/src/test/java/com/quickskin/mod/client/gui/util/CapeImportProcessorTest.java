package com.quickskin.mod.client.gui.util;

import com.quickskin.mod.common.util.CapeElytraSilhouette;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CapeImportProcessorTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void acceptsOnlyPngAndGifFileNames() {
        assertTrue(CapeImportProcessor.isSupported(Path.of("cape.PNG")));
        assertTrue(CapeImportProcessor.isSupported(Path.of("animated.GiF")));
        assertFalse(CapeImportProcessor.isSupported(Path.of("cape.webp")));
        assertFalse(CapeImportProcessor.isSupported(null));
    }

    @Test
    void preparesAValidStandardPng() throws IOException {
        Path source = temporaryDirectory.resolve("cape.png");
        BufferedImage image = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(2, 3, 0xff123456);
        assertTrue(ImageIO.write(image, "png", source.toFile()));

        CapeImportProcessor.PreparedCape prepared = CapeImportProcessor.prepare(source);

        assertEquals(source, prepared.source());
        assertEquals(64, prepared.atlas().getWidth());
        assertEquals(32, prepared.atlas().getHeight());
        assertEquals(1, prepared.frameCount());
        assertFalse(prepared.gif());
        assertTrue(prepared.standardFormat());
    }

    @Test
    void rejectsNonPngBytesEvenWhenTheFileIsRenamedToPng() throws IOException {
        Path impostor = temporaryDirectory.resolve("not-really-a-cape.png");
        Files.writeString(impostor, "this is not PNG data");

        assertThrows(IOException.class, () -> CapeImportProcessor.prepare(impostor));
    }

    @Test
    void allocatesCollisionFreeNamesInsideTheTargetDirectory() throws IOException {
        Path destination = temporaryDirectory.resolve("capes");
        Files.createDirectories(destination);
        Files.createFile(destination.resolve("cape.png"));
        Files.createFile(destination.resolve("cape_1.png"));

        Path target = CapeImportProcessor.uniqueTarget(
                Path.of("..", "untrusted", "cape.gif"), destination, ".png");

        assertEquals(destination.toAbsolutePath().normalize().resolve("cape_2.png"), target);
        assertTrue(target.startsWith(destination.toAbsolutePath().normalize()));
    }

    @Test
    void compositesVanillaElytraOnlyWhenTheCapeAreaIsTransparent() throws IOException {
        BufferedImage transparentCape = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB);
        BufferedImage vanillaElytra = new BufferedImage(16, 8, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = vanillaElytra.createGraphics();
        try {
            graphics.setColor(new java.awt.Color(0xffcc3300, true));
            graphics.fillRect(0, 0, vanillaElytra.getWidth(), vanillaElytra.getHeight());
        } finally {
            graphics.dispose();
        }

        assertTrue(CapeImportProcessor.isElytraAreaTransparent(transparentCape));
        BufferedImage composite = CapeImportProcessor.compositeElytraIfNeeded(
                transparentCape, 1, vanillaElytra);
        assertEquals(0xffcc3300, composite.getRGB(0, 0));

        transparentCape.setRGB(36, 2, 0xff00aa00);
        assertFalse(CapeImportProcessor.isElytraAreaTransparent(transparentCape));
        assertSame(
                transparentCape,
                CapeImportProcessor.compositeElytraIfNeeded(
                        transparentCape, 1, vanillaElytra));
    }

    @Test
    void opaqueCapeImportsReceiveATaperedElytraCutout() throws IOException {
        BufferedImage opaqueCape = opaqueAtlas(256, 128, 1);

        BufferedImage masked = CapeImportProcessor.compositeElytraIfNeeded(
                opaqueCape, 1, null);

        assertFalse(masked == opaqueCape, "an invalid opaque atlas needs a presentation copy");
        assertTrue(CapeElytraSilhouette.hasRequiredCutout(masked, 1));
        assertEquals(0x00000000, masked.getRGB(45 * 4, 2 * 4));
        assertEquals(0x00000000, masked.getRGB(24 * 4, 2 * 4),
                "the unused inner face must not expose the opaque canvas");
        assertEquals(0xff117788, masked.getRGB(36 * 4, 2 * 4));
        assertEquals(0xff117788, masked.getRGB(1, 1), "cape pixels must stay untouched");
        assertFalse(CapeElytraSilhouette.hasRequiredCutout(opaqueCape, 1),
                "the caller-owned source must not be mutated");
    }

    @Test
    void everyAnimatedFrameReceivesTheSameCutout() throws IOException {
        BufferedImage atlas = opaqueAtlas(128, 128, 2);

        BufferedImage masked = CapeImportProcessor.compositeElytraIfNeeded(atlas, 2, null);

        assertTrue(CapeElytraSilhouette.hasRequiredCutout(masked, 2));
        assertEquals(0x00000000, masked.getRGB(45 * 2, 2 * 2));
        assertEquals(0x00000000, masked.getRGB(45 * 2, 64 + 2 * 2));
        assertEquals(0xff117788, masked.getRGB(36 * 2, 64 + 2 * 2));
    }

    @Test
    void exactElytraFaceScanIgnoresPaddingButFindsRealContent() {
        BufferedImage cape = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB);
        cape.setRGB(63, 31, 0xffffffff);
        assertTrue(CapeImportProcessor.isElytraAreaTransparent(cape));

        cape.setRGB(24, 2, 0xffffffff);
        assertTrue(CapeImportProcessor.isElytraAreaTransparent(cape),
                "pixels outside vanilla's Elytra envelope are not renderable content");

        cape.setRGB(36, 2, 0xffffffff);
        assertFalse(CapeImportProcessor.isElytraAreaTransparent(cape));
    }

    private static BufferedImage opaqueAtlas(int width, int height, int frameCount) {
        BufferedImage atlas = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = atlas.createGraphics();
        try {
            graphics.setColor(new java.awt.Color(0xff117788, true));
            graphics.fillRect(0, 0, width, height);
        } finally {
            graphics.dispose();
        }
        assertEquals(height, width / 2 * frameCount);
        return atlas;
    }
}
