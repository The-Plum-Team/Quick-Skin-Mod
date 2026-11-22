package com.quickskin.mod.mixin.immersive;

//? if <1.21 {
import com.mojang.blaze3d.vertex.PoseStack;
import com.quickskin.mod.client.immersive.ImmersiveMode;
import net.minecraft.client.Camera;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
//?} else if <1.21.2 {
import com.mojang.blaze3d.vertex.PoseStack;
import com.quickskin.mod.client.immersive.ImmersiveMode;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
//?} else if <1.21.6 {
import com.mojang.blaze3d.vertex.PoseStack;
import com.quickskin.mod.client.immersive.ImmersiveMode;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
//?} else if <1.21.9 {
import com.mojang.blaze3d.vertex.PoseStack;
import com.quickskin.mod.client.immersive.ImmersiveMode;
import com.quickskin.mod.client.rendering.WorldArmor;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
//?} else if <26.1 {
import com.mojang.blaze3d.vertex.PoseStack;
import com.quickskin.mod.client.immersive.ImmersiveMode;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.quickskin.mod.client.rendering.WorldArmor;
import com.quickskin.mod.client.rendering.WorldPose;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
//?} else {
import com.mojang.blaze3d.vertex.PoseStack;
import com.quickskin.mod.client.immersive.ImmersiveMode;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import com.quickskin.mod.client.rendering.WorldArmor;
import com.quickskin.mod.client.rendering.WorldPose;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
    //? if <26.3 {
import net.minecraft.client.DeltaTracker;
    //?}
//?}
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;

/**
 * What the world is drawn with in the immersive view: the world pass is marked while it runs, the
 * world takes the view's field of view, and the walk's view bobbing fades as the camera leaves the
 * player's head. The frame and the floating screen are {@link ImmersiveGameRendererMixin}'s.
 *
 * <p>Every version marks the world pass ({@code renderLevel}'s start and end; from 1.21.9 that also
 * lets go of the draws kept for the models posed after their {@code submit}, {@code WorldPose}) and
 * fades the bobbing ({@code bobView}). The field of view is set at the end of {@code getFov} to
 * 1.21.11; from 26.1 it is the camera's ({@code Camera.calculateFov}), which
 * {@link ImmersiveCameraMixin} sets instead. From 1.21.6, where the game keeps the projection only
 * on the GPU, it is also read as the game hands it over, unchanged, for the skin menu's armour on
 * the real player ({@code WorldArmor}): the argument of {@code LevelRenderer.renderLevel} (with
 * the camera's rotation from 1.21.9) to 1.21.11, the one of {@code ProjectionMatrixBuffer.getBuffer}
 * from 26.1.
 */
@Mixin(GameRenderer.class)
public abstract class ImmersiveWorldViewMixin {
//? if <1.21 {
    @Inject(method = "renderLevel", at = @At("HEAD"), require = 0, expect = 1, allow = 1)
    private void quickskin$worldPassBegins(CallbackInfo callback) {
        ImmersiveMode.worldPass(true);
    }

    @Inject(method = "renderLevel", at = @At("RETURN"), require = 0, expect = 1)
    private void quickskin$worldPassEnds(CallbackInfo callback) {
        ImmersiveMode.worldPass(false);
    }

    @Inject(method = "getFov", at = @At("RETURN"), cancellable = true, require = 0, expect = 1)
    private void quickskin$immersiveFov(Camera camera, float partialTick, boolean useFovSetting,
                                        CallbackInfoReturnable<Double> callback) {
        if (!useFovSetting) {
            return;
        }
        double vanilla = callback.getReturnValueD();
        double fov = ImmersiveMode.fov(vanilla);
        if (fov != vanilla) {
            callback.setReturnValue(fov);
        }
    }

    /** The projection as it was before the walk's bobbing, kept from the start of the bobbing to its end. */
    @Unique
    private Matrix4f quickskin$beforeViewBob;

