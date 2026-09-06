package com.quickskin.mod.common.util;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PngAnimationIdentityTest {

    @Test
    void timingChangesIdentityWithoutChangingPixels() throws Exception {
        byte[] source = png();
        byte[] fast = PngAnimationIdentity.attach(source, metadata(50));
        byte[] slow = PngAnimationIdentity.attach(source, metadata(125));

        assertNotEquals(HashUtil.computeHash(fast), HashUtil.computeHash(slow));
        assertEquals(metadata(50), PngAnimationIdentity.extract(fast));
        assertEquals(metadata(125), PngAnimationIdentity.extract(slow));
        BufferedImage decoded = SafeImageReader.readPng(fast);
        assertEquals(64, decoded.getWidth());
        assertEquals(32, decoded.getHeight());
        assertEquals(0x7f336699, decoded.getRGB(0, 0));
    }

    @Test
    void replacingMetadataIsDeterministicAndDoesNotStackIdentityChunks() throws Exception {
        byte[] source = png();
        byte[] first = PngAnimationIdentity.attach(source, metadata(50));
        byte[] replaced = PngAnimationIdentity.attach(first, metadata(125));

        assertArrayEquals(
                PngAnimationIdentity.attach(source, metadata(125)), replaced);
        assertTrue(replaced.length < first.length + metadata(125).length());
    }

    @Test
    void localCapeIdsAreDomainSeparatedFromRawSkinIds() throws Exception {
        byte[] source = png();
        assertEquals(HashUtil.computeHash(source), HashUtil.computeAssetHash(source, "skin"));
        assertNotEquals(HashUtil.computeHash(source), HashUtil.computeAssetHash(source, "cape"));
        assertEquals(HashUtil.computeContentId(source),
                HashUtil.computeAssetContentId(source, "skin"));
        assertNotEquals(HashUtil.computeContentId(source),
                HashUtil.computeAssetContentId(source, "cape"));
        assertTrue(HashUtil.computeAssetContentId(source, "cape").startsWith("sha256-"));
    }

    private static String metadata(int delay) {
        return "{\"frames\":[{\"delay\":" + delay
                + ",\"index\":0}],\"frameCount\":1}";
    }

    private static byte[] png() throws Exception {
        BufferedImage image = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0x7f336699);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "png", output));
        return output.toByteArray();
    }
}
