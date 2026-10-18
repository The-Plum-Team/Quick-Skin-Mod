package com.quickskin.mod.common.util;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws a skin's head and shoulders as a small 3D picture, for the rows of the skin menu's list.
 *
 * <p>The player's head, body and arms are boxes with the skin's own UV layout, inner and outer
 * layer, turned a little toward the right and seen from slightly above, in parallel projection.
 * Each face is lit by its angle to one light from the upper front, so the bust reads as solid, and
 * texels are sampled nearest, so a skin's pixels stay sharp at any size. Faces are drawn back to
 * front, which is exact for these separate convex boxes.
 *
 * <p>The picture is computed once per skin and size and then shown as a texture, so it costs
 * nothing per frame and looks the same on every Minecraft version, whatever that version's GUI
 * renderer can draw. Free of Minecraft and AWT types so it is unit testable.
 */
public final class SkinBustRasterizer {

    /** The largest picture side, in pixels. */
    public static final int MAX_SIZE = 1024;

    /** How far the bust is turned toward the right, and how far the camera looks down, in degrees. */
    private static final double YAW = 28.0;
    private static final double PITCH = 7.0;
    /** Model pixels shown per picture pixel, for a picture this many pixels tall... */
    private static final double ZOOM_PER_HEIGHT = 6.4 / 99.0;
    /** ...with its bottom edge this high on the model, in model pixels above the feet: mid-chest. */
    private static final double CUT = 17.5;
    /** Faces in full light keep all their colour; faces turned away keep this share of it. */
    private static final double AMBIENT = 0.42;
    private static final double DIFFUSE = 0.68;
    private static final double[] LIGHT = normalize(new double[] {0.3, 0.55, 0.8});

    private SkinBustRasterizer() {
    }

    /**
     * The bust of a skin.
     *
     * @param skin the skin's pixels as ARGB, row by row: a 64x64 layout at any whole multiple, or
     *             the legacy 64x32 one
     * @param slim whether the arms are three pixels wide instead of four
     * @return the picture's pixels as non-premultiplied ARGB, row by row; transparent around the bust
     */
    public static int[] render(int[] skin, int skinWidth, int skinHeight, boolean slim, int width, int height) {
        if (width < 1 || height < 1 || width > MAX_SIZE || height > MAX_SIZE) {
            throw new IllegalArgumentException("bust size out of range: " + width + "x" + height);
        }
        if (skinWidth < 64 || skinWidth % 64 != 0
                || (skinHeight != skinWidth && skinHeight * 2 != skinWidth)
                || skin == null || skin.length != skinWidth * skinHeight) {
            throw new IllegalArgumentException("not a skin layout: " + skinWidth + "x" + skinHeight);
        }
        boolean legacy = skinHeight != skinWidth;
        int texelSize = skinWidth / 64;
        Texture texture = new Texture(skin, skinWidth, skinHeight, texelSize);

        double yaw = Math.toRadians(YAW);
        double pitch = Math.toRadians(PITCH);
        double[][] view = multiply(rotateX(pitch), rotateY(yaw));
        double zoom = ZOOM_PER_HEIGHT * height;
        double[] base = apply(view, new double[] {0, CUT, 0});

        List<Face> faces = new ArrayList<>();
        int armWidth = slim ? 3 : 4;
        addPart(faces, view, base, zoom, width, height, -4, 24, -4, 4, 32, 4, 0, 0, 32, 0, 0.5, legacy);
        addPart(faces, view, base, zoom, width, height, -4, 12, -2, 4, 24, 2, 16, 16, 16, 32, 0.25, legacy);
        addPart(faces, view, base, zoom, width, height, -4 - armWidth, 12, -2, -4, 24, 2, 40, 16, 40, 32, 0.25, legacy);
        // The legacy layout has one arm, which vanilla mirrors; the bust shows that arm's front
        addPart(faces, view, base, zoom, width, height, 4, 12, -2, 4 + armWidth, 24, 2,
                legacy ? 40 : 32, legacy ? 16 : 48, 48, 48, 0.25, legacy);
        faces.sort((a, b) -> Double.compare(a.depth, b.depth));

        int[] out = new int[width * height];
        for (Face face : faces) {
            face.draw(texture, out, width, height);
        }
        return out;
    }

