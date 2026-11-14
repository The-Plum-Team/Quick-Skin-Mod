package com.quickskin.mod.client.rendering;

import com.quickskin.mod.client.rendering.SkinSwapAnimation.Pose;
import com.quickskin.mod.client.rendering.SkinSwapAnimation.Variant;
import com.quickskin.mod.client.rendering.SkinSwapSkeleton.Part;
import net.minecraft.client.model.geom.ModelPart;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkinSwapPoserTest {

    private static final int RIGHT_ARM = Part.RIGHT_ARM.ordinal();
    /** The frames of a move the tests play: 20 a second for two seconds, past every move's end. */
    private static final int FRAMES = 40;

    /**
     * A player model as vanilla poses it before 1.21.2: every frame sets the angles a walk uses and
     * leaves the pivots and the roll alone. With {@code swapping}, another mod puts parts of its own
     * in the model for each draw, as Customizable Player Models does: they start from the vanilla
     * parts' poses, and each vanilla part that is drawn is left with the pose it was drawn in.
     */
    private static final class Model {
        final ModelPart[] vanilla = sixParts();
        final ModelPart[] swappedIn = sixParts();
        final boolean swapping;
        ModelPart[] held = vanilla;

        Model(boolean swapping) {
            this.swapping = swapping;
        }

        /** One draw of the model: the preview's while {@code move} is not null, else the world's. */
        void draw(Pose move, int... drawn) {
            if (swapping) {
                for (int i = 0; i < vanilla.length; i++) {
                    copy(vanilla[i], swappedIn[i]);
                }
                held = swappedIn;
            }
            // The head of setupAnim, vanilla's own posing, and its tail
            for (ModelPart part : held) {
                SkinSwapPoser.restore(part);
            }
            for (ModelPart part : held) {
                part.xRot = 0.0f;
                part.yRot = 0.0f;
            }
            if (move != null && !move.isRest()) {
                SkinSwapPoser.layOver(() -> held, move);
            }
            if (swapping) {
                for (int i : drawn) {
                    copy(swappedIn[i], vanilla[i]);
                }
                held = vanilla;
            }
        }

        void drawWhole(Pose move) {
            draw(move, 0, 1, 2, 3, 4, 5);
        }
    }

    @Test
    void aMoveLeavesNothingOnAModelWhosePartsAreSwappedForEachDraw() {
        for (Variant variant : Variant.values()) {
            Model model = new Model(true);
            float[][] baked = poses(model.vanilla);
            for (int frame = 0; frame < FRAMES; frame++) {
                // The first-person hand, drawn by the world, poses the model and draws one arm
                model.draw(null, RIGHT_ARM);
                Pose move = SkinSwapAnimation.sample(variant, frame / 20.0f);
                SkinSwapPoser.beginInline(move);
                model.drawWhole(move);
                SkinSwapPoser.endInline();
                assertArrayEquals(baked, poses(model.vanilla), variant + " at frame " + frame);
            }
            // The world's draw of the whole player afterwards starts from the baked poses
            model.drawWhole(null);
            assertArrayEquals(baked, poses(model.vanilla), variant + " in the world");
        }
    }

    @Test
    void aMoveShowsDuringItsDrawAndIsGoneWhenTheDrawEnds() {
        Model model = new Model(false);
        float[][] baked = poses(model.vanilla);
        boolean moved = false;
        for (int frame = 0; frame < FRAMES; frame++) {
            Pose move = SkinSwapAnimation.sample(Variant.values()[0], frame / 20.0f);
            SkinSwapPoser.beginInline(move);
            model.drawWhole(move);
            moved |= !Arrays.deepEquals(baked, poses(model.vanilla));
            SkinSwapPoser.endInline();
            assertArrayEquals(baked, poses(model.vanilla), "frame " + frame);
        }
        assertTrue(moved, "the move never changed a part");
    }

    /** The six parts with the pivots {@code PlayerModel} bakes them with, classic arms. */
    private static ModelPart[] sixParts() {
        float[][] pivots = new float[Part.values().length][];
        pivots[Part.HEAD.ordinal()] = new float[] {0.0f, 0.0f, 0.0f};
        pivots[Part.BODY.ordinal()] = new float[] {0.0f, 0.0f, 0.0f};
        pivots[Part.RIGHT_ARM.ordinal()] = new float[] {-5.0f, 2.0f, 0.0f};
        pivots[Part.LEFT_ARM.ordinal()] = new float[] {5.0f, 2.0f, 0.0f};
        pivots[Part.RIGHT_LEG.ordinal()] = new float[] {-1.9f, 12.0f, 0.0f};
        pivots[Part.LEFT_LEG.ordinal()] = new float[] {1.9f, 12.0f, 0.0f};
        ModelPart[] parts = new ModelPart[pivots.length];
        for (int i = 0; i < parts.length; i++) {
            parts[i] = new ModelPart(List.of(), Map.of());
            parts[i].x = pivots[i][0];
            parts[i].y = pivots[i][1];
            parts[i].z = pivots[i][2];
        }
        return parts;
    }

    private static void copy(ModelPart from, ModelPart to) {
        to.x = from.x;
        to.y = from.y;
        to.z = from.z;
        to.xRot = from.xRot;
        to.yRot = from.yRot;
        to.zRot = from.zRot;
    }

    private static float[][] poses(ModelPart[] parts) {
        float[][] poses = new float[parts.length][];
        for (int i = 0; i < parts.length; i++) {
            ModelPart part = parts[i];
            poses[i] = new float[] {part.x, part.y, part.z, part.xRot, part.yRot, part.zRot, part.xScale};
        }
        return poses;
    }
}
