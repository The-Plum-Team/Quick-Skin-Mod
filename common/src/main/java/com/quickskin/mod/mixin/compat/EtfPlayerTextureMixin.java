package com.quickskin.mod.mixin.compat;

import com.mojang.blaze3d.platform.NativeImage;
import com.quickskin.mod.client.services.PlayerAppearanceService;
import com.quickskin.mod.platform.QuickSkinInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
//? if <1.21.11 {
import net.minecraft.resources.ResourceLocation;
//?} else {
import net.minecraft.resources.Identifier;
//?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hands Entity Texture Features the pixels of a Quick Skin skin.
 *
 * <p>ETF decodes its player skin features (emissive and enchanted pixels, blinking, nose, jacket)
 * from the Mojang skin file or profile. A Quick Skin skin is a registered {@code DynamicTexture}
 * in the {@code quickskin} namespace, so that loader fails or reads the Mojang skin instead.
 * {@code checkTexture(true)} is ETF's own entry for pixels that are already loaded.</p>
 */
@Pseudo
@Mixin(targets = "traben.entity_texture_features.features.player.ETFPlayerTexture", remap = false)
public abstract class EtfPlayerTextureMixin {

    @Shadow
    public static NativeImage clientPlayerOriginalSkinImageForTool;

    @Shadow
    private NativeImage originalSkin;

    @Shadow
    //? if <1.21.11 {
    private ResourceLocation normalVanillaSkinIdentifier;
    //?} else {
    private Identifier normalVanillaSkinIdentifier;
    //?}

    @Shadow
    public abstract void checkTexture(boolean skipSkinLoad);

    @Inject(
            method = "checkTexture",
            at = @At("HEAD"),
            cancellable = true,
            require = 0,
            expect = 1,
            allow = 1,
            remap = false
    )
    private void quickskin$readQuickSkinPixels(boolean skipSkinLoad, CallbackInfo ci) {
        if (skipSkinLoad) return;
        try {
            if (this.normalVanillaSkinIdentifier == null
                    || !QuickSkinInfo.MOD_ID.equals(this.normalVanillaSkinIdentifier.getNamespace())) {
                return;
            }
            Minecraft minecraft = Minecraft.getInstance();
            AbstractTexture texture = minecraft.getTextureManager().getTexture(this.normalVanillaSkinIdentifier);
            if (!(texture instanceof DynamicTexture dynamicTexture)) return;
            NativeImage pixels = dynamicTexture.getPixels();
            if (pixels == null) return;
            // ETF reads its markers at fixed 64x64 coordinates, so a larger skin cannot carry them.
            // It gets a blank image rather than ETF's own loader, which on 1.21.4 to 1.21.8 decodes
            // the account's Mojang skin and would draw that one in place of the Quick Skin skin.
            boolean markerLayout = pixels.getWidth() == 64 && pixels.getHeight() == 64;

            PlayerAppearanceService service = PlayerAppearanceService.getInstance();
            boolean ownSkin = minecraft.player != null && service != null
                    && this.normalVanillaSkinIdentifier.equals(service.getSkinLocation(minecraft.player.getUUID()));
            NativeImage copy = new NativeImage(64, 64, true);
            if (markerLayout) copy.copyFrom(pixels);
            this.originalSkin = copy;
            if (markerLayout && ownSkin) {
                // ETF enables its skin feature tool only once it holds the local player's pixels.
                clientPlayerOriginalSkinImageForTool = copy;
            }
        } catch (RuntimeException | LinkageError e) {
            // Degrade locally: ETF's own loader handles the texture as it does today.
            return;
        }
        ci.cancel();
        this.checkTexture(true);
    }
}
