package com.quickskin.mod.common.util;

import java.util.Arrays;

/**
 * The quads a model drew, as captured from the vertices it wrote, with the one texture they
 * sample: what {@link ModelMeshRasterizer} needs to draw a picture of a model whose geometry only
 * another mod knows, such as a CPM model.
 *
 * <p>Positions are in Minecraft's model space as a preview's own model is drawn with an identity
 * pose: blocks, y pointing down with the feet at 1.5, the player facing -z, their right side at
 * -x. Texture coordinates run from 0 to 1 across the texture. Free of Minecraft and AWT types.
 */
public final class ModelMesh {

    /** How one quad takes its colour. */
    public enum Shading {
        /** The texture, lit by the quad's angle to the light. */
        TEXTURED,
        /** The texture at full brightness: a glowing part. */
        TEXTURED_UNLIT,
        /** The vertex colour alone, lit. */
        COLOR,
        /** The vertex colour alone at full brightness. */
        COLOR_UNLIT
    }

    /** The most quads one mesh keeps; a model with more is cut off there. */
    public static final int MAX_QUADS = 65_536;
    /** The largest texture side a mesh keeps. */
    public static final int MAX_TEXTURE_SIDE = 8192;

    private static final int POSITION_FLOATS = 3 * 4;
    private static final int UV_FLOATS = 2 * 4;

    private final float[] positions;
    private final float[] uvs;
    private final int[] colors;
    private final byte[] shadings;
    private final int quadCount;
    private final int[] texture;
    private final int textureWidth;
    private final int textureHeight;

    private ModelMesh(float[] positions, float[] uvs, int[] colors, byte[] shadings, int quadCount,
                      int[] texture, int textureWidth, int textureHeight) {
        this.positions = positions;
        this.uvs = uvs;
        this.colors = colors;
        this.shadings = shadings;
        this.quadCount = quadCount;
        this.texture = texture;
        this.textureWidth = textureWidth;
        this.textureHeight = textureHeight;
    }

    public int quadCount() {
        return quadCount;
    }

    /** One coordinate (0 x, 1 y, 2 z) of a quad's vertex (0 to 3). */
    public float position(int quad, int vertex, int axis) {
        return positions[quad * POSITION_FLOATS + vertex * 3 + axis];
    }

    /** One texture coordinate (0 u, 1 v) of a quad's vertex. */
    public float uv(int quad, int vertex, int axis) {
        return uvs[quad * UV_FLOATS + vertex * 2 + axis];
    }

    /** The ARGB colour of a quad's vertex, which tints the texture. */
    public int color(int quad, int vertex) {
        return colors[quad * 4 + vertex];
    }

    public Shading shading(int quad) {
        return Shading.values()[shadings[quad]];
    }

    /** The texture's pixels as non-premultiplied ARGB, row by row; empty without a texture. */
    public int[] texture() {
        return texture;
    }

    public int textureWidth() {
        return textureWidth;
    }

    public int textureHeight() {
        return textureHeight;
    }

    /** Collects a mesh one vertex at a time, four to a quad, in the order a model writes them. */
    public static final class Builder {
        private float[] positions = new float[POSITION_FLOATS * 256];
        private float[] uvs = new float[UV_FLOATS * 256];
        private int[] colors = new int[4 * 256];
        private byte[] shadings = new byte[256];
        private int vertexCount;
        private boolean full;

        /**
         * Adds the next vertex of the quad being written; its fourth vertex completes the quad
         * with {@code shading}. Vertices past {@link #MAX_QUADS} quads are dropped.
         */
        public void vertex(float x, float y, float z, float u, float v, int argb, Shading shading) {
            int quad = vertexCount / 4;
            if (quad >= MAX_QUADS) {
                full = true;
                return;
            }
            if (quad >= shadings.length) {
                int capacity = Math.min(MAX_QUADS, shadings.length * 2);
                positions = Arrays.copyOf(positions, POSITION_FLOATS * capacity);
                uvs = Arrays.copyOf(uvs, UV_FLOATS * capacity);
                colors = Arrays.copyOf(colors, 4 * capacity);
                shadings = Arrays.copyOf(shadings, capacity);
            }
            int vertex = vertexCount % 4;
            int p = quad * POSITION_FLOATS + vertex * 3;
            positions[p] = x;
            positions[p + 1] = y;
            positions[p + 2] = z;
            int t = quad * UV_FLOATS + vertex * 2;
            uvs[t] = u;
            uvs[t + 1] = v;
            colors[quad * 4 + vertex] = argb;
            shadings[quad] = (byte) shading.ordinal();
            vertexCount++;
        }

        /** How many whole quads were written so far. */
        public int quadCount() {
            return vertexCount / 4;
        }

        /** Whether quads were dropped because the mesh was full. */
        public boolean isFull() {
            return full;
        }

        /**
         * The mesh of the whole quads written, with their texture.
         *
         * @param texture the texture's pixels as ARGB, row by row, or null for a mesh of
         *                coloured quads only
         */
        public ModelMesh build(int[] texture, int textureWidth, int textureHeight) {
            int quads = vertexCount / 4;
            boolean textured = texture != null && textureWidth > 0 && textureHeight > 0
                    && textureWidth <= MAX_TEXTURE_SIDE && textureHeight <= MAX_TEXTURE_SIDE
                    && texture.length == textureWidth * textureHeight;
            return new ModelMesh(
                    Arrays.copyOf(positions, quads * POSITION_FLOATS),
                    Arrays.copyOf(uvs, quads * UV_FLOATS),
                    Arrays.copyOf(colors, quads * 4),
                    Arrays.copyOf(shadings, quads),
                    quads,
                    textured ? texture : new int[0],
                    textured ? textureWidth : 0,
                    textured ? textureHeight : 0);
        }
    }
}
