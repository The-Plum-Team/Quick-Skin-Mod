package com.quickskin.mod.mixin.immersive;

//? if <1.21 {
import com.mojang.blaze3d.vertex.PoseStack;
import com.quickskin.mod.client.immersive.ImmersiveMode;
import com.quickskin.mod.client.immersive.ImmersiveScreen;
import com.quickskin.mod.client.rendering.WorldLook;
import com.quickskin.mod.client.rendering.WorldPose;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?} else if <1.21.2 {
import com.mojang.blaze3d.vertex.PoseStack;
import com.quickskin.mod.client.immersive.ImmersiveMode;
import com.quickskin.mod.client.immersive.ImmersiveScreen;
import com.quickskin.mod.client.rendering.WorldLook;
import com.quickskin.mod.client.rendering.WorldPose;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?} else if <1.21.6 {
import com.mojang.blaze3d.vertex.PoseStack;
import com.quickskin.mod.client.immersive.ImmersiveMode;
import com.quickskin.mod.client.immersive.ImmersiveScreen;
import com.quickskin.mod.client.rendering.WorldLook;
import com.quickskin.mod.client.rendering.WorldPose;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.entity.state.PlayerRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?} else if <1.21.9 {
import com.mojang.blaze3d.vertex.PoseStack;
import com.quickskin.mod.client.immersive.ImmersiveMode;
import com.quickskin.mod.client.immersive.ImmersiveScreen;
import com.quickskin.mod.client.rendering.WorldLook;
import com.quickskin.mod.client.rendering.WorldPose;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.entity.state.PlayerRenderState;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?} else if <26.1 {
import com.mojang.blaze3d.vertex.PoseStack;
import com.quickskin.mod.client.immersive.ImmersiveMode;
import com.quickskin.mod.client.immersive.ImmersiveScreen;
import com.quickskin.mod.client.rendering.WorldLook;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.state.CameraRenderState;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?} else {
import com.mojang.blaze3d.vertex.PoseStack;
import com.quickskin.mod.client.immersive.ImmersiveMode;
import com.quickskin.mod.client.immersive.ImmersiveScreen;
import com.quickskin.mod.client.rendering.WorldLook;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?}
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import org.spongepowered.asm.mixin.Mixin;

/**
 * In the immersive view the player in the world wears what the open screen previews, as the
 * screen's own model would: the cape menu's cape under the mouse, the cape being edited, wings on
 * an Elytra tab ({@code WorldLook}). The look is put on for the world's draw of the local player
 * and taken off when that draw ends, so no other draw of them takes it. The screens that show the
 * player's back turn them even when they do not play the skin menu's pose
 * ({@code WorldLook.turnUnposed}), right after the game posed the model and before it is drawn.
 *
 * <p>Each hook names the draw by what the renderer is handed ({@code who}: the entity before
 * 1.21.2, its render state after) and the player it draws. To 1.21.8 the hooks are the start of
 * {@code render}, right after the model's {@code setupAnim} (the turn) and its end. From 1.21.9
 * they are the start and end of {@code submit} only: the look is bound to the player's render
 * state, which the cape and wings layers are handed inside it, and the turn is
 * {@link ImmersiveLivingRendererMixin}'s, at the point where the pose is decided.
 */
@Mixin(LivingEntityRenderer.class)
public abstract class ImmersiveLookRendererMixin {
//? if <1.21 {
    @Inject(method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("HEAD"), require = 0, expect = 1, allow = 1)
    private void quickskin$wearScreenLook(LivingEntity entity, float entityYaw, float partialTick, PoseStack poseStack,
                                          MultiBufferSource buffers, int packedLight, CallbackInfo callback) {
        if (ImmersiveMode.posesPlayer()) {
            Screen screen = Minecraft.getInstance().screen;
            WorldLook.beforeDraw(ImmersiveMode.session(), entity, entity, screen, screen instanceof ImmersiveScreen);
        } else if (!ImmersiveMode.running()) {
            WorldLook.end();
        }
    }

    @Inject(method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/model/EntityModel;setupAnim(Lnet/minecraft/world/entity/Entity;FFFFF)V",
                    shift = At.Shift.AFTER),
            require = 0, expect = 1, allow = 1)
    private void quickskin$turnForScreen(LivingEntity entity, float entityYaw, float partialTick, PoseStack poseStack,
                                         MultiBufferSource buffers, int packedLight, CallbackInfo callback) {
        // The skin menu's pose turns the player itself; a player who does not play it still turns
        if (ImmersiveMode.posesPlayer() && !WorldPose.posing(entity)) {
            float turn = ImmersiveMode.playerTurn();
            WorldLook.turnUnposed(entity, Mth.rotLerp(partialTick, entity.yBodyRotO, entity.yBodyRot), poseStack,
                    ImmersiveMode.faceYaw() - turn, turn, ImmersiveMode.settle());
        }
    }

    @Inject(method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("RETURN"), require = 0, expect = 1)
    private void quickskin$screenLookWorn(LivingEntity entity, float entityYaw, float partialTick, PoseStack poseStack,
                                          MultiBufferSource buffers, int packedLight, CallbackInfo callback) {
        WorldLook.drawn();
    }
