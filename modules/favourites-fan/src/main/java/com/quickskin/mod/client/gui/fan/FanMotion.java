package com.quickskin.mod.client.gui.fan;

/**
 * Where each card of the favourites fan is, in GUI pixels, for one frame.
 *
 * <p>The fan is a hand of cards around a pivot below the screen, above the hotbar. It opens as the
 * cards grow out of the pivot while the hand spreads from closed; the card under the pointer rises
 * and the others dim and sink; a chosen card comes to the centre, big, turning over once, while
 * the others fall; and closing without a choice drops them one by one behind the hotbar, right to
 * left. Pure arithmetic, so it is the same on every Minecraft version.
 */
public final class FanMotion {
    /** The most cards a page of the fan holds; more favourites go on further pages. */
    public static final int PAGE_SIZE = 12;
    public static final float OPEN_SECONDS = 0.40f;
    public static final float CHOOSE_SECONDS = 0.80f;
    public static final float CLOSE_SECONDS = 0.32f;
    /** How far into the choice the skin is put on. */
    public static final float WEAR_AT = 0.5f;

    public enum Phase { CLOSED, OPENING, OPEN, CHOOSING, CLOSING }

    /** The fan's geometry for a screen and a number of cards. */
    public record Layout(float width, float height, float cardWidth, float cardHeight,
                         float radius, float pivotX, float pivotY, float step) {
        float total(int count) {
            return Math.max(0, count - 1) * step;
        }
    }

    /**
     * One card as drawn: centre, turn (radians), scale, opacity, dimming and how much it is pointed
     * at, and its width as it turns over: 1 face up, 0 edge on, below 0 showing its back.
     */
    public static final class Card {
        public float x;
        public float y;
        public float angle;
        public float scale;
        public float alpha;
        public float dim;
        public float hover;
        public float flip;
        /** Opacity of the chosen card's name under it while it comes to the centre. */
        public float bigName;
        public boolean chosen;
    }

    private FanMotion() {
    }

    /**
     * The fan for {@code count} cards on a {@code width} x {@code height} screen whose HUD (hotbar,
     * hearts, armour) starts at {@code hudTop}.
     */
    public static Layout layout(int count, float width, float height, float hudTop) {
        int n = Math.max(1, count);
        float step = n > 1 ? Math.min(0.25f, 1.65f / (n - 1)) : 0f;
        float half = (n - 1) * step / 2f;
        // The outer cards sit lower than the middle one, by this many card heights
        float drop = 2.3f * (1f - (float) Math.cos(half));
        // As tall as a third of the screen, but small enough that the whole hand fits across it
        // and between the top of the screen and the hotbar
        float cardHeight = clamp(height * 0.32f, 48f, 120f);
        float fitWidth = (width / 2f - 6f) / (2.3f * (float) Math.sin(half) + 0.34f);
        float fitHeight = hudTop / (1.37f + drop);
        cardHeight = Math.min(cardHeight, Math.min(fitWidth, fitHeight));
        float cardWidth = cardHeight * 0.64f;
        float radius = cardHeight * 2.3f;
        float centreY = Math.min(height * 0.54f, hudTop - cardHeight * (0.62f + drop));
        return new Layout(width, height, cardWidth, cardHeight, radius, width / 2f, centreY + radius, step);
    }

    /**
     * The card the pointer at {@code (px, py)} points at, by its direction from the pivot, or -1
     * when the pointer is down by the hotbar, below the lowest card's middle, where a click closes
     * the fan without a change.
     */
    public static int pick(Layout layout, int count, float px, float py) {
        if (count <= 0) return -1;
        float h = layout.cardHeight();
        float outer = layout.pivotY() - layout.radius() * (float) Math.cos(layout.total(count) / 2f) + h * 0.3f;
        if (py > Math.max(outer, layout.pivotY() - layout.radius() + h * 0.62f)) return -1;
        if (count == 1) return 0;
        double angle = Math.atan2(px - layout.pivotX(), layout.pivotY() - py);
        int index = (int) Math.round((angle + layout.total(count) / 2.0) / layout.step());
        return Math.max(0, Math.min(count - 1, index));
    }

