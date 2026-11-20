package com.quickskin.mod.client.immersive;

//? if <1.21.6 {
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

import java.util.function.Consumer;
//?}

/** Gives a shader pack the real light camera while clipping only visible terrain geometry. */
public final class LegacyPackCamera {
    //? if <1.21.6 {
    private static LegacyPackCamera current;
    private final Matrix4f normal, clipped, terrainArgument;
    private final double near, far, terrainNear;
    private final Runnable subjects;
    private boolean drawn;

    private LegacyPackCamera(ImmersiveOpening opening, Matrix4f normal, Matrix4f clipped,
                             double[] depthRange, Runnable subjects) {
        this.normal = normal;
        this.clipped = clipped;
        this.terrainArgument = new Matrix4f(normal);
        this.near = depthRange[0];
        this.far = depthRange[1];
        this.terrainNear = Mth.lerp(opening.cut(), near, far);
        this.subjects = subjects;
    }

    /** Iris copies the incoming camera for lighting, shadows and world-position reconstruction. */
    public static boolean render(ImmersiveOpening opening, Matrix4f normal, Matrix4f clipped, double[] depthRange,
                                 Consumer<Matrix4f> world, Runnable subjects) {
        LegacyPackCamera pass = new LegacyPackCamera(opening, normal, clipped, depthRange, subjects);
        current = pass;
        try {
            world.accept(pass.terrainArgument);
            return pass.drawn;
        } finally {
            current = null;
            GL11.glDepthRange(pass.near, pass.far);
        }
    }

    static void beforeTerrain() {
        LegacyPackCamera pass = current;
        if (pass == null || ImmersiveShaderPacks.shadows()) return;
        // Real terrain uses the cropped projection. Its surviving fragments keep their original depths.
        pass.terrainArgument.set(pass.clipped);
        GL11.glDepthRange(pass.terrainNear, pass.far);
    }

    static void beforeEntities() {
        LegacyPackCamera pass = current;
        if (pass == null || pass.drawn || ImmersiveShaderPacks.shadows()) return;
        pass.drawn = true;
        Matrix4f previous = new Matrix4f(RenderSystem.getProjectionMatrix());
        //? if <1.21.2 {
        var sorting = RenderSystem.getVertexSorting();
        //?} else {
        var sorting = RenderSystem.getProjectionType();
        //?}
        try {
            RenderSystem.setProjectionMatrix(pass.normal, sorting);
            GL11.glDepthRange(pass.near, pass.far);
            LegacyOpeningRenderer.depthWrites(true);
            // The uncut receivers and all layers of the local player enter the pack's G-buffer together.
            pass.subjects.run();
        } finally {
            GL11.glDepthRange(pass.terrainNear, pass.far);
            RenderSystem.setProjectionMatrix(previous, sorting);
        }
    }
    //?}
}
