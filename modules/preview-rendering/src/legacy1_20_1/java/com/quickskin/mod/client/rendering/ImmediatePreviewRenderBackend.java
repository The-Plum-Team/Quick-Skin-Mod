package com.quickskin.mod.client.rendering;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphics;

/** Minecraft 1.20.1 immediate/InventoryScreen-style preview backend. */
@Environment(EnvType.CLIENT)
public final class ImmediatePreviewRenderBackend implements PreviewRenderBackend {
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
