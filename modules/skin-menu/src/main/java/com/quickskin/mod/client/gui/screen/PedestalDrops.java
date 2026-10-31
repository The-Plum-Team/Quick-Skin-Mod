package com.quickskin.mod.client.gui.screen;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntFunction;

/**
 * The pieces the player hides, lying on the floor round the skin menu's turntable ring the way
 * Minecraft draws dropped items: each one turns slowly and bobs. A piece just hidden hops up off
 * the model, falls onto its place and bounces twice there, and a piece put back on flies up to the
 * model.
 *
 * <p>The places are numbered: the first two lie on the ring's front, left and right of the feet;
 * the rest lie beyond the ring, alternately left and right, closer together once the room runs out.
 * An item keeps its place for as long as it lies there. One that leaves leaves its place empty and
 * a new one takes the first empty place, so putting pieces on or taking them off moves none of the
 * others, which the player may be about to click.
 *
 * <p>Positions are GUI coordinates and times are milliseconds. An item's place is the point on the
 * floor it stands on, and the item is drawn above that point.
 */
final class PedestalDrops {
    /** How long a dropped item flies, then bounces once and again, and how long a rising one flies. */
    static final long DROP_MILLIS = 560L;
    static final long BOUNCE_MILLIS = 230L;
    static final long SECOND_BOUNCE_MILLIS = 130L;
    static final long RISE_MILLIS = 300L;
    /** How long a lying item takes to turn once, and to bob up and back down. */
    static final long TURN_MILLIS = 5000L;
    static final long BOB_MILLIS = 2600L;
    /** How high, in GUI pixels, an item bobs, and how high it hops when it rises. */
    static final float BOB_HEIGHT = 1.2f;
    static final float RISE_HOP = 8.0f;
    /**
     * How high a dropped item flies above where it left the model, and how high it bounces on the
     * floor, as shares of its size.
     */
    static final float DROP_APEX = 0.95f;
    static final float BOUNCE_HEIGHT = 0.45f;
    static final float SECOND_BOUNCE_HEIGHT = 0.14f;
    /** A turning item is never drawn narrower than this share of its width. */
    static final float MIN_WIDTH = 0.32f;
    /** How high above its place an item's middle is drawn, as a share of its size. */
    static final float LIFT = 0.45f;
    /** How fast an item glides to a new place, as the time constant of an exponential approach. */
    static final float GLIDE_SECONDS = 0.08f;
    /** A rising item shrinks to this share of its size as it reaches the model. */
    static final float RISE_END_SCALE = 0.55f;

    /** An item lying on the floor, or dropping onto it: where it is drawn now. */
    static final class Item {
        final int piece;
        /** The number of its place. */
        final int slot;
        float x;
        float y;
        private float fromX;
        private float fromY;
        /** When its drop started, or {@link Long#MIN_VALUE} once it lies still. */
        private long droppedAt = Long.MIN_VALUE;
        /** Whether it is dropping and has not reached the floor yet. */
        private boolean inAir;

        private Item(int piece, int slot) {
            this.piece = piece;
            this.slot = slot;
        }

        /** Whether it is still dropping: flying or bouncing. */
        boolean dropping() {
            return droppedAt != Long.MIN_VALUE;
        }

        /** Whether it is flying down from the model, above the floor rather than bouncing on it. */
        boolean inAir() {
            return inAir;
        }
    }

    /** A piece put back on, flying from where it lay to where the model wears it. */
    record Rise(int piece, float fromX, float fromY, float toX, float toY, long startedAt) {
        /** Where it is drawn at {@code now}. */
        float[] at(long now) {
            return hop(fromX, fromY, toX, toY, inOut(progress(now)), RISE_HOP);
        }

        /** Its size at {@code now}, as a share of a lying item's. */
        float scale(long now) {
            return (float) (1 - (1 - RISE_END_SCALE) * progress(now));
        }

        private double progress(long now) {
            return Math.max(0.0, Math.min(1.0, (double) (now - startedAt) / RISE_MILLIS));
        }
    }

