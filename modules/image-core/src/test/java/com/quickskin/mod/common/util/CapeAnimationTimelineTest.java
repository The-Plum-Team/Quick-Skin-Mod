package com.quickskin.mod.common.util;

import com.quickskin.mod.common.data.AnimationMetadata;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The cape editor's timeline: trimming, order, speed, and the metadata it writes. */
class CapeAnimationTimelineTest {

    private static AnimationMetadata timing(int... delays) {
        List<AnimationMetadata.FrameData> frames = new ArrayList<>();
        for (int i = 0; i < delays.length; i++) {
            frames.add(new AnimationMetadata.FrameData(delays[i], i));
        }
        return new AnimationMetadata(frames, delays.length);
    }

    @Test
    void keepsEverySourceFrameWithItsOwnDelay() {
        CapeAnimationTimeline timeline = new CapeAnimationTimeline(3, timing(40, 80, 120));
        assertArrayEquals(new int[] {0, 1, 2}, timeline.sequence());
        assertArrayEquals(new int[] {40, 80, 120}, timeline.delays());
        assertEquals(240, timeline.durationMs());
    }

    @Test
    void framesWithoutUsableTimingGetTheDefault() {
        CapeAnimationTimeline missing = new CapeAnimationTimeline(2, null);
        assertArrayEquals(new int[] {100, 100}, missing.delays());
        CapeAnimationTimeline invalid = new CapeAnimationTimeline(2, timing(0, 70_000));
        assertArrayEquals(new int[] {100, 100}, invalid.delays());
    }

    @Test
    void trimKeepsOneRunAndNeverCrosses() {
        CapeAnimationTimeline timeline = new CapeAnimationTimeline(10, null);
        timeline.setStart(3);
        timeline.setEnd(6);
        assertArrayEquals(new int[] {3, 4, 5, 6}, timeline.sequence());
        timeline.setStart(9);
        assertEquals(6, timeline.start());
        timeline.setEnd(1);
        assertEquals(6, timeline.end());
        timeline.setStart(-5);
        timeline.setEnd(99);
        assertEquals(0, timeline.start());
        assertEquals(9, timeline.end());
    }

    @Test
    void reversedAndPingPongNeverShowAnEndTwice() {
        CapeAnimationTimeline timeline = new CapeAnimationTimeline(5, null);
        timeline.setReversed(true);
        assertArrayEquals(new int[] {4, 3, 2, 1, 0}, timeline.sequence());
        timeline.setPingPong(true);
        assertArrayEquals(new int[] {4, 3, 2, 1, 0, 1, 2, 3}, timeline.sequence());
        timeline.setReversed(false);
        timeline.setStart(1);
        assertArrayEquals(new int[] {1, 2, 3, 4, 3, 2}, timeline.sequence());
        // Two frames already alternate; one cannot bounce
        timeline.setEnd(2);
        assertArrayEquals(new int[] {1, 2}, timeline.sequence());
        timeline.setEnd(1);
        assertArrayEquals(new int[] {1}, timeline.sequence());
    }

    @Test
    void speedDividesEveryDelayWithinTheMetadataRange() {
        CapeAnimationTimeline timeline = new CapeAnimationTimeline(2, timing(30, 1000));
        assertEquals("×1", timeline.speedLabel());
        timeline.speedUp();
        assertEquals("×1.25", timeline.speedLabel());
        assertArrayEquals(new int[] {24, 800}, timeline.delays());
        while (timeline.canSpeedUp()) {
            timeline.speedUp();
        }
        assertEquals("×4", timeline.speedLabel());
        assertArrayEquals(new int[] {20, 250}, timeline.delays());
        while (timeline.canSlowDown()) {
            timeline.slowDown();
        }
        assertEquals("×0.25", timeline.speedLabel());
        assertArrayEquals(new int[] {120, 4000}, timeline.delays());
    }

    @Test
    void findsTheFrameShowingAtATimeAndLoops() {
        CapeAnimationTimeline timeline = new CapeAnimationTimeline(3, timing(40, 80, 120));
        assertEquals(0, timeline.sourceFrameAt(0));
        assertEquals(0, timeline.sourceFrameAt(39));
        assertEquals(1, timeline.sourceFrameAt(40));
        assertEquals(1, timeline.sourceFrameAt(119));
        assertEquals(2, timeline.sourceFrameAt(120));
        assertEquals(2, timeline.sourceFrameAt(239));
        assertEquals(0, timeline.sourceFrameAt(240));
        timeline.setReversed(true);
        assertEquals(2, timeline.sourceFrameAt(0));
        assertEquals(0, timeline.sourceFrameAt(239));
    }

    @Test
    void pingPongPastTheLimitDoesNotFit() {
        CapeAnimationTimeline timeline = new CapeAnimationTimeline(200, null);
        assertTrue(timeline.fitsFrameLimit());
        timeline.setPingPong(true);
        assertEquals(398, timeline.outputFrameCount());
        assertFalse(timeline.fitsFrameLimit());
        timeline.setStart(71);
        assertEquals(CapeAnimationTimeline.MAX_FRAMES, timeline.outputFrameCount());
        assertTrue(timeline.fitsFrameLimit());
    }

    @Test
    void aLongerSourceStartsWithTheFramesACapeMayHave() {
        CapeAnimationTimeline timeline = new CapeAnimationTimeline(600, null);
        assertEquals(0, timeline.start());
        assertEquals(CapeAnimationTimeline.MAX_FRAMES - 1, timeline.end());
        assertTrue(timeline.fitsFrameLimit());
        timeline.setEnd(599);
        assertEquals(599, timeline.end());
        assertFalse(timeline.fitsFrameLimit());
    }

    /** The written timing must pass the same check the server and the catalogue apply. */
    @Test
    void metadataNamesEachAtlasFrameOnceInOrder() {
        CapeAnimationTimeline timeline = new CapeAnimationTimeline(4, timing(50, 60, 70, 80));
        timeline.setPingPong(true);
        timeline.speedUp();
        AnimationMetadata metadata = timeline.toMetadata();
        assertEquals(6, metadata.frameCount());
        for (int i = 0; i < metadata.frames().size(); i++) {
            assertEquals(i, metadata.frames().get(i).index());
            assertEquals(timeline.delays()[i], metadata.frames().get(i).delay());
        }
        assertNotNull(AnimationMetadata.fromJson(metadata.toJson()));
        assertEquals(timeline.durationMs(), metadata.getTotalDuration());
    }
}
