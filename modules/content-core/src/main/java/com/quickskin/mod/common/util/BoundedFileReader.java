package com.quickskin.mod.common.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Reads a regular file through a hard allocation cap instead of trusting its size metadata. */
public final class BoundedFileReader {
    private BoundedFileReader() {
    }

    public static byte[] readBytes(Path path, int maxBytes) throws IOException {
        if (path == null || maxBytes < 1 || !Files.isRegularFile(path)) {
            throw new IOException("File is missing or the read limit is invalid");
        }
        long advertisedSize = Files.size(path);
        if (advertisedSize < 0 || advertisedSize > maxBytes) {
            throw new IOException("File exceeds the " + maxBytes + " byte limit");
        }
        try (InputStream input = Files.newInputStream(path)) {
            return readBytes(input, maxBytes);
        }
    }

    /** Reads an already-open stream without assuming its advertised size. The caller owns it. */
    public static byte[] readBytes(InputStream input, int maxBytes) throws IOException {
        if (input == null || maxBytes < 1 || maxBytes == Integer.MAX_VALUE) {
            throw new IOException("Input stream is missing or the read limit is invalid");
        }
        byte[] bytes = input.readNBytes(maxBytes + 1);
        if (bytes.length > maxBytes) {
            throw new IOException("Input exceeded the " + maxBytes + " byte limit");
        }
        return bytes;
    }

    public static String readUtf8(Path path, int maxBytes) throws IOException {
        return new String(readBytes(path, maxBytes), StandardCharsets.UTF_8);
    }
}
