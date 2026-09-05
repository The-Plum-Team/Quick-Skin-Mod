package com.quickskin.mod.common.data;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AnimationMetadataTest {
    @Test
    void frameBoundariesAndLoopingSurviveJsonRoundTrip() {
        AnimationMetadata original = new AnimationMetadata(List.of(
                new AnimationMetadata.FrameData(100, 0),
                new AnimationMetadata.FrameData(250, 1)), 2);
        AnimationMetadata decoded = AnimationMetadata.fromJson(original.toJson());

        assertEquals(original, decoded);
        assertEquals(350, decoded.getTotalDuration());
        assertEquals(0, decoded.getFrameAtTime(99));
        assertEquals(1, decoded.getFrameAtTime(100));
        assertEquals(1, decoded.getFrameAtTime(349));
        assertEquals(0, decoded.getFrameAtTime(350));
    }

    @Test
    void absentFramesHaveNoDurationOnEveryMinecraftVersion() {
        assertEquals(0, new AnimationMetadata(null, 0).getTotalDuration());
        assertEquals(0, new AnimationMetadata(List.of(), 0).getTotalDuration());
    }
}
