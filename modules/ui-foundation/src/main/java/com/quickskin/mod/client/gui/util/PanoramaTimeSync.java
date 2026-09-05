package com.quickskin.mod.client.gui.util;

//? if <1.21.11 {
import net.minecraft.Util;
//?} else {
import net.minecraft.util.Util;
//?}
//? if <26.1.2 {
import net.minecraft.client.renderer.PanoramaRenderer;
//?} else {
import net.minecraft.client.renderer.Panorama;
//?}

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * Utility class for synchronizing panorama time across different screens.
 * Uses a global time source to ensure consistent panorama position.
 */
public class PanoramaTimeSync {

    private static final boolean DETERMINISTIC_E2E_RENDER =
        Boolean.getBoolean("quickskin.e2e.enabled");
    private static final float E2E_FIXED_PANORAMA_TIME = 0.0F;
    private static Field panoramaTimeField = null;
    private static Field[] panoramaMotionFields = new Field[0];
    private static boolean initialized = false;
    private static boolean initFailed = false;

    /**
     * Gets the global panorama time based on Minecraft's time utilities.
     * This ensures consistent panorama position regardless of which screen is rendering.
     */
    public static float getGlobalPanoramaTime() {
        if (DETERMINISTIC_E2E_RENDER) {
            return E2E_FIXED_PANORAMA_TIME;
        }
        // Use Minecraft's Util.getMillis() which is what vanilla uses for timing
        // Convert to seconds and modulo to prevent float overflow
        return (Util.getMillis() / 1000.0f) % 10000.0f;
    }

    /**
     * Initialize reflection fields for accessing Panorama's time field.
     */
    private static void initFields() {
        if (initialized || initFailed) return;
        initialized = true;

        try {
            List<Field> motionFields = new ArrayList<>();
            //? if <26.1.2 {
            for (Field field : PanoramaRenderer.class.getDeclaredFields()) {
            //?} else {
            // Find the time field in Panorama (first non-static float)
            for (Field field : Panorama.class.getDeclaredFields()) {
            //?}
                if (field.getType() == float.class && !java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    field.setAccessible(true);
                    motionFields.add(field);
                    if (panoramaTimeField == null) {
                        panoramaTimeField = field;
                    }
                }
            }
            panoramaMotionFields = motionFields.toArray(new Field[0]);
        } catch (Exception e) {
            initFailed = true;
        }
    }

    /**
     * Sets the time on a Panorama instance to the global time.
     */
    //? if <26.1.2 {
    public static void syncPanoramaRenderer(PanoramaRenderer renderer) {
    //?} else {
    public static void syncPanoramaRenderer(Panorama renderer) {
    //?}
        initFields();

        if (renderer == null) return;

        try {
            if (DETERMINISTIC_E2E_RENDER) {
                for (Field field : panoramaMotionFields) {
                    field.setFloat(renderer, E2E_FIXED_PANORAMA_TIME);
                }
                return;
            }
            if (panoramaTimeField == null) return;
            panoramaTimeField.setFloat(renderer, getGlobalPanoramaTime());
        } catch (Exception e) {
            // Silently ignore
        }
    }
}
