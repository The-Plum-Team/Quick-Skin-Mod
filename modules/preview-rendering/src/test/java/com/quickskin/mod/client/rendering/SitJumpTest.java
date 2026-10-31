package com.quickskin.mod.client.rendering;

import com.quickskin.mod.client.rendering.SitJump.Move;
import com.quickskin.mod.client.rendering.SitJump.Pose;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The jumps onto the block and off it: each starts where the pose it leaves is, never puts
 * standing feet inside the block, rides the block's top while the block squashes, moves without a
 * jolt from frame to frame and ends at rest, sitting on the block or standing in the picked pose.
 */
class SitJumpTest {

    private static final float FRAME = 1.0f / 60.0f;

    @Test
    void ontoStartsStandingWithoutTheBlock() {
        Pose start = SitJump.sample(Move.ONTO, 0.0f);
        assertFalse(start.sitting());
        assertFalse(start.blockShown());
        assertEquals(0.0f, start.lift());
        assertEquals(1.0f, start.scaleX());
        assertEquals(1.0f, start.scaleY());
        assertEquals(0.0f, start.settled());
    }

    @Test
    void offStartsSittingOnTheBlock() {
        assertEquals(Pose.ON_BLOCK, SitJump.sample(Move.OFF, 0.0f));
    }

    @Test
    void standingFeetStayAboveTheBlock() {
        for (Move move : Move.values()) {
            for (float t = 0.0f; t < SitJump.duration(move); t += FRAME) {
                Pose pose = SitJump.sample(move, t);
                if (pose.blockShown() && !pose.sitting()) {
                    // Sitting legs reach out over the block; standing feet would stick into it
                    assertTrue(pose.lift() >= SitJump.BLOCK_HEIGHT * pose.blockScaleY() - 0.2f,
                            move + ": block over standing feet at " + t);
                }
            }
        }
        assertTrue(SitJump.sample(Move.ONTO, SitJump.POP + 0.05f).blockShown());
    }

    @Test
    void ridesTheBlocksTopWhileItSquashes() {
        for (float t = SitJump.LAND; t < SitJump.duration(Move.ONTO); t += FRAME) {
            Pose pose = SitJump.sample(Move.ONTO, t);
            assertTrue(pose.sitting());
            assertTrue(pose.blockShown());
            float sink = (1.0f - pose.blockScaleY()) * SitJump.BLOCK_HEIGHT;
            assertEquals(-sink, pose.lift(), 0.02f, "onto at " + t);
        }
        for (float t = 0.0f; t < SitJump.WIND_UP; t += FRAME) {
            Pose pose = SitJump.sample(Move.OFF, t);
            float sink = (1.0f - pose.blockScaleY()) * SitJump.BLOCK_HEIGHT;
            assertEquals(-sink, pose.lift(), 0.001f, "off at " + t);
        }
    }

    @Test
    void offLandsStandingWithoutTheBlockAndSettlesIntoThePickedPose() {
        for (float t = SitJump.LAND_OFF; t < SitJump.duration(Move.OFF); t += FRAME) {
            Pose pose = SitJump.sample(Move.OFF, t);
            assertFalse(pose.sitting());
            assertFalse(pose.blockShown());
            assertEquals(0.0f, pose.lift());
        }
        assertEquals(0.0f, SitJump.sample(Move.OFF, SitJump.LAND_OFF).settled());
        assertEquals(Pose.OFF_BLOCK, SitJump.sample(Move.OFF, SitJump.duration(Move.OFF)));
    }

    @Test
    void endsAtRestWithoutAJolt() {
        for (Move move : Move.values()) {
            float end = SitJump.duration(move);
            Pose before = SitJump.sample(move, end - 0.001f);
            Pose rest = SitJump.sample(move, end);
            assertEquals(move == Move.ONTO ? Pose.ON_BLOCK : Pose.OFF_BLOCK, rest);
            assertEquals(rest.lift(), before.lift(), 0.005f, move.toString());
            assertEquals(rest.scaleY(), before.scaleY(), 0.005f, move.toString());
            assertEquals(rest.settled(), before.settled(), 0.005f, move.toString());
            if (move == Move.ONTO) {
                assertEquals(rest.blockScaleX(), before.blockScaleX(), 0.005f);
                assertEquals(rest.blockScaleY(), before.blockScaleY(), 0.005f);
            }
        }
    }

    @Test
    void movesSmoothlyFromFrameToFrame() {
        for (Move move : Move.values()) {
            Pose previous = SitJump.sample(move, 0.0f);
            for (float t = FRAME; t <= SitJump.duration(move) + FRAME; t += FRAME) {
                Pose pose = SitJump.sample(move, t);
                // No faster than the model falls
                assertEquals(previous.lift(), pose.lift(), 0.07f, move + " lift at " + t);
                assertEquals(previous.scaleY(), pose.scaleY(), 0.05f, move + " squash at " + t);
                assertEquals(previous.settled(), pose.settled(), 0.08f, move + " settling at " + t);
                // The pop grows fast, but within a frame it never jumps by more than a third of the block
                if (previous.blockShown() && pose.blockShown()) {
                    assertEquals(previous.blockScaleY(), pose.blockScaleY(), 0.35f, move + " block at " + t);
                }
                previous = pose;
            }
        }
    }
}