    @Inject(method = "bobView", at = @At("HEAD"), require = 0, expect = 1, allow = 1)
    private void quickskin$viewBobBegins(PoseStack poseStack, float partialTick, CallbackInfo callback) {
        this.quickskin$beforeViewBob = ImmersiveMode.running() ? new Matrix4f(poseStack.last().pose()) : null;
    }

    @Inject(method = "bobView", at = @At("RETURN"), require = 0, expect = 1)
    private void quickskin$viewBobLeavesWithTheCamera(PoseStack poseStack, float partialTick, CallbackInfo callback) {
        Matrix4f before = this.quickskin$beforeViewBob;
        this.quickskin$beforeViewBob = null;
        if (before != null) {
            // The bobbing of a walk is the head's: it fades as the camera leaves the head, where
            // it would shake the whole floating screen while a player who was walking stops
            poseStack.last().pose().lerp(before, ImmersiveMode.settle());
        }
    }
//?} else if <1.21.2 {
    @Inject(method = "renderLevel(Lnet/minecraft/client/DeltaTracker;)V", at = @At("HEAD"), require = 0, expect = 1, allow = 1)
    private void quickskin$worldPassBegins(DeltaTracker deltaTracker, CallbackInfo callback) {
        ImmersiveMode.worldPass(true);
    }

    @Inject(method = "renderLevel(Lnet/minecraft/client/DeltaTracker;)V", at = @At("RETURN"), require = 0, expect = 1)
    private void quickskin$worldPassEnds(DeltaTracker deltaTracker, CallbackInfo callback) {
        ImmersiveMode.worldPass(false);
    }

    @Inject(method = "getFov", at = @At("RETURN"), cancellable = true, require = 0, expect = 1)
    private void quickskin$immersiveFov(Camera camera, float partialTick, boolean useFovSetting,
                                        CallbackInfoReturnable<Double> callback) {
        if (!useFovSetting) {
            return;
        }
        double vanilla = callback.getReturnValueD();
        double fov = ImmersiveMode.fov(vanilla);
        if (fov != vanilla) {
            callback.setReturnValue(fov);
        }
    }

    /** The projection as it was before the walk's bobbing, kept from the start of the bobbing to its end. */
    @Unique
    private Matrix4f quickskin$beforeViewBob;

    @Inject(method = "bobView", at = @At("HEAD"), require = 0, expect = 1, allow = 1)
    private void quickskin$viewBobBegins(PoseStack poseStack, float partialTick, CallbackInfo callback) {
        this.quickskin$beforeViewBob = ImmersiveMode.running() ? new Matrix4f(poseStack.last().pose()) : null;
    }

    @Inject(method = "bobView", at = @At("RETURN"), require = 0, expect = 1)
    private void quickskin$viewBobLeavesWithTheCamera(PoseStack poseStack, float partialTick, CallbackInfo callback) {
        Matrix4f before = this.quickskin$beforeViewBob;
        this.quickskin$beforeViewBob = null;
        if (before != null) {
            // The bobbing of a walk is the head's: it fades as the camera leaves the head, where
            // it would shake the whole floating screen while a player who was walking stops
            poseStack.last().pose().lerp(before, ImmersiveMode.settle());
        }
    }
//?} else if <1.21.6 {
    @Inject(method = "renderLevel(Lnet/minecraft/client/DeltaTracker;)V", at = @At("HEAD"), require = 0, expect = 1, allow = 1)
    private void quickskin$worldPassBegins(DeltaTracker deltaTracker, CallbackInfo callback) {
        ImmersiveMode.worldPass(true);
    }

    @Inject(method = "renderLevel(Lnet/minecraft/client/DeltaTracker;)V", at = @At("RETURN"), require = 0, expect = 1)
    private void quickskin$worldPassEnds(DeltaTracker deltaTracker, CallbackInfo callback) {
        ImmersiveMode.worldPass(false);
    }

