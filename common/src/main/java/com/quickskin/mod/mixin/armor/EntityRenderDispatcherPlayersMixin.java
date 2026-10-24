package com.quickskin.mod.mixin.armor;

import com.quickskin.mod.client.rendering.PreviewEquipment;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Map;

/**
 * Lends the skin menu's preview the player renderer of each model type, so the equipment it draws
 * fits the previewed model, slim or classic, whatever the player's own skin is (ADR 0017).
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherPlayersMixin implements PreviewEquipment.PlayerRenderers {
    @Shadow
    @SuppressWarnings("rawtypes")
    private Map playerRenderers;

    @Override
    public Map<?, ?> quickskin$playerRenderers() {
        return playerRenderers;
    }
}
