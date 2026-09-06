package com.quickskin.mod.networking;

/**
 * Central limits for untrusted texture network traffic and caches.
 *
 * <p>Keep these values shared by codecs, assemblers, and storage so a payload
 * accepted at one boundary cannot exceed a later boundary.</p>
 */
public final class TextureTransferLimits {
    /** Fixed width of the historical v1 bare SHA-1 wire field. */
    public static final int CONTENT_ID_LENGTH = 40;
    /** Maximum UTF-8 width of a canonical content ID ({@code sha256-<64 hex>}). */
    public static final int MAX_CONTENT_ID_BYTES = 71;
    public static final int MAX_TEXTURE_TYPE_BYTES = 4;
    public static final int MAX_APPEARANCE_ID_BYTES = 256;
    public static final int MAX_MODEL_BYTES = 16;
    public static final int MAX_CONFIG_KEY_BYTES = 64;
    public static final int MAX_JSON_BYTES = 32 * 1024 - 1;

    public static final int CHUNK_BYTES = 30 * 1024;
    public static final int MAX_WIRE_CHUNK_BYTES = 32 * 1024;
    public static final int MAX_DIRECT_TEXTURE_BYTES = MAX_WIRE_CHUNK_BYTES;
    public static final int MAX_TEXTURE_BYTES = 16 * 1024 * 1024;
    public static final int MAX_CHUNKS =
            (MAX_TEXTURE_BYTES + CHUNK_BYTES - 1) / CHUNK_BYTES;

    public static final int MAX_IMAGE_WIDTH = 2048;
    public static final int MAX_IMAGE_HEIGHT = 32 * 1024;
    public static final long MAX_IMAGE_PIXELS = 16L * 1024 * 1024;

    public static final int MAX_SERVER_CACHE_ENTRIES = 4096;
    public static final long MAX_SERVER_CACHE_BYTES = 256L * 1024 * 1024;
    /** Hard subset of the cache budget that connected appearances may make non-evictable. */
    public static final long MAX_SERVER_PINNED_BYTES = MAX_SERVER_CACHE_BYTES;
    public static final int MAX_CLIENT_CACHE_ENTRIES = 1024;
    public static final long MAX_CLIENT_CACHE_BYTES = 128L * 1024 * 1024;
    /** Bounds the worst-case decoded/GPU footprint, not just highly-compressible PNG bytes. */
    public static final long MAX_CLIENT_CACHE_PIXELS = 32L * 1024 * 1024;

    public static final int MAX_SERVER_ASSEMBLIES = 128;
    public static final int MAX_ASSEMBLIES_PER_PLAYER = 4;
    public static final long MAX_SERVER_ASSEMBLY_BYTES = 128L * 1024 * 1024;
    public static final long MAX_ASSEMBLY_BYTES_PER_PLAYER = 32L * 1024 * 1024;
    public static final int MAX_CLIENT_ASSEMBLIES = 16;
    public static final long MAX_CLIENT_ASSEMBLY_BYTES = 64L * 1024 * 1024;
    public static final long ASSEMBLY_TTL_MILLIS = 30_000L;

    public static final long REQUEST_RETRY_MILLIS = 5_000L;
    public static final int MAX_PENDING_REQUESTS = 2048;

    private TextureTransferLimits() {
    }
}
