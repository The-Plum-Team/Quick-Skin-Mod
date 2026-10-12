package com.quickskin.mod.client.gui.widget;

import com.mojang.blaze3d.platform.InputConstants;
//? if <1.21.6 {
import com.mojang.blaze3d.systems.RenderSystem;
//?} else {
import com.quickskin.mod.client.services.LocalAssetManager;
//?}
import com.quickskin.mod.client.gui.GuiCompat;
//? if <26.2 {
import com.quickskin.mod.client.services.LocalAssetManager;
//?}
import com.quickskin.mod.client.util.PremiumDetector;
import com.quickskin.mod.common.data.AssetMetadata;
import com.quickskin.mod.common.data.TextureQuality;
import com.quickskin.mod.config.ClientConfig;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
//? if <26.1 {
import net.minecraft.client.gui.GuiGraphics;
//?} else {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?}
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.network.chat.Component;
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
 * One skin in the skin column: a square tile showing its face
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

    private static final int ACTION_BUTTON_SIZE = 11;

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
    //? if <1.21.9 {
    public void render(GuiGraphics graphics, int index, int top, int left, int width, int height,
                      int mouseX, int mouseY, boolean isHovered, float partialTicks) {
    //?} else if <26.1 {
    public void renderContent(GuiGraphics graphics, int mouseX, int mouseY, boolean isHovered, float partialTicks) {
    //?} else {
    public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean isHovered, float partialTicks) {
    //?}
    //? if <1.21.9 {
    //?} else {
        int top = this.getY();
        int left = this.getX();
        int width = this.getWidth();
        int height = this.getHeight();
    //?}

        // One square tile per skin: the face, framed by the selection and hover state
        int tileSize = height;
        int tileLeft = left;
        int tileTop = top;
        int tileRight = tileLeft + tileSize;
        int tileBottom = tileTop + tileSize;

        // Check if this is the player's own skin
        ClientConfig config = ClientConfig.getInstance();
        boolean isPlayerOwnSkin = config.enablePlayerOwnSkinSystem && metadata.hash().equals(config.playerOwnSkinHash);
        boolean isSelected = parentList.getSelected() == this;

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
        if (isHovered) {
            parentList.setHoveredEntry(this);
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

        // Action buttons beside the tile on hover (but not for player's own skin)
        this.isDeleteHovered = false;
        this.isEditHovered = false;
        this.isUploadHovered = false;

        if (isHovered && !isPlayerOwnSkin) {
            int buttonX = tileRight + 3;
            int deleteButtonY = tileTop;
            this.isDeleteHovered = drawActionButton(graphics, buttonX, deleteButtonY, "x", 3,
                0xA0E04040, 0x80C00000, mouseX, mouseY);

            int editButtonY = deleteButtonY + ACTION_BUTTON_SIZE + 2;
            this.isEditHovered = drawActionButton(graphics, buttonX, editButtonY, "✎", 2,
                0xA040C0C0, 0x80408080, mouseX, mouseY);

            if (isPremiumAccount && !metadata.isCpmModel()) {
                int uploadButtonY = editButtonY + ACTION_BUTTON_SIZE + 2;
                this.isUploadHovered = drawActionButton(graphics, buttonX, uploadButtonY, "↑", 2,
                    0xA040A040, 0x80408040, mouseX, mouseY);
            }
        }
    }

    /** Draws one hover action button; returns whether the mouse is over it. */
    private boolean drawActionButton(
            //? if <26.1 {
            GuiGraphics graphics,
            //?} else {
            GuiGraphicsExtractor graphics,
            //?}
            int x, int y, String glyph, int glyphOffsetX, int hoverColor, int color, int mouseX, int mouseY) {
        boolean hovered = mouseX >= x && mouseX < x + ACTION_BUTTON_SIZE
                && mouseY >= y && mouseY < y + ACTION_BUTTON_SIZE;
        graphics.fill(x, y, x + ACTION_BUTTON_SIZE, y + ACTION_BUTTON_SIZE, hovered ? hoverColor : color);
        //? if <26.1 {
        graphics.drawString(mc.font, glyph, x + glyphOffsetX, y + 1, 0xFFFFFFFF);
        //?} else {
        graphics.text(mc.font, glyph, x + glyphOffsetX, y + 1, 0xFFFFFFFF);
        //?}
        return hovered;
    }

    /** Width of a tile row: the tile plus the column of hover action buttons beside it. */
    public static int rowWidth(int tileSize) {
        return tileSize + 3 + ACTION_BUTTON_SIZE;
    }

    /**
     * What a tooltip says about this entry: the action under the mouse, or the skin's name and its
     * model, resolution or CPM label.
     */
    public List<Component> tooltipLines() {
        if (isDeleteHovered) return List.of(Component.translatable("quickskin.button.delete"));
        if (isEditHovered) return List.of(Component.translatable("quickskin.screen.rename.title"));
        if (isUploadHovered) return List.of(Component.translatable("quickskin.button.upload_skin"));

        Component detail;
        if (metadata.isCpmModel()) {
            detail = Component.literal("CPM Model").withStyle(ChatFormatting.AQUA);
        } else {
            String modelText = "slim".equals(metadata.skinModel() != null ? metadata.skinModel().toLowerCase(Locale.ROOT) : null) ? "Slim" : "Classic";
            if (metadata.resolution().isHD()) {
                detail = Component.literal(modelText + " • " + metadata.resolution().name())
                        .withStyle(ChatFormatting.GREEN);
            } else {
                detail = Component.literal(modelText).withStyle(ChatFormatting.GRAY);
            }
        }
        return List.of(Component.literal(getSortName()), detail);
    }

    @Override
    //? if <1.21.9 {
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
    //?} else {
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean focused) {
        double mouseX = GuiCompat.mouseX(event);
        double mouseY = GuiCompat.mouseY(event);
        int button = GuiCompat.mouseButton(event);
    //?}
        if (button == InputConstants.MOUSE_BUTTON_LEFT) {
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
