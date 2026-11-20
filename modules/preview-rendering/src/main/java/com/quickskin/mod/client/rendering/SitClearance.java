package com.quickskin.mod.client.rendering;

/**
 * How far a cape tilts back, past its own angle, so that it clears the grass block the skin menu's
 * model sits on, in the menu and in the world.
 *
 * <p>A cape hangs 16 pixels from the shoulders, and the seated body's shoulders are only 12 to 14
 * pixels above the block's top, 2 to 3 pixels in front of its back face. Hanging at the 6 degrees
 * the game gives a cape at rest, the cape's lower edge goes into the block. Tilted back just far
 * enough, the cape's inner face passes behind the block's top back edge, and its last couple of
 * pixels hang below the top behind the back face, so the cape lies over the edge.
 *
 * <p>The tilt is worked out from where the block is, every frame, against the posed body: the sit
 * leans the upper body from 9 degrees forward to 12 back about the hips, which moves the edge
 * relative to the shoulders, and the jumps onto the block and off it lift and squash the model and
 * pop and squash the block (see {@link SitJump}). The cape's end comes down to the block's top at
 * nearly a pixel a frame as the model lands, so the cape starts tilting while its end is still
 * {@link #REACH} above the top and has its full tilt as the end gets there; rising off the block,
 * it lets the tilt go the same way. The cape therefore never snaps from one frame to the next.
 *
 * <p>Units: model pixels, with the vanilla model's axes (Y down, the front towards -Z); the model
 * root is 24 pixels above the feet. Angles in degrees, positive back. A cape is hinged 2 pixels
 * behind the body's pivot, as the game hangs it, and {@code inner} says how far behind that hinge,
 * along the cape's own depth, its inner face is: 0 for the cloak before 1.21.2, 2 for the cape model
 * from 1.21.2, which carries an offset of its own.
 *
 * <p>Folded elytra hang from the same hinge, 15 degrees back, and reach further down than a cape,
 * so leaning back the seated body puts their inner face into the block's top back edge as well:
 * {@link #wingsTilt} tilts them back the same way.
 *
 * <p>Pure geometry on plain numbers, so it is the same on every Minecraft version.
 */
public final class SitClearance {

    /** The block's size, as the preview draws it, in pixels. */
    static final float BLOCK = SitJump.BLOCK_HEIGHT * 16.0f;
    /** The middle of the block's bottom face, which its pop and squash keep in place: on the floor, under the hips. */
    static final float BLOCK_BOTTOM_Y = 24.0f;
    static final float BLOCK_CENTRE_Z = (0.28125f - SitJump.BLOCK_HEIGHT / 2.0f) * 16.0f;
    /** The model's feet, about which the jump lifts and squashes it: the 1.501 blocks the game moves the model down by. */
    static final float FEET = 1.501f * 16.0f;
    /** Where a cape is hinged behind the body's pivot. */
    static final float HINGE = 2.0f;
    /** The cape's length. */
    public static final float CAPE_LENGTH = 16.0f;
    /** Half the cape's width: the body's twist brings one of its lower corners nearer the block. */
    static final float CAPE_HALF_WIDTH = 5.0f;
    /** The angle the game hangs a cape at when the player stands still. */
    public static final float CAPE_REST = 6.0f;
    /** How far the cape stays from the edge, so it is never drawn into the grass. */
    static final float GAP = 0.6f;
    /** Never tilts the cape further back than this. */
    static final float MOST = 80.0f;
    /** How far above the block's top the cape's end starts tilting back as it comes down. */
    static final float REACH = 6.0f;
    /** The angle the game folds elytra at on a player standing, walking or sitting: 15 degrees back. */
    public static final float WINGS_REST = 15.0f;
    /**
     * Where a folded wing's inner face is, behind the hinge: a pixel in front of it, since the
     * wing's box starts on the hinge and the game inflates it by a pixel.
     */
    static final float WINGS_INNER = -1.0f;
    /** How far sideways each wing spreads after its fold, about Z. */
    static final float WINGS_SPREAD = 15.0f;
    /** How far to its side of the middle each wing is hinged. */
    static final float WING_HINGE_X = 5.0f;
    /**
     * The wing's inflated box in its own frame, across: a pixel past its hinge on the outside,
     * {@code WING_NEAR}, and 11 pixels the other way, past the middle of the back, {@code WING_FAR};
     * down: 21 pixels from the hinge.
     */
    static final float WING_NEAR = 1.0f;
    static final float WING_FAR = 11.0f;
    static final float WING_BOTTOM = 21.0f;

