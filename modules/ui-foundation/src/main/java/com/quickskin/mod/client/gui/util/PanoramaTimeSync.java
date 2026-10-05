package com.quickskin.mod.client.gui.util;

//? if <1.21.11 {
import net.minecraft.Util;
//?} else {
import net.minecraft.util.Util;
//?}

/**
 * The shared clock that turns every panorama together.
 *
 * <p>Quick Skin draws a panorama of its own behind its screens. So that the background does not
 * jump when the player moves between the title screen and those screens, every panorama renderer
 * takes its angle from this clock instead of its own per-instance spin; the renderer mixin
 * redirects each read of that spin to {@link #panoramaSpin()}. The angle advances one degree per
 * second of {@link Util#getMillis()}.
 */
public final class PanoramaTimeSync {

    private static final boolean DETERMINISTIC_E2E_RENDER =
        Boolean.getBoolean("quickskin.e2e.enabled");
    static final float E2E_FIXED_PANORAMA_SPIN = 0.0F;
    static final long MILLIS_PER_DEGREE = 1000L;
    static final long MILLIS_PER_TURN = 360L * MILLIS_PER_DEGREE;

    private PanoramaTimeSync() {
    }

    /**
     * The angle in degrees, in {@code [0, 360)}, that every panorama shows this frame. Packaged
     * E2E pins it so title and Quick Skin backgrounds are identical in every capture.
     */
    public static float panoramaSpin() {
        if (DETERMINISTIC_E2E_RENDER) {
            return E2E_FIXED_PANORAMA_SPIN;
        }
        return spinAt(Util.getMillis());
    }

    /**
     * Converts a clock reading to the panorama angle.
     *
     * <p>The reading is reduced to one turn in {@code long} arithmetic before anything becomes a
     * {@code float}. {@code Util.getMillis()} counts from an arbitrary origin: Minecraft's own GLFW
     * clock starts near zero when the game launches, but a mod may replace it, and Very Many
     * Players installs {@code System::nanoTime}, which counts from boot on Windows and Linux. A
     * {@code float} keeps 24 significant bits, so converting such a reading directly rounds it to
     * 64 ms once the machine has been up for six days, and the panorama then moves in visible
     * 15 Hz steps (issue #2050). Wrapping at a whole turn also keeps the angle continuous where
     * the reduction wraps.
     */
    static float spinAt(long millis) {
        return Math.floorMod(millis, MILLIS_PER_TURN) / (float) MILLIS_PER_DEGREE;
    }
}
