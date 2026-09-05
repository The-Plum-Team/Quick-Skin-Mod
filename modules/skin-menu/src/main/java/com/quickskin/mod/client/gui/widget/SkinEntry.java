package com.quickskin.mod.client.gui.widget;

//? if <1.21.5 {
import com.mojang.blaze3d.systems.RenderSystem;
//?}
import com.quickskin.mod.client.gui.GuiCompat;
import com.quickskin.mod.client.services.LocalAssetManager;
import com.quickskin.mod.client.util.PremiumDetector;
import com.quickskin.mod.common.data.AssetMetadata;
import com.quickskin.mod.common.data.TextureQuality;
import com.quickskin.mod.config.ClientConfig;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
//? if <26.1.2 {
import net.minecraft.client.gui.GuiGraphics;
//?} else {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?}
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
//? if <1.21.11 {
import net.minecraft.resources.ResourceLocation;
//?} else {
import net.minecraft.resources.Identifier;
//?}
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Individual skin entry in the skin list
 */
@Environment(EnvType.CLIENT)
public class SkinEntry extends ContainerObjectSelectionList.Entry<SkinEntry> {

    private final Minecraft mc;
    private final AssetMetadata metadata;
    //? if <1.21.11 {
    private final ResourceLocation textureLocation;
    //?} else {
    private final Identifier textureLocation;
    //?}
    private final SkinListWidget parentList;
    private final boolean isPremiumAccount;

    private boolean isDeleteHovered;
    private boolean isEditHovered;
    private boolean isUploadHovered;