    // getFov returns a float from 1.21.2
    @Inject(method = "getFov", at = @At("RETURN"), cancellable = true, require = 0, expect = 1)
    private void quickskin$immersiveFov(Camera camera, float partialTick, boolean useFovSetting,
                                        CallbackInfoReturnable<Float> callback) {
        if (!useFovSetting) {
            return;
        }
        float vanilla = callback.getReturnValueF();
        double fov = ImmersiveMode.fov(vanilla);
        if (fov != vanilla) {
            callback.setReturnValue((float) fov);
        }
    }

    /** The projection as it was before the walk's bobbing, kept from the start of the bobbing to its end. */
    @Unique
    private Matrix4f quickskin$beforeViewBob;

    @Inject(method = "bobView", at = @At("HEAD"), require = 0, expect = 1, allow = 1)
    private void quickskin$viewBobBegins(PoseStack poseStack, float partialTick, CallbackInfo callback) {
        this.quickskin$beforeViewBob = ImmersiveMode.running() ? new Matrix4f(poseStack.last().pose()) : null;
    }

    @Inject(method = "bobView", at = @At("RETURN"), require = 0, expect = 1)
    private void quickskin$viewBobLeavesWithTheCamera(PoseStack poseStack, float partialTick, CallbackInfo callback) {
        Matrix4f before = this.quickskin$beforeViewBob;
        this.quickskin$beforeViewBob = null;
        if (before != null) {
            // The bobbing of a walk is the head's: it fades as the camera leaves the head, where
            // it would shake the whole floating screen while a player who was walking stops
            poseStack.last().pose().lerp(before, ImmersiveMode.settle());
        }
    }
//?} else if <1.21.9 {
    @Inject(method = "renderLevel(Lnet/minecraft/client/DeltaTracker;)V", at = @At("HEAD"),
            require = 0, expect = 1, allow = 1)
    private void quickskin$worldPassBegins(DeltaTracker deltaTracker, CallbackInfo callback) {
        ImmersiveMode.worldPass(true);
    }

    @Inject(method = "renderLevel(Lnet/minecraft/client/DeltaTracker;)V", at = @At("RETURN"), require = 0, expect = 1)
    private void quickskin$worldPassEnds(DeltaTracker deltaTracker, CallbackInfo callback) {
        com.quickskin.mod.client.immersive.ApertureSectionDraw.finishWorld();
        ImmersiveMode.worldPass(false);
    }

    // The field of view is a float from 1.21.2
    @Inject(method = "getFov(Lnet/minecraft/client/Camera;FZ)F", at = @At("RETURN"), cancellable = true,
            require = 0, expect = 1)
    private void quickskin$immersiveFov(Camera camera, float partialTick, boolean useFovSetting,
                                        CallbackInfoReturnable<Float> callback) {
        if (!useFovSetting) {
            return;
        }
        float vanilla = callback.getReturnValueF();
        double fov = ImmersiveMode.fov(vanilla);
        if (fov != vanilla) {
            callback.setReturnValue((float) fov);
        }
    }

    /** The projection as it was before the walk's bobbing, kept from the start of the bobbing to its end. */
    @Unique
    private Matrix4f quickskin$beforeViewBob;

    @Inject(method = "bobView(Lcom/mojang/blaze3d/vertex/PoseStack;F)V", at = @At("HEAD"),
            require = 0, expect = 1, allow = 1)
    private void quickskin$viewBobBegins(PoseStack poseStack, float partialTick, CallbackInfo callback) {
        this.quickskin$beforeViewBob = ImmersiveMode.running() ? new Matrix4f(poseStack.last().pose()) : null;
    }

    @Inject(method = "bobView(Lcom/mojang/blaze3d/vertex/PoseStack;F)V", at = @At("RETURN"), require = 0, expect = 1)
    private void quickskin$viewBobLeavesWithTheCamera(PoseStack poseStack, float partialTick, CallbackInfo callback) {
        Matrix4f before = this.quickskin$beforeViewBob;
        this.quickskin$beforeViewBob = null;
        if (before != null) {
            // The bobbing of a walk is the head's: it fades as the camera leaves the head, where
            // it would shake the whole floating screen while a player who was walking stops
            poseStack.last().pose().lerp(before, ImmersiveMode.settle());
        }
    }

