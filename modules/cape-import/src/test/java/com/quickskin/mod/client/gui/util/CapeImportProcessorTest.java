package com.quickskin.mod.client.gui.util;

import com.quickskin.mod.common.util.CapeElytraSilhouette;
import com.quickskin.mod.common.util.GifDecoder;
import com.quickskin.mod.common.util.UserFacingIOException;
import com.quickskin.mod.common.data.AnimationMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CapeImportProcessorTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void acceptsOnlyPngJpgWebpAndGifFileNames() {
        assertTrue(CapeImportProcessor.isSupported(Path.of("cape.PNG")));
        assertTrue(CapeImportProcessor.isSupported(Path.of("animated.GiF")));
        assertTrue(CapeImportProcessor.isSupported(Path.of("cape.WebP")));
        assertTrue(CapeImportProcessor.isSupported(Path.of("photo.jpg")));
        assertTrue(CapeImportProcessor.isSupported(Path.of("photo.JPEG")));
        assertFalse(CapeImportProcessor.isSupported(Path.of("cape.bmp")));
        assertFalse(CapeImportProcessor.isSupported(Path.of("jpg")));
        assertFalse(CapeImportProcessor.isSupported(null));
    }

    @Test
    void aJpgGoesToTheEditorLikeAnyOtherPicture() throws IOException {
        Path source = temporaryDirectory.resolve("photo.jpeg");
        BufferedImage photo = new BufferedImage(120, 90, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = photo.createGraphics();
        graphics.setColor(new java.awt.Color(0x2060C0));
        graphics.fillRect(0, 0, 120, 90);
        graphics.dispose();
        assertTrue(ImageIO.write(photo, "jpg", source.toFile()));

        CapeImportProcessor.PreparedCape prepared = preparePng(source);

        assertEquals(120, prepared.atlas().getWidth());
        assertEquals(90, prepared.atlas().getHeight());
        assertEquals(1, prepared.frameCount());
        assertFalse(prepared.gif());
        assertFalse(prepared.standardFormat());
        // A JPG has no transparency: every pixel is opaque
        assertEquals(0xFF, prepared.atlas().getRGB(60, 45) >>> 24);
    }

    @Test
    void aPictureLargerThanACapeCanBeIsShrunkAndGoesToTheEditor() throws IOException {
        Path source = temporaryDirectory.resolve("camera.jpg");
        assertTrue(ImageIO.write(new BufferedImage(6000, 40, BufferedImage.TYPE_INT_RGB), "jpg", source.toFile()));

        CapeImportProcessor.PreparedCape prepared = preparePng(source);

        assertEquals(3000, prepared.atlas().getWidth());
        assertEquals(20, prepared.atlas().getHeight());
        assertFalse(prepared.standardFormat());
    }

    @Test
    void aPictureTooLargeToOpenIsRefusedWithItsSize() throws IOException {
        Path longSide = temporaryDirectory.resolve("endless.png");
        Files.write(longSide, pngDeclaring(20000, 10));
        Path manyPixels = temporaryDirectory.resolve("vast.png");
        Files.write(manyPixels, pngDeclaring(16000, 9000));

        UserFacingIOException side = assertThrows(UserFacingIOException.class, () -> preparePng(longSide));
        UserFacingIOException pixels = assertThrows(UserFacingIOException.class, () -> preparePng(manyPixels));

        assertEquals("quickskin.cape.error.dimensions", side.userMessage().translationKey());
        assertEquals(List.of("20000×10", "16384"), side.userMessage().args());
        assertEquals("quickskin.cape.error.too_big", pixels.userMessage().translationKey());
    }

    @Test
    void aJpgOfACapeTexturesSizeIsSavedAsAPngCape() throws IOException {
        Path source = temporaryDirectory.resolve("texture.jpg");
        assertTrue(ImageIO.write(new BufferedImage(64, 32, BufferedImage.TYPE_INT_RGB), "jpg", source.toFile()));
        Path capes = Files.createDirectories(temporaryDirectory.resolve("capes"));
        Path cache = Files.createDirectories(temporaryDirectory.resolve("cache"));

        CapeImportProcessor.PreparedCape prepared = preparePng(source);
        Path saved = CapeImportProcessor.saveStandard(prepared, capes, cache, null);

        assertTrue(prepared.standardFormat());
        assertEquals("texture.png", saved.getFileName().toString());
        BufferedImage cape = ImageIO.read(saved.toFile());
        assertEquals(64, cape.getWidth());
        assertEquals(32, cape.getHeight());
    }

    @Test
    void preparesAValidStandardPng() throws IOException {
        Path source = temporaryDirectory.resolve("cape.png");
        BufferedImage image = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(2, 3, 0xff123456);
        assertTrue(ImageIO.write(image, "png", source.toFile()));

        CapeImportProcessor.PreparedCape prepared = preparePng(source);

        assertEquals(source, prepared.source());
        assertEquals(64, prepared.atlas().getWidth());
        assertEquals(32, prepared.atlas().getHeight());
        assertEquals(1, prepared.frameCount());
        assertFalse(prepared.gif());
        assertTrue(prepared.standardFormat());
    }

    @Test
    void aGreyscalePngCapeKeepsItsRawGreySamples() throws IOException {
        java.awt.image.ComponentColorModel model = new java.awt.image.ComponentColorModel(
                java.awt.color.ColorSpace.getInstance(java.awt.color.ColorSpace.CS_GRAY), true, false,
                java.awt.Transparency.TRANSLUCENT, java.awt.image.DataBuffer.TYPE_BYTE);
        java.awt.image.WritableRaster raster = model.createCompatibleWritableRaster(64, 32);
        raster.setSample(2, 3, 0, 128);
        raster.setSample(2, 3, 1, 200);
        Path source = temporaryDirectory.resolve("grey.png");
        assertTrue(ImageIO.write(new BufferedImage(model, raster, false, null), "png", source.toFile()));

        CapeImportProcessor.PreparedCape prepared = preparePng(source);

        // getRGB would read grey 128 as 188; vanilla shows 128.
        assertEquals(0xC8808080, prepared.atlas().getRGB(2, 3));
    }

    @Test
    void preparesAValidStandardWebp() throws IOException {
        // A lossless 64x32 WebP, transparent but for an opaque red cape face at (1,1)-(10,16)
        Path source = temporaryDirectory.resolve("cape.webp");
        Files.write(source, java.util.Base64.getDecoder().decode(
                "UklGRiwAAABXRUJQVlA4TCAAAAAvP8AHEA8wHoM8HvMf8CDSthmbf8Nnh+MR/Z8A7qzrBg=="));

        CapeImportProcessor.PreparedCape prepared = preparePng(source);

        assertEquals(64, prepared.atlas().getWidth());
        assertEquals(32, prepared.atlas().getHeight());
        assertEquals(0xFFC81E1E, prepared.atlas().getRGB(5, 8));
        assertEquals(0, prepared.atlas().getRGB(40, 20) >>> 24);
        assertFalse(prepared.gif());
        assertTrue(prepared.standardFormat());
    }

    @Test
    void rejectsNonPngBytesEvenWhenTheFileIsRenamedToPng() throws IOException {
        Path impostor = temporaryDirectory.resolve("not-really-a-cape.png");
        Files.writeString(impostor, "this is not PNG data");

        assertThrows(IOException.class, () -> preparePng(impostor));
    }

    @Test
    void delegatesGifDecodingAndClosesTheOwnedStream() throws IOException {
        Path source = temporaryDirectory.resolve("cape.gif");
        Files.write(source, new byte[] {42});
        BufferedImage atlas = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        atlas.setRGB(2, 35, 0xff123456);
        AnimationMetadata metadata = new AnimationMetadata(List.of(
                new AnimationMetadata.FrameData(50, 0),
                new AnimationMetadata.FrameData(125, 1)), 2);
        AtomicReference<InputStream> ownedStream = new AtomicReference<>();

        CapeImportProcessor.PreparedCape prepared = CapeImportProcessor.prepare(source, input -> {
            ownedStream.set(input);
            assertEquals(42, input.read());
            return new GifDecoder.DecodedGif(atlas, 2, metadata);
        });

        assertSame(atlas, prepared.atlas());
        assertSame(metadata, prepared.animationMetadata());
        assertEquals(2, prepared.frameCount());
        assertTrue(prepared.gif());
        assertTrue(prepared.standardFormat());
        assertThrows(IOException.class, () -> ownedStream.get().read());
    }

    @Test
    void rejectsAnInconsistentDecodedAtlas() throws IOException {
        Path source = temporaryDirectory.resolve("invalid.gif");
        Files.write(source, new byte[] {42});
        GifDecoder decoder = input -> new GifDecoder.DecodedGif(
                new BufferedImage(64, 65, BufferedImage.TYPE_INT_ARGB), 2,
                new AnimationMetadata(List.of(new AnimationMetadata.FrameData(50, 0)), 2));

        assertThrows(IOException.class, () -> CapeImportProcessor.prepare(source, decoder));
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

    /** A 1x1 PNG whose header declares another size: enough for a refusal made before decoding. */
    private static byte[] pngDeclaring(int width, int height) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        assertTrue(ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), "png", out));
        byte[] encoded = out.toByteArray();
        java.nio.ByteBuffer.wrap(encoded).putInt(16, width).putInt(20, height);
        return encoded;
    }

    private static CapeImportProcessor.PreparedCape preparePng(Path source) throws IOException {
        return CapeImportProcessor.prepare(source, input -> {
            throw new AssertionError("PNG input must not invoke the native GIF decoder");
        });
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