    public SkinEntry(SkinListWidget parentList, AssetMetadata metadata) {
        this.parentList = parentList;
        this.metadata = metadata;
        this.mc = Minecraft.getInstance();
        this.isPremiumAccount = PremiumDetector.isPremiumAccount();

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

    @Override
    //? if <1.21.11 {
    public void render(GuiGraphics graphics, int index, int top, int left, int width, int height,
                      int mouseX, int mouseY, boolean isHovered, float partialTicks) {
    //?} else {
        //? if <26.1.2 {
    public void renderContent(GuiGraphics graphics, int mouseX, int mouseY, boolean isHovered, float partialTicks) {
        //?} else {
    public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean isHovered, float partialTicks) {
        //?}
        int top = this.getY();
        int left = this.getX();
        int width = this.getWidth();
        int height = this.getHeight();
    //?}

        // Selection and hover highlight
        int highlightPaddingH = 4;
        int highlightPaddingV = 2;
        int highlightLeft = left - highlightPaddingH;
        int highlightRight = left + width - 10;
        int highlightTop = top - highlightPaddingV;
        int highlightBottom = top + height + highlightPaddingV;

        // Check if this is the player's own skin
        ClientConfig config = ClientConfig.getInstance();
        boolean isPlayerOwnSkin = config.enablePlayerOwnSkinSystem && metadata.hash().equals(config.playerOwnSkinHash);

        if (parentList.getSelected() == this) {
            if (isPlayerOwnSkin) {
                // Selected state for player's own skin - purple highlight with border
                graphics.fill(highlightLeft, highlightTop, highlightRight, highlightBottom, 0x80A020F0);
                //? if <1.21.11 {
                graphics.renderOutline(highlightLeft, highlightTop, highlightRight - highlightLeft,
                //?} else {
                drawOutline(graphics, highlightLeft, highlightTop, highlightRight - highlightLeft,
                //?}
                    highlightBottom - highlightTop, 0xFFA020F0);
            } else {
                // Selected state - blue highlight with border
                graphics.fill(highlightLeft, highlightTop, highlightRight, highlightBottom, 0x80308CC0);
                //? if <1.21.11 {
                graphics.renderOutline(highlightLeft, highlightTop, highlightRight - highlightLeft,
                //?} else {
                drawOutline(graphics, highlightLeft, highlightTop, highlightRight - highlightLeft,
                //?}
                    highlightBottom - highlightTop, 0xFF4080FF);
            }
        } else if (isHovered) {
            // Hover state - subtle white highlight
            graphics.fill(highlightLeft, highlightTop, highlightRight, highlightBottom, 0x30FFFFFF);
        }

        // Render skin face preview
        int faceSize = height - 8;
        int faceX = left + 4;
        int faceY = top + 4;

        if (textureLocation != null) {
            //? if <1.21.5 {
            RenderSystem.enableBlend();
            //?}

            if (metadata.isCpmModel()) {
                GuiCompat.blit(graphics, textureLocation, faceX, faceY, faceSize, faceSize,
                    0, 0, 64, 64, 64, 64);
            } else {
                int textureWidth = metadata.resolution().getWidth();
                int textureHeight = metadata.resolution().getHeight();

                //? if <1.21 {
                float scaleX = textureWidth / 64.0f;
                float scaleY = textureHeight / 64.0f;
                //?} else {
            // Scale UV coordinates proportionally for HD textures
            float scaleX = textureWidth / 64.0f;
            float scaleY = textureHeight / 64.0f;
                //?}

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
            //? if <26.1.2 {
            graphics.drawCenteredString(mc.font, "?", faceX + faceSize / 2,
                faceY + faceSize / 2 - 4, 0xFFFFFFFF);
            //?} else {
            graphics.centeredText(mc.font, "?", faceX + faceSize / 2,
                faceY + faceSize / 2 - 4, 0xFFFFFFFF);
            //?}
        }

        // Render text info
        int textX = left + faceSize + 12;

        // Calculate max width for text to avoid overlapping buttons
        int actionButtonSize = 11;
        int buttonAreaWidth = actionButtonSize + 8;
        int textMaxWidth = (highlightRight - buttonAreaWidth) - textX;

        // Display name (truncated if needed)
        String displayName = getSortName();
        if (mc.font.width(displayName) > textMaxWidth) {
            displayName = mc.font.plainSubstrByWidth(displayName, textMaxWidth - mc.font.width("...")) + "...";
        }
        //? if <1.21.11 {
        graphics.drawString(mc.font, displayName, textX, top + 6, 0xFFFFFFFF);
        //?} else {
            //? if <26.1.2 {
        graphics.drawString(mc.font, displayName, textX, top + 6, 0xFFFFFFFF);
            //?} else {
        graphics.text(mc.font, displayName, textX, top + 6, 0xFFFFFFFF);
            //?}
        //?}

        // Model type and resolution
        String modelText;
        int modelTextColor;
        if (metadata.isCpmModel()) {
            modelText = "CPM Model";
            modelTextColor = 0xFF55AAFF;
        } else {
            modelText = "slim".equals(metadata.skinModel() != null ? metadata.skinModel().toLowerCase(Locale.ROOT) : null) ? "Slim" : "Classic";
            if (metadata.resolution().isHD()) {
                modelText += " • " + metadata.resolution().name();
            }
            modelTextColor = metadata.resolution().isHD() ? 0xFF55FF55 : 0xFFAAAAAA;
        }
        //? if <1.21.11 {
        graphics.drawString(mc.font, modelText, textX, top + 6 + mc.font.lineHeight + 2, modelTextColor);
        //?} else {
            //? if <26.1.2 {
        graphics.drawString(mc.font, modelText, textX, top + 6 + mc.font.lineHeight + 2,
            0xFF000000 | modelTextColor);
            //?} else {
        graphics.text(mc.font, modelText, textX, top + 6 + mc.font.lineHeight + 2,
            0xFF000000 | modelTextColor);
            //?}
        //?}

        // Render action buttons on hover (but not for player's own skin)
        this.isDeleteHovered = false;
        this.isEditHovered = false;
        this.isUploadHovered = false;

        if (isHovered && !isPlayerOwnSkin) {
            int margin = 4;
            // Action button state
            int deleteButtonX = highlightRight - actionButtonSize - margin;
            int deleteButtonY = highlightTop + margin;

            boolean deleteHovered = mouseX >= deleteButtonX && mouseX < deleteButtonX + actionButtonSize &&
                                   mouseY >= deleteButtonY && mouseY < deleteButtonY + actionButtonSize;

            graphics.fill(deleteButtonX, deleteButtonY,
                deleteButtonX + actionButtonSize, deleteButtonY + actionButtonSize,
                deleteHovered ? 0xA0E04040 : 0x80C00000);
            //? if <26.1.2 {
            graphics.drawString(mc.font, "x", deleteButtonX + 3, deleteButtonY + 1, 0xFFFFFFFF);
            //?} else {
            graphics.text(mc.font, "x", deleteButtonX + 3, deleteButtonY + 1, 0xFFFFFFFF);
            //?}

            this.isDeleteHovered = deleteHovered;

            // Render edit/rename button below delete button
            int editButtonY = deleteButtonY + actionButtonSize + 2;

            boolean editHovered = mouseX >= deleteButtonX && mouseX < deleteButtonX + actionButtonSize &&
                                 mouseY >= editButtonY && mouseY < editButtonY + actionButtonSize;

            graphics.fill(deleteButtonX, editButtonY,
                deleteButtonX + actionButtonSize, editButtonY + actionButtonSize,
                editHovered ? 0xA040C0C0 : 0x80408080);
            //? if <26.1.2 {
            graphics.drawString(mc.font, "✎", deleteButtonX + 2, editButtonY + 1, 0xFFFFFFFF);
            //?} else {
            graphics.text(mc.font, "✎", deleteButtonX + 2, editButtonY + 1, 0xFFFFFFFF);
            //?}

            this.isEditHovered = editHovered;

            if (isPremiumAccount && !metadata.isCpmModel()) {
                int uploadButtonY = editButtonY + actionButtonSize + 2;
                boolean uploadHovered = mouseX >= deleteButtonX && mouseX < deleteButtonX + actionButtonSize &&
                                       mouseY >= uploadButtonY && mouseY < uploadButtonY + actionButtonSize;

                graphics.fill(deleteButtonX, uploadButtonY,
                    deleteButtonX + actionButtonSize, uploadButtonY + actionButtonSize,
                    uploadHovered ? 0xA040A040 : 0x80408040);
                //? if <26.1.2 {
                graphics.drawString(mc.font, "↑", deleteButtonX + 2, uploadButtonY + 1, 0xFFFFFFFF);
                //?} else {
                graphics.text(mc.font, "↑", deleteButtonX + 2, uploadButtonY + 1, 0xFFFFFFFF);
                //?}

                this.isUploadHovered = uploadHovered;
            }
        }
    }

    @Override
    //? if <1.21.11 {
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
    //?} else {
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean focused) {
        double mouseX = GuiCompat.mouseX(event);
        double mouseY = GuiCompat.mouseY(event);
        int button = GuiCompat.mouseButton(event);
    //?}
        if (button == 0) {
            if (this.isDeleteHovered) {
                // Request deletion confirmation from parent
                parentList.requestDeletion(this);
                return true;
            }

            if (this.isEditHovered) {
                // Request rename from parent
                parentList.requestRename(this);
                return true;
            }

            if (this.isUploadHovered) {
                // Request upload to Mojang
                parentList.requestUploadToMojang(this);
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
        return false;
    }

    @Override
    public @NotNull List<? extends GuiEventListener> children() {
        return Collections.emptyList();
    }

    @Override
    public @NotNull List<? extends NarratableEntry> narratables() {
        return List.of();
    }
    //? if >=1.21.11 {

    /**
     * Draws an outline immediately using fill calls instead of submitOutline,
     * which defers rendering and can cause z-order issues with modals.
     */
    private static void drawOutline(
            //? if <26.1.2 {
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
    //?}
}
