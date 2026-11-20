package com.quickskin.mod.mixin.immersive;

//? if <1.21 {
import com.mojang.blaze3d.vertex.PoseStack;
import com.quickskin.mod.client.immersive.ImmersiveMode;
import com.quickskin.mod.client.rendering.WorldArmor;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?} else if <1.21.2 {
import com.mojang.blaze3d.vertex.PoseStack;
import com.quickskin.mod.client.immersive.ImmersiveMode;
import com.quickskin.mod.client.rendering.WorldArmor;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?} else if <1.21.6 {
import com.mojang.blaze3d.vertex.PoseStack;
import com.quickskin.mod.client.immersive.ImmersiveMode;
import com.quickskin.mod.client.rendering.WorldArmor;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?} else if <1.21.9 {
import com.mojang.blaze3d.vertex.PoseStack;
import com.quickskin.mod.client.immersive.ImmersiveMode;
import com.quickskin.mod.client.rendering.WorldArmor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.entity.state.PlayerRenderState;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?} else if <26.1 {
import com.mojang.blaze3d.vertex.PoseStack;
import com.quickskin.mod.client.immersive.ImmersiveMode;
import com.quickskin.mod.client.rendering.WorldArmor;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.state.CameraRenderState;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?} else {
import com.mojang.blaze3d.vertex.PoseStack;
import com.quickskin.mod.client.immersive.ImmersiveMode;
import com.quickskin.mod.client.rendering.WorldArmor;
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
 * In the immersive view the skin menu points at the armour on the real player ({@code WorldArmor}):
 * the world's draw of the local player says where its feet and, once posed, its model are seen, so
 * the menu traces each piece on it; it draws the menu's see-through ghost on the player after the
 * layers, and the pieces nobody sees as items on the ground at the end. Only the world's draw of
 * the local player during a view is touched; should a hook fail, the pieces cannot be clicked on
 * the player and the menu works on.
 *
 * <p>Each hook names the draw by what the renderer is handed ({@code who}: the entity before
 * 1.21.2, its render state after) and the player it draws. Every version hooks the same four
 * points of the living draw: its start, right after the model's {@code setupAnim}, before the
 * {@code popPose} after the layers, and its end; the draw is {@code render} to 1.21.8 and
 * {@code submit} from 1.21.9.
 */
@Mixin(LivingEntityRenderer.class)
public abstract class ImmersiveArmorRendererMixin {
//? if <1.21 {
    @Inject(method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("HEAD"), require = 0, expect = 1, allow = 1)
    private void quickskin$armorDrawStarts(LivingEntity entity, float entityYaw, float partialTick, PoseStack poseStack,
                                           MultiBufferSource buffers, int packedLight, CallbackInfo callback) {
        // Only while the menu is open: the camera's way back after it closed points at nothing
        WorldArmor.beginDraw(ImmersiveMode.posesPlayer() && ImmersiveMode.active(), entity, entity, partialTick,
                poseStack);
    }

    @Inject(method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/model/EntityModel;setupAnim(Lnet/minecraft/world/entity/Entity;FFFFF)V",
                    shift = At.Shift.AFTER),
            require = 0, expect = 1, allow = 1)
    private void quickskin$armorModelPosed(LivingEntity entity, float entityYaw, float partialTick, PoseStack poseStack,
                                           MultiBufferSource buffers, int packedLight, CallbackInfo callback) {
        WorldArmor.modelPosed(ImmersiveMode.session(), entity, this, ((LivingEntityRenderer<?, ?>) (Object) this).getModel(), poseStack);
    }

    @Inject(method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;popPose()V"),
            require = 0, expect = 1, allow = 1)
    private void quickskin$armorLayersDrawn(LivingEntity entity, float entityYaw, float partialTick, PoseStack poseStack,
                                            MultiBufferSource buffers, int packedLight, CallbackInfo callback) {
        WorldArmor.layersDrawn(entity, this, ((LivingEntityRenderer<?, ?>) (Object) this).getModel(), poseStack, buffers);
    }

    @Inject(method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("RETURN"), require = 0, expect = 1)
    private void quickskin$armorDrawEnds(LivingEntity entity, float entityYaw, float partialTick, PoseStack poseStack,
                                         MultiBufferSource buffers, int packedLight, CallbackInfo callback) {
        WorldArmor.drawDrops(ImmersiveMode.session(), entity, poseStack, buffers, packedLight);
    }
//?} else if <1.21.2 {
    @Inject(method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("HEAD"), require = 0, expect = 1, allow = 1)
    private void quickskin$armorDrawStarts(LivingEntity entity, float entityYaw, float partialTick, PoseStack poseStack,
                                           MultiBufferSource buffers, int packedLight, CallbackInfo callback) {
        // Only while the menu is open: the camera's way back after it closed points at nothing
        WorldArmor.beginDraw(ImmersiveMode.posesPlayer() && ImmersiveMode.active(), entity, entity, partialTick,
                poseStack);
    }

    @Inject(method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/model/EntityModel;setupAnim(Lnet/minecraft/world/entity/Entity;FFFFF)V",
                    shift = At.Shift.AFTER),
            require = 0, expect = 1, allow = 1)
    private void quickskin$armorModelPosed(LivingEntity entity, float entityYaw, float partialTick, PoseStack poseStack,
                                           MultiBufferSource buffers, int packedLight, CallbackInfo callback) {
        WorldArmor.modelPosed(ImmersiveMode.session(), entity, this, ((LivingEntityRenderer<?, ?>) (Object) this).getModel(), poseStack);
    }

    @Inject(method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;popPose()V"),
            require = 0, expect = 1, allow = 1)
    private void quickskin$armorLayersDrawn(LivingEntity entity, float entityYaw, float partialTick, PoseStack poseStack,
                                            MultiBufferSource buffers, int packedLight, CallbackInfo callback) {
        WorldArmor.layersDrawn(entity, this, ((LivingEntityRenderer<?, ?>) (Object) this).getModel(), poseStack, buffers);
    }

    @Inject(method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("RETURN"), require = 0, expect = 1)
    private void quickskin$armorDrawEnds(LivingEntity entity, float entityYaw, float partialTick, PoseStack poseStack,
                                         MultiBufferSource buffers, int packedLight, CallbackInfo callback) {
        WorldArmor.drawDrops(ImmersiveMode.session(), entity, poseStack, buffers, packedLight);
    }