    private SitClearance() {
    }

    /**
     * The degrees a cape tilts back past {@code rest}, its own angle, to clear the block, or 0
     * when it clears it already or no block is drawn. {@code jump} is the jump going on, or null
     * when the model sits still on the block (when {@code block} says one is drawn); the body is
     * the posed body part's pivot in pixels and its angles in radians, as a {@code ModelPart}
     * holds them.
     */
    public static float capeTilt(SitJump.Pose jump, boolean block, float rest, float inner, float length,
                                 float bodyX, float bodyY, float bodyZ,
                                 float bodyXRot, float bodyYRot, float bodyZRot) {
        // The edge where it passes the cape's two sides: the body's twist brings one nearer
        return tilt(jump, block, rest, inner, length, CAPE_HALF_WIDTH,
                bodyX, bodyY, bodyZ, bodyXRot, bodyYRot, bodyZRot);
    }

    /**
     * The degrees folded elytra tilt back past {@code rest}, the angle the game folds them at
     * ({@link #WINGS_REST} standing still), about the same hinge as a cape, to clear the block; the
     * arguments are those of {@link #capeTilt}. Each wing is hinged 5 pixels to its side and
     * spread {@link #WINGS_SPREAD} sideways after its fold, which turns its inner face sideways
     * too, and the two wings together are wider than the block: so the whole of the block's top
     * back edge that each wing covers is kept behind that wing's own inner face.
     */
    public static float wingsTilt(SitJump.Pose jump, boolean block, float rest,
                                  float bodyX, float bodyY, float bodyZ,
                                  float bodyXRot, float bodyYRot, float bodyZRot) {
        if (!block) {
            return 0.0f;
        }
        float halfBlock = BLOCK / 2.0f * (jump != null ? jump.blockScaleX() : 1.0f);
        double[][] edge = {
                edgeInBody(jump, -halfBlock, bodyX, bodyY, bodyZ, bodyXRot, bodyYRot, bodyZRot),
                edgeInBody(jump, halfBlock, bodyX, bodyY, bodyZ, bodyXRot, bodyYRot, bodyZRot)};
        double fold = Math.toRadians(rest);
        // How far above the block's top, less the gap, the wings' lowest corner hangs at their own angle
        double lowest = wingPoint(fold, 0.0, -1, -WING_FAR, WING_BOTTOM, WINGS_INNER)[1];
        double above = Math.min(edge[0][1], edge[1][1]) - GAP - lowest;
        if (above >= REACH || wingsClear(0.0, fold, edge)) {
            return 0.0f;
        }
        double low = 0.0;
        double high = Math.toRadians(MOST - rest);
        if (!wingsClear(high, fold, edge)) {
            return MOST - rest;
        }
        for (int i = 0; i < 24; i++) {
            double middle = (low + high) / 2.0;
            if (wingsClear(middle, fold, edge)) {
                high = middle;
            } else {
                low = middle;
            }
        }
        float tilt = (float) Math.toDegrees(high);
        if (above > 0.0) {
            // The wings have not come down to the top yet: tilt part of the way, as the cape does
            double near = 1.0 - above / REACH;
            tilt *= (float) (near * near * (3.0 - 2.0 * near));
        }
        return tilt;
    }

