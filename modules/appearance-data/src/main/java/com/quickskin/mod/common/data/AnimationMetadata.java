package com.quickskin.mod.common.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.List;

/**
 * Metadata for animated textures (GIF capes)
 * Stores frame timing information
 * Serialized as JSON for persistent storage
 */
public record AnimationMetadata(
    List<FrameData> frames,
    int frameCount
) {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /**
     * Frame data for a single animation frame
     */
    public record FrameData(
        int delay,  // Frame delay in milliseconds
        int index   // Frame index in atlas
    ) {}

    /**
     * Get total animation duration in milliseconds
     */
    public int getTotalDuration() {
        if (frames == null || frames.isEmpty()) {
            return 0;
        }
        return frames.stream()
                .mapToInt(FrameData::delay)
                .sum();
    }

    /**
     * Get frame at specific time offset
     * @param timeMs Time offset in milliseconds
     * @return Frame index
     */
    public int getFrameAtTime(long timeMs) {
        if (frames.isEmpty()) {
            return 0;
        }

        // Loop time within total duration
        long totalDuration = getTotalDuration();
        if (totalDuration == 0) {
            return 0;
        }

        long loopTime = timeMs % totalDuration;

        // Find which frame we're in
        int accumulatedTime = 0;
        for (FrameData frame : frames) {
            accumulatedTime += frame.delay;
            if (loopTime < accumulatedTime) {
                return frame.index;
            }
        }

        return frames.get(frames.size() - 1).index();
    }

    /**
     * Serialize to JSON string
     */
    public String toJson() {
        return GSON.toJson(this);
    }

    /**
     * Deserialize from JSON string
     */
    public static AnimationMetadata fromJson(String json) {
        return GSON.fromJson(json, AnimationMetadata.class);
    }

}