//?} else if <1.21.6 {
    // The renderer draws the render state the game extracted from the entity just before; the
    // points are the same as before 1.21.2
    @Inject(method = "render(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("HEAD"), require = 0, expect = 1, allow = 1)
    private void quickskin$armorDrawStarts(LivingEntityRenderState state, PoseStack poseStack, MultiBufferSource buffers,
                                           int packedLight, CallbackInfo callback) {
        // Only while the menu is open: the camera's way back after it closed points at nothing
        WorldArmor.beginDraw(ImmersiveMode.posesPlayer() && ImmersiveMode.active(), state, poseStack);
    }

    @Inject(method = "render(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/model/EntityModel;setupAnim(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;)V",
                    shift = At.Shift.AFTER),
            require = 0, expect = 1, allow = 1)
    private void quickskin$armorModelPosed(LivingEntityRenderState state, PoseStack poseStack, MultiBufferSource buffers,
                                           int packedLight, CallbackInfo callback) {
        WorldArmor.modelPosed(ImmersiveMode.session(), state, this,
                ((LivingEntityRenderer<?, ?, ?>) (Object) this).getModel(), poseStack);
    }

    @Inject(method = "render(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;popPose()V"),
            require = 0, expect = 1, allow = 1)
    private void quickskin$armorLayersDrawn(LivingEntityRenderState state, PoseStack poseStack, MultiBufferSource buffers,
                                            int packedLight, CallbackInfo callback) {
        WorldArmor.layersDrawn(state, this, ((LivingEntityRenderer<?, ?, ?>) (Object) this).getModel(), poseStack,
                buffers);
    }

    @Inject(method = "render(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("RETURN"), require = 0, expect = 1)
    private void quickskin$armorDrawEnds(LivingEntityRenderState state, PoseStack poseStack, MultiBufferSource buffers,
                                         int packedLight, CallbackInfo callback) {
        WorldArmor.drawDrops(ImmersiveMode.session(), state, poseStack, buffers, packedLight);
    }
//?} else if <1.21.9 {
    /** A render state of this renderer's own for the ghost, made once: see {@code quickskin$armorLayersDrawn}. */
    @Unique
    private EntityRenderState quickskin$ghostState;

    @Inject(method = "render(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("HEAD"), require = 0, expect = 1, allow = 1)
    private void quickskin$armorDrawStarts(LivingEntityRenderState state, PoseStack poseStack, MultiBufferSource buffers,
                                           int packedLight, CallbackInfo callback) {
        // Only while the menu is open: the camera's way back after it closed points at nothing
        Minecraft minecraft = Minecraft.getInstance();
        boolean local = state instanceof PlayerRenderState player && minecraft.player != null
                && player.id == minecraft.player.getId();
        WorldArmor.beginDraw(local && ImmersiveMode.posesPlayer() && ImmersiveMode.active(), state,
                local ? minecraft.player : null, poseStack);
    }

    @Inject(method = "render(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/model/EntityModel;setupAnim(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;)V",
                    shift = At.Shift.AFTER),
            require = 0, expect = 1, allow = 1)
    private void quickskin$armorModelPosed(LivingEntityRenderState state, PoseStack poseStack, MultiBufferSource buffers,
                                           int packedLight, CallbackInfo callback) {
        WorldArmor.modelPosed(ImmersiveMode.session(), state, this,
                ((LivingEntityRenderer<?, ?, ?>) (Object) this).getModel(), poseStack);
    }

    @Inject(method = "render(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;popPose()V"),
            require = 0, expect = 1, allow = 1)
    private void quickskin$armorLayersDrawn(LivingEntityRenderState state, PoseStack poseStack, MultiBufferSource buffers,
                                            int packedLight, CallbackInfo callback) {
        if (!WorldArmor.isDrawing(state)) {
            return;
        }
        // The ghost is drawn from a state the renderer makes from the player (createRenderState),
        // which would be the very state the world is still drawing: the renderer hands every draw
        // of its type the same one. For this call it is handed one of its own instead.
        ImmersiveRenderStateAccessor states = (ImmersiveRenderStateAccessor) this;
        EntityRenderState own = states.quickskin$reusedState();
        if (quickskin$ghostState == null) {
            quickskin$ghostState = ((EntityRenderer<?, ?>) (Object) this).createRenderState();
        }
        states.quickskin$setReusedState(quickskin$ghostState);
        try {
            WorldArmor.layersDrawn(state, this, ((LivingEntityRenderer<?, ?, ?>) (Object) this).getModel(), poseStack,
                    buffers);
        } finally {
            states.quickskin$setReusedState(own);
        }
    }

    @Inject(method = "render(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("RETURN"), require = 0, expect = 1)
    private void quickskin$armorDrawEnds(LivingEntityRenderState state, PoseStack poseStack, MultiBufferSource buffers,
                                         int packedLight, CallbackInfo callback) {
        WorldArmor.drawDrops(ImmersiveMode.session(), state, poseStack, buffers, packedLight);
    }
//?} else if <26.1 {
    // From 1.21.9 the player is submitted, its model and every layer drawn later from copies of
    // the pose stack; the model is posed for the layers right before them, inside the submit
    @Inject(method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/CameraRenderState;)V", at = @At("HEAD"), require = 0, expect = 1, allow = 1)
    private void quickskin$armorDrawStarts(LivingEntityRenderState state, PoseStack poseStack,
                                           SubmitNodeCollector collector, CameraRenderState camera,
                                           CallbackInfo callback) {
        // Only while the menu is open: the camera's way back after it closed points at nothing
        WorldArmor.beginDraw(ImmersiveMode.posesPlayer() && ImmersiveMode.active(), state, poseStack);
    }

    @Inject(method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/CameraRenderState;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/model/EntityModel;setupAnim(Ljava/lang/Object;)V",
                    shift = At.Shift.AFTER),
            require = 0, expect = 1, allow = 1)
    private void quickskin$armorModelPosed(LivingEntityRenderState state, PoseStack poseStack,
                                           SubmitNodeCollector collector, CameraRenderState camera,
                                           CallbackInfo callback) {
        WorldArmor.modelPosed(ImmersiveMode.session(), state, this,
                ((LivingEntityRenderer<?, ?, ?>) (Object) this).getModel(), poseStack);
    }

    @Inject(method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/CameraRenderState;)V",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;popPose()V"),
            require = 0, expect = 1, allow = 1)
    private void quickskin$armorLayersDrawn(LivingEntityRenderState state, PoseStack poseStack,
                                            SubmitNodeCollector collector, CameraRenderState camera,
                                            CallbackInfo callback) {
        WorldArmor.layersDrawn(state, this, ((LivingEntityRenderer<?, ?, ?>) (Object) this).getModel(), poseStack,
                collector);
    }

    @Inject(method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/CameraRenderState;)V", at = @At("RETURN"), require = 0, expect = 1)
    private void quickskin$armorDrawEnds(LivingEntityRenderState state, PoseStack poseStack,
                                         SubmitNodeCollector collector, CameraRenderState camera,
                                         CallbackInfo callback) {
        WorldArmor.drawDrops(ImmersiveMode.session(), state, poseStack, collector, state.lightCoords);
    }