    /**
     * Whether both wings, folded {@code fold} radians and tilted back {@code tilt} more about the
     * hinge, keep every point of the block's top back edge they cover, from {@code edge[0]} to
     * {@code edge[1]} (in the body's space from the hinge), the gap behind their inner face. Along
     * the straight edge a wing's frame changes linearly, so the part each wing covers is a piece of
     * the edge, checked at its two ends.
     */
    private static boolean wingsClear(double tilt, double fold, double[][] edge) {
        for (int side = -1; side <= 1; side += 2) {
            double[] from = inWing(edge[0], tilt, fold, side);
            double[] to = inWing(edge[1], tilt, fold, side);
            // The wing's width, in its own frame: from its hinge across the back, past the middle
            double near = -side * WING_NEAR;
            double far = side * WING_FAR;
            double start = 0.0;
            double end = 1.0;
            double across = to[0] - from[0];
            if (Math.abs(across) < 1.0e-9) {
                if (from[0] < Math.min(near, far) || from[0] > Math.max(near, far)) {
                    continue;
                }
            } else {
                double first = (near - from[0]) / across;
                double second = (far - from[0]) / across;
                start = Math.max(start, Math.min(first, second));
                end = Math.min(end, Math.max(first, second));
                if (start > end) {
                    continue;
                }
            }
            double depthStart = from[2] + (to[2] - from[2]) * start;
            double depthEnd = from[2] + (to[2] - from[2]) * end;
            if (Math.max(depthStart, depthEnd) > WINGS_INNER - GAP) {
                return false;
            }
        }
        return true;
    }

    /**
     * A point in the body's space from the hinge, {@code {x, down, behind}}, in the frame of the
     * wing on {@code side} (-1 for the left wing, hinged at x = 5), folded {@code fold} radians and
     * tilted back {@code tilt} more: the inverse of the tilt, then the hinge's offset, then the
     * wing's rotation (its fold about X, then its spread about Z, which the game gives the left
     * wing as -15 degrees).
     */
    private static double[] inWing(double[] point, double tilt, double fold, int side) {
        double cos = Math.cos(tilt);
        double sin = Math.sin(tilt);
        double y = cos * point[1] + sin * point[2];
        double z = -sin * point[1] + cos * point[2];
        double x = point[0] + side * WING_HINGE_X;
        double[] r = SkinSwapSkeleton.rotation(fold, 0.0, side * Math.toRadians(WINGS_SPREAD));
        return new double[] {
                r[0] * x + r[3] * y + r[6] * z,
                r[1] * x + r[4] * y + r[7] * z,
                r[2] * x + r[5] * y + r[8] * z};
    }

    /** A point of the wing on {@code side}, given in its own frame, in the body's space from the hinge. */
    static double[] wingPoint(double fold, double tilt, int side, double x, double y, double z) {
        double[] r = SkinSwapSkeleton.rotation(fold, 0.0, side * Math.toRadians(WINGS_SPREAD));
        double bx = r[0] * x + r[1] * y + r[2] * z - side * WING_HINGE_X;
        double by = r[3] * x + r[4] * y + r[5] * z;
        double bz = r[6] * x + r[7] * y + r[8] * z;
        double cos = Math.cos(tilt);
        double sin = Math.sin(tilt);
        return new double[] {bx, cos * by - sin * bz, sin * by + cos * bz};
    }

    /**
     * The tilt of a flat face hinged behind the body, {@code inner} behind the hinge and
     * {@code length} long, that keeps the block's top back edge behind it where the edge is
     * {@code side} pixels either side of the middle. The edge is straight, so if both those points
     * are behind the face, every point between them is.
     */
    private static float tilt(SitJump.Pose jump, boolean block, float rest, float inner, float length, float side,
                              float bodyX, float bodyY, float bodyZ,
                              float bodyXRot, float bodyYRot, float bodyZRot) {
        if (!block) {
            return 0.0f;
        }
        float[][] edges = {
                edgeBehindHinge(jump, -side, bodyX, bodyY, bodyZ, bodyXRot, bodyYRot, bodyZRot),
                edgeBehindHinge(jump, side, bodyX, bodyY, bodyZ, bodyXRot, bodyYRot, bodyZRot)};
        double low = Math.toRadians(rest);
        // How far above the block's top, less the gap, the cape's end hangs at its own angle
        double above = Double.MAX_VALUE;
        for (float[] edge : edges) {
            above = Math.min(above, edge[0] - GAP - (length * Math.cos(low) - inner * Math.sin(low)));
        }
        if (above >= REACH || !inFront(low, edges, inner)) {
            return 0.0f;
        }
        double high = Math.toRadians(MOST);
        if (inFront(high, edges, inner)) {
            return MOST - rest;
        }
        // The further back the cape tilts, the further back it passes the edge, so the least tilt
        // that takes it behind the edge is found by halving
        for (int i = 0; i < 24; i++) {
            double middle = (low + high) / 2.0;
            if (inFront(middle, edges, inner)) {
                low = middle;
            } else {
                high = middle;
            }
        }
        float tilt = (float) Math.toDegrees(high) - rest;
        if (above > 0.0) {
            // The end has not come down to the top yet: tilt part of the way, more the nearer it is
            double near = 1.0 - above / REACH;
            tilt *= (float) (near * near * (3.0 - 2.0 * near));
        }
        return tilt;
    }

