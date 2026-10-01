package com.quickskin.mod.mixin.compat;

import com.quickskin.mod.client.compat.EarsCompatIntegration;
//? if <1.21.11 {
import net.minecraft.resources.ResourceLocation;
//?} else {
import net.minecraft.resources.Identifier;
//?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.UUID;

/**
 * Mixin into the Ears 2.x feature lookup of the Ward (Minecraft 1.21.11) and Thermite (26.x) ports.
 * Both send every caller, after their own invisibility check, through
 * getEarsFeatures(String, UUID, Identifier), which only recognises the texture class Ears creates
 * itself. Answer from QuickSkin's parsed skin data before Ears looks.
 *
 * The selector carries the match-all quantifier on purpose. A bare name selects only the first
 * declared overload (the Avatar one), which this handler does not match. With every overload
 * selected, Mixin skips the two whose descriptors differ and injects into the three-argument
 * lookup only. The handler's Identifier parameter is remapped with the rest of this class, so no
 * mapping-specific descriptor string is needed.
 */
@Pseudo
@Mixin(targets = {"diy.y2k.five.thermite.Thermite", "diy.y2k.five.ward.Ward"}, remap = false)
public class EarsFiveMixin {

    @Inject(
            method = "getEarsFeatures*",
            at = @At("HEAD"),
            cancellable = true,
            require = 0,
            expect = 1,
            allow = 1,
            remap = false
    )
    // Neither port exists before 1.21.11; that branch only keeps the class compiling.
    //? if <1.21.11 {
    private static void quickskin$getEarsFeatures(String name, UUID id, ResourceLocation skin, CallbackInfoReturnable<Object> cir) {
    //?} else {
    private static void quickskin$getEarsFeatures(String name, UUID id, Identifier skin, CallbackInfoReturnable<Object> cir) {
    //?}
        Object features = EarsCompatIntegration.getFeatures(skin);
        if (features != null) {
            cir.setReturnValue(features);
        }
    }
}
