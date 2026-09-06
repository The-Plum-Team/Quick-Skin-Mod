package com.quickskin.mod.common.util;

import com.quickskin.mod.networking.TextureTransferLimits;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.util.Arrays;
import java.util.zip.CRC32;

/** Adds validated animation timing to a PNG's content identity without changing its pixels. */
public final class PngAnimationIdentity {
    private static final byte[] PNG_SIGNATURE = {
            (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
    };
    // Ancillary, private, reserved-bit-valid, unsafe-to-copy PNG chunk.
    private static final byte[] CHUNK_TYPE = {'q', 's', 'M', 'D'};
    private static final int IEND = 0x49454e44;
    private static final int IDENTITY_CHUNK = 0x71734d44;

    private PngAnimationIdentity() {
    }

    /** Returns a deterministic PNG whose hash also identifies the canonical timing document. */
    public static byte[] attach(byte[] png, String metadataJson) throws IOException {
        if (png == null || png.length < PNG_SIGNATURE.length + 12
                || !startsWith(png, PNG_SIGNATURE) || metadataJson == null) {
            throw new IOException("Invalid animated PNG identity input");
        }
        byte[] metadata = metadataJson.getBytes(StandardCharsets.UTF_8);
        if (metadata.length == 0 || metadata.length > TextureTransferLimits.MAX_JSON_BYTES) {
            throw new IOException("Animation metadata exceeds its network limit");
        }
        // Validate the complete chunk walk and reject duplicate/malformed identity chunks.
        extract(png);

        ByteArrayOutputStream output = new ByteArrayOutputStream(
                Math.min(TextureTransferLimits.MAX_TEXTURE_BYTES,
                        png.length + metadata.length + 12));
        output.write(png, 0, PNG_SIGNATURE.length);
        int cursor = PNG_SIGNATURE.length;
        boolean foundEnd = false;
        while (cursor <= png.length - 12) {
            long length = Integer.toUnsignedLong(readInt(png, cursor));
            long chunkEndLong = (long) cursor + 12L + length;
            if (length > Integer.MAX_VALUE || chunkEndLong > png.length) {
                throw new IOException("Malformed PNG chunk length");
            }
            int chunkEnd = (int) chunkEndLong;
            int type = readInt(png, cursor + 4);
            if (type == IEND) {
                if (length != 0L || chunkEnd != png.length) {
                    throw new IOException("Malformed PNG end chunk");
                }
                writeIdentityChunk(output, metadata);
                output.write(png, cursor, chunkEnd - cursor);
                cursor = chunkEnd;
                foundEnd = true;
                break;
            }
            if (type != IDENTITY_CHUNK) {
                output.write(png, cursor, chunkEnd - cursor);
            }
            cursor = chunkEnd;
        }
        if (!foundEnd || cursor != png.length
                || output.size() > TextureTransferLimits.MAX_TEXTURE_BYTES) {
            throw new IOException("Animated PNG exceeds the transfer limit or has no valid end");
        }
        return output.toByteArray();
    }

    /** Extracts the exact embedded timing document, or {@code null} for a legacy/static PNG. */
    public static String extract(byte[] png) throws IOException {
        if (png == null || png.length < PNG_SIGNATURE.length + 12
                || !startsWith(png, PNG_SIGNATURE)) {
            throw new IOException("Invalid PNG identity input");
        }
        int cursor = PNG_SIGNATURE.length;
        byte[] identity = null;
        boolean foundEnd = false;
        while (cursor <= png.length - 12) {
            long length = Integer.toUnsignedLong(readInt(png, cursor));
            long chunkEndLong = (long) cursor + 12L + length;
            if (length > Integer.MAX_VALUE || chunkEndLong > png.length) {
                throw new IOException("Malformed PNG chunk length");
            }
            int chunkEnd = (int) chunkEndLong;
            int type = readInt(png, cursor + 4);
            if (type == IDENTITY_CHUNK) {
                if (identity != null || length == 0L
                        || length > TextureTransferLimits.MAX_JSON_BYTES) {
                    throw new IOException("Invalid or duplicate animation identity chunk");
                }
                identity = Arrays.copyOfRange(png, cursor + 8, cursor + 8 + (int) length);
                CRC32 crc = new CRC32();
                crc.update(CHUNK_TYPE, 0, CHUNK_TYPE.length);
                crc.update(identity, 0, identity.length);
                if ((int) crc.getValue() != readInt(png, cursor + 8 + (int) length)) {
                    throw new IOException("Invalid animation identity CRC");
                }
            }
            if (type == IEND) {
                if (length != 0L || chunkEnd != png.length) {
                    throw new IOException("Malformed PNG end chunk");
                }
                foundEnd = true;
                cursor = chunkEnd;
                break;
            }
            cursor = chunkEnd;
        }
        if (!foundEnd || cursor != png.length) throw new IOException("PNG has no valid end chunk");
        if (identity == null) return null;
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(identity)).toString();
        } catch (java.nio.charset.CharacterCodingException error) {
            throw new IOException("Animation identity is not valid UTF-8", error);
        }
    }

    private static void writeIdentityChunk(
            ByteArrayOutputStream output, byte[] metadata) {
        writeInt(output, metadata.length);
        output.write(CHUNK_TYPE, 0, CHUNK_TYPE.length);
        output.write(metadata, 0, metadata.length);
        CRC32 crc = new CRC32();
        crc.update(CHUNK_TYPE, 0, CHUNK_TYPE.length);
        crc.update(metadata, 0, metadata.length);
        writeInt(output, (int) crc.getValue());
    }

    private static boolean startsWith(byte[] value, byte[] prefix) {
        return value.length >= prefix.length
                && Arrays.equals(Arrays.copyOf(value, prefix.length), prefix);
    }

    private static int readInt(byte[] value, int offset) {
        return ((value[offset] & 0xff) << 24)
                | ((value[offset + 1] & 0xff) << 16)
                | ((value[offset + 2] & 0xff) << 8)
                | (value[offset + 3] & 0xff);
    }

    private static void writeInt(ByteArrayOutputStream output, int value) {
        output.write(value >>> 24);
        output.write(value >>> 16);
        output.write(value >>> 8);
        output.write(value);
    }
}