//?} else if <1.21.2 {
    @Inject(method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("HEAD"), require = 0, expect = 1, allow = 1)
    private void quickskin$wearScreenLook(LivingEntity entity, float entityYaw, float partialTick, PoseStack poseStack,
                                          MultiBufferSource buffers, int packedLight, CallbackInfo callback) {
        if (ImmersiveMode.posesPlayer()) {
            Screen screen = Minecraft.getInstance().screen;
            WorldLook.beforeDraw(ImmersiveMode.session(), entity, entity, screen, screen instanceof ImmersiveScreen);
        } else if (!ImmersiveMode.running()) {
            WorldLook.end();
        }
    }

    @Inject(method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/model/EntityModel;setupAnim(Lnet/minecraft/world/entity/Entity;FFFFF)V",
                    shift = At.Shift.AFTER),
            require = 0, expect = 1, allow = 1)
    private void quickskin$turnForScreen(LivingEntity entity, float entityYaw, float partialTick, PoseStack poseStack,
                                         MultiBufferSource buffers, int packedLight, CallbackInfo callback) {
        // The skin menu's pose turns the player itself; a player who does not play it still turns
        if (ImmersiveMode.posesPlayer() && !WorldPose.posing(entity)) {
            float turn = ImmersiveMode.playerTurn();
            WorldLook.turnUnposed(entity, Mth.rotLerp(partialTick, entity.yBodyRotO, entity.yBodyRot), poseStack,
                    ImmersiveMode.faceYaw() - turn, turn, ImmersiveMode.settle());
        }
    }

    @Inject(method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("RETURN"), require = 0, expect = 1)
    private void quickskin$screenLookWorn(LivingEntity entity, float entityYaw, float partialTick, PoseStack poseStack,
                                          MultiBufferSource buffers, int packedLight, CallbackInfo callback) {
        WorldLook.release();
        WorldLook.drawn();
    }
//?} else if <1.21.6 {
    // The renderer draws the render state the game extracted from the entity just before: the
    // look is put on that state's draw, and the cape bound to the player it was extracted from
    @Inject(method = "render(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("HEAD"), require = 0, expect = 1, allow = 1)
    private void quickskin$wearScreenLook(LivingEntityRenderState state, PoseStack poseStack, MultiBufferSource buffers,
                                          int packedLight, CallbackInfo callback) {
        if (ImmersiveMode.posesPlayer()) {
            Screen screen = Minecraft.getInstance().screen;
            WorldLook.beforeDraw(ImmersiveMode.session(), state, quickskin$localPlayer(state), screen,
                    screen instanceof ImmersiveScreen);
        } else if (!ImmersiveMode.running()) {
            WorldLook.end();
        }
    }

    @Inject(method = "render(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/model/EntityModel;setupAnim(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;)V",
                    shift = At.Shift.AFTER),
            require = 0, expect = 1, allow = 1)
    private void quickskin$turnForScreen(LivingEntityRenderState state, PoseStack poseStack, MultiBufferSource buffers,
                                         int packedLight, CallbackInfo callback) {
        // The skin menu's pose turns the player itself; a player who does not play it still turns
        if (ImmersiveMode.posesPlayer() && !WorldPose.posing(state)) {
            LivingEntity player = quickskin$localPlayer(state);
            if (player != null) {
                float turn = ImmersiveMode.playerTurn();
                // The state's body yaw is already the frame's
                WorldLook.turnUnposed(player, state.bodyRot, poseStack, ImmersiveMode.faceYaw() - turn, turn,
                        ImmersiveMode.settle());
            }
        }
    }

    @Inject(method = "render(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("RETURN"), require = 0, expect = 1)
    private void quickskin$screenLookWorn(LivingEntityRenderState state, PoseStack poseStack, MultiBufferSource buffers,
                                          int packedLight, CallbackInfo callback) {
        WorldLook.release();
        WorldLook.drawn();
    }

    /** The local player, when {@code state} was extracted from them; null for any other draw. */
    @Unique
    @Nullable
    private static LivingEntity quickskin$localPlayer(LivingEntityRenderState state) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!(state instanceof PlayerRenderState player) || minecraft.player == null || minecraft.level == null
                || player.id != minecraft.player.getId()) {
            return null;
        }
        Entity entity = minecraft.level.getEntity(player.id);
        return entity == minecraft.player ? minecraft.player : null;
    }
