package com.quickskin.mod.common.util;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StbGifLoaderPreflightTest {
    private static final byte[] MINIMAL_GIF = Base64.getDecoder().decode(
            "R0lGODlhAQABAIAAAAAAAP///ywAAAAAAQABAAACAUwAOw==");

    @Test
    void acceptsACompleteMinimalGifWithoutInvokingNativeDecoding() throws Exception {
        Object preflight = inspectGif(MINIMAL_GIF);

        assertEquals(1, recordValue(preflight, "width"));
        assertEquals(1, recordValue(preflight, "height"));
        assertEquals(1, recordValue(preflight, "frameCount"));
    }

    @Test
    void rejectsATruncatedImageDataBlock() {
        byte[] truncated = Arrays.copyOf(MINIMAL_GIF, MINIMAL_GIF.length - 2);

        assertThrows(IOException.class, () -> inspectGif(truncated));
    }

    @Test
    void rejectsOversizedLogicalCanvasBeforeNativeAllocation() {
        byte[] oversized = MINIMAL_GIF.clone();
        oversized[6] = 0x01;
        oversized[7] = 0x08; // 2049, little-endian.

        assertThrows(IOException.class, () -> inspectGif(oversized));
    }

    @Test
    void rejectsAFrameCountBombBeforeNativeAllocation() {
        assertThrows(IOException.class, () -> inspectGif(gifWithEmptyFrames(257)));
    }

    private static byte[] gifWithEmptyFrames(int frameCount) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.writeBytes(new byte[] {
                'G', 'I', 'F', '8', '9', 'a',
                1, 0, 1, 0, // 1x1 logical canvas.
                0, 0, 0 // No global color table.
        });
        byte[] emptyFrame = new byte[] {
                0x2c,
                0, 0, 0, 0, // Left/top.
                1, 0, 1, 0, // Width/height.
                0, // No local color table.
                2, // LZW minimum code size.
                0 // Empty sub-block terminator; structural preflight does not decode pixels.
        };
        for (int index = 0; index < frameCount; index++) {
            output.writeBytes(emptyFrame);
        }
        output.write(0x3b);
        return output.toByteArray();
    }

    private static Object inspectGif(byte[] bytes) throws IOException {
        try {
            Method method = StbGifLoader.class.getDeclaredMethod("inspectGif", byte[].class);
            method.setAccessible(true);
            return method.invoke(null, (Object) bytes);
        } catch (InvocationTargetException exception) {
            if (exception.getCause() instanceof IOException ioException) {
                throw ioException;
            }
            throw new AssertionError("GIF preflight threw an unexpected exception", exception.getCause());
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Could not invoke GIF preflight", exception);
        }
    }

    private static int recordValue(Object record, String accessor) throws Exception {
        Method method = record.getClass().getDeclaredMethod(accessor);
        method.setAccessible(true);
        return (int) method.invoke(record);
    }
}
