package com.quickskin.mod.networking;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientTextureIngressLimiterTest {
    private final ClientTextureIngressLimiter limiter =
            ClientTextureIngressLimiter.getInstance();

    @AfterEach
    void clearLimiter() {
        limiter.clear();
    }

    @Test
    void capsDecodedPixelWorkEvenForTinyCompressedPayloads() {
        byte[] sixteenMillionPixels = pngHeader(2048, 8192);

        assertTrue(limiter.allowDecode(sixteenMillionPixels, "cape"));
        assertTrue(limiter.allowDecode(sixteenMillionPixels, "cape"));
        assertFalse(limiter.allowDecode(sixteenMillionPixels, "cape"));
    }

    @Test
    void rejectsInvalidImagePreflightWithoutSpendingAFullDecode() {
        assertFalse(limiter.allowDecode(new byte[33], "cape"));
        assertTrue(limiter.allowDecode(pngHeader(64, 32), "cape"));
    }

    private static byte[] pngHeader(int width, int height) {
        byte[] data = new byte[33];
        byte[] signature = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
        System.arraycopy(signature, 0, data, 0, signature.length);
        writeInt(data, 8, 13);
        data[12] = 'I';
        data[13] = 'H';
        data[14] = 'D';
        data[15] = 'R';
        writeInt(data, 16, width);
        writeInt(data, 20, height);
        return data;
    }

    private static void writeInt(byte[] data, int offset, int value) {
        data[offset] = (byte) (value >>> 24);
        data[offset + 1] = (byte) (value >>> 16);
        data[offset + 2] = (byte) (value >>> 8);
        data[offset + 3] = (byte) value;
    }
}
