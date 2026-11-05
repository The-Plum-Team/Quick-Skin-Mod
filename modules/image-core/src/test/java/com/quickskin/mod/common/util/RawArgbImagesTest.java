package com.quickskin.mod.common.util;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.Transparency;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ComponentColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.WritableRaster;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RawArgbImagesTest {

    @Test
    void greyWithAlphaKeepsItsRawSamplesAtEightAndSixteenBits() throws IOException {
        for (int dataType : new int[] {DataBuffer.TYPE_BYTE, DataBuffer.TYPE_USHORT}) {
            int scale = dataType == DataBuffer.TYPE_BYTE ? 1 : 257;
            ComponentColorModel model = new ComponentColorModel(ColorSpace.getInstance(ColorSpace.CS_GRAY),
                    true, false, Transparency.TRANSLUCENT, dataType);
            WritableRaster raster = model.createCompatibleWritableRaster(2, 1);
            raster.setSample(0, 0, 0, 128 * scale);
            raster.setSample(0, 0, 1, 200 * scale);
            BufferedImage decoded = SafeImageReader.readPng(png(new BufferedImage(model, raster, false, null)));

            assertTrue(RawArgbImages.isRawGray(decoded));
            assertEquals(0xC8808080, RawArgbImages.toIntArgb(decoded).getRGB(0, 0), "data type " + dataType);
        }
    }

    @Test
    void greyWithoutAlphaMatchesTheBlitVanillaShows() {
        for (BufferedImage gray : new BufferedImage[] {
                new BufferedImage(256, 1, BufferedImage.TYPE_BYTE_GRAY),
                new BufferedImage(256, 1, BufferedImage.TYPE_USHORT_GRAY)}) {
            int scale = gray.getType() == BufferedImage.TYPE_BYTE_GRAY ? 1 : 257;
            for (int x = 0; x < 256; x++) gray.getRaster().setSample(x, 0, 0, x * scale);
            BufferedImage blit = new BufferedImage(256, 1, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = blit.createGraphics();
            graphics.drawImage(gray, 0, 0, null);
            graphics.dispose();

            BufferedImage raw = RawArgbImages.toIntArgb(gray);
            for (int x = 0; x < 256; x++) {
                assertEquals(blit.getRGB(x, 0), raw.getRGB(x, 0), "type " + gray.getType() + " grey " + x);
                assertEquals(0xFF000000 | x * 0x010101, raw.getRGB(x, 0));
            }
        }
    }

    @Test
    void colourImagesAreCopiedExactly() {
        BufferedImage abgr = new BufferedImage(1, 1, BufferedImage.TYPE_4BYTE_ABGR);
        abgr.setRGB(0, 0, 0x01FEDCBA);
        assertFalse(RawArgbImages.isRawGray(abgr));
        assertEquals(0x01FEDCBA, RawArgbImages.toIntArgb(abgr).getRGB(0, 0));

        BufferedImage argb = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        assertSame(argb, RawArgbImages.toIntArgb(argb));
    }

    private static byte[] png(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "PNG", out));
        return out.toByteArray();
    }
}
