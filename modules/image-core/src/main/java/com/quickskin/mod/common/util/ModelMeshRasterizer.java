package com.quickskin.mod.common.util;

import java.util.Arrays;

/**
 * Draws a captured model ({@link ModelMesh}) as the same small pictures {@link SkinBustRasterizer}
 * draws of a skin: its head and shoulders for the rows of the skin menu's list, or the whole
 * model standing for the cards of the favourites fan. The view, the light and the scale are the
 * bust's, so a CPM model's picture sits beside a skin's as one of the same set.
 *
 * <p>A skin's bust is a handful of boxes drawn back to front. A captured model is any number of
 * quads at any angle, which may cross, so each pixel keeps the quad nearest the camera
 * (a depth buffer); see-through texels are laid over what is behind them, far quads first.
 * Texels are sampled nearest, as the bust's are. A model taller or wider than a player is drawn
 * smaller, so its head or its whole figure still fits. Free of Minecraft and AWT types.
 */
public final class ModelMeshRasterizer {

    /** The bust's picture shows this many model pixels of height at the skin bust's scale. */
    private static final double BUST_WINDOW = 1.0 / SkinBustRasterizer.ZOOM_PER_HEIGHT;
    /** A bust is drawn at most this much smaller than a skin's to fit a tall head or hat. */
    private static final double MAX_BUST_SHRINK = 1.6;
    /** The whole model is drawn at most this much smaller than a skin's to fit. */
    private static final double MAX_BODY_SHRINK = 4.0;
    /** Space kept free above a model drawn smaller to fit, in model pixels. */
    private static final double FIT_MARGIN = 0.75;
    private static final double EDGE_EPSILON = 1e-7;

    private ModelMeshRasterizer() {
    }

    /**
     * The model's head and shoulders, framed as {@link SkinBustRasterizer#render} frames a skin.
     *
     * @return the picture's pixels as non-premultiplied ARGB, row by row; transparent around the model
     */
    public static int[] renderBust(ModelMesh mesh, int width, int height) {
        return draw(mesh, width, height, SkinBustRasterizer.CUT,
                SkinBustRasterizer.ZOOM_PER_HEIGHT * height, SkinBustRasterizer.DEFAULT_YAW, false);
    }

    /**
     * The whole model standing, its feet at the picture's bottom edge, turned {@code yawDegrees},
     * framed as {@link SkinBustRasterizer#renderBody(int[], int, int, boolean, int, int, double)}
     * frames a skin.
     */
    public static int[] renderBody(ModelMesh mesh, int width, int height, double yawDegrees) {
        return draw(mesh, width, height, SkinBustRasterizer.BODY_CUT,
                height / SkinBustRasterizer.BODY_HEIGHT, yawDegrees, true);
    }

