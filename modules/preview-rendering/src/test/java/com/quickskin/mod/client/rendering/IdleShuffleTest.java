package com.quickskin.mod.client.rendering;

import com.quickskin.mod.client.rendering.PreviewLoops.Idle;
import com.quickskin.mod.client.rendering.SkinSwapAnimation.Axis;
import com.quickskin.mod.client.rendering.SkinSwapAnimation.Bone;
import com.quickskin.mod.client.rendering.SkinSwapAnimation.Pose;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A preview's idle loops until the skin changes, then changes to another one without a jump. */
class IdleShuffleTest {

    private static final double FRAME = 1.0 / 60.0;

    @Test
    void theIdleLoopsUntilTheSkinChanges() {
        Random random = new Random(7);
        IdleShuffle shuffle = new IdleShuffle();
        shuffle.sample(100.0, random::nextInt);
        Idle first = shuffle.current();
        for (double t = 100.0 + FRAME; t < 100.0 + 600.0; t += FRAME) {
            shuffle.sample(t, random::nextInt);
            assertEquals(first, shuffle.current(), "changed on its own at " + t);
        }
    }

    @Test
    void everySkinChangePicksAnotherIdleWithoutAJump() {
        Random random = new Random(7);
        IdleShuffle shuffle = new IdleShuffle();
        Set<Idle> seen = EnumSet.noneOf(Idle.class);
        Pose previous = shuffle.sample(100.0, random::nextInt);
        Idle playing = shuffle.current();
        seen.add(playing);
        // As fast as the menu allows a change: one per skin-change move.
        double shortestMove = Double.MAX_VALUE;
        for (SkinSwapAnimation.Variant variant : SkinSwapAnimation.Variant.values()) {
            shortestMove = Math.min(shortestMove, SkinSwapAnimation.duration(variant));
        }
        double nextSkin = 100.0 + shortestMove;
        int skinChanges = 0;
        for (double t = 100.0 + FRAME; t < 100.0 + 600.0; t += FRAME) {
            boolean skinChanged = t >= nextSkin;
            if (skinChanged) {
                shuffle.shuffle();
                skinChanges++;
                nextSkin = t + shortestMove + random.nextInt(8);
            }
            Pose pose = shuffle.sample(t, random::nextInt);
            for (Bone bone : Bone.values()) {
                for (Axis axis : Axis.values()) {
                    float step = Math.abs(pose.get(bone, axis) - previous.get(bone, axis));
                    float limit = axis.ordinal() < 3 ? 5.0f : 0.1f;
                    assertTrue(step <= limit, bone + "." + axis + " jumps " + step + " at " + t);
                }
            }
            if (skinChanged) {
                assertNotEquals(playing, shuffle.current(), "the same idle twice in a row at " + t);
                playing = shuffle.current();
            } else {
                assertEquals(playing, shuffle.current(), "changed without a skin change at " + t);
            }
            seen.add(playing);
            previous = pose;
        }
        assertTrue(skinChanges > 40, skinChanges + " skin changes");
        assertEquals(EnumSet.allOf(Idle.class), seen);
    }

    @Test
    void aRebuiltPreviewCarriesOnWithItsMenusIdle() {
        IdleShuffle menu = new IdleShuffle();
        PreviewPlayerData before = new PreviewPlayerData();
        before.shareIdles(menu);
        before.idlePose(100.0);
        Idle playing = menu.current();
        // The window is resized: the menu builds a new preview
        PreviewPlayerData after = new PreviewPlayerData();
        after.shareIdles(menu);
        for (double t = 100.0 + FRAME; t < 100.0 + 60.0; t += FRAME) {
            Pose pose = after.idlePose(t);
            assertEquals(playing, menu.current(), "picked another idle at " + t);
            Pose expected = PreviewLoops.idle(playing, t);
            for (Bone bone : Bone.values()) {
                for (Axis axis : Axis.values()) {
                    assertEquals(expected.get(bone, axis), pose.get(bone, axis), 1.0e-6f, bone + "." + axis + " at " + t);
                }
            }
        }
    }

    @Test
    void theNextIdleIsNeverTheSame() {
        for (Idle previous : Idle.values()) {
            for (int roll = 0; roll < Idle.values().length - 1; roll++) {
                int fixed = roll;
                assertNotEquals(previous, IdleShuffle.pick(previous, bound -> fixed));
            }
        }
    }
}