//?} else if <1.21.9 {
    @Inject(method = "render(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("HEAD"), require = 0, expect = 1, allow = 1)
    private void quickskin$wearScreenLook(LivingEntityRenderState state, PoseStack poseStack, MultiBufferSource buffers,
                                          int packedLight, CallbackInfo callback) {
        if (ImmersiveMode.posesPlayer()) {
            Screen screen = Minecraft.getInstance().screen;
            WorldLook.beforeDraw(ImmersiveMode.session(), state, quickskin$localPlayer(state), screen,
                    screen instanceof ImmersiveScreen);
        } else if (!ImmersiveMode.running()) {
            WorldLook.end();
        }
    }

    @Inject(method = "render(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/model/EntityModel;setupAnim(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;)V",
                    shift = At.Shift.AFTER),
            require = 0, expect = 1, allow = 1)
    private void quickskin$turnForScreen(LivingEntityRenderState state, PoseStack poseStack, MultiBufferSource buffers,
                                         int packedLight, CallbackInfo callback) {
        // The skin menu's pose turns the player itself; a player who does not play it still turns
        LocalPlayer player = ImmersiveMode.posesPlayer() ? quickskin$localPlayer(state) : null;
        if (player != null && !WorldPose.posing(state)) {
            float turn = ImmersiveMode.playerTurn();
            WorldLook.turnUnposed(player, state.bodyRot, poseStack, ImmersiveMode.faceYaw() - turn, turn,
                    ImmersiveMode.settle());
        }
    }

    @Inject(method = "render(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("RETURN"), require = 0, expect = 1)
    private void quickskin$screenLookWorn(LivingEntityRenderState state, PoseStack poseStack, MultiBufferSource buffers,
                                          int packedLight, CallbackInfo callback) {
        WorldLook.drawn(state);
    }

    /** The local player when {@code state} is the render state of their draw, or null. */
    @Unique
    @Nullable
    private static LocalPlayer quickskin$localPlayer(LivingEntityRenderState state) {
        LocalPlayer player = Minecraft.getInstance().player;
        return player != null && state instanceof PlayerRenderState playerState && playerState.id == player.getId()
                ? player : null;
    }
//?} else if <26.1 {
    // The look is bound to the player's render state, which the cape and wings layers are handed
    // inside this submit. The turn of a player who does not play the pose is ImmersiveLivingRendererMixin's,
    // right after WorldPose decided whether this draw poses (both hooks would sit at one point)
    @Inject(method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/CameraRenderState;)V", at = @At("HEAD"), require = 0, expect = 1, allow = 1)
    private void quickskin$wearScreenLook(LivingEntityRenderState state, PoseStack poseStack,
                                          SubmitNodeCollector collector, CameraRenderState camera,
                                          CallbackInfo callback) {
        if (ImmersiveMode.posesPlayer()) {
            Screen screen = Minecraft.getInstance().screen;
            WorldLook.beforeDraw(ImmersiveMode.session(), state, screen, screen instanceof ImmersiveScreen);
        } else if (!ImmersiveMode.running()) {
            WorldLook.end();
        }
    }

    @Inject(method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/CameraRenderState;)V", at = @At("RETURN"), require = 0, expect = 1)
    private void quickskin$screenLookWorn(LivingEntityRenderState state, PoseStack poseStack,
                                          SubmitNodeCollector collector, CameraRenderState camera,
                                          CallbackInfo callback) {
        WorldLook.drawEnds();
    }
//?} else {
    // The look is bound to the player's render state, which the cape and wings layers are handed
    // inside this submit. The turn of a player who does not play the pose is ImmersiveLivingRendererMixin's,
    // right after WorldPose decided whether this draw poses (both hooks would sit at one point)
    @Inject(method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V", at = @At("HEAD"), require = 0, expect = 1, allow = 1)
    private void quickskin$wearScreenLook(LivingEntityRenderState state, PoseStack poseStack,
                                          SubmitNodeCollector collector, CameraRenderState camera,
                                          CallbackInfo callback) {
        if (ImmersiveMode.posesPlayer()) {
    //? if <26.2 {
            Screen screen = Minecraft.getInstance().screen;
    //?} else {
            Screen screen = Minecraft.getInstance().gui.screen();
    //?}
            WorldLook.beforeDraw(ImmersiveMode.session(), state, screen, screen instanceof ImmersiveScreen);
        } else if (!ImmersiveMode.running()) {
            WorldLook.end();
        }
    }

    @Inject(method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V", at = @At("RETURN"), require = 0, expect = 1)
    private void quickskin$screenLookWorn(LivingEntityRenderState state, PoseStack poseStack,
                                          SubmitNodeCollector collector, CameraRenderState camera,
                                          CallbackInfo callback) {
        WorldLook.drawEnds();
    }
//?}
}
