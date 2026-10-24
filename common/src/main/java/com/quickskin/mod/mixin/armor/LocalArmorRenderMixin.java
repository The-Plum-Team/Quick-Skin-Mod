package com.quickskin.mod.mixin.armor;

import com.quickskin.mod.client.rendering.PlayerModelRenderer;
//? if <1.21.2 {
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
//?} else if <1.21.9 {
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.renderer.entity.state.PlayerRenderState;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?} else {
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.Avatar;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;

/**
 * The local player does not see the armour pieces they hide from everyone either (ADR 0017): in
 * third person, in the inventory and in the previews. From 1.21.2 the pieces are left out of the
 * render state extracted from the player; before, {@link LocalArmorLayerMixin} and its siblings
 * skip them in the layers that draw them, and this one does nothing. Every other player is drawn
 * from the equipment the server sent, which already lacks their hidden pieces.
 */
//? if <1.21.9 {
@Mixin(PlayerRenderer.class)
//?} else {
@Mixin(AvatarRenderer.class)
//?}
public abstract class LocalArmorRenderMixin {
//? if <1.21.2 {
//?} else if <1.21.9 {
    @Inject(method = "extractRenderState(Lnet/minecraft/client/player/AbstractClientPlayer;Lnet/minecraft/client/renderer/entity/state/PlayerRenderState;F)V",
            at = @At("TAIL"), require = 0, expect = 1, allow = 1)
    private void quickskin$hideOwnArmor(AbstractClientPlayer player, PlayerRenderState state, float partialTick,
                                        CallbackInfo ci) {
        PlayerModelRenderer.hideOwnArmor(player, state);
    }
//?} else {
    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V",
            at = @At("TAIL"), require = 0, expect = 1, allow = 1)
    private void quickskin$hideOwnArmor(Avatar avatar, AvatarRenderState state, float partialTick,
                                        CallbackInfo ci) {
        PlayerModelRenderer.hideOwnArmor(avatar, state);
    }
//?}
}