    private final Map<Integer, Item> items = new LinkedHashMap<>();
    private final List<Rise> rises = new ArrayList<>();
    private boolean primed;
    private long lastUpdate;

    /**
     * Where the {@code index}-th of {@code count} lying items stands, round a ring centred on
     * {@code (centerX, centerY)} whose band's middle has radii {@code radiusX} and {@code radiusY},
     * for items {@code size} wide that reach at most {@code reach} from the centre to either side.
     */
    static float[] place(int index, int count, float centerX, float centerY, float radiusX, float radiusY,
                         float size, float reach) {
        int side = index % 2 == 0 ? -1 : 1;
        int step = index / 2;
        if (step == 0) {
            // On the band's front, beside the feet
            double angle = Math.toRadians(side < 0 ? 144 : 36);
            return new float[]{(float) (centerX + radiusX * Math.cos(angle)), (float) (centerY + radiusY * Math.sin(angle))};
        }
        // Beyond the ring, a little apart, or overlapping where the room runs out
        int outerSteps = (count + 1) / 2 - 1;
        float first = radiusX + size * 0.62f;
        float gap = size * 1.1f;
        if (outerSteps > 1 && first + gap * (outerSteps - 1) > reach) {
            gap = Math.max(0.0f, (reach - first) / (outerSteps - 1));
        }
        return new float[]{centerX + side * (first + gap * (step - 1)), centerY + radiusY * 0.35f};
    }

    /** How far above its place a lying item is drawn as it bobs, at {@code now}. */
    static float bob(long now, int seed) {
        double phase = 2 * Math.PI * (now % BOB_MILLIS) / BOB_MILLIS + seed * 2.3;
        return (float) (BOB_HEIGHT * (1 + Math.sin(phase)));
    }

    /** How wide a lying item is drawn as it turns, at {@code now}, as a share of its width. */
    static float width(long now, int seed) {
        double turn = (double) (now % TURN_MILLIS) / TURN_MILLIS + seed * 0.29;
        return (float) (MIN_WIDTH + (1 - MIN_WIDTH) * Math.abs(Math.cos(2 * Math.PI * turn)));
    }

    /** A point {@code progress} (0 to 1) along a hop {@code height} high from one point to another. */
    static float[] hop(float fromX, float fromY, float toX, float toY, double progress, float height) {
        double p = Math.max(0.0, Math.min(1.0, progress));
        return new float[]{
                (float) (fromX + (toX - fromX) * p),
                (float) (fromY + (toY - fromY) * p - height * 4 * p * (1 - p))};
    }

    /**
     * Where an item thrown up from one point is {@code elapsed} milliseconds later, falling under
     * constant gravity onto another point after {@code millis}, at its highest {@code apex} above
     * the first point (or as high as the second, should that be higher).
     */
    static float[] thrown(float fromX, float fromY, float toX, float toY, double elapsed, double millis, float apex) {
        double t = Math.max(0.0, Math.min(millis, elapsed));
        double drop = toY - fromY;
        double high = Math.max(apex, -drop);
        // The gravity that lands it at millis: its root solves drop = -v*millis + g*millis^2/2, v = sqrt(2*g*high)
        double root = (Math.sqrt(2 * high) + Math.sqrt(2 * (high + drop))) / millis;
        double gravity = root * root;
        double speed = Math.sqrt(2 * gravity * high);
        return new float[]{
                (float) (fromX + (toX - fromX) * t / millis),
                (float) (fromY - speed * t + gravity * t * t / 2)};
    }

    /** How high a bounce {@code height} high and {@code millis} long is {@code elapsed} into it. */
    private static float bounce(double elapsed, double millis, float height) {
        double p = Math.max(0.0, Math.min(1.0, elapsed / millis));
        return (float) (height * 4 * p * (1 - p));
    }

    private static double inOut(double p) {
        return p < 0.5 ? 2 * p * p : 1 - Math.pow(-2 * p + 2, 2) / 2;
    }