    /**
     * Hands the skin menu's armour on the real player ({@code WorldArmor}) the projection the
     * world is drawn with: from 1.21.6 the game keeps it only on the GPU.
     */
    @ModifyArg(method = "renderLevel(Lnet/minecraft/client/DeltaTracker;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/LevelRenderer;renderLevel(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/Camera;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Vector4f;Z)V"),
            index = 5, require = 0, expect = 1, allow = 1)
    private Matrix4f quickskin$worldProjection(Matrix4f projection) {
        com.quickskin.mod.client.immersive.ModernAperture.prepare(projection, null);
        if (ImmersiveMode.running()) {
            WorldArmor.worldProjection(projection);
        }
        return projection;
    }
//?} else if <26.1 {
    @Inject(method = "renderLevel(Lnet/minecraft/client/DeltaTracker;)V", at = @At("HEAD"), require = 0, expect = 1, allow = 1)
    private void quickskin$worldPassBegins(DeltaTracker deltaTracker, CallbackInfo callback) {
        ImmersiveMode.worldPass(true);
        // The draws kept for the models the game poses after submitting them are a frame's own
        WorldPose.forgetBound();
    }

    @Inject(method = "renderLevel(Lnet/minecraft/client/DeltaTracker;)V", at = @At("RETURN"), require = 0, expect = 1)
    private void quickskin$worldPassEnds(DeltaTracker deltaTracker, CallbackInfo callback) {
        com.quickskin.mod.client.immersive.ApertureSectionDraw.finishWorld();
        ImmersiveMode.worldPass(false);
    }

    @Inject(method = "getFov(Lnet/minecraft/client/Camera;FZ)F", at = @At("RETURN"), cancellable = true, require = 0, expect = 1)
    private void quickskin$immersiveFov(Camera camera, float partialTick, boolean useFovSetting,
                                        CallbackInfoReturnable<Float> callback) {
        if (!useFovSetting) {
            return;
        }
        float vanilla = callback.getReturnValueF();
        double fov = ImmersiveMode.fov(vanilla);
        if (fov != vanilla) {
            callback.setReturnValue((float) fov);
        }
    }

    /** The projection as it was before the walk's bobbing, kept from the start of the bobbing to its end. */
    @Unique
    private Matrix4f quickskin$beforeViewBob;

    @Inject(method = "bobView(Lcom/mojang/blaze3d/vertex/PoseStack;F)V", at = @At("HEAD"), require = 0, expect = 1, allow = 1)
    private void quickskin$viewBobBegins(PoseStack poseStack, float partialTick, CallbackInfo callback) {
        this.quickskin$beforeViewBob = ImmersiveMode.running() ? new Matrix4f(poseStack.last().pose()) : null;
    }

    @Inject(method = "bobView(Lcom/mojang/blaze3d/vertex/PoseStack;F)V", at = @At("RETURN"), require = 0, expect = 1)
    private void quickskin$viewBobLeavesWithTheCamera(PoseStack poseStack, float partialTick, CallbackInfo callback) {
        Matrix4f before = this.quickskin$beforeViewBob;
        this.quickskin$beforeViewBob = null;
        if (before != null) {
            // The bobbing of a walk is the head's: it fades as the camera leaves the head
            poseStack.last().pose().lerp(before, ImmersiveMode.settle());
        }
    }

    // The projection (the bobbing included) and the camera's rotation are handed to the level
    // renderer and live on the GPU from then on; the skin menu traces the armour on the player
    // with them (WorldArmor). Nothing is changed.
    @ModifyArg(method = "renderLevel(Lnet/minecraft/client/DeltaTracker;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/LevelRenderer;renderLevel(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/Camera;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Vector4f;Z)V"),
            index = 5, require = 0, expect = 1, allow = 1)
    private Matrix4f quickskin$keepLevelMatrices(GraphicsResourceAllocator allocator, DeltaTracker deltaTracker,
                                                 boolean blockOutline, Camera camera, Matrix4f view,
                                                 Matrix4f projection, Matrix4f culling, GpuBufferSlice fog,
                                                 Vector4f fogColor, boolean skyFog) {
        com.quickskin.mod.client.immersive.ModernAperture.prepare(projection, view);
        if (ImmersiveMode.posesPlayer()) {
            WorldArmor.levelMatrices(view, projection);
        }
        return projection;
    }