    /** One box's inner layer and, inflated around it, its outer layer. */
    private static void addPart(List<Face> faces, double[][] view, double[] base, double zoom, int width, int height,
                                double x0, double y0, double z0, double x1, double y1, double z1,
                                int u, int v, int outerU, int outerV, double inflate, boolean legacy) {
        int w = (int) (x1 - x0), h = (int) (y1 - y0), d = (int) (z1 - z0);
        addBox(faces, view, base, zoom, width, height, x0, y0, z0, x1, y1, z1, u, v, w, h, d, 0.0);
        // The legacy layout has an outer layer for the head only
        if (!legacy || outerV < 32) {
            addBox(faces, view, base, zoom, width, height, x0 - inflate, y0 - inflate, z0 - inflate,
                    x1 + inflate, y1 + inflate, z1 + inflate, outerU, outerV, w, h, d, inflate);
        }
    }

    /**
     * The five faces a bust can show (not the bottom), each as its texture rectangle in skin
     * texels and its corners at the texture's top-left, along the texture's u axis and along its v
     * axis. The layout is Minecraft's: the right side, front, left side and back in a row under the
     * top and bottom. The player faces +z; their right side is at -x.
     */
    private static void addBox(List<Face> faces, double[][] view, double[] base, double zoom, int width, int height,
                               double x0, double y0, double z0, double x1, double y1, double z1,
                               int u, int v, int w, int h, int d, double inflate) {
        double[][][] corners = {
                {{x0, y1, z1}, {x1, y1, z1}, {x0, y0, z1}},   // front
                {{x1, y1, z0}, {x0, y1, z0}, {x1, y0, z0}},   // back
                {{x0, y1, z0}, {x0, y1, z1}, {x0, y0, z0}},   // right side
                {{x1, y1, z1}, {x1, y1, z0}, {x1, y0, z1}},   // left side
                {{x0, y1, z0}, {x1, y1, z0}, {x0, y1, z1}},   // top
        };
        int[][] rects = {
                {u + d, v + d, w, h},
                {u + 2 * d + w, v + d, w, h},
                {u, v + d, d, h},
                {u + d + w, v + d, d, h},
                {u + d, v, w, d},
        };
        for (int i = 0; i < corners.length; i++) {
            double[] a = project(view, base, zoom, width, height, corners[i][0]);
            double[] b = project(view, base, zoom, width, height, corners[i][1]);
            double[] c = project(view, base, zoom, width, height, corners[i][2]);
            double ux = b[0] - a[0], uy = b[1] - a[1];
            double vx = c[0] - a[0], vy = c[1] - a[1];
            if (ux * vy - uy * vx <= 0) {
                continue; // turned away from the camera
            }
            double[] normal = normalize(apply(view, cross(subtract(corners[i][1], corners[i][0]),
                    subtract(corners[i][2], corners[i][0]))));
            double light = -(normal[0] * LIGHT[0] + normal[1] * LIGHT[1] + normal[2] * LIGHT[2]);
            double shade = Math.min(1.0, AMBIENT + DIFFUSE * Math.max(0.0, light));
            double depth = (b[2] + c[2]) / 2.0 + inflate * 0.01;
            faces.add(new Face(rects[i], a[0], a[1], ux, uy, vx, vy, shade, depth));
        }
    }

    /** A model point to picture pixels, with its depth toward the camera third. */
    private static double[] project(double[][] view, double[] base, double zoom, int width, int height, double[] point) {
        double[] r = apply(view, point);
        return new double[] {width / 2.0 + (r[0] - base[0]) * zoom, height - (r[1] - base[1]) * zoom, r[2]};
    }

    private record Texture(int[] pixels, int width, int height, int texelSize) {
        int sample(int x, int y) {
            return x < 0 || y < 0 || x >= width || y >= height ? 0 : pixels[y * width + x];
        }
    }

