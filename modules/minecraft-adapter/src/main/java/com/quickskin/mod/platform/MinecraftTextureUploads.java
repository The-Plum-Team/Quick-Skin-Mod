package com.quickskin.mod.platform;

import com.mojang.blaze3d.platform.NativeImage;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.renderer.texture.DynamicTexture;

import java.util.function.Supplier;

/** Native texture construction; callers retain registration, lifetime and render-thread ownership. */
@Environment(EnvType.CLIENT)
public final class MinecraftTextureUploads {
    private MinecraftTextureUploads() {
    }

    public static DynamicTexture create(Supplier<String> label, NativeImage image) {
        //? if <1.21.5 {
        return new DynamicTexture(image);
        //?} else {
        return new DynamicTexture(label, image);
        //?}
    }
}