//?} else {
    //? if <26.3 {
    @Inject(method = "renderLevel(Lnet/minecraft/client/DeltaTracker;)V", at = @At("HEAD"), require = 0, expect = 1, allow = 1)
    private void quickskin$worldPassBegins(DeltaTracker deltaTracker, CallbackInfo callback) {
        WorldPose.forgetPassDraws();
        ImmersiveMode.worldPass(true);
    }

    @Inject(method = "renderLevel(Lnet/minecraft/client/DeltaTracker;)V", at = @At("RETURN"), require = 0, expect = 1)
    private void quickskin$worldPassEnds(DeltaTracker deltaTracker, CallbackInfo callback) {
        WorldPose.forgetPassDraws();
        com.quickskin.mod.client.immersive.ApertureSectionDraw.finishWorld();
        ImmersiveMode.worldPass(false);
    }
    //?} else {
    @Inject(method = "renderLevel()V", at = @At("HEAD"), require = 0, expect = 1, allow = 1)
    private void quickskin$worldPassBegins(CallbackInfo callback) {
        WorldPose.forgetPassDraws();
        ImmersiveMode.worldPass(true);
    }

    @Inject(method = "renderLevel()V", at = @At("RETURN"), require = 0, expect = 1)
    private void quickskin$worldPassEnds(CallbackInfo callback) {
        WorldPose.forgetPassDraws();
        com.quickskin.mod.client.immersive.ApertureSectionDraw.finishWorld();
        ImmersiveMode.worldPass(false);
    }
    //?}

    /** The bobbing as it was before the walk's bobbing, kept from the start of the bobbing to its end. */
    @Unique
    private Matrix4f quickskin$beforeViewBob;

    // The world's bobbing is a pose of its own, multiplied into the projection (and the hand's,
    // which a hidden HUD does not draw); the field of view is the camera's (ImmersiveCameraMixin)
    @Inject(method = "bobView", at = @At("HEAD"), require = 0, expect = 1, allow = 1)
    private void quickskin$viewBobBegins(CameraRenderState camera, PoseStack poseStack, CallbackInfo callback) {
        this.quickskin$beforeViewBob = ImmersiveMode.running() ? new Matrix4f(poseStack.last().pose()) : null;
    }

    @Inject(method = "bobView", at = @At("RETURN"), require = 0, expect = 1)
    private void quickskin$viewBobLeavesWithTheCamera(CameraRenderState camera, PoseStack poseStack, CallbackInfo callback) {
        Matrix4f before = this.quickskin$beforeViewBob;
        this.quickskin$beforeViewBob = null;
        if (before != null) {
            // The bobbing of a walk is the head's: it fades as the camera leaves the head
            poseStack.last().pose().lerp(before, ImmersiveMode.settle());
        }
    }

    // The projection the world is drawn with, the bobbing included, lives on the GPU once handed
    // over; the skin menu traces the armour on the player with it (WorldArmor). Nothing is changed
    //? if <26.3 {
    @ModifyArg(method = "renderLevel(Lnet/minecraft/client/DeltaTracker;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/blaze3d/buffers/GpuBufferSlice;"),
            index = 0, require = 0, expect = 1, allow = 1)
    //?} else {
    @ModifyArg(method = "renderLevel()V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;"),
            index = 0, require = 0, expect = 1, allow = 1)
    //?}
    private Matrix4f quickskin$keepLevelProjection(Matrix4f projection) {
        com.quickskin.mod.client.immersive.ModernAperture.prepare(projection, null);
        if (ImmersiveMode.posesPlayer()) {
            WorldArmor.levelProjection(projection);
        }
        return projection;
    }
//?}
}
