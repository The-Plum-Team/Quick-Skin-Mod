//? if >=1.21.9 && <26.1 {
package com.quickskin.mod.client.rendering;

import net.minecraft.client.gui.GuiGraphics;

/** GUI render-state preview submission backend. */
@net.fabricmc.api.Environment(net.fabricmc.api.EnvType.CLIENT)
public final class RenderStatePreviewRenderBackend implements PreviewRenderBackend {
    @Override
    public void renderPlayerModel(
            GuiGraphics graphics,
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
                graphics, x, y, scale, yRotation, playerData, mouseX, mouseY, followMouse);
    }
}
//?}
