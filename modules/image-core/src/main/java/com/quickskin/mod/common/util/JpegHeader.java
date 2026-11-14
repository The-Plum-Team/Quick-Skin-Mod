package com.quickskin.mod.common.util;

/**
 * What a cape import has to know about a JPEG before it is decoded: the way up it is meant to be
 * shown, and whether its decoder reads it a few rows at a time.
 *
 * <p>Both are read from the segments ahead of the image data, in the file's own bytes with every
 * offset checked against them. A file that is not a JPEG, or whose segments cannot be read, is
 * upright and is not read a few rows at a time.</p>
 */
final class JpegHeader {
    /** The pixels are stored the way they are shown. */
    static final int UPRIGHT = 1;

    private static final JpegHeader NOT_A_JPEG = new JpegHeader(false, UPRIGHT, false);

    private static final int START_OF_IMAGE = 0xD8;
    private static final int END_OF_IMAGE = 0xD9;
    private static final int START_OF_SCAN = 0xDA;
    private static final int APP1 = 0xE1;
    private static final int BASELINE_FRAME = 0xC0;
    private static final int EXTENDED_SEQUENTIAL_FRAME = 0xC1;
    private static final byte[] EXIF_HEADER = {'E', 'x', 'i', 'f', 0, 0};
    private static final int ORIENTATION_TAG = 0x0112;
    private static final int TIFF_SHORT = 3;
    /** Far more segments than a JPEG carries ahead of its image data; this only bounds the scan. */
    private static final int MAX_SEGMENTS = 1024;

    private final boolean jpeg;
    private final int orientation;
    private final boolean decodedRowByRow;

    private JpegHeader(boolean jpeg, int orientation, boolean decodedRowByRow) {
        this.jpeg = jpeg;
        this.orientation = orientation;
        this.decodedRowByRow = decodedRowByRow;
    }

    boolean isJpeg() {
        return jpeg;
    }

    /**
     * The EXIF orientation, 1 to 8, or {@link #UPRIGHT} without one. A camera or phone saves the
     * sensor's pixels as they came and notes how it was held; every viewer turns the picture by
     * that note, and ImageIO does not.
     */
    int orientation() {
        return orientation;
    }

    /**
     * Whether the decoder holds only a few rows of this JPEG at a time: a sequential one whose
     * first scan carries every component. A progressive JPEG, or one whose components come in
     * scans of their own, is held whole as coefficients, up to six bytes a pixel, until its last
     * scan is in.
     */
    boolean decodedRowByRow() {
        return decodedRowByRow;
    }

    static JpegHeader read(byte[] encoded) {
        if (encoded == null || encoded.length < 4
                || (encoded[0] & 0xFF) != 0xFF || (encoded[1] & 0xFF) != START_OF_IMAGE) {
            return NOT_A_JPEG;
        }
        int orientation = UPRIGHT;
        boolean exifSeen = false;
        int frame = -1;
        int frameComponents = -1;
        int scanComponents = -1;
        // Each segment ahead of the image data: FF, its marker, and a length that counts itself
        int offset = 2;
        for (int segment = 0; segment < MAX_SEGMENTS && offset + 4 <= encoded.length; segment++) {
            if ((encoded[offset] & 0xFF) != 0xFF) {
                break;
            }
            int marker = encoded[offset + 1] & 0xFF;
            if (marker == 0xFF) {
                offset++;
                continue;
            }
            if (marker == END_OF_IMAGE) {
                break;
            }
            if (marker == 0x01 || (marker >= 0xD0 && marker <= START_OF_IMAGE)) {
                // A marker that stands alone
                offset += 2;
                continue;
            }
            int length = unsignedShort(encoded, offset + 2, false);
            int end = offset + 2 + length;
            if (length < 2 || end > encoded.length) {
                break;
            }
            int payload = offset + 4;
            if (marker == START_OF_SCAN) {
                if (end - payload >= 1) {
                    scanComponents = encoded[payload] & 0xFF;
                }
                break;
            }
            if (marker == APP1 && !exifSeen && startsWithExifHeader(encoded, payload, end)) {
                exifSeen = true;
                orientation = orientationOf(encoded, payload + EXIF_HEADER.length, end);
            } else if (isFrame(marker) && frame < 0 && end - payload >= 6) {
                // Sample precision, height, width, and then how many components the picture has
                frame = marker;
                frameComponents = encoded[payload + 5] & 0xFF;
            }
            offset = end;
        }
        boolean sequential = frame == BASELINE_FRAME || frame == EXTENDED_SEQUENTIAL_FRAME;
        return new JpegHeader(true, orientation,
                sequential && frameComponents > 0 && scanComponents == frameComponents);
    }

    /** Start-of-frame markers are C0 to CF, but for the three that define tables or extensions. */
    private static boolean isFrame(int marker) {
        return marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC;
    }

    private static boolean startsWithExifHeader(byte[] encoded, int start, int end) {
        if (end - start < EXIF_HEADER.length) {
            return false;
        }
        for (int i = 0; i < EXIF_HEADER.length; i++) {
            if (encoded[start + i] != EXIF_HEADER[i]) {
                return false;
            }
        }
        return true;
    }

    /** Reads the orientation from the first directory of the TIFF block in {@code [tiff, end)}. */
    private static int orientationOf(byte[] encoded, int tiff, int end) {
        if (end - tiff < 8) {
            return UPRIGHT;
        }
        boolean littleEndian;
        if (encoded[tiff] == 'I' && encoded[tiff + 1] == 'I') {
            littleEndian = true;
        } else if (encoded[tiff] == 'M' && encoded[tiff + 1] == 'M') {
            littleEndian = false;
        } else {
            return UPRIGHT;
        }
        if (unsignedShort(encoded, tiff + 2, littleEndian) != 42) {
            return UPRIGHT;
        }
        long directoryOffset = unsignedInt(encoded, tiff + 4, littleEndian);
        if (directoryOffset < 8 || directoryOffset > end - tiff - 2) {
            return UPRIGHT;
        }
        int directory = tiff + (int) directoryOffset;
        int entries = unsignedShort(encoded, directory, littleEndian);
        for (int i = 0; i < entries; i++) {
            int entry = directory + 2 + i * 12;
            if (entry + 12 > end) {
                return UPRIGHT;
            }
            if (unsignedShort(encoded, entry, littleEndian) != ORIENTATION_TAG) {
                continue;
            }
            // One 16-bit value, which the entry holds itself
            if (unsignedShort(encoded, entry + 2, littleEndian) != TIFF_SHORT
                    || unsignedInt(encoded, entry + 4, littleEndian) != 1) {
                return UPRIGHT;
            }
            int orientation = unsignedShort(encoded, entry + 8, littleEndian);
            return orientation >= 1 && orientation <= 8 ? orientation : UPRIGHT;
        }
        return UPRIGHT;
    }

    private static int unsignedShort(byte[] data, int offset, boolean littleEndian) {
        int first = data[offset] & 0xFF;
        int second = data[offset + 1] & 0xFF;
        return littleEndian ? (second << 8) | first : (first << 8) | second;
    }

    private static long unsignedInt(byte[] data, int offset, boolean littleEndian) {
        long low = unsignedShort(data, littleEndian ? offset : offset + 2, littleEndian);
        long high = unsignedShort(data, littleEndian ? offset + 2 : offset, littleEndian);
        return (high << 16) | low;
    }
}
