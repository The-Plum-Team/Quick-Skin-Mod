package com.quickskin.mod.mixin.immersive;

//? if <1.21 {
import com.mojang.blaze3d.vertex.PoseStack;
import com.quickskin.mod.client.immersive.ImmersiveMode;
import com.quickskin.mod.client.rendering.WorldArmor;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?} else if <1.21.2 {
import com.mojang.blaze3d.vertex.PoseStack;
import com.quickskin.mod.client.immersive.ImmersiveMode;
import com.quickskin.mod.client.rendering.WorldArmor;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?} else if <1.21.6 {
//?} else if <1.21.9 {
//?} else if <26.1 {
//?} else {
//?}
//? if <1.21.9 {
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
//?} else {
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
//?}
import org.spongepowered.asm.mixin.Mixin;

/**
 * In the immersive view, keeps the armour the local player wears before other mods' render hooks
 * run for the player's draw (Forge's or NeoForge's {@code RenderPlayerEvent.Pre}), so a piece one
 * of them takes off for the draw counts as covered on the real player, as on the skin menu's model
 * ({@code WorldArmor.beforePlayer}). Should it fail, such a piece is pointed out as worn.
 *
 * <p>Only 1.20.1 and 1.21.1 hook it. From 1.21.2 the player is drawn from a render state, which
 * such a mod changes as it is filled, so what it took off is not known and no piece counts as
 * covered; the class exists there, empty (its target is {@code AvatarRenderer} from 1.21.9), since
 * a mixin config names it everywhere.
 */
//? if <1.21.9 {
@Mixin(PlayerRenderer.class)
//?} else {
@Mixin(AvatarRenderer.class)
//?}
public abstract class ImmersiveArmorPlayerMixin {
//? if <1.21 {
    @Inject(method = "render(Lnet/minecraft/client/player/AbstractClientPlayer;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("HEAD"), require = 0, expect = 1, allow = 1)
    private void quickskin$keepArmourBeforeHooks(AbstractClientPlayer player, float entityYaw, float partialTick,
                                                 PoseStack poseStack, MultiBufferSource buffers, int packedLight,
                                                 CallbackInfo callback) {
        WorldArmor.beforePlayer(ImmersiveMode.posesPlayer() && ImmersiveMode.active(), player);
    }
//?} else if <1.21.2 {
    @Inject(method = "render(Lnet/minecraft/client/player/AbstractClientPlayer;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("HEAD"), require = 0, expect = 1, allow = 1)
    private void quickskin$keepArmourBeforeHooks(AbstractClientPlayer player, float entityYaw, float partialTick,
                                                 PoseStack poseStack, MultiBufferSource buffers, int packedLight,
                                                 CallbackInfo callback) {
        WorldArmor.beforePlayer(ImmersiveMode.posesPlayer() && ImmersiveMode.active(), player);
    }
//?} else if <1.21.6 {
//?} else if <1.21.9 {
//?} else if <26.1 {
//?} else {
//?}
}