    private static int[] draw(ModelMesh mesh, int width, int height, double cut, double zoom,
                              double yawDegrees, boolean wholeBody) {
        if (width < 1 || height < 1 || width > SkinBustRasterizer.MAX_SIZE || height > SkinBustRasterizer.MAX_SIZE) {
            throw new IllegalArgumentException("picture size out of range: " + width + "x" + height);
        }
        if (mesh == null) {
            throw new IllegalArgumentException("no mesh");
        }
        int[] out = new int[width * height];
        int quads = mesh.quadCount();
        if (quads == 0) {
            return out;
        }

        double[][] view = SkinBustRasterizer.multiply(
                SkinBustRasterizer.rotateX(Math.toRadians(SkinBustRasterizer.PITCH)),
                SkinBustRasterizer.rotateY(Math.toRadians(yawDegrees)));
        double[] base = SkinBustRasterizer.apply(view, new double[] {0, cut, 0});

        // Every vertex in the camera's space: x right, y up, z toward the camera, in model pixels
        double[] viewed = new double[quads * 12];
        double top = Double.NEGATIVE_INFINITY;
        double reach = 0.0;
        for (int quad = 0; quad < quads; quad++) {
            for (int vertex = 0; vertex < 4; vertex++) {
                // Model space has y down from the feet's 24 pixels and faces -z; the bust's space
                // has y up from the feet and faces +z. Half a turn about x, so nothing is mirrored.
                double[] point = SkinBustRasterizer.apply(view, new double[] {
                        mesh.position(quad, vertex, 0) * 16.0,
                        24.0 - mesh.position(quad, vertex, 1) * 16.0,
                        -mesh.position(quad, vertex, 2) * 16.0});
                int index = quad * 12 + vertex * 3;
                viewed[index] = point[0];
                viewed[index + 1] = point[1];
                viewed[index + 2] = point[2];
                top = Math.max(top, point[1]);
                reach = Math.max(reach, Math.abs(point[0] - base[0]));
            }
        }

        // A model that would leave the picture is drawn smaller: a bust by its height alone, as a
        // bust is expected to cut the shoulders; the whole model by its height and its width
        double shown = height / zoom;
        double shrink = (top + FIT_MARGIN - base[1]) / shown;
        if (wholeBody) {
            shrink = Math.max(shrink, (reach + FIT_MARGIN) * 2.0 / (width / zoom));
        }
        shrink = Math.min(wholeBody ? MAX_BODY_SHRINK : MAX_BUST_SHRINK, Math.max(1.0, shrink));
        zoom /= shrink;

        // Far quads first, so see-through texels lie over what is behind them
        Integer[] order = new Integer[quads];
        double[] depth = new double[quads];
        for (int quad = 0; quad < quads; quad++) {
            order[quad] = quad;
            int index = quad * 12;
            depth[quad] = (viewed[index + 2] + viewed[index + 5] + viewed[index + 8] + viewed[index + 11]) / 4.0;
        }
        Arrays.sort(order, (a, b) -> Double.compare(depth[a], depth[b]));

        double[] nearest = new double[width * height];
        Arrays.fill(nearest, Double.NEGATIVE_INFINITY);
        double[] sx = new double[4];
        double[] sy = new double[4];
        double[] sz = new double[4];
        double[] u = new double[4];
        double[] v = new double[4];
        for (int quad : order) {
            int index = quad * 12;
            for (int vertex = 0; vertex < 4; vertex++) {
                sx[vertex] = width / 2.0 + (viewed[index + vertex * 3] - base[0]) * zoom;
                sy[vertex] = height - (viewed[index + vertex * 3 + 1] - base[1]) * zoom;
                sz[vertex] = viewed[index + vertex * 3 + 2];
                u[vertex] = mesh.uv(quad, vertex, 0);
                v[vertex] = mesh.uv(quad, vertex, 1);
            }
            ModelMesh.Shading shading = mesh.shading(quad);
            boolean lit = shading == ModelMesh.Shading.TEXTURED || shading == ModelMesh.Shading.COLOR;
            boolean textured = shading == ModelMesh.Shading.TEXTURED
                    || shading == ModelMesh.Shading.TEXTURED_UNLIT;
            if (textured && mesh.textureWidth() == 0) {
                continue;
            }
            double shade = lit ? shade(viewed, index) : 1.0;
            int tint = mesh.color(quad, 0);
            fill(mesh, out, nearest, width, height, sx, sy, sz, u, v, 0, 1, 2, textured, shade, tint);
            fill(mesh, out, nearest, width, height, sx, sy, sz, u, v, 0, 2, 3, textured, shade, tint);
        }
        return out;
    }

    /**
     * How much of its colour a quad keeps under the bust's light. A captured quad may be wound
     * either way and is seen from whichever side faces the camera, so its normal is taken toward
     * the camera.
     */
    private static double shade(double[] viewed, int index) {
        double ax = viewed[index + 3] - viewed[index], ay = viewed[index + 4] - viewed[index + 1],
                az = viewed[index + 5] - viewed[index + 2];
        double bx = viewed[index + 6] - viewed[index], by = viewed[index + 7] - viewed[index + 1],
                bz = viewed[index + 8] - viewed[index + 2];
        double nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
        double length = Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (length < 1e-12) {
            // The first three corners lie on a line: take the last three
            ax = viewed[index + 6] - viewed[index + 3];
            ay = viewed[index + 7] - viewed[index + 4];
            az = viewed[index + 8] - viewed[index + 5];
            bx = viewed[index + 9] - viewed[index + 3];
            by = viewed[index + 10] - viewed[index + 4];
            bz = viewed[index + 11] - viewed[index + 5];
            nx = ay * bz - az * by;
            ny = az * bx - ax * bz;
            nz = ax * by - ay * bx;
            length = Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (length < 1e-12) {
                return 1.0;
            }
        }
        if (nz < 0) {
            nx = -nx;
            ny = -ny;
            nz = -nz;
        }
        double[] light = SkinBustRasterizer.LIGHT;
        double facing = (nx * light[0] + ny * light[1] + nz * light[2]) / length;
        return Math.min(1.0, SkinBustRasterizer.AMBIENT + SkinBustRasterizer.DIFFUSE * Math.max(0.0, facing));
    }