    private record Face(int[] rect, double ax, double ay, double ux, double uy, double vx, double vy,
                        double shade, double depth) {

        void draw(Texture texture, int[] out, int width, int height) {
            int texelSize = texture.texelSize();
            int texWidth = rect[2] * texelSize;
            int texHeight = rect[3] * texelSize;
            if (texWidth == 0 || texHeight == 0) {
                return;
            }
            // Half a pixel more on every side, so that neighbouring faces meet without a seam
            double uLength = Math.hypot(ux, uy), vLength = Math.hypot(vx, vy);
            double ox = ax - ux / uLength * 0.5 - vx / vLength * 0.5;
            double oy = ay - uy / uLength * 0.5 - vy / vLength * 0.5;
            double eux = ux * (1 + 1 / uLength), euy = uy * (1 + 1 / uLength);
            double evx = vx * (1 + 1 / vLength), evy = vy * (1 + 1 / vLength);
            double det = eux * evy - euy * evx;
            if (Math.abs(det) < 1e-9) {
                return;
            }
            int minX = (int) Math.max(0, Math.floor(min4(ox, ox + eux, ox + evx, ox + eux + evx)));
            int maxX = (int) Math.min(width - 1, Math.ceil(max4(ox, ox + eux, ox + evx, ox + eux + evx)));
            int minY = (int) Math.max(0, Math.floor(min4(oy, oy + euy, oy + evy, oy + euy + evy)));
            int maxY = (int) Math.min(height - 1, Math.ceil(max4(oy, oy + euy, oy + evy, oy + euy + evy)));
            int originX = rect[0] * texelSize, originY = rect[1] * texelSize;
            for (int y = minY; y <= maxY; y++) {
                for (int x = minX; x <= maxX; x++) {
                    double px = x + 0.5 - ox, py = y + 0.5 - oy;
                    double s = (px * evy - py * evx) / det;
                    double t = (py * eux - px * euy) / det;
                    if (s < 0 || t < 0 || s >= 1 || t >= 1) {
                        continue;
                    }
                    int texel = texture.sample(originX + (int) (s * texWidth), originY + (int) (t * texHeight));
                    if ((texel >>> 24) == 0) {
                        continue;
                    }
                    out[y * width + x] = over(shaded(texel, shade), out[y * width + x]);
                }
            }
        }
    }

    private static int shaded(int argb, double shade) {
        int r = (int) Math.round(((argb >> 16) & 0xFF) * shade);
        int g = (int) Math.round(((argb >> 8) & 0xFF) * shade);
        int b = (int) Math.round((argb & 0xFF) * shade);
        return (argb & 0xFF000000) | r << 16 | g << 8 | b;
    }

    /** Source-over compositing of non-premultiplied ARGB pixels. */
    public static int over(int src, int dst) {
        int sa = src >>> 24;
        if (sa == 255) {
            return src;
        }
        int da = dst >>> 24;
        int outA = sa + da * (255 - sa) / 255;
        if (outA == 0) {
            return 0;
        }
        int r = (((src >> 16) & 0xFF) * sa + ((dst >> 16) & 0xFF) * da * (255 - sa) / 255) / outA;
        int g = (((src >> 8) & 0xFF) * sa + ((dst >> 8) & 0xFF) * da * (255 - sa) / 255) / outA;
        int b = ((src & 0xFF) * sa + (dst & 0xFF) * da * (255 - sa) / 255) / outA;
        return outA << 24 | r << 16 | g << 8 | b;
    }

    private static double min4(double a, double b, double c, double d) {
        return Math.min(Math.min(a, b), Math.min(c, d));
    }

    private static double max4(double a, double b, double c, double d) {
        return Math.max(Math.max(a, b), Math.max(c, d));
    }

    private static double[][] rotateY(double angle) {
        double c = Math.cos(angle), s = Math.sin(angle);
        return new double[][] {{c, 0, s}, {0, 1, 0}, {-s, 0, c}};
    }

    private static double[][] rotateX(double angle) {
        double c = Math.cos(angle), s = Math.sin(angle);
        return new double[][] {{1, 0, 0}, {0, c, -s}, {0, s, c}};
    }

    private static double[][] multiply(double[][] a, double[][] b) {
        double[][] m = new double[3][3];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                m[i][j] = a[i][0] * b[0][j] + a[i][1] * b[1][j] + a[i][2] * b[2][j];
            }
        }
        return m;
    }

    private static double[] apply(double[][] m, double[] p) {
        return new double[] {
                m[0][0] * p[0] + m[0][1] * p[1] + m[0][2] * p[2],
                m[1][0] * p[0] + m[1][1] * p[1] + m[1][2] * p[2],
                m[2][0] * p[0] + m[2][1] * p[1] + m[2][2] * p[2]};
    }

    private static double[] subtract(double[] a, double[] b) {
        return new double[] {a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }

    private static double[] cross(double[] a, double[] b) {
        return new double[] {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    private static double[] normalize(double[] v) {
        double length = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        return new double[] {v[0] / length, v[1] / length, v[2] / length};
    }
}
