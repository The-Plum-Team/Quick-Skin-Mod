package com.quickskin.mod.common.util;

/**
 * Pixel rule behind the cape adjust screen's "opaque cape" toggle.
 *
 * <p>The toggle flattens a cape's transparency onto a solid colour the user picks. This class owns
 * that rule and nothing else: the screen calls {@link #flatten(int, int)} once per in-region pixel
 * and {@link #opaque(int)} for the pixels it clears, from the single pass that finishes both the
 * live preview frame and the applied atlas. Keeping the rule here is what lets the preview and the
 * applied cape share one implementation instead of two that can drift apart.
 *
 * <p>The rule is source-over compositing against an opaque backdrop, which is exactly the mental
 * model of "the colour that shows through where the cape is transparent". It agrees with
 * {@code HDTextureProcessor.removeTransparency} — the codebase's existing flatten-for-skins
 * operation — at both endpoints: a fully transparent pixel becomes the fill, and a fully opaque
 * pixel is returned untouched. It differs only for partial alpha, where blending toward the fill
 * avoids the hard halo that force-setting alpha to 255 leaves on the antialiased edges this
 * screen's bilinear resampling routinely produces.
 *
 * <p>Deliberately free of Minecraft and AWT types so the rule, and the hex parsing that feeds it,
 * stay unit testable in the loader-independent test source set.
 */
public final class CapeOpaqueFill {

    /** Fill used until the user picks another, matching the existing skin flatten's black. */
    public static final int DEFAULT_FILL_RGB = 0x000000;

    /** Characters a hex field may hold, excluding the optional leading {@code #}. */
    public static final int HEX_DIGITS = 6;

    private CapeOpaqueFill() {
    }

    /** Drop anything above the 24 colour bits so a stray alpha byte cannot leak into a fill. */
    public static int clampRgb(int rgb) {
        return rgb & 0xFFFFFF;
    }

    /** The fill colour as a fully opaque ARGB pixel. */
    public static int opaque(int fillRgb) {
        return 0xFF000000 | clampRgb(fillRgb);
    }

    /** One 0..255 channel of {@code rgb}; {@code shift} is 16 for red, 8 for green, 0 for blue. */
    public static int channel(int rgb, int shift) {
        return (clampRgb(rgb) >>> shift) & 0xFF;
    }

    /** {@code rgb} with one channel replaced; the new value is clamped into 0..255. */
    public static int withChannel(int rgb, int shift, int value) {
        int clamped = Math.max(0, Math.min(255, value));
        return (clampRgb(rgb) & ~(0xFF << shift)) | (clamped << shift);
    }

    /**
     * Composite one non-premultiplied ARGB pixel over an opaque {@code fillRgb} backdrop.
     *
     * <p>Fully opaque input is returned bit-for-bit, so a cape with no transparency is unchanged by
     * the toggle. Everything else comes back with alpha {@code 0xFF}, which is what makes
     * {@code TextureAlphaDetector.hasTransparentPixels} — whose predicate is {@code alpha < 255} —
     * report the composed cape as opaque.
     */
    public static int flatten(int argb, int fillRgb) {
        int alpha = (argb >>> 24) & 0xFF;
        if (alpha == 0xFF) {
            return argb;
        }
        int fill = clampRgb(fillRgb);
        if (alpha == 0) {
            return 0xFF000000 | fill;
        }
        int inverse = 0xFF - alpha;
        int red = mix((argb >>> 16) & 0xFF, (fill >>> 16) & 0xFF, alpha, inverse);
        int green = mix((argb >>> 8) & 0xFF, (fill >>> 8) & 0xFF, alpha, inverse);
        int blue = mix(argb & 0xFF, fill & 0xFF, alpha, inverse);
        return 0xFF000000 | (red << 16) | (green << 8) | blue;
    }

    private static int mix(int source, int fill, int alpha, int inverse) {
        return (source * alpha + fill * inverse + 127) / 255;
    }

    /** {@code #RRGGBB}, the form the hex field shows and accepts. */
    public static String toHex(int rgb) {
        return String.format("#%06X", clampRgb(rgb));
    }

    /**
     * Parse {@code #RRGGBB} or {@code RRGGBB}, case insensitively.
     *
     * @return the 0xRRGGBB value, or {@code -1} when {@code text} is not a complete colour. The
     *     sentinel lets a live text field ignore half-typed input instead of snapping the preview
     *     to an accidental colour on every keystroke.
     */
    public static int parseHex(String text) {
        if (text == null) {
            return -1;
        }
        String body = text.trim();
        if (body.startsWith("#")) {
            body = body.substring(1);
        }
        if (body.length() != HEX_DIGITS) {
            return -1;
        }
        int value = 0;
        for (int i = 0; i < HEX_DIGITS; i++) {
            int digit = Character.digit(body.charAt(i), 16);
            if (digit < 0) {
                return -1;
            }
            value = (value << 4) | digit;
        }
        return value;
    }

    /** Keep only the characters {@link #parseHex(String)} can consume, capped at its length. */
    public static String sanitizeHexInput(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder kept = new StringBuilder(HEX_DIGITS + 1);
        for (int i = 0; i < text.length() && kept.length() < HEX_DIGITS; i++) {
            char c = text.charAt(i);
            if (Character.digit(c, 16) >= 0) {
                kept.append(Character.toUpperCase(c));
            }
        }
        return kept.toString();
    }
}
