package com.quickskin.mod.common.util;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ComponentColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.IndexColorModel;
import java.awt.image.WritableRaster;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Customizable Player Models (CPM) can store a whole model definition inside the texels of a skin
 * that no vanilla model face samples. After its first 48 bytes CPM also uses the alpha byte of
 * those texels, so the skin import must carry every texel through bit for bit, colour included
 * where alpha is below 255, and transparency flattening must keep alpha wherever vanilla does.
 * The fixtures are written by CPM's own writer; see {@code src/test/resources/cpm/README.txt}.
 */
class CpmEmbeddedSkinImportTest {

    private static final String FULL = "cpm-embedded-full";
    private static final String CAPACITY = "cpm-embedded-capacity-classic";
    private static final int HOLE_X = 4;
    private static final int HOLE_Y = 20;

    /** Vanilla forces opacity only on these 1x-scale rectangles (x0, y0, x1, y1, exclusive). */
    private static final int[][] VANILLA_OPAQUE_REGIONS = {{0, 0, 32, 16}, {0, 16, 64, 32}, {16, 48, 48, 64}};

    @Test
    void importKeepsEmbeddedModelExact() {
        BufferedImage source = readSkin(FULL + ".png");
        BufferedImage imported = importSkin(source, true);

        assertPayload(imported, freeSpace("classic"), resourceBytes(FULL + ".payload.bin"));
        assertPixelsEqual(source, imported, "whole skin");
    }

    @Test
    void importKeepsFullCapacityPayloadExact() {
        BufferedImage source = readSkin(CAPACITY + ".png");
        byte[] payload = resourceBytes(CAPACITY + ".payload.bin");
        assertEquals(2912, payload.length, "the fixture fills the whole classic capacity");
        assertPayload(source, freeSpace("classic"), payload); // the fixture itself is sound

        BufferedImage imported = importSkin(source, true);

        assertPayload(imported, freeSpace("classic"), payload);
        assertPixelsEqual(source, imported, "whole skin");
    }

