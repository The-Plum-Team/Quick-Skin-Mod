package com.quickskin.mod.client.rendering;

import com.quickskin.mod.client.rendering.SitJump.Move;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cape tilted back over the sit block's edge: through the whole sit loop and both jumps, in
 * every frame, no point of the cape is inside the block, checked here in three dimensions against
 * the block as the preview draws it; the tilt is the least that clears, eases with the jumps
 * instead of snapping, and is nothing while no block is drawn or the model is high above it. The
 * folded elytra, tilted the same way, never enter the block either.
 */
class SitClearanceTest {

    private static final float FRAME = 1.0f / 60.0f;
    /** The cloak before 1.21.2 has its inner face on the hinge; the cape model from 1.21.2 two pixels behind. */
    private static final float[] INNER_FACES = {0.0f, 2.0f};

    @Test
    void noBlockNoTilt() {
        SkinSwapSkeleton.PartPose body = sitBody(7.0);
        assertEquals(0.0f, tilt(null, false, 0.0f, body));
    }

    @Test
    void theRestingAngleCutsIntoTheBlockWhileSitting() {
        // The reported problem: the 6 degrees a cape hangs at put its end into the block, all
        // through the sit for the cloak, and while leaning back for the cape model, whose own
        // offset holds it 2 pixels further back
        for (double t = 0.0; t < PreviewLoops.SIT_CYCLE; t += 0.25) {
            assertTrue(cutsBlock(null, sitBody(t), 0.0f, SitClearance.CAPE_REST), "t=" + t);
        }
        assertTrue(cutsBlock(null, sitBody(10.0), 2.0f, SitClearance.CAPE_REST));
    }

    @Test
    void theCapeNeverEntersTheBlockThroughTheSitLoop() {
        for (float inner : INNER_FACES) {
            for (double t = 0.0; t < PreviewLoops.SIT_CYCLE; t += FRAME) {
                SkinSwapSkeleton.PartPose body = sitBody(t);
                float angle = SitClearance.CAPE_REST + tilt(null, true, inner, body);
                assertFalse(cutsBlock(null, body, inner, angle), "inner=" + inner + " t=" + t);
            }
        }
    }

    @Test
    void theTiltIsTheLeastThatClears() {
        for (float inner : INNER_FACES) {
            for (double t = 0.0; t < PreviewLoops.SIT_CYCLE; t += 0.5) {
                SkinSwapSkeleton.PartPose body = sitBody(t);
                float tilt = tilt(null, true, inner, body);
                assertTrue(tilt >= 0.0f && tilt < 20.0f, "t=" + t + " tilt=" + tilt);
                if (tilt > 4.0f) {
                    // A few degrees less, past the gap kept from the edge, and the cape is in the block
                    assertTrue(cutsBlock(null, body, inner, SitClearance.CAPE_REST + tilt - 4.0f), "t=" + t);
                }
            }
        }
    }

    @Test
    void theCapeNeverEntersTheBlockThroughTheJumps() {
        for (Move move : Move.values()) {
            for (float inner : INNER_FACES) {
                for (float t = 0.0f; t < SitJump.duration(move); t += FRAME) {
                    SitJump.Pose jump = SitJump.sample(move, t);
                    SkinSwapSkeleton.PartPose body = jumpBody(jump, t);
                    float angle = SitClearance.CAPE_REST + tilt(jump, jump.blockShown(), inner, body);
                    assertFalse(jump.blockShown() && cutsBlock(jump, body, inner, angle),
                            move + " inner=" + inner + " t=" + t);
                }
            }
        }
    }

    @Test
    void theTiltEasesWithTheJumpsInsteadOfSnapping() {
        for (Move move : Move.values()) {
            float previous = Float.NaN;
            for (float t = 0.0f; t < SitJump.duration(move) + 0.5f; t += FRAME) {
                SitJump.Pose jump = SitJump.sample(move, t);
                float tilt = tilt(jump, jump.blockShown(), 0.0f, jumpBody(jump, t));
                if (!Float.isNaN(previous)) {
                    assertTrue(Math.abs(tilt - previous) < 2.0f, move + " t=" + t + ": " + previous + " -> " + tilt);
                }
                previous = tilt;
            }
        }
        float previous = Float.NaN;
        for (double t = 0.0; t < PreviewLoops.SIT_CYCLE; t += FRAME) {
            float tilt = tilt(null, true, 0.0f, sitBody(t));
            if (!Float.isNaN(previous)) {
                assertTrue(Math.abs(tilt - previous) < 0.5f, "sit t=" + t);
            }
            previous = tilt;
        }
    }

    @Test
    void highAboveTheBlockTheCapeHangsAsItWould() {
        // At the top of the jump onto the block the block has popped up far below the feet
        float apex = SitJump.CROUCH + SitJump.AIR / 2.0f;
        SitJump.Pose jump = SitJump.sample(Move.ONTO, apex + 0.05f);
        assertTrue(jump.blockShown());
        assertEquals(0.0f, tilt(jump, true, 0.0f, jumpBody(jump, apex + 0.05f)));
    }

