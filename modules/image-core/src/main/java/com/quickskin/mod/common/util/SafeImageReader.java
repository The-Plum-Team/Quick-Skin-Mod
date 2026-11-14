package com.quickskin.mod.common.util;

import com.twelvemonkeys.imageio.plugins.webp.WebPImageReaderSpi;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

/**
 * Bounded decoder for local PNG assets.
 *
 * <p>ImageIO normally allocates during {@code read}. This boundary inspects dimensions first,
 * caps both encoded and decoded size, and only then performs the full decode.</p>
 *
 * <p>WebP skins and capes are decoded by the bundled pure-Java reader, called directly rather than through
 * the ImageIO registry: it is relocated into Quick Skin's package and registers nothing globally,
 * so it never meets another mod's copy of a WebP library. JPEG skins and capes are decoded by
 * the Java runtime's own reader.</p>
 */
public final class SafeImageReader {
    public static final long MAX_ENCODED_BYTES = 32L * 1024L * 1024L;
    public static final int MAX_WIDTH = 4096;
    public static final int MAX_HEIGHT = 32 * 1024;
    public static final long MAX_PIXELS = 16L * 1024L * 1024L;
    /** A still cape's limit per side: a larger picture is shrunk to it as it is read. */
    public static final int MAX_STILL_CAPE_SIDE = MAX_WIDTH;
    /** The longest side of a picture a cape import opens. */
    public static final int MAX_CAPE_SOURCE_SIDE = 16384;
    /**
     * The most pixels such a picture may have when its decoder reads it a few rows at a time (a
     * PNG, or a JPEG that is not progressive): only the shrunk picture is ever held.
     */
    public static final long MAX_CAPE_SOURCE_PIXELS = 64L * 1024L * 1024L;
    /**
     * The same for a JPEG its decoder holds whole until the last scan is in, as up to six bytes of
     * coefficients a pixel outside the Java heap.
     */
    public static final long MAX_HELD_JPEG_SOURCE_PIXELS = 32L * 1024L * 1024L;
    private static final Set<String> PNG_FORMAT = Set.of("png");
    private static final Set<String> SKIN_FORMATS = Set.of("png", "jpeg", "jpg");
    private static final Set<String> STILL_CAPE_FORMATS = SKIN_FORMATS;
    private static final byte[] RIFF = "RIFF".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] WEBP = "WEBP".getBytes(StandardCharsets.US_ASCII);
    private static final ImageReaderSpi WEBP_READER = new WebPImageReaderSpi();

    private SafeImageReader() {
    }

    public static BufferedImage readPng(Path source) throws IOException {
        if (source == null || !Files.isRegularFile(source) || !Files.isReadable(source)) {
            throw new IOException("PNG file does not exist or is not readable");
        }
        return readPng(BoundedFileReader.readBytes(source, (int) MAX_ENCODED_BYTES));
    }

    public static BufferedImage readPng(byte[] encoded) throws IOException {
        if (encoded == null || encoded.length == 0 || encoded.length > MAX_ENCODED_BYTES) {
            throw new IOException("PNG data must be between 1 byte and 32 MB");
        }
        try (ImageInputStream input =
                     new MemoryCacheImageInputStream(new ByteArrayInputStream(encoded))) {
            return decode(input, registeredReader(input, PNG_FORMAT), MAX_WIDTH, MAX_HEIGHT, MAX_PIXELS);
        } catch (RuntimeException exception) {
            throw new IOException("Invalid PNG image", exception);
        }
    }

    /** Reads an owned or resource-pack stream without allowing an unbounded encoded allocation. */
    public static BufferedImage readPng(InputStream source) throws IOException {
        if (source == null) throw new IOException("PNG stream is missing");
        byte[] encoded = source.readNBytes((int) MAX_ENCODED_BYTES + 1);
        if (encoded.length > MAX_ENCODED_BYTES) {
            throw new IOException("PNG data exceeds the 32 MB limit");
        }
        return readPng(encoded);
    }

    /**
     * Decodes an imported still cape, PNG, JPEG or WebP, into at most
     * {@link #MAX_STILL_CAPE_SIDE} pixels per side. The format comes from the bytes, never the
     * file name; an animated WebP gives its first frame, and a JPEG is turned the way its EXIF
     * orientation says it is shown, as a photo viewer does.
     *
     * <p>A larger picture, such as a camera's photo, is shrunk evenly as it is read, up to the
     * source limits above. A WebP is decoded whole on the heap, about six bytes a pixel, so it
     * may be of any shape but of no more pixels than a still cape itself ({@link #MAX_PIXELS}).
     * A picture over its limit is refused from its header with an
     * {@link OversizedImageException}.</p>
     */
    public static BufferedImage readStillCape(Path source) throws IOException {
        if (source == null || !Files.isRegularFile(source) || !Files.isReadable(source)) {
            throw new IOException("Cape image does not exist or is not readable");
        }
        byte[] encoded = BoundedFileReader.readBytes(source, (int) MAX_ENCODED_BYTES);
        if (encoded.length == 0) {
            throw new IOException("Cape image data must be between 1 byte and 32 MB");
        }
        try (ImageInputStream input =
                     new MemoryCacheImageInputStream(new ByteArrayInputStream(encoded))) {
            boolean webp = isWebP(encoded);
            ImageReader reader = webp
                    ? WEBP_READER.createReaderInstance(null)
                    : registeredReader(input, STILL_CAPE_FORMATS);
            JpegHeader jpeg = JpegHeader.read(encoded);
            long maxSourcePixels = webp ? MAX_PIXELS
                    : jpeg.isJpeg() && !jpeg.decodedRowByRow() ? MAX_HELD_JPEG_SOURCE_PIXELS
                    : MAX_CAPE_SOURCE_PIXELS;
            BufferedImage decoded = decodeShrunk(
                    input, reader, MAX_STILL_CAPE_SIDE, MAX_CAPE_SOURCE_SIDE, maxSourcePixels);
            return JpegOrientation.upright(decoded, jpeg.orientation());
        } catch (RuntimeException exception) {
            throw new IOException("Invalid cape image", exception);
        }
    }

    /** Decodes an imported PNG/JPEG/WebP skin under the stricter supported-skin policy. */
    public static BufferedImage readSkin(Path source) throws IOException {
        if (source == null || !Files.isRegularFile(source) || !Files.isReadable(source)) {
            throw new IOException("Skin image does not exist or is not readable");
        }
        return readSkin(BoundedFileReader.readBytes(source, (int) MAX_ENCODED_BYTES));
    }

    public static BufferedImage readSkin(byte[] encoded) throws IOException {
        if (encoded == null || encoded.length == 0 || encoded.length > MAX_ENCODED_BYTES) {
            throw new IOException("Skin image data must be between 1 byte and 32 MB");
        }
        try (ImageInputStream input =
                     new MemoryCacheImageInputStream(new ByteArrayInputStream(encoded))) {
            ImageReader reader = isWebP(encoded)
                    ? WEBP_READER.createReaderInstance(null)
                    : registeredReader(input, SKIN_FORMATS);
            return decode(input, reader, 2048, 2048, 2048L * 2048L);
        } catch (RuntimeException exception) {
            throw new IOException("Invalid skin image", exception);
        }
    }

    private static boolean isWebP(byte[] encoded) {
        return encoded.length >= 12
                && Arrays.equals(encoded, 0, 4, RIFF, 0, 4)
                && Arrays.equals(encoded, 8, 12, WEBP, 0, 4);
    }

    private static ImageReader registeredReader(ImageInputStream input, Set<String> allowedFormats)
            throws IOException {
        if (input == null) {
            throw new IOException("Could not open PNG image");
        }
        var readers = ImageIO.getImageReaders(input);
        if (!readers.hasNext()) {
            throw new IOException("Invalid PNG image");
        }
        ImageReader reader = readers.next();
        String format = reader.getFormatName().toLowerCase(Locale.ROOT);
        if (!allowedFormats.contains(format)) {
            reader.dispose();
            throw new IOException("Unsupported image format: " + format);
        }
        return reader;
    }

    /**
     * Decodes a picture into at most {@code maxSide} pixels per side. One that is larger, within
     * the source limits, is read keeping one pixel in every {@code step} each way, with the
     * smallest whole {@code step} that fits, so the decoder never hands over the full picture.
     */
    private static BufferedImage decodeShrunk(
            ImageInputStream input,
            ImageReader reader,
            int maxSide,
            int maxSourceSide,
            long maxSourcePixels
    ) throws IOException {
        try {
            reader.setInput(input, true, true);
            int width = reader.getWidth(0);
            int height = reader.getHeight(0);
            if (width < 1 || height < 1) {
                throw new IOException("Image dimensions exceed the configured limits");
            }
            if (width > maxSourceSide || height > maxSourceSide
                    || (long) width * height > maxSourcePixels) {
                throw new OversizedImageException(width, height, maxSourceSide);
            }
            int step = Math.max((width + maxSide - 1) / maxSide, (height + maxSide - 1) / maxSide);
            BufferedImage decoded;
            if (step > 1) {
                ImageReadParam shrunk = reader.getDefaultReadParam();
                shrunk.setSourceSubsampling(step, step, 0, 0);
                decoded = reader.read(0, shrunk);
            } else {
                decoded = reader.read(0);
            }
            if (decoded == null || decoded.getWidth() != (width + step - 1) / step
                    || decoded.getHeight() != (height + step - 1) / step) {
                throw new IOException("Image decoder returned inconsistent dimensions");
            }
            return decoded;
        } finally {
            reader.dispose();
        }
    }

    private static BufferedImage decode(
            ImageInputStream input,
            ImageReader reader,
            int maxWidth,
            int maxHeight,
            long maxPixels
    ) throws IOException {
        try {
            reader.setInput(input, true, true);
            int width = reader.getWidth(0);
            int height = reader.getHeight(0);
            if (width < 1 || height < 1 || width > maxWidth || height > maxHeight
                    || (long) width * height > maxPixels) {
                throw new IOException("Image dimensions exceed the configured limits");
            }
            BufferedImage decoded = reader.read(0);
            if (decoded == null || decoded.getWidth() != width || decoded.getHeight() != height) {
                throw new IOException("Image decoder returned inconsistent dimensions");
            }
            return decoded;
        } finally {
            reader.dispose();
        }
    }
}
