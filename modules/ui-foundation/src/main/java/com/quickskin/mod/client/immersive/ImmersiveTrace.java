package com.quickskin.mod.client.immersive;

import com.quickskin.mod.platform.QuickSkinInfo;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Locale;

/**
 * Says in the log how each camera move of the immersive view went, one line a move: how long its
 * frames took, how long the longest one was and where in the move it fell, how long the frames
 * right after the screen opened took, and how much the time from one frame to the next wavered.
 * A move that felt rough can then be told from one that was, and a rough one's cause read off: a
 * hitch as the screen opened, a slow frame on the way, or frames of uneven length.
 *
 * <p>It also measures the clock the view went by at first, read where the frame is drawn, after
 * the game's ticks, beside the game's own frame time, so the log shows what that clock would have
 * done to the same move.
 */
final class ImmersiveTrace {
    private static final int MOST_FRAMES = 512;
    /** The frames right after the screen opened, which its first build and draw make slow. */
    private static final int OPENING_FRAMES = 3;

    private static final float[] frameMillis = new float[MOST_FRAMES];
    private static final float[] laterMillis = new float[MOST_FRAMES];
    private static final float[] progressAt = new float[MOST_FRAMES];
    @Nullable
    private static String move;
    private static int frames;
    private static float countedSeconds;
    private static long laterNanos;
    private static float laterSinceLast;

    private ImmersiveTrace() {
    }

    /** Told once a frame, where the frame is drawn: reads the clock the view went by at first. */
    static void frameDrawn() {
        long now = System.nanoTime();
        laterSinceLast = laterNanos == 0L ? 0.0f : (now - laterNanos) / 1.0e6f;
        laterNanos = now;
    }

    /** A camera move starts: {@code name} is what the log calls it. */
    static void begin(String name) {
        move = name;
        frames = 0;
        countedSeconds = 0.0f;
    }

    /** One frame of the move: the time since the frame before, the time the view counted, and how far it is. */
    static void frame(float measuredSeconds, float steppedSeconds, float progress) {
        if (move == null || frames >= MOST_FRAMES) {
            return;
        }
        frameMillis[frames] = measuredSeconds * 1000.0f;
        laterMillis[frames] = laterSinceLast;
        progressAt[frames] = progress;
        countedSeconds += steppedSeconds;
        frames++;
    }

    /** The move is over: one line about it goes to the log. */
    static void end() {
        String name = move;
        move = null;
        if (name == null || frames <= OPENING_FRAMES) {
            return;
        }
        float total = 0.0f;
        int longest = 0;
        for (int i = 0; i < frames; i++) {
            total += frameMillis[i];
            if (frameMillis[i] > frameMillis[longest]) {
                longest = i;
            }
        }
        float[] sorted = Arrays.copyOf(frameMillis, frames);
        Arrays.sort(sorted);
        StringBuilder opening = new StringBuilder();
        for (int i = 0; i < OPENING_FRAMES; i++) {
            opening.append(i == 0 ? "" : " ").append(number(frameMillis[i]));
        }
        QuickSkinInfo.LOGGER.info("Immersive view {}: {} frames in {} ms, {} ms counted; a frame takes {} ms, "
                        + "the longest {} ms at {}% of the way; the first frames took {} ms; from one frame to "
                        + "the next the game's frame time wavers by {} ms and a clock read after the ticks by {} ms",
                name, frames, number(total), number(countedSeconds * 1000.0f), number(sorted[frames / 2]),
                number(frameMillis[longest]), Math.round(progressAt[longest] * 100.0f), opening,
                number(wavering(frameMillis)), number(wavering(laterMillis)));
    }

    /** How much a frame's length differs from the one before, on average, past the opening frames. */
    private static float wavering(float[] millis) {
        float sum = 0.0f;
        int counted = 0;
        for (int i = OPENING_FRAMES + 1; i < frames; i++) {
            sum += Math.abs(millis[i] - millis[i - 1]);
            counted++;
        }
        return counted == 0 ? 0.0f : sum / counted;
    }

    private static String number(float value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }
}
