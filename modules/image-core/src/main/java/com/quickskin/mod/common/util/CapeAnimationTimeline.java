package com.quickskin.mod.common.util;

import com.quickskin.mod.common.data.AnimationMetadata;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The cape editor's timeline for an animated source: which of its frames the cape keeps, in what
 * order, and for how long each one shows.
 *
 * <p>The kept frames are one run of the source, {@link #start()} to {@link #end()}. They play
 * forwards, or backwards when {@link #isReversed() reversed}; {@link #isPingPong() there and back}
 * then plays them the other way too, without showing either end twice. Every frame keeps its own
 * delay from the source, divided by the {@link #speed()}. The result, {@link #sequence()}, is what
 * the editor writes: one atlas frame per entry, because the animation metadata names each atlas
 * frame exactly once.</p>
 */
public final class CapeAnimationTimeline {
    /** The most frames an animated cape may have; the metadata and the renderer stop here. */
    public static final int MAX_FRAMES = 256;
    /** The shortest and longest frame the animation metadata accepts. */
    public static final int MIN_DELAY_MS = 20;
    public static final int MAX_DELAY_MS = 60_000;
    /** The delay of a source frame that has none. */
    public static final int DEFAULT_DELAY_MS = 100;

    private static final double[] SPEEDS = {0.25, 0.5, 0.75, 1.0, 1.25, 1.5, 2.0, 3.0, 4.0};
    private static final int NORMAL_SPEED = 3;

    private final int[] sourceDelays;
    private int start;
    private int end;
    private boolean reversed;
    private boolean pingPong;
    private int speedIndex = NORMAL_SPEED;

    // Derived from the fields above; rebuilt whenever one of them changes
    private int[] sequence;
    private int[] delays;
    private long[] endTimes;

    /**
     * @param frameCount the source's frames
     * @param timing the source's own timing, or {@code null}; a frame it does not name, or names
     *               with a delay outside the metadata's range, gets {@link #DEFAULT_DELAY_MS}
     */
    public CapeAnimationTimeline(int frameCount, AnimationMetadata timing) {
        if (frameCount < 1) {
            throw new IllegalArgumentException("an animation needs at least one frame");
        }
        sourceDelays = new int[frameCount];
        Arrays.fill(sourceDelays, DEFAULT_DELAY_MS);
        if (timing != null && timing.frames() != null) {
            for (AnimationMetadata.FrameData frame : timing.frames()) {
                if (frame != null && frame.index() >= 0 && frame.index() < frameCount
                        && frame.delay() >= MIN_DELAY_MS && frame.delay() <= MAX_DELAY_MS) {
                    sourceDelays[frame.index()] = frame.delay();
                }
            }
        }
        end = frameCount - 1;
        rebuild();
    }

    public int sourceFrameCount() {
        return sourceDelays.length;
    }

    public int start() {
        return start;
    }

    public int end() {
        return end;
    }

    /** Keeps the source from {@code frame} on; never past {@link #end()}. */
    public void setStart(int frame) {
        int clamped = Math.max(0, Math.min(frame, end));
        if (clamped != start) {
            start = clamped;
            rebuild();
        }
    }

    /** Keeps the source up to {@code frame}; never before {@link #start()}. */
    public void setEnd(int frame) {
        int clamped = Math.max(start, Math.min(frame, sourceDelays.length - 1));
        if (clamped != end) {
            end = clamped;
            rebuild();
        }
    }

    public boolean isReversed() {
        return reversed;
    }

    public void setReversed(boolean reversed) {
        this.reversed = reversed;
        rebuild();
    }

    public boolean isPingPong() {
        return pingPong;
    }

    public void setPingPong(boolean pingPong) {
        this.pingPong = pingPong;
        rebuild();
    }

    public double speed() {
        return SPEEDS[speedIndex];
    }

    public boolean canSpeedUp() {
        return speedIndex < SPEEDS.length - 1;
    }

    public boolean canSlowDown() {
        return speedIndex > 0;
    }

    public void speedUp() {
        if (canSpeedUp()) {
            speedIndex++;
            rebuild();
        }
    }

    public void slowDown() {
        if (canSlowDown()) {
            speedIndex--;
            rebuild();
        }
    }

    /** The speed as the editor shows it: "×1", "×0.5", "×1.25". */
    public String speedLabel() {
        double speed = speed();
        String number = speed == Math.rint(speed)
                ? Long.toString((long) speed)
                : Double.toString(speed);
        return "×" + number;
    }

    /** The source frame of every frame the cape will have, in playing order. */
    public int[] sequence() {
        return sequence.clone();
    }

    /** How long each frame of {@link #sequence()} shows, in milliseconds. */
    public int[] delays() {
        return delays.clone();
    }

    public int outputFrameCount() {
        return sequence.length;
    }

    /** Whether the cape stays within {@link #MAX_FRAMES}; ping-pong can double the kept frames. */
    public boolean fitsFrameLimit() {
        return sequence.length <= MAX_FRAMES;
    }

    /** How long one loop of the cape lasts, in milliseconds. */
    public long durationMs() {
        return endTimes[endTimes.length - 1];
    }

    /** The source frame showing {@code elapsedMs} after the animation started, looping. */
    public int sourceFrameAt(long elapsedMs) {
        long time = Math.floorMod(elapsedMs, durationMs());
        int index = Arrays.binarySearch(endTimes, time + 1);
        if (index < 0) {
            index = -index - 1;
        }
        return sequence[Math.min(index, sequence.length - 1)];
    }

    /** The cape's own timing: one entry per atlas frame, in order. */
    public AnimationMetadata toMetadata() {
        List<AnimationMetadata.FrameData> frames = new ArrayList<>(sequence.length);
        for (int i = 0; i < sequence.length; i++) {
            frames.add(new AnimationMetadata.FrameData(delays[i], i));
        }
        return new AnimationMetadata(List.copyOf(frames), sequence.length);
    }

    private void rebuild() {
        int kept = end - start + 1;
        int bounce = pingPong && kept > 2 ? kept - 2 : 0;
        int[] order = new int[kept + bounce];
        for (int i = 0; i < kept; i++) {
            order[i] = reversed ? end - i : start + i;
        }
        // Back the other way, without repeating either end
        for (int i = 0; i < bounce; i++) {
            order[kept + i] = order[kept - 2 - i];
        }
        sequence = order;
        delays = new int[order.length];
        endTimes = new long[order.length];
        long total = 0;
        for (int i = 0; i < order.length; i++) {
            long delay = Math.round(sourceDelays[order[i]] / speed());
            delays[i] = (int) Math.max(MIN_DELAY_MS, Math.min(MAX_DELAY_MS, delay));
            total += delays[i];
            endTimes[i] = total;
        }
    }
}
