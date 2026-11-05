package com.quickskin.mod.common.util;

import org.junit.jupiter.api.Test;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CapeAtlasFramesTest {

    private static final int CAPE_W = 64;
    private static final int CAPE_H = 32;

    @Test
    void anOversizedFrameNeverSpillsIntoItsNeighbours() {
        // Two square 64x64 source frames, red over blue, drawn cover-fit (64x64) into 64x32 frames
        // and dragged up by 8 rows, so each draw overflows its frame both above and below.
        BufferedImage source = new BufferedImage(64, 128, BufferedImage.TYPE_INT_ARGB);
        fill(source, 0, 64, 0xffff0000);
        fill(source, 64, 128, 0xff0000ff);

        BufferedImage atlas = new BufferedImage(CAPE_W, CAPE_H * 3, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = atlas.createGraphics();
        CapeAtlasFrames.drawFrame(g, source, 0, 64, 0, CAPE_W, CAPE_H, 0, -8, 64, 64);
        CapeAtlasFrames.drawFrame(g, source, 64, 64, CAPE_H, CAPE_W, CAPE_H, 0, -8, 64, 64);
        g.dispose();

        for (int y = 0; y < CAPE_H; y++) {
            assertEquals(0xffff0000, atlas.getRGB(10, y), "frame 0 row " + y);
            assertEquals(0xff0000ff, atlas.getRGB(10, CAPE_H + y), "frame 1 row " + y);
            assertEquals(0x00000000, atlas.getRGB(10, 2 * CAPE_H + y), "unused frame row " + y);
        }
    }

    @Test
    void theCallersClipIsRestored() {
        BufferedImage source = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB);
        BufferedImage atlas = new BufferedImage(CAPE_W, CAPE_H * 2, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = atlas.createGraphics();
        CapeAtlasFrames.drawFrame(g, source, 0, 32, CAPE_H, CAPE_W, CAPE_H, 0, 0, 64, 32);
        assertEquals(null, g.getClip());
        g.dispose();
    }

    private static void fill(BufferedImage image, int fromY, int toY, int argb) {
        for (int y = fromY; y < toY; y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                image.setRGB(x, y, argb);
            }
        }
    }
}