//?} else {
    // From 26.1 the player is submitted and drawn later from copies of the pose stack, which
    // goes from the camera to the feet; the camera's rotation is in the camera state. The model
    // is posed for the layers right before them, inside the submit
    @Inject(method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V", at = @At("HEAD"), require = 0, expect = 1, allow = 1)
    private void quickskin$armorDrawStarts(LivingEntityRenderState state, PoseStack poseStack,
                                           SubmitNodeCollector collector, CameraRenderState camera,
                                           CallbackInfo callback) {
        // Only while the menu is open: the camera's way back after it closed points at nothing
        WorldArmor.beginDraw(ImmersiveMode.posesPlayer() && ImmersiveMode.active(), state, poseStack, camera);
    }

    @Inject(method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/model/EntityModel;setupAnim(Ljava/lang/Object;)V",
                    shift = At.Shift.AFTER),
            require = 0, expect = 1, allow = 1)
    private void quickskin$armorModelPosed(LivingEntityRenderState state, PoseStack poseStack,
                                           SubmitNodeCollector collector, CameraRenderState camera,
                                           CallbackInfo callback) {
        WorldArmor.modelPosed(ImmersiveMode.session(), state, this,
                ((LivingEntityRenderer<?, ?, ?>) (Object) this).getModel(), poseStack);
    }

    @Inject(method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;popPose()V"),
            require = 0, expect = 1, allow = 1)
    private void quickskin$armorLayersDrawn(LivingEntityRenderState state, PoseStack poseStack,
                                            SubmitNodeCollector collector, CameraRenderState camera,
                                            CallbackInfo callback) {
        WorldArmor.layersDrawn(state, this, ((LivingEntityRenderer<?, ?, ?>) (Object) this).getModel(), poseStack,
                collector);
    }

    @Inject(method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V", at = @At("RETURN"), require = 0, expect = 1)
    private void quickskin$armorDrawEnds(LivingEntityRenderState state, PoseStack poseStack,
                                         SubmitNodeCollector collector, CameraRenderState camera,
                                         CallbackInfo callback) {
        WorldArmor.drawDrops(ImmersiveMode.session(), state, poseStack, collector, state.lightCoords);
    }
//?}
}
