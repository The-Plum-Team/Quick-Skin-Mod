package com.quickskin.mod.mixin.compat;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Keeps CPM's server-model removal race from turning a valid skin reset into a background error.
 *
 * <p>CPM first checks whether its server-model map contains a player key, then reads that map from
 * an asynchronous supplier. A simultaneous {@code SetSkinS2C} removal can therefore make the
 * later read return {@code null}. An empty stream has CPM's intended "no model" result without
 * changing valid model bytes.</p>
 */
@Pseudo
@Mixin(targets = "com.tom.cpm.shared.definition.ModelDefinitionLoader", remap = false)
public abstract class CpmModelDefinitionLoaderMixin {

    @ModifyArg(
            method = "loadModel([BLcom/tom/cpm/shared/config/Player;)"
                    + "Lcom/tom/cpm/shared/definition/ModelDefinition;",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/io/ByteArrayInputStream;<init>([B)V",
                    remap = false
            ),
            index = 0,
            require = 0,
            expect = 1,
            allow = 1,
            remap = false
    )
    private byte[] quickskin$guardClearedServerModel(byte[] data) {
        return data == null ? new byte[0] : data;
    }
}
