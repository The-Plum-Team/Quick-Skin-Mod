package com.quickskin.mod.networking;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/** Bounded per-session budget for server-supplied texture work on the client. */
@Environment(EnvType.CLIENT)
public final class ClientTextureIngressLimiter {
    private static final ClientTextureIngressLimiter INSTANCE =
            new ClientTextureIngressLimiter();
    private static final long WINDOW_MILLIS = 10_000L;
    private static final int MAX_PACKETS = 2 * TextureTransferLimits.MAX_CHUNKS;
    private static final long MAX_WIRE_BYTES = 64L * 1024L * 1024L;
    private static final int MAX_DECODES = 64;
    private static final long MAX_DECODE_PIXELS = 32L * 1024L * 1024L;
    private static final int MAX_CONTROL_PACKETS = 256;
    private static final long MAX_CONTROL_BYTES = 4L * 1024L * 1024L;
    private static final int MAX_CONFIG_PACKETS = 4;

    private long windowStarted = System.currentTimeMillis();
    private int packets;
    private long wireBytes;
    private int decodes;
    private long decodePixels;
    private int controlPackets;
    private long controlBytes;
    private int configPackets;

    private ClientTextureIngressLimiter() {
    }

    public static ClientTextureIngressLimiter getInstance() {
        return INSTANCE;
    }

    public synchronized boolean allowWireBytes(int byteCount) {
        resetWindowIfNeeded(System.currentTimeMillis());
        if (byteCount <= 0 || byteCount > TextureTransferLimits.MAX_WIRE_CHUNK_BYTES
                || packets >= MAX_PACKETS || wireBytes + byteCount > MAX_WIRE_BYTES) {
            return false;
        }
        packets++;
        wireBytes += byteCount;
        return true;
    }

    public synchronized boolean allowDecode(byte[] data, String textureType) {
        resetWindowIfNeeded(System.currentTimeMillis());
        long pixels = NetworkSecurity.getTexturePixelCount(data, textureType);
        if (pixels < 1 || decodes >= MAX_DECODES
                || decodePixels + pixels > MAX_DECODE_PIXELS) return false;
        decodes++;
        decodePixels += pixels;
        return true;
    }

    public synchronized boolean allowControlBytes(int byteCount) {
        resetWindowIfNeeded(System.currentTimeMillis());
        if (byteCount < 0 || byteCount > TextureTransferLimits.MAX_JSON_BYTES
                || controlPackets >= MAX_CONTROL_PACKETS
                || controlBytes + byteCount > MAX_CONTROL_BYTES) return false;
        controlPackets++;
        controlBytes += byteCount;
        return true;
    }

    public synchronized boolean allowConfigPacket() {
        resetWindowIfNeeded(System.currentTimeMillis());
        if (configPackets >= MAX_CONFIG_PACKETS) return false;
        configPackets++;
        return true;
    }

    public synchronized void clear() {
        windowStarted = System.currentTimeMillis();
        packets = 0;
        wireBytes = 0;
        decodes = 0;
        decodePixels = 0;
        controlPackets = 0;
        controlBytes = 0;
        configPackets = 0;
    }

    private void resetWindowIfNeeded(long now) {
        if (now - windowStarted < WINDOW_MILLIS) return;
        windowStarted = now;
        packets = 0;
        wireBytes = 0;
        decodes = 0;
        decodePixels = 0;
        controlPackets = 0;
        controlBytes = 0;
        configPackets = 0;
    }
}
