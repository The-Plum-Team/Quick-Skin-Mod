package com.quickskin.mod.e2e;

import com.quickskin.mod.client.gui.screen.PlayerCapeMenuScreen;
import com.quickskin.mod.client.services.AnimatedTextureManager;
import net.minecraft.client.Minecraft;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic animation state for cape-menu captures.
 *
 * <p>The cape menu draws every animated tile and the 3D preview from the live
 * {@link AnimatedTextureManager} frame, so an unpinned capture samples whatever frame wall-clock
 * playback had reached: the same checkpoint shows the red or the blue motif of the local GIF cape,
 * and a different frame of the bundled animated capes, depending on when each loader started. That
 * phase difference against the Fabric anchor has been read by semantic review as a colour defect.
 * While a capture step holds on a cape-menu screen, the harness pauses every registered animation
 * at its first frame before the rendered passes it captures, and restores the remembered playback
 * speeds before the step's assertion runs, so assertions still observe the real playback state.
 * A step that pins an animation itself (speed zero) is preserved: its remembered speed is zero.</p>
 */
public final class AnimationHold {
    private static final Map<String, Float> remembered = new LinkedHashMap<>();
    private static String summary;

    private AnimationHold() {}

    /** True while the current screen draws animated cape tiles from live animation frames. */
    public static boolean applies(Minecraft mc) {
        return VanillaShim.currentScreen(mc) instanceof PlayerCapeMenuScreen;
    }

    /**
     * Pause every registered animation and pin it to frame 0. Idempotent: an animation that is
     * already held keeps its remembered speed, and one that is already on frame 0 is not re-uploaded.
     */
    public static void hold() {
        AnimatedTextureManager manager = AnimatedTextureManager.getInstance();
        int held = 0;
        for (Map.Entry<String, Object> entry : registered(manager)) {
            String id = entry.getKey();
            if (!remembered.containsKey(id)) {
                float speed = speedOf(entry.getValue());
                if (Float.isNaN(speed)) continue;
                remembered.put(id, speed);
            }
            manager.setAnimationSpeed(id, 0.0f);
            if (manager.setAnimationFrame(id, 0)) held++;
        }
        if (held > 0) {
            summary = "cape-menu capture held " + held + " registered cape animation(s) at frame 0";
        }
    }

    /** Restore the remembered playback speeds; frames stay where the hold left them. */
    public static void release() {
        if (remembered.isEmpty()) return;
        AnimatedTextureManager manager = AnimatedTextureManager.getInstance();
        for (Map.Entry<String, Float> entry : remembered.entrySet()) {
            manager.setAnimationSpeed(entry.getKey(), entry.getValue());
        }
        remembered.clear();
    }

    /** The bounded summary of the hold that preceded the current capture, consumed once. */
    public static String consumeSummary() {
        String value = summary;
        summary = null;
        return value;
    }

    /** Forget any hold state when a step ends without a capture. */
    public static void reset() {
        release();
        summary = null;
    }

    private static List<Map.Entry<String, Object>> registered(AnimatedTextureManager manager) {
        List<Map.Entry<String, Object>> snapshot = new ArrayList<>();
        try {
            Field field = AnimatedTextureManager.class.getDeclaredField("animations");
            field.setAccessible(true);
            Object value = field.get(manager);
            if (value instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (entry.getKey() instanceof String id && entry.getValue() != null) {
                        snapshot.add(Map.entry(id, entry.getValue()));
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException failure) {
            E2ELog.warn("AnimationHold: cannot read registered animations: " + failure);
        }
        return snapshot;
    }

    private static float speedOf(Object state) {
        try {
            Field field = state.getClass().getDeclaredField("speedMultiplier");
            field.setAccessible(true);
            return field.getFloat(state);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            E2ELog.warn("AnimationHold: cannot read the playback speed of " + state + ": " + failure);
            return Float.NaN;
        }
    }
}
