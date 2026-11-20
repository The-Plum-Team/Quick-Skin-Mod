package com.quickskin.mod.client.immersive.light;

import com.mojang.blaze3d.vertex.PoseStack;
import com.quickskin.mod.client.immersive.ImmersiveMode;
//? if <1.21.6 {
import com.quickskin.mod.mixin.immersive.ImmersiveLightDirectionsAccessor;
//?}
import org.joml.Vector3f;
import org.joml.Vector3fc;

/** The local player's top-face shade for one world draw, including its camera transition. */
public final class PlayerFaceLight {
    private final Vector3f direction;
    private final Vector3fc light0;
    private final Vector3fc light1;
    private final float top;
    private final float progress;

    //? if >=1.21.6 {
    // Lighting uploads the level's lamps before drawing entities. Keep just their CPU values.
    private static final Vector3f levelLight0 = new Vector3f();
    private static final Vector3f levelLight1 = new Vector3f();

    public static void levelLights(Vector3fc first, Vector3fc second) {
        levelLight0.set(first);
        levelLight1.set(second);
    }
    //?}

    public static PlayerFaceLight forView(PoseStack view) {
        float progress = ImmersiveMode.settle();
        if (progress <= 0) return null;
        Vector3f up = view.last().normal().getColumn(1, new Vector3f()).normalize();
        if (progress >= 1) return new PlayerFaceLight(up);
        //? if <1.21.6 {
        Vector3f[] lights = ImmersiveLightDirectionsAccessor.quickskin$lights();
        if (lights[0] == null || lights[1] == null) return null;
        return new PlayerFaceLight(up, progress, lights[0], lights[1]);
        //?} else {
        if (levelLight0.lengthSquared() == 0 || levelLight1.lengthSquared() == 0) return null;
        return new PlayerFaceLight(up, progress, levelLight0, levelLight1);
        //?}
    }

    private PlayerFaceLight(Vector3f up) {
        direction = up;
        light0 = light1 = null;
        top = progress = 1;
    }

    private PlayerFaceLight(Vector3f up, float progress, Vector3fc first, Vector3fc second) {
        this.progress = progress;
        light0 = new Vector3f(first).normalize();
        light1 = new Vector3f(second).normalize();
        top = diffuse(up.x, up.y, up.z);
        // This vector receives unit diffuse light. Scaling it reconstructs an exact shade
        // between the original face and the ceiling, even for an originally opposing normal.
        direction = new Vector3f(light0).div(1 + Math.max(0, light0.dot(light1)));
    }

    private float diffuse(float x, float y, float z) {
        return Math.min(1, Math.max(0, light0.dot(x, y, z)) + Math.max(0, light1.dot(x, y, z)));
    }

    public void apply(float x, float y, float z, Vector3f result) {
        if (progress >= 1) {
            result.set(direction);
        } else {
            float source = diffuse(x, y, z);
            result.set(direction).mul(source + progress * (top - source));
        }
    }
}
