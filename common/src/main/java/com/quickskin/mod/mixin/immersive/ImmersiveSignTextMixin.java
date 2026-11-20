package com.quickskin.mod.mixin.immersive;

import org.spongepowered.asm.mixin.Mixin;
//? if >=1.21.9 {
import com.mojang.blaze3d.vertex.PoseStack;
import com.quickskin.mod.client.immersive.ModernAperture;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.AbstractSignRenderer;
import net.minecraft.client.renderer.blockentity.state.SignRenderState;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;
@Mixin(AbstractSignRenderer.class)
//?} else {
@Mixin(net.minecraft.client.renderer.GameRenderer.class)
//?}
public abstract class ImmersiveSignTextMixin {
    //? if >=1.21.9 {
    /** Glowing ink replaces the sign's light with full brightness immediately before submitting the letters. */
    @ModifyArgs(method = "submitSignText", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitText(Lcom/mojang/blaze3d/vertex/PoseStack;FFLnet/minecraft/util/FormattedCharSequence;ZLnet/minecraft/client/gui/Font$DisplayMode;IIII)V"),
            require = 0, expect = 1, allow = 1)
    private void quickskin$markSignLetters(Args text, SignRenderState sign, PoseStack pose, SubmitNodeCollector collector,
            //? if <26.1 {
            boolean front) {
            //?} else {
            net.minecraft.world.level.block.entity.SignText face) {
            //?}
        text.set(6, ModernAperture.markBlockLight(sign.blockPos, text.get(6)));
    }
    //?}
}
