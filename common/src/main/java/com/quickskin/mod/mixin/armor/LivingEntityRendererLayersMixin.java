package com.quickskin.mod.mixin.armor;

import com.quickskin.mod.client.rendering.PreviewEquipment;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.List;

/** Lends the skin menu's preview the equipment layers a living-entity renderer holds (ADR 0017). */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererLayersMixin implements PreviewEquipment.RendererLayers {
    @Shadow
    @Final
    @SuppressWarnings("rawtypes")
    protected List layers;

    @Override
    public List<?> quickskin$previewLayers() {
        return layers;
    }
}