    @Test
    void flatteningLeavesEveryCpmPixelExact() {
        BufferedImage source = readSkin(CAPACITY + ".png");
        char[][] map = freeSpace("classic");
        assertEquals('.', map[HOLE_Y][HOLE_X], "the hole is not a CPM texel");
        assertEquals(0, source.getRGB(HOLE_X, HOLE_Y) >>> 24, "the fixture has a base-layer hole");

        BufferedImage flattened = importSkin(source, false);

        List<String> changed = new ArrayList<>();
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                if (map[y][x] != '.' && source.getRGB(x, y) != flattened.getRGB(x, y)) {
                    changed.add("(" + x + "," + y + ")");
                }
            }
        }
        assertTrue(changed.isEmpty(), changed.size() + " CPM texels changed, first " + first(changed));
        assertPayload(flattened, map, resourceBytes(CAPACITY + ".payload.bin"));
        assertEquals(0xFF000000, flattened.getRGB(HOLE_X, HOLE_Y), "a base-layer hole is still flattened");
    }

    @Test
    void flattenMaskIsVanillaSetNoAlpha() {
        for (int scale : new int[] {1, 2}) {
            int size = 64 * scale;
            BufferedImage translucent = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    translucent.setRGB(x, y, 0x40123456);
                }
            }

            BufferedImage flattened = HDTextureProcessor.removeTransparency(translucent);

            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    boolean forcedOpaque = vanillaForcesOpacity(x / scale, y / scale);
                    int expected = forcedOpaque ? 0xFF123456 : 0x40123456;
                    if (flattened.getRGB(x, y) != expected) {
                        fail("scale " + scale + " texel (" + x / scale + "," + y / scale + ") must "
                                + (forcedOpaque ? "become opaque" : "keep its alpha") + " as in vanilla, got "
                                + Integer.toHexString(flattened.getRGB(x, y)));
                    }
                }
            }
        }

        for (String model : new String[] {"classic", "slim"}) {
            char[][] map = freeSpace(model);
            for (int y = 0; y < 64; y++) {
                for (int x = 0; x < 64; x++) {
                    if (map[y][x] == '4') {
                        assertTrue(!vanillaForcesOpacity(x, y), model + " four-byte texel (" + x + "," + y
                                + ") must keep its alpha");
                    } else if (map[y][x] == '3') {
                        // Three-byte texels are always written opaque, so flattening cannot alter them.
                        assertTrue(vanillaForcesOpacity(x, y), model + " three-byte texel (" + x + "," + y + ")");
                    }
                }
            }
        }
    }

    @Test
    void conversionIsExactForEveryDecodeType() throws IOException {
        Random random = new Random(0x5EEDL);
        int[] translucent = new int[64 * 64];
        for (int i = 0; i < translucent.length; i++) {
            translucent[i] = random.nextInt(); // every alpha, including 0 with colour behind it
        }

        BufferedImage rgba = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        rgba.setRGB(0, 0, 64, 64, translucent, 0, 64);

        BufferedImage rgb = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        rgb.setRGB(0, 0, 64, 64, translucent, 0, 64);

        byte[] reds = new byte[256];
        byte[] greens = new byte[256];
        byte[] blues = new byte[256];
        byte[] alphas = new byte[256];
        for (int i = 0; i < 256; i++) {
            reds[i] = (byte) (i * 7);
            greens[i] = (byte) (255 - i);
            blues[i] = (byte) (i * 13);
            alphas[i] = (byte) i;
        }
        BufferedImage indexed = new BufferedImage(64, 64, BufferedImage.TYPE_BYTE_INDEXED,
                new IndexColorModel(8, 256, reds, greens, blues, alphas));
        WritableRaster indexRaster = indexed.getRaster();
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                indexRaster.setSample(x, y, 0, (x * 64 + y * 3) & 0xFF);
            }
        }

        ComponentColorModel grayAlphaModel = new ComponentColorModel(ColorSpace.getInstance(ColorSpace.CS_GRAY),
                true, false, java.awt.Transparency.TRANSLUCENT, DataBuffer.TYPE_BYTE);
        WritableRaster grayAlphaRaster = grayAlphaModel.createCompatibleWritableRaster(64, 64);
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                grayAlphaRaster.setSample(x, y, 0, (x * 4 + y) & 0xFF);
                grayAlphaRaster.setSample(x, y, 1, (y * 4 + x * 3) & 0xFF);
            }
        }
        BufferedImage grayAlpha = new BufferedImage(grayAlphaModel, grayAlphaRaster, false, null);

        BufferedImage[] sources = {rgba, rgb, indexed, grayAlpha};
        String[] names = {"RGBA", "RGB", "indexed+tRNS", "gray+alpha"};
        for (int i = 0; i < sources.length; i++) {
            BufferedImage decoded = SafeImageReader.readSkin(png(sources[i]));
            assertTrue(decoded.getType() != BufferedImage.TYPE_INT_ARGB, names[i] + " must take the conversion path");
            assertPixelsEqual(decoded, importSkin(decoded, true), names[i]);
        }

        // An opaque skin must encode exactly as before, so its content id does not move.
        for (BufferedImage opaque : new BufferedImage[] {
                SafeImageReader.readSkin(png(rgb)), SafeImageReader.readSkin(png(opaqueArgb(translucent)))}) {
            BufferedImage composited = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = composited.createGraphics();
            graphics.drawImage(opaque, 0, 0, null);
            graphics.dispose();
            assertArrayEquals(HDTextureProcessor.imageToPng(composited),
                    HDTextureProcessor.processHDSkin(opaque, true),
                    "opaque decode type " + opaque.getType() + " must keep its canonical bytes");
        }
    }

    @Test
    void legacyHatRuleUnchanged() throws IOException {
        BufferedImage legacy = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 32; y++) {
            for (int x = 0; x < 64; x++) {
                legacy.setRGB(x, y, 0xFF3A7CC8);
            }
        }
        // Hat: opaque black texels mixed with fully transparent texels that still carry a colour.
        for (int y = 0; y < 16; y++) {
            for (int x = 32; x < 64; x++) {
                legacy.setRGB(x, y, (x + y) % 2 == 0 ? 0xFF000000 : 0x00FF00FF);
            }
        }
        BufferedImage decoded = SafeImageReader.readSkin(png(legacy));
        assertEquals(BufferedImage.TYPE_4BYTE_ABGR, decoded.getType());

        BufferedImage modern = importSkin(decoded, true);

        assertEquals(64, modern.getHeight(), "a legacy skin is converted to the modern layout");
        for (int y = 0; y < 16; y++) {
            for (int x = 32; x < 64; x++) {
                assertEquals(0, modern.getRGB(x, y), "hat texel (" + x + "," + y + ") must be cleared");
            }
        }
        assertEquals(0xFF3A7CC8, modern.getRGB(8, 8), "the head base layer is kept");
    }

    private static boolean vanillaForcesOpacity(int x, int y) {
        for (int[] region : VANILLA_OPAQUE_REGIONS) {
            if (x >= region[0] && y >= region[1] && x < region[2] && y < region[3]) {
                return true;
            }
        }
        return false;
    }

    private static BufferedImage opaqueArgb(int[] pixels) {
        BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        for (int i = 0; i < pixels.length; i++) {
            image.setRGB(i % 64, i / 64, pixels[i] | 0xFF000000);
        }
        return image;
    }

    private static BufferedImage importSkin(BufferedImage source, boolean allowTransparency) {
        byte[] stored = HDTextureProcessor.processHDSkin(source, allowTransparency);
        assertNotNull(stored, "the import must accept the skin");
        try {
            return SafeImageReader.readPng(stored);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Reads the payload back in CPM's order: row-major, B, G, R, and then A only on four-byte texels. */
    private static byte[] extractPayload(BufferedImage image, char[][] map, int length) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int y = 0; y < 64 && out.size() < length; y++) {
            for (int x = 0; x < 64 && out.size() < length; x++) {
                if (map[y][x] == '.') {
                    continue;
                }
                int argb = image.getRGB(x, y);
                int bytes = map[y][x] == '4' ? 4 : 3;
                for (int b = 0; b < bytes && out.size() < length; b++) {
                    out.write((argb >>> (8 * b)) & 0xFF);
                }
            }
        }
        return out.toByteArray();
    }

    private static void assertPayload(BufferedImage image, char[][] map, byte[] expected) {
        byte[] actual = extractPayload(image, map, expected.length);
        int mismatch = Arrays.mismatch(expected, actual);
        assertEquals(-1, mismatch, "embedded model differs first at byte " + mismatch + " of " + expected.length);
    }

    private static void assertPixelsEqual(BufferedImage expected, BufferedImage actual, String what) {
        assertEquals(expected.getWidth(), actual.getWidth(), what + " width");
        assertEquals(expected.getHeight(), actual.getHeight(), what + " height");
        List<String> changed = new ArrayList<>();
        for (int y = 0; y < expected.getHeight(); y++) {
            for (int x = 0; x < expected.getWidth(); x++) {
                if (expected.getRGB(x, y) != actual.getRGB(x, y)) {
                    changed.add("(" + x + "," + y + ") " + Integer.toHexString(expected.getRGB(x, y))
                            + "->" + Integer.toHexString(actual.getRGB(x, y)));
                }
            }
        }
        assertTrue(changed.isEmpty(), what + ": " + changed.size() + " pixels changed, first " + first(changed));
    }

    private static String first(List<String> values) {
        return values.isEmpty() ? "none" : values.get(0);
    }

    private static char[][] freeSpace(String model) {
        String[] lines = new String(resourceBytes("free-space-" + model + ".txt"), StandardCharsets.US_ASCII)
                .strip().split("\\R");
        assertEquals(64, lines.length, model + " map rows");
        char[][] map = new char[64][];
        for (int y = 0; y < 64; y++) {
            assertEquals(64, lines[y].length(), model + " map row " + y);
            map[y] = lines[y].toCharArray();
        }
        return map;
    }

    private static BufferedImage readSkin(String resource) {
        try {
            return SafeImageReader.readSkin(resourceBytes(resource));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] png(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "PNG", out), "PNG writer for type " + image.getType());
        return out.toByteArray();
    }

    private static byte[] resourceBytes(String name) {
        try (InputStream in = CpmEmbeddedSkinImportTest.class.getResourceAsStream("/cpm/" + name)) {
            assertNotNull(in, "missing test resource cpm/" + name);
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
