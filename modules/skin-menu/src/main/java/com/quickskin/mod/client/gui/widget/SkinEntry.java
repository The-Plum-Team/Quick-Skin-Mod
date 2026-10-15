package com.quickskin.mod.client.gui.widget;

//? if <1.21.6 {
import com.mojang.blaze3d.systems.RenderSystem;
//?} else {
import com.quickskin.mod.client.services.LocalAssetManager;
//?}
import com.quickskin.mod.client.gui.GuiCompat;
//? if <26.2 {
import com.quickskin.mod.client.services.LocalAssetManager;
//?}
import com.quickskin.mod.common.data.AssetMetadata;
import com.quickskin.mod.common.data.TextureQuality;
import com.quickskin.mod.config.ClientConfig;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
//? if <26.1 {
import net.minecraft.client.gui.GuiGraphics;
//?} else {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?}
//? if <1.21.11 {
import net.minecraft.resources.ResourceLocation;
//?} else {
import net.minecraft.resources.Identifier;
//?}

/**
 * One skin in the skin column: a square tile showing its face. A hovered tile shows a small delete
 * button in its top-right corner, except on the player's own skin, which cannot be deleted.
 */
@Environment(EnvType.CLIENT)
public class SkinEntry {

    private final Minecraft mc;
    private final AssetMetadata metadata;
    //? if <1.21.11 {
    private final ResourceLocation textureLocation;
    //?} else {
    private final Identifier textureLocation;
    //?}
    private final SkinListWidget parentList;

    /** Size of the delete button in a hovered tile's top-right corner. */
    private static final int DELETE_BUTTON_SIZE = 8;

    private boolean isDeleteHovered;

    public SkinEntry(SkinListWidget parentList, AssetMetadata metadata) {
        this.parentList = parentList;
        this.metadata = metadata;
        this.mc = Minecraft.getInstance();

        // Get texture location from LocalAssetManager
        this.textureLocation = LocalAssetManager.getInstance()
            .getTextureLocation(metadata.hash(), TextureQuality.PREVIEW);
    }

    public AssetMetadata getMetadata() {
        return metadata;
    }

    public String getSortName() {
        return metadata.friendlyName();
    }