    /**
     * Places every card for {@code phase} at {@code progress} (0..1 of the phase), with each
     * card's {@code hover} weight (0..1) and the {@code chosen} card (-1 for none).
     */
    public static void place(Layout layout, int count, Phase phase, float progress, float[] hover, int chosen, Card[] out) {
        float anyHover = 0f;
        for (int i = 0; i < count; i++) anyHover = Math.max(anyHover, hover[i]);
        float h = layout.cardHeight();
        float total = layout.total(count);
        float p = clamp(progress, 0f, 1f);
        for (int i = 0; i < count; i++) {
            Card c = out[i];
            float baseAngle = -total / 2f + i * layout.step();
            float spread = 1f, radial = 1f, sink = 0f, lift = 0f, scale = 1f, alpha = 1f, dim = 0f;
            float toCentre = 0f, upright = 0f, flip = 1f, bigName = 0f;

            // Pointed at: it rises and grows, the others dim and sink a little
            float w = hover[i];
            lift += w * h * 0.22f;
            scale *= 1f + 0.1f * w;
            float others = anyHover * (1f - w);
            dim += 0.55f * others;
            sink += 0.06f * h * others;
            scale *= 1f - 0.04f * others;

            switch (phase) {
                case OPENING -> {
                    float q = backOut(p);
                    radial *= 0.55f + 0.45f * q;
                    scale *= 0.55f + 0.45f * clamp(q, 0f, 1f);
                    spread *= 0.12f + 0.88f * easeOut(p);
                    alpha *= clamp(p * 3f, 0f, 1f);
                }
                case CHOOSING -> {
                    if (i == chosen) {
                        float q = easeInOut(p / 0.45f);
                        toCentre = q;
                        scale *= 1f + 0.65f * q;
                        upright = q;
                        flip = (float) Math.cos(2 * Math.PI * q);
                        bigName = clamp((p - 0.45f) / 0.1f, 0f, 1f);
                        alpha *= 1f - clamp((p - 0.78f) / 0.22f, 0f, 1f);
                    } else {
                        float q = easeIn(p / 0.3f);
                        sink += q * h * 1.3f;
                        alpha *= 1f - clamp(p / 0.3f, 0f, 1f);
                    }
                }
                case CLOSING -> {
                    float q = easeIn(stagger(p, count - 1 - i, count, 0.5f));
                    sink += q * h * 1.3f;
                    if (q >= 1f) alpha = 0f;
                }
                default -> {
                }
            }

            float angle = baseAngle * spread;
            float distance = layout.radius() * radial + lift - sink;
            float x = layout.pivotX() + (float) Math.sin(angle) * distance;
            float y = layout.pivotY() - (float) Math.cos(angle) * distance;
            if (toCentre > 0f) {
                x = lerp(x, layout.width() / 2f, toCentre);
                y = lerp(y, layout.height() * 0.42f, toCentre);
            }
            c.x = x;
            c.y = y;
            c.angle = lerp(angle, 0f, upright);
            c.scale = scale;
            c.alpha = clamp(alpha, 0f, 1f);
            c.dim = clamp(dim, 0f, 1f);
            c.hover = w;
            c.flip = flip;
            c.bigName = bigName;
            c.chosen = i == chosen;
        }
    }

    /** How far card {@code i} of {@code n} is into a wave that starts the cards across {@code span} of the time. */
    static float stagger(float p, int i, int n, float span) {
        float start = (n > 1 ? (float) i / (n - 1) : 0f) * span;
        return clamp((p - start) / (1f - span), 0f, 1f);
    }

    static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    static float lerp(float a, float b, float k) {
        return a + (b - a) * k;
    }

    static float easeOut(float x) {
        x = clamp(x, 0f, 1f);
        return 1f - (1f - x) * (1f - x) * (1f - x);
    }

    static float easeIn(float x) {
        x = clamp(x, 0f, 1f);
        return x * x * x;
    }

    static float easeInOut(float x) {
        x = clamp(x, 0f, 1f);
        return x < 0.5f ? 4f * x * x * x : 1f - (float) Math.pow(-2f * x + 2f, 3) / 2f;
    }

    /** Overshoots a little past 1 and settles back. */
    static float backOut(float x) {
        x = clamp(x, 0f, 1f);
        float c1 = 1.70158f, c3 = c1 + 1f, t = x - 1f;
        return 1f + c3 * t * t * t + c1 * t * t;
    }
}