    /**
     * Brings the floor up to {@code now}: {@code pieces}, items {@code size} wide, lie on it, each
     * at the point {@code places} gives for the number of its place. A piece new to the floor takes
     * the first empty place and drops there from where {@code worn} says the model wears it (null:
     * it simply lies there), and one that left it flies back there. The first update lays every
     * piece in its place at once, in order.
     */
    void update(int[] pieces, IntFunction<float[]> places, float size, IntFunction<float[]> worn, long now) {
        float seconds = Math.max(0.0f, Math.min(0.1f, (now - lastUpdate) / 1000.0f));
        float glide = primed ? 1.0f - (float) Math.exp(-seconds / GLIDE_SECONDS) : 1.0f;
        Set<Integer> lying = new HashSet<>();
        for (int piece : pieces) {
            lying.add(piece);
        }
        // Those that left first, so a piece arriving now may take a place one of them left
        for (Iterator<Item> it = items.values().iterator(); it.hasNext(); ) {
            Item item = it.next();
            if (!lying.contains(item.piece)) {
                it.remove();
                float[] to = worn.apply(item.piece);
                if (to != null) {
                    rises.add(new Rise(item.piece, item.x, item.y, to[0], to[1], now));
                }
            }
        }
        for (int piece : pieces) {
            Item item = items.get(piece);
            if (item == null) {
                item = new Item(piece, emptySlot());
                float[] place = places.apply(item.slot);
                item.x = place[0];
                item.y = place[1];
                float[] from = primed ? worn.apply(piece) : null;
                if (from != null) {
                    item.fromX = from[0];
                    item.fromY = from[1];
                    item.droppedAt = now;
                }
                items.put(piece, item);
                // Hidden again on its way up: it drops from the model instead
                rises.removeIf(rise -> rise.piece() == piece);
            }
            float[] place = places.apply(item.slot);
            if (item.dropping()) {
                long elapsed = now - item.droppedAt;
                item.inAir = elapsed < DROP_MILLIS;
                if (item.inAir) {
                    float[] at = thrown(item.fromX, item.fromY, place[0], place[1], elapsed, DROP_MILLIS,
                            size * DROP_APEX);
                    item.x = at[0];
                    item.y = at[1];
                } else {
                    long bouncing = elapsed - DROP_MILLIS;
                    item.x = place[0];
                    item.y = place[1] - (bouncing < BOUNCE_MILLIS
                            ? bounce(bouncing, BOUNCE_MILLIS, size * BOUNCE_HEIGHT)
                            : bounce(bouncing - BOUNCE_MILLIS, SECOND_BOUNCE_MILLIS, size * SECOND_BOUNCE_HEIGHT));
                    if (bouncing >= BOUNCE_MILLIS + SECOND_BOUNCE_MILLIS) {
                        item.droppedAt = Long.MIN_VALUE;
                        item.y = place[1];
                    }
                }
            } else {
                item.x += (place[0] - item.x) * glide;
                item.y += (place[1] - item.y) * glide;
            }
        }
        rises.removeIf(rise -> now - rise.startedAt() >= RISE_MILLIS);
        primed = true;
        lastUpdate = now;
    }

    /** The lowest place number no item lies in. */
    private int emptySlot() {
        Set<Integer> taken = new HashSet<>();
        for (Item item : items.values()) {
            taken.add(item.slot);
        }
        int slot = 0;
        while (taken.contains(slot)) {
            slot++;
        }
        return slot;
    }

    /** The items on the floor, in the order they came to it. */
    Collection<Item> items() {
        return items.values();
    }

    /** The item of a piece on the floor, or null when it is not there. */
    Item item(int piece) {
        return items.get(piece);
    }

    /** The pieces flying back to the model. */
    List<Rise> rises() {
        return rises;
    }

    /** The lying piece whose item, {@code size} wide, covers a GUI point, or 0. */
    int pieceAt(double x, double y, float size) {
        int found = 0;
        for (Item item : items.values()) {
            if (!item.dropping() && Math.abs(x - item.x) <= size * 0.55f
                    && Math.abs(y - (item.y - size * LIFT)) <= size * 0.6f) {
                // A later item is drawn over an earlier one
                found = item.piece;
            }
        }
        return found;
    }
}