    /** Fills one triangle of a quad: every pixel whose centre lies in it. */
    private static void fill(ModelMesh mesh, int[] out, double[] nearest, int width, int height,
                             double[] sx, double[] sy, double[] sz, double[] u, double[] v,
                             int a, int b, int c, boolean textured, double shade, int tint) {
        double area = (sx[b] - sx[a]) * (sy[c] - sy[a]) - (sy[b] - sy[a]) * (sx[c] - sx[a]);
        if (Math.abs(area) < 1e-9) {
            return;
        }
        int minX = (int) Math.max(0, Math.floor(Math.min(sx[a], Math.min(sx[b], sx[c]))));
        int maxX = (int) Math.min(width - 1, Math.ceil(Math.max(sx[a], Math.max(sx[b], sx[c]))));
        int minY = (int) Math.max(0, Math.floor(Math.min(sy[a], Math.min(sy[b], sy[c]))));
        int maxY = (int) Math.min(height - 1, Math.ceil(Math.max(sy[a], Math.max(sy[b], sy[c]))));
        int textureWidth = mesh.textureWidth();
        int textureHeight = mesh.textureHeight();
        int[] texture = mesh.texture();
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                double px = x + 0.5, py = y + 0.5;
                double wa = ((sx[b] - px) * (sy[c] - py) - (sy[b] - py) * (sx[c] - px)) / area;
                double wb = ((sx[c] - px) * (sy[a] - py) - (sy[c] - py) * (sx[a] - px)) / area;
                double wc = 1.0 - wa - wb;
                if (wa < -EDGE_EPSILON || wb < -EDGE_EPSILON || wc < -EDGE_EPSILON) {
                    continue;
                }
                double z = wa * sz[a] + wb * sz[b] + wc * sz[c];
                int pixel = y * width + x;
                if (z < nearest[pixel]) {
                    continue;
                }
                int argb;
                if (textured) {
                    int tx = (int) Math.floor((wa * u[a] + wb * u[b] + wc * u[c]) * textureWidth);
                    int ty = (int) Math.floor((wa * v[a] + wb * v[b] + wc * v[c]) * textureHeight);
                    tx = Math.max(0, Math.min(textureWidth - 1, tx));
                    ty = Math.max(0, Math.min(textureHeight - 1, ty));
                    argb = tinted(texture[ty * textureWidth + tx], tint);
                } else {
                    argb = tint;
                }
                int alpha = argb >>> 24;
                if (alpha == 0) {
                    continue;
                }
                int shaded = SkinBustRasterizer.shaded(argb, shade);
                if (alpha == 255) {
                    out[pixel] = shaded;
                    nearest[pixel] = z;
                } else {
                    out[pixel] = SkinBustRasterizer.over(shaded, out[pixel]);
                }
            }
        }
    }

    /** A texel multiplied by a vertex colour, channel by channel. */
    private static int tinted(int argb, int tint) {
        if (tint == 0xFFFFFFFF) {
            return argb;
        }
        int a = (argb >>> 24) * (tint >>> 24) / 255;
        int r = ((argb >> 16) & 0xFF) * ((tint >> 16) & 0xFF) / 255;
        int g = ((argb >> 8) & 0xFF) * ((tint >> 8) & 0xFF) / 255;
        int b = (argb & 0xFF) * (tint & 0xFF) / 255;
        return a << 24 | r << 16 | g << 8 | b;
    }
}
