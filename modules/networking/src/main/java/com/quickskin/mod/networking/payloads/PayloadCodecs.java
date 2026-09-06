//? if >=1.21 {
package com.quickskin.mod.networking.payloads;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Helper class for encoding/decoding data to/from ByteBuf for CustomPacketPayloads
 * Provides methods similar to FriendlyByteBuf but for raw ByteBuf
 */
public class PayloadCodecs {

    private static final int MAX_STRING_LENGTH = 32767;

    /**
     * Write a string to the buffer
     */
    public static void writeString(ByteBuf buf, String string) {
        writeString(buf, string, MAX_STRING_LENGTH);
    }

    public static void writeString(ByteBuf buf, String string, int maxBytes) {
        if (string == null) {
            throw new EncoderException("String cannot be null");
        }
        byte[] bytes = string.getBytes(StandardCharsets.UTF_8);
        if (maxBytes < 0 || maxBytes > MAX_STRING_LENGTH || bytes.length > maxBytes) {
            throw new EncoderException("String too big (was " + bytes.length + " bytes encoded, max " + maxBytes + ")");
        }
        writeVarInt(buf, bytes.length);
        buf.writeBytes(bytes);
    }

    /**
     * Read a string from the buffer
     */
    public static String readString(ByteBuf buf) {
        return readString(buf, MAX_STRING_LENGTH);
    }

    public static String readString(ByteBuf buf, int maxBytes) {
        if (maxBytes < 0 || maxBytes > MAX_STRING_LENGTH) {
            throw new DecoderException("Invalid maximum string length: " + maxBytes);
        }
        int length = readVarInt(buf);
        if (length < 0 || length > maxBytes || length > buf.readableBytes()) {
            throw new DecoderException("Invalid encoded string length " + length + " (max " + maxBytes
                    + ", readable " + buf.readableBytes() + ")");
        }
        byte[] bytes = new byte[length];
        buf.readBytes(bytes);
        String string = new String(bytes, StandardCharsets.UTF_8);
        return string;
    }

    public static void writeByteArray(ByteBuf buf, byte[] data, int maxLength) {
        if (data == null || data.length > maxLength) {
            throw new EncoderException("Byte array too big (was "
                    + (data == null ? "null" : data.length) + ", max " + maxLength + ")");
        }
        buf.writeInt(data.length);
        buf.writeBytes(data);
    }

    /** Validate the wire length before allocating the destination array. */
    public static byte[] readByteArray(ByteBuf buf, int maxLength) {
        int length = buf.readInt();
        if (length < 0 || length > maxLength || length > buf.readableBytes()) {
            throw new DecoderException("Invalid byte array length " + length + " (max " + maxLength
                    + ", readable " + buf.readableBytes() + ")");
        }
        byte[] data = new byte[length];
        buf.readBytes(data);
        return data;
    }

    /**
     * Write a UUID to the buffer
     */
    public static void writeUUID(ByteBuf buf, UUID uuid) {
        buf.writeLong(uuid.getMostSignificantBits());
        buf.writeLong(uuid.getLeastSignificantBits());
    }

    /**
     * Read a UUID from the buffer
     */
    public static UUID readUUID(ByteBuf buf) {
        return new UUID(buf.readLong(), buf.readLong());
    }

    /**
     * Write a variable-length integer
     */
    private static void writeVarInt(ByteBuf buf, int value) {
        while ((value & -128) != 0) {
            buf.writeByte(value & 127 | 128);
            value >>>= 7;
        }
        buf.writeByte(value);
    }

    /**
     * Read a variable-length integer
     */
    private static int readVarInt(ByteBuf buf) {
        int i = 0;
        int j = 0;
        byte b;
        do {
            if (!buf.isReadable()) {
                throw new DecoderException("Truncated VarInt");
            }
            b = buf.readByte();
            i |= (b & 127) << j++ * 7;
            if (j > 5) {
                throw new DecoderException("VarInt too big");
            }
        } while ((b & 128) == 128);
        return i;
    }
}

//?}
