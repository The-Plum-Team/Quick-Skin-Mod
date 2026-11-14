package com.quickskin.mod.common.util;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pictures of a captured model, beside the pictures of a skin. */
class ModelMeshRasterizerTest {

    private static final int RED = 0xFFFF0000;
    private static final int GREEN = 0xFF00FF00;
    private static final int BLUE = 0xFF0000FF;
    private static final int YELLOW = 0xFFFFFF00;
    private static final int WHITE = 0xFFFFFFFF;

    /** A 64x64 skin with only the head's front: four quadrants, each its own colour. */
    private static int[] skinWithHeadFront() {
        int[] pixels = new int[64 * 64];
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                pixels[(8 + y) * 64 + 8 + x] = y < 4 ? (x < 4 ? RED : GREEN) : (x < 4 ? BLUE : YELLOW);
            }
        }
        return pixels;
    }

    /** A quad in the bust's space (pixels, y up from the feet, facing +z), as model space. */
    private static void quad(ModelMesh.Builder builder, ModelMesh.Shading shading, int color, double[]... corners) {
        for (double[] corner : corners) {
            builder.vertex((float) (corner[0] / 16.0), (float) ((24.0 - corner[1]) / 16.0),
                    (float) (-corner[2] / 16.0), (float) corner[3], (float) corner[4], color, shading);
        }
    }

    /** The head's front face alone, with the skin's own texture coordinates. */
    private static ModelMesh headFront(int[] skin) {
        ModelMesh.Builder builder = new ModelMesh.Builder();
        quad(builder, ModelMesh.Shading.TEXTURED, WHITE,
                new double[] {-4, 32, 4, 8 / 64.0, 8 / 64.0},
                new double[] {4, 32, 4, 16 / 64.0, 8 / 64.0},
                new double[] {4, 24, 4, 16 / 64.0, 16 / 64.0},
                new double[] {-4, 24, 4, 8 / 64.0, 16 / 64.0});
        return builder.build(skin, 64, 64);
    }

    private static long opaque(int[] picture) {
        return Arrays.stream(picture).filter(p -> (p >>> 24) == 255).count();
    }

    @Test
    void drawsAFaceWhereTheSkinBustDrawsItInTheSameLight() {
        int[] skin = skinWithHeadFront();
        int[] bust = SkinBustRasterizer.render(skin, 64, 64, false, 120, 99);
        int[] model = ModelMeshRasterizer.renderBust(headFront(skin), 120, 99);

        long drawn = opaque(model);
        assertTrue(drawn > 1500, "the face is drawn: " + drawn);
        // The same camera, turn, side and light: the pictures differ only along the face's edge,
        // where the bust widens each face by half a pixel
        long same = 0;
        long both = 0;
        for (int i = 0; i < bust.length; i++) {
            if ((bust[i] >>> 24) == 255 && (model[i] >>> 24) == 255) {
                both++;
                if (bust[i] == model[i]) {
                    same++;
                }
            }
        }
        assertTrue(both > drawn * 0.97, "the face covers the bust's face: " + both + " of " + drawn);
        assertTrue(same > both * 0.97, "the texels and their shade match the bust's: " + same + " of " + both);
    }

    @Test
    void keepsTheQuadNearestTheCameraWhateverTheOrder() {
        // A blue quad behind a red one, written first and written last
        for (boolean nearFirst : new boolean[] {true, false}) {
            ModelMesh.Builder builder = new ModelMesh.Builder();
            double[][] near = {{-4, 32, 4, 0, 0}, {4, 32, 4, 0, 0}, {4, 24, 4, 0, 0}, {-4, 24, 4, 0, 0}};
            double[][] far = {{-4, 32, -4, 0, 0}, {4, 32, -4, 0, 0}, {4, 24, -4, 0, 0}, {-4, 24, -4, 0, 0}};
            if (nearFirst) {
                quad(builder, ModelMesh.Shading.COLOR_UNLIT, RED, near);
                quad(builder, ModelMesh.Shading.COLOR_UNLIT, BLUE, far);
            } else {
                quad(builder, ModelMesh.Shading.COLOR_UNLIT, BLUE, far);
                quad(builder, ModelMesh.Shading.COLOR_UNLIT, RED, near);
            }
            int[] picture = ModelMeshRasterizer.renderBust(builder.build(null, 0, 0), 120, 99);
            long red = Arrays.stream(picture).filter(p -> p == RED).count();
            long blue = Arrays.stream(picture).filter(p -> p == BLUE).count();
            assertTrue(red > 1500, "the near quad is whole: " + red);
            // The far quad shows only where the turned view lets it out from behind the near one
            assertTrue(blue > 0 && blue < red, "the far quad is hidden behind the near one: " + blue);
        }
    }

    @Test
    void lightsTexturedQuadsAndLeavesGlowingOnesAtFullBrightness() {
        int[] texture = {RED};
        double[][] side = {{4, 32, 4, 0, 0}, {4, 32, -4, 0, 0}, {4, 24, -4, 0, 0}, {4, 24, 4, 0, 0}};
        ModelMesh.Builder lit = new ModelMesh.Builder();
        quad(lit, ModelMesh.Shading.TEXTURED, WHITE, side);
        ModelMesh.Builder glowing = new ModelMesh.Builder();
        quad(glowing, ModelMesh.Shading.TEXTURED_UNLIT, WHITE, side);

        int[] shaded = ModelMeshRasterizer.renderBust(lit.build(texture, 1, 1), 120, 99);
        int[] bright = ModelMeshRasterizer.renderBust(glowing.build(texture, 1, 1), 120, 99);
        assertTrue(Arrays.stream(bright).anyMatch(p -> p == RED), "a glowing quad keeps its colour");
        assertFalse(Arrays.stream(shaded).anyMatch(p -> p == RED), "a side turned from the light is darker");
        assertTrue(Arrays.stream(shaded).anyMatch(p -> (p & 0xFF00FFFF) == 0xFF000000 && ((p >> 16) & 0xFF) > 0x40),
                "and still red");
    }

    @Test
    void tintsTheTextureWithTheVertexColour() {
        ModelMesh.Builder builder = new ModelMesh.Builder();
        quad(builder, ModelMesh.Shading.TEXTURED_UNLIT, 0xFF0000FF,
                new double[] {-4, 32, 4, 0, 0}, new double[] {4, 32, 4, 0, 0},
                new double[] {4, 24, 4, 0, 0}, new double[] {-4, 24, 4, 0, 0});
        int[] picture = ModelMeshRasterizer.renderBust(builder.build(new int[] {WHITE}, 1, 1), 120, 99);
        assertTrue(Arrays.stream(picture).anyMatch(p -> p == BLUE));
        assertFalse(Arrays.stream(picture).anyMatch(p -> p == WHITE));
    }

    @Test
    void laysSeeThroughTexelsOverWhatIsBehindThem() {
        ModelMesh.Builder builder = new ModelMesh.Builder();
        // A half-transparent red pane in front of an opaque blue one, written first
        quad(builder, ModelMesh.Shading.COLOR_UNLIT, 0x80FF0000,
                new double[] {-4, 32, 4, 0, 0}, new double[] {4, 32, 4, 0, 0},
                new double[] {4, 24, 4, 0, 0}, new double[] {-4, 24, 4, 0, 0});
        quad(builder, ModelMesh.Shading.COLOR_UNLIT, BLUE,
                new double[] {-4, 32, 3, 0, 0}, new double[] {4, 32, 3, 0, 0},
                new double[] {4, 24, 3, 0, 0}, new double[] {-4, 24, 3, 0, 0});
        int[] picture = ModelMeshRasterizer.renderBust(builder.build(null, 0, 0), 120, 99);
        assertTrue(Arrays.stream(picture).anyMatch(p -> (p >>> 24) == 255
                && ((p >> 16) & 0xFF) > 0x60 && (p & 0xFF) > 0x60 && ((p >> 8) & 0xFF) == 0),
                "red over blue is purple");
    }

    @Test
    void drawsATallModelSmallerSoItsHeadFits() {
        ModelMesh.Builder player = new ModelMesh.Builder();
        quad(player, ModelMesh.Shading.COLOR_UNLIT, RED,
                new double[] {-4, 32, 0, 0, 0}, new double[] {4, 32, 0, 0, 0},
                new double[] {4, 0, 0, 0, 0}, new double[] {-4, 0, 0, 0, 0});
        ModelMesh.Builder giant = new ModelMesh.Builder();
        quad(giant, ModelMesh.Shading.COLOR_UNLIT, RED,
                new double[] {-4, 64, 0, 0, 0}, new double[] {4, 64, 0, 0, 0},
                new double[] {4, 0, 0, 0, 0}, new double[] {-4, 0, 0, 0, 0});

        int[] playerBody = ModelMeshRasterizer.renderBody(player.build(null, 0, 0), 60, 100,
                SkinBustRasterizer.DEFAULT_YAW);
        int[] giantBody = ModelMeshRasterizer.renderBody(giant.build(null, 0, 0), 60, 100,
                SkinBustRasterizer.DEFAULT_YAW);
        // Neither touches the top edge, and the giant is drawn narrower to fit its height
        assertTrue(Arrays.stream(playerBody, 0, 60).allMatch(p -> p == 0), "the player's top row is clear");
        assertTrue(Arrays.stream(giantBody, 0, 60).allMatch(p -> p == 0), "the giant's top row is clear");
        assertTrue(widest(giantBody, 60) < widest(playerBody, 60), "the giant is drawn smaller");
        // The feet stand just above the bottom edge either way, as a skin's do
        assertTrue(Arrays.stream(playerBody, 96 * 60, 97 * 60).anyMatch(p -> p == RED));
        assertTrue(Arrays.stream(giantBody, 96 * 60, 97 * 60).anyMatch(p -> p == RED));
        assertTrue(Arrays.stream(playerBody, 99 * 60, 100 * 60).allMatch(p -> p == 0));
    }

    private static int widest(int[] picture, int width) {
        int widest = 0;
        for (int y = 0; y < picture.length / width; y++) {
            int count = 0;
            for (int x = 0; x < width; x++) {
                if (picture[y * width + x] != 0) {
                    count++;
                }
            }
            widest = Math.max(widest, count);
        }
        return widest;
    }

    @Test
    void anEmptyMeshIsAnEmptyPicture() {
        int[] picture = ModelMeshRasterizer.renderBust(new ModelMesh.Builder().build(null, 0, 0), 10, 10);
        assertEquals(100, picture.length);
        assertTrue(Arrays.stream(picture).allMatch(p -> p == 0));
    }

    @Test
    void refusesAPictureOutOfRange() {
        ModelMesh mesh = new ModelMesh.Builder().build(null, 0, 0);
        assertThrows(IllegalArgumentException.class, () -> ModelMeshRasterizer.renderBust(mesh, 0, 10));
        assertThrows(IllegalArgumentException.class,
                () -> ModelMeshRasterizer.renderBust(mesh, SkinBustRasterizer.MAX_SIZE + 1, 10));
        assertThrows(IllegalArgumentException.class, () -> ModelMeshRasterizer.renderBust(null, 10, 10));
    }

    @Test
    void aMeshKeepsWholeQuadsOnlyAndStopsWhenFull() {
        ModelMesh.Builder builder = new ModelMesh.Builder();
        for (int i = 0; i < 6; i++) {
            builder.vertex(i, 0, 0, 0, 0, WHITE, ModelMesh.Shading.COLOR);
        }
        ModelMesh mesh = builder.build(null, 0, 0);
        assertEquals(1, mesh.quadCount(), "two loose vertices are not a quad");
        assertEquals(3.0f, mesh.position(0, 3, 0));
        assertEquals(ModelMesh.Shading.COLOR, mesh.shading(0));
        assertEquals(0, mesh.textureWidth());

        ModelMesh.Builder full = new ModelMesh.Builder();
        for (int i = 0; i < (ModelMesh.MAX_QUADS + 2) * 4; i++) {
            full.vertex(0, 0, 0, 0, 0, WHITE, ModelMesh.Shading.COLOR);
        }
        assertTrue(full.isFull());
        assertEquals(ModelMesh.MAX_QUADS, full.build(null, 0, 0).quadCount());
    }

    @Test
    void aTextureOfTheWrongSizeIsLeftOut() {
        ModelMesh.Builder builder = new ModelMesh.Builder();
        for (int i = 0; i < 4; i++) {
            builder.vertex(i, 0, 0, 0, 0, WHITE, ModelMesh.Shading.TEXTURED);
        }
        ModelMesh mesh = builder.build(new int[3], 2, 2);
        assertEquals(0, mesh.textureWidth());
        // Its textured quads then draw nothing
        assertTrue(Arrays.stream(ModelMeshRasterizer.renderBust(mesh, 20, 20)).allMatch(p -> p == 0));
    }
}