    @Test
    void foldedWingsAtTheirOwnAngleCutIntoTheBlockLeaningBack() {
        // The review's finding: leaning back, the wings' inflated inner face goes into the top back edge
        assertTrue(wingsCutBlock(null, sitBody(10.0), 0.0f));
    }

    @Test
    void foldedWingsNeverEnterTheBlockThroughTheSitLoopAndTheJumps() {
        for (double t = 0.0; t < PreviewLoops.SIT_CYCLE; t += FRAME) {
            SkinSwapSkeleton.PartPose body = sitBody(t);
            assertFalse(wingsCutBlock(null, body, wingsTilt(null, true, body)), "t=" + t);
        }
        for (Move move : Move.values()) {
            for (float t = 0.0f; t < SitJump.duration(move); t += FRAME) {
                SitJump.Pose jump = SitJump.sample(move, t);
                SkinSwapSkeleton.PartPose body = jumpBody(jump, t);
                assertFalse(jump.blockShown() && wingsCutBlock(jump, body, wingsTilt(jump, jump.blockShown(), body)),
                        move + " t=" + t);
            }
        }
    }

    @Test
    void theWingsTiltIsSmallAndEases() {
        float previous = Float.NaN;
        for (double t = 0.0; t < PreviewLoops.SIT_CYCLE; t += FRAME) {
            SkinSwapSkeleton.PartPose body = sitBody(t);
            float tilt = wingsTilt(null, true, body);
            assertTrue(tilt >= 0.0f && tilt < 20.0f, "t=" + t + " tilt=" + tilt);
            if (tilt > 4.0f && Math.round(t / FRAME) % 30 == 0) {
                // The least that clears: a few degrees less and the wings are in the block
                assertTrue(wingsCutBlock(null, body, tilt - 4.0f), "t=" + t);
            }
            if (!Float.isNaN(previous)) {
                assertTrue(Math.abs(tilt - previous) < 0.5f, "sit t=" + t);
            }
            previous = tilt;
        }
        for (Move move : Move.values()) {
            previous = Float.NaN;
            for (float t = 0.0f; t < SitJump.duration(move) + 0.5f; t += FRAME) {
                SitJump.Pose jump = SitJump.sample(move, t);
                float tilt = wingsTilt(jump, jump.blockShown(), jumpBody(jump, t));
                if (!Float.isNaN(previous)) {
                    assertTrue(Math.abs(tilt - previous) < 2.0f, move + " t=" + t + ": " + previous + " -> " + tilt);
                }
                previous = tilt;
            }
        }
        assertEquals(0.0f, wingsTilt(null, false, sitBody(10.0)));
    }

    private static float wingsTilt(SitJump.Pose jump, boolean block, SkinSwapSkeleton.PartPose body) {
        return SitClearance.wingsTilt(jump, block, SitClearance.WINGS_REST,
                body.x, body.y, body.z, body.xRot, body.yRot, body.zRot);
    }

    private static float tilt(SitJump.Pose jump, boolean block, float inner, SkinSwapSkeleton.PartPose body) {
        return SitClearance.capeTilt(jump, block, SitClearance.CAPE_REST, inner, SitClearance.CAPE_LENGTH,
                body.x, body.y, body.z, body.xRot, body.yRot, body.zRot);
    }

    /** The body in the sit loop, as the preview poses it from the baked stance. */
    private static SkinSwapSkeleton.PartPose sitBody(double seconds) {
        SkinSwapSkeleton.PartPose body = new SkinSwapSkeleton.PartPose().set(0, 0, 0, 0, 0, 0);
        SkinSwapSkeleton.poseBody(PreviewLoops.sit(seconds), body);
        return body;
    }

    /** The body during a jump: seated over the block, standing in the calm idle otherwise. */
    private static SkinSwapSkeleton.PartPose jumpBody(SitJump.Pose jump, double seconds) {
        SkinSwapSkeleton.PartPose body = new SkinSwapSkeleton.PartPose().set(0, 0, 0, 0, 0, 0);
        SkinSwapSkeleton.poseBody(jump.sitting() ? PreviewLoops.sit(seconds)
                : PreviewLoops.idle(PreviewLoops.Idle.CALM, seconds), body);
        return body;
    }