    /** Draws the tile with its top-left corner at {@code (left, top)}. */
    public void render(
            //? if <26.1 {
            GuiGraphics graphics,
            //?} else {
            GuiGraphicsExtractor graphics,
            //?}
            int left, int top, int size, int mouseX, int mouseY, boolean isHovered, boolean isSelected) {
        // One square tile per skin: the face, framed by the selection and hover state
        int tileSize = size;
        int tileLeft = left;
        int tileTop = top;
        int tileRight = tileLeft + tileSize;
        int tileBottom = tileTop + tileSize;

        // Check if this is the player's own skin
        ClientConfig config = ClientConfig.getInstance();
        boolean isPlayerOwnSkin = config.enablePlayerOwnSkinSystem && metadata.hash().equals(config.playerOwnSkinHash);

        graphics.fill(tileLeft, tileTop, tileRight, tileBottom, 0x80000000);
        int borderColor;
        if (isSelected) {
            // Purple for the player's own skin, blue for any other selection
            graphics.fill(tileLeft, tileTop, tileRight, tileBottom, isPlayerOwnSkin ? 0x60A020F0 : 0x60308CC0);
            borderColor = isPlayerOwnSkin ? 0xFFA020F0 : 0xFF4080FF;
        } else if (isHovered) {
            borderColor = 0xC0FFFFFF;
        } else {
            borderColor = isPlayerOwnSkin ? 0x80A020F0 : 0x50FFFFFF;
        }
        drawOutline(graphics, tileLeft, tileTop, tileSize, tileSize, borderColor);
        if (isSelected) {
            drawOutline(graphics, tileLeft + 1, tileTop + 1, tileSize - 2, tileSize - 2, borderColor);
        }

        // Render skin face preview
        int facePadding = 4;
        int faceSize = tileSize - facePadding * 2;
        int faceX = tileLeft + facePadding;
        int faceY = tileTop + facePadding;

        if (textureLocation != null) {
            //? if <1.21.5 {
            RenderSystem.enableBlend();
            //?}

            if (metadata.isCpmModel()) {
                GuiCompat.blit(graphics, textureLocation, faceX, faceY, faceSize, faceSize,
                    0, 0, 64, 64, 64, 64);
            } else {
                // Scale UV coordinates proportionally for HD textures
                int textureWidth = metadata.resolution().getWidth();
                int textureHeight = metadata.resolution().getHeight();
                float scaleX = textureWidth / 64.0f;
                float scaleY = textureHeight / 64.0f;

                GuiCompat.blit(graphics, textureLocation, faceX, faceY, faceSize, faceSize,
                    8.0f * scaleX, 8.0f * scaleY, (int)(8 * scaleX), (int)(8 * scaleY),
                    textureWidth, textureHeight);
                GuiCompat.blit(graphics, textureLocation, faceX, faceY, faceSize, faceSize,
                    40.0f * scaleX, 8.0f * scaleY, (int)(8 * scaleX), (int)(8 * scaleY),
                    textureWidth, textureHeight);
            }

            //? if <1.21.5 {
            RenderSystem.disableBlend();
            //?}
        } else {
            // Fallback if texture not loaded
            graphics.fill(faceX, faceY, faceX + faceSize, faceY + faceSize, 0xFF333333);
            //? if <26.1 {
            graphics.drawCenteredString(mc.font, "?", faceX + faceSize / 2,
                faceY + faceSize / 2 - 4, 0xFFFFFFFF);
            //?} else {
            graphics.centeredText(mc.font, "?", faceX + faceSize / 2,
                faceY + faceSize / 2 - 4, 0xFFFFFFFF);
            //?}
        }

        // A small delete button in the top-right corner on hover (but not for the player's own skin)
        this.isDeleteHovered = false;
        if (isHovered && !isPlayerOwnSkin) {
            int buttonRight = tileRight - 2;
            int buttonLeft = buttonRight - DELETE_BUTTON_SIZE;
            int buttonTop = tileTop + 2;
            int buttonBottom = buttonTop + DELETE_BUTTON_SIZE;
            this.isDeleteHovered = mouseX >= buttonLeft && mouseX < buttonRight && mouseY >= buttonTop && mouseY < buttonBottom;
            graphics.fill(buttonLeft, buttonTop, buttonRight, buttonBottom, this.isDeleteHovered ? 0xF0D03838 : 0xD0181820);
            // A small cross drawn from fills, so it stays crisp: two four-pixel diagonals
            int color = this.isDeleteHovered ? 0xFFFFFFFF : 0xFFE0E0E0;
            for (int i = 2; i < DELETE_BUTTON_SIZE - 2; i++) {
                graphics.fill(buttonLeft + i, buttonTop + i, buttonLeft + i + 1, buttonTop + i + 1, color);
                graphics.fill(buttonRight - 1 - i, buttonTop + i, buttonRight - i, buttonTop + i + 1, color);
            }
        }
    }

    /**
     * A left click on this tile, as last drawn: deletes from the delete button, or selects the skin.
     */
    public boolean click() {
        if (this.isDeleteHovered) {
            // Request deletion confirmation from parent
            parentList.requestDeletion(this);
            return true;
        }

        // Select this skin
        mc.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
            net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK.value(), 1.0f, 0.25f
        ));
        parentList.setSelected(this);
        parentList.onSkinSelected(this);
        return true;
    }

    /**
     * Draws an outline immediately using fill calls instead of submitOutline,
     * which defers rendering and can cause z-order issues with modals.
     */
    private static void drawOutline(
            //? if <26.1 {
            GuiGraphics graphics,
            //?} else {
            GuiGraphicsExtractor graphics,
            //?}
            int x, int y, int width, int height, int color) {
        graphics.fill(x, y, x + width, y + 1, color);
        graphics.fill(x, y + height - 1, x + width, y + height, color);
        graphics.fill(x, y + 1, x + 1, y + height - 1, color);
        graphics.fill(x + width - 1, y + 1, x + width, y + height - 1, color);
    }
}
