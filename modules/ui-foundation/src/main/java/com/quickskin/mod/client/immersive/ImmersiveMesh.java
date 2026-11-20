package com.quickskin.mod.client.immersive;

import java.util.Arrays;
import java.util.TreeSet;

/**
 * The mesh the floating screen's picture is drawn with, worked out on the CPU in GUI units, the
 * same on every Minecraft version: {@link ImmersiveSurface} only hands each version's GPU the
 * vertices.
 *
 * <p>A mesh is a run of quads, four vertices each in the order they are drawn, every vertex
 * {@link #STRIDE} floats: where it is drawn ({@code x}, {@code y} in GUI units) and the point of
 * the picture it shows ({@code u}, {@code v}, from 0 to 1, with the picture's first row its
 * bottom one, as the game's render targets hold it).
 */
public final class ImmersiveMesh {
    /** Floats per vertex: x, y, u, v. */
    public static final int STRIDE = 4;
    /** Vertices per quad. */
    public static final int QUAD = 4;
    /** The mesh's cells are about this many GUI units of the screen across. */
    static final float CELL = 16.0f;

    private ImmersiveMesh() {
    }

    /**
     * The picture of the screen where {@code frame} stands it this frame, in a GUI of
     * {@code guiWidth} by {@code guiHeight} units. The flat sheet is cut along the panels' edges,
     * so no cell lies half under a panel, and each rectangle a part holds is a grid of small
     * quads, so it bends as the perspective does. A cell with a corner the camera cannot see
     * (behind it, or nearer than the frame draws) is left out.
     */
    public static float[] sheet(ImmersiveFrame frame, float guiWidth, float guiHeight) {
        float scaleX = guiWidth / frame.width();
        float scaleY = guiHeight / frame.height();
        TreeSet<Float> columns = new TreeSet<>();
        TreeSet<Float> rows = new TreeSet<>();
        columns.add(0.0f);
        columns.add(frame.width());
        rows.add(0.0f);
        rows.add(frame.height());
        for (ImmersiveLayout.Panel panel : frame.panels()) {
            columns.add(clamp(panel.left(), frame.width()));
            columns.add(clamp(panel.right(), frame.width()));
            rows.add(clamp(panel.top(), frame.height()));
            rows.add(clamp(panel.bottom(), frame.height()));
        }
        Builder builder = new Builder();
        Float left = null;
        for (Float right : columns) {
            if (left != null) {
                Float top = null;
                for (Float bottom : rows) {
                    if (top != null) {
                        int part = frame.partAt((left + right) / 2.0f, (top + bottom) / 2.0f);
                        cells(builder, frame, part, left, top, right, bottom, scaleX, scaleY);
                    }
                    top = bottom;
                }
            }
            left = right;
        }
        return builder.build();
    }

    /**
     * The picture of what the screen drew above itself (its tooltip) as one flat quad over the
     * whole GUI, moved by {@code (aboveX, aboveY)} screen units: from where the mouse points on the
     * floating screen to where the mouse is.
     */
    public static float[] above(ImmersiveFrame frame, float guiWidth, float guiHeight, float aboveX, float aboveY) {
        float movedLeft = aboveX * (guiWidth / frame.width());
        float movedTop = aboveY * (guiHeight / frame.height());
        return new float[]{
                movedLeft, movedTop, 0.0f, 1.0f,
                movedLeft, movedTop + guiHeight, 0.0f, 0.0f,
                movedLeft + guiWidth, movedTop + guiHeight, 1.0f, 0.0f,
                movedLeft + guiWidth, movedTop, 1.0f, 1.0f,
        };
    }

    /** One rectangle of the screen, held by one part, as a grid of small quads. */
    private static void cells(Builder builder, ImmersiveFrame frame, int part, float left, float top, float right,
                              float bottom, float scaleX, float scaleY) {
        int across = Math.max(1, (int) Math.ceil((right - left) / CELL));
        int down = Math.max(1, (int) Math.ceil((bottom - top) / CELL));
        float[][][] points = new float[across + 1][down + 1][];
        for (int column = 0; column <= across; column++) {
            float x = left + (right - left) * column / across;
            for (int row = 0; row <= down; row++) {
                float y = top + (bottom - top) * row / down;
                float[] seen = new float[4];
                if (frame.project(part, x, y, seen)) {
                    seen[0] *= scaleX;
                    seen[1] *= scaleY;
                    seen[2] = x / frame.width();
                    // The picture's first row is its bottom one
                    seen[3] = 1.0f - y / frame.height();
                    points[column][row] = seen;
                }
            }
        }
        for (int column = 0; column < across; column++) {
            for (int row = 0; row < down; row++) {
                float[] a = points[column][row];
                float[] b = points[column][row + 1];
                float[] c = points[column + 1][row + 1];
                float[] d = points[column + 1][row];
                if (a == null || b == null || c == null || d == null) continue;
                builder.add(a);
                builder.add(b);
                builder.add(c);
                builder.add(d);
            }
        }
    }

    private static float clamp(float value, float limit) {
        return Math.max(0.0f, Math.min(limit, value));
    }

    /** A growing run of vertices. */
    private static final class Builder {
        private float[] data = new float[1024];
        private int size;

        void add(float[] point) {
            if (size + STRIDE > data.length) {
                data = Arrays.copyOf(data, data.length * 2);
            }
            System.arraycopy(point, 0, data, size, STRIDE);
            size += STRIDE;
        }

        float[] build() {
            return Arrays.copyOf(data, size);
        }
    }
}
