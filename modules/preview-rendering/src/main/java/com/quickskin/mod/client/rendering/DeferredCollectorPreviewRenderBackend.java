//? if >=26.2 {
package com.quickskin.mod.client.rendering;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Minecraft 26.2 deferred-collector preview backend.
 */
@Environment(EnvType.CLIENT)
public final class DeferredCollectorPreviewRenderBackend implements PreviewRenderBackend {
    @Override
    public void renderPlayerModel(
            GuiGraphicsExtractor graphics,
            int x,
            int y,
            float scale,
            float yRotation,
            PreviewPlayerData playerData,
            int mouseX,
            int mouseY,
            boolean followMouse
    ) {
        PlayerModelRenderer.renderPlayerModel(
                graphics, x, y, scale, yRotation, playerData, mouseX, mouseY, followMouse
        );
    }
}

//?}