    /**
     * Whether any point of the cape's inner face, hung {@code angle} degrees back from the body, is
     * inside the block, worked out in three dimensions the way the preview draws both: the cape
     * hinged 2 pixels behind the body, the body posed, the model lifted and squashed by the jump,
     * and the block popped and squashed about the middle of its bottom face.
     */
    private static boolean cutsBlock(SitJump.Pose jump, SkinSwapSkeleton.PartPose body, float inner, float angle) {
        double[] bodyTurn = SkinSwapSkeleton.rotation(body.xRot, body.yRot, body.zRot);
        double[] capeTurn = SkinSwapSkeleton.rotation(Math.toRadians(angle), 0.0, 0.0);
        float blockScaleX = jump != null ? jump.blockScaleX() : 1.0f;
        float blockScaleY = jump != null ? jump.blockScaleY() : 1.0f;
        double half = SitClearance.BLOCK / 2.0 * blockScaleX;
        double top = SitClearance.BLOCK_BOTTOM_Y - SitClearance.BLOCK * blockScaleY;
        for (double x = -5.0; x <= 5.0; x += 0.5) {
            for (double down = 0.0; down <= SitClearance.CAPE_LENGTH; down += 0.25) {
                double[] onCape = apply(capeTurn, new double[] {x, down, inner});
                double[] inBody = {onCape[0], onCape[1], onCape[2] + SitClearance.HINGE};
                double[] turned = apply(bodyTurn, inBody);
                double mx = turned[0] + body.x;
                double my = turned[1] + body.y;
                double mz = turned[2] + body.z;
                if (jump != null) {
                    my = SitClearance.FEET + jump.scaleY() * (my - SitClearance.FEET) - jump.lift() * 16.0;
                    mx *= jump.scaleX();
                    mz *= jump.scaleX();
                }
                boolean inside = Math.abs(mx) < half
                        && my > top && my < SitClearance.BLOCK_BOTTOM_Y
                        && Math.abs(mz - SitClearance.BLOCK_CENTRE_Z) < half;
                if (inside) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Whether any point of the folded elytra, both wings' whole boxes as the game builds them (10
     * by 20 by 2 pixels, inflated by a pixel, hinged 5 pixels either side of the middle, folded 15
     * degrees back and then spread 15 degrees sideways), hung 2 pixels behind the posed body and
     * tilted back {@code tilt} degrees about that hinge as the draw does, is inside the block.
     */
    private static boolean wingsCutBlock(SitJump.Pose jump, SkinSwapSkeleton.PartPose body, float tilt) {
        double[] bodyTurn = SkinSwapSkeleton.rotation(body.xRot, body.yRot, body.zRot);
        double[] hingeTurn = SkinSwapSkeleton.rotation(Math.toRadians(tilt), 0.0, 0.0);
        for (int wing = -1; wing <= 1; wing += 2) {
            // The left wing (wing -1) is hinged at x = 5 and spreads -15 degrees about Z
            double[] wingTurn = SkinSwapSkeleton.rotation(Math.toRadians(SitClearance.WINGS_REST), 0.0,
                    Math.toRadians(15.0 * wing));
            double pivot = -5.0 * wing;
            double from = wing < 0 ? -11.0 : -1.0;
            for (double x = from; x <= from + 12.0; x += 0.5) {
                for (double y = -1.0; y <= 21.0; y += 0.5) {
                    for (double z = -1.0; z <= 3.0; z += 0.5) {
                        double[] onWing = apply(wingTurn, new double[] {x, y, z});
                        double[] tilted = apply(hingeTurn, new double[] {onWing[0] + pivot, onWing[1], onWing[2]});
                        double[] inBody = {tilted[0], tilted[1], tilted[2] + SitClearance.HINGE};
                        if (inBlock(jump, body, bodyTurn, inBody)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    /** Whether a point in the posed body's space is inside the block as the preview draws it. */
    private static boolean inBlock(SitJump.Pose jump, SkinSwapSkeleton.PartPose body, double[] bodyTurn, double[] point) {
        float blockScaleX = jump != null ? jump.blockScaleX() : 1.0f;
        float blockScaleY = jump != null ? jump.blockScaleY() : 1.0f;
        double half = SitClearance.BLOCK / 2.0 * blockScaleX;
        double top = SitClearance.BLOCK_BOTTOM_Y - SitClearance.BLOCK * blockScaleY;
        double[] turned = apply(bodyTurn, point);
        double mx = turned[0] + body.x;
        double my = turned[1] + body.y;
        double mz = turned[2] + body.z;
        if (jump != null) {
            my = SitClearance.FEET + jump.scaleY() * (my - SitClearance.FEET) - jump.lift() * 16.0;
            mx *= jump.scaleX();
            mz *= jump.scaleX();
        }
        return Math.abs(mx) < half && my > top && my < SitClearance.BLOCK_BOTTOM_Y
                && Math.abs(mz - SitClearance.BLOCK_CENTRE_Z) < half;
    }

    private static double[] apply(double[] r, double[] v) {
        return new double[] {
                r[0] * v[0] + r[1] * v[1] + r[2] * v[2],
                r[3] * v[0] + r[4] * v[1] + r[5] * v[2],
                r[6] * v[0] + r[7] * v[1] + r[8] * v[2]};
    }
}