    /**
     * Whether the cape tilted back {@code angle} radians crosses the block's top in front of its
     * back edge, less the gap, at either side.
     */
    private static boolean inFront(double angle, float[][] edges, double inner) {
        for (float[] edge : edges) {
            if (crossing(angle, edge[0], inner) < edge[1] + GAP) {
                return true;
            }
        }
        return false;
    }

    /**
     * A point of the block's top back edge, {@code side} pixels to the side of the middle, in the
     * body's own space, measured from the cape's hinge: {@code {down, back}}. The jump's lift and
     * squash move the model and not the block, so the edge is taken back through them, then
     * through the body's pose.
     */
    static float[] edgeBehindHinge(SitJump.Pose jump, float side, float bodyX, float bodyY, float bodyZ,
                                   float bodyXRot, float bodyYRot, float bodyZRot) {
        double[] edge = edgeInBody(jump, side, bodyX, bodyY, bodyZ, bodyXRot, bodyYRot, bodyZRot);
        return new float[] {(float) edge[1], (float) edge[2]};
    }

    /** The same point of the edge as {@link #edgeBehindHinge}, with how far to the side it is: {@code {x, down, back}}. */
    static double[] edgeInBody(SitJump.Pose jump, float side, float bodyX, float bodyY, float bodyZ,
                               float bodyXRot, float bodyYRot, float bodyZRot) {
        float blockScaleX = jump != null ? jump.blockScaleX() : 1.0f;
        float blockScaleY = jump != null ? jump.blockScaleY() : 1.0f;
        double across = side;
        double top = BLOCK_BOTTOM_Y - BLOCK * blockScaleY;
        double back = BLOCK_CENTRE_Z + BLOCK / 2.0f * blockScaleX;
        if (jump != null) {
            // The model is drawn lifted by the jump and squashed about its feet: undo both
            top = FEET + (top + jump.lift() * 16.0 - FEET) / jump.scaleY();
            back = back / jump.scaleX();
            across = across / jump.scaleX();
        }
        // Into the body's space: the inverse of its pose, a rotation Rz Ry Rx about its pivot
        double[] r = SkinSwapSkeleton.rotation(bodyXRot, bodyYRot, bodyZRot);
        double x = across - bodyX;
        double y = top - bodyY;
        double z = back - bodyZ;
        double sideways = r[0] * x + r[3] * y + r[6] * z;
        double down = r[1] * x + r[4] * y + r[7] * z;
        double behind = r[2] * x + r[5] * y + r[8] * z;
        return new double[] {sideways, down, behind - HINGE};
    }

    /**
     * How far behind the hinge the cape's inner face, tilted back {@code angle} radians from the
     * body, crosses the level {@code down} pixels below the hinge: the block's top. The inner face
     * is the line from the hinge, {@code inner} behind it, down the cape's length; behind the
     * block's back edge, the part of the cape below the top hangs behind the back face.
     */
    static double crossing(double angle, double down, double inner) {
        double cos = Math.cos(angle);
        double sin = Math.sin(angle);
        double along = (down + inner * sin) / cos;
        return inner * cos + along * sin;
    }
}
