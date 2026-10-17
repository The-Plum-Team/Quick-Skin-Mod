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
 * One skin in the skin column: a square tile showing its face in the grid, or a card with its 3D
 * bust, name and details in the list. A hovered tile or card shows a delete button in its top-right
 * corner, except on the player's own skin, which cannot be deleted.
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

    // The list rows are cards in the Confirm button's style: a charcoal face in a thin border and a
    // black outline, on a darker lip
    private static final int CARD_OUTLINE = 0xFF0E0E0F;
    private static final int CARD_FACE = 0xFF3A3B3E;
    private static final int CARD_FACE_HOVER = 0xFF4A4B4F;
    private static final int CARD_BORDER = 0xFF6E6F73;
    private static final int CARD_BORDER_HOVER = 0xFF8D8E93;
    private static final int CARD_LIP = 0xFF1C1D1F;
    private static final int CARD_LIP_HEIGHT = 3;
    /** The selected card's border and the colour behind its bust: purple for the player's own skin. */
    private static final int SELECTED_COLOR = 0xFF6FA0FF;
    private static final int OWN_SKIN_COLOR = 0xFFA020F0;
    /** The colour behind an unselected bust. */
    private static final int IDLE_COLOR = 0xFF8D8E93;
    /** How strong that colour starts at the card's left edge, before it fades out over this width. */
    private static final int SELECTED_GLOW = 0x70;
    private static final int OWN_SKIN_GLOW = 0x22;
    private static final int IDLE_GLOW = 0x40;
    private static final int GLOW_WIDTH = 70;
    /** The bust's width, and the gap between the card's left edge and the text. */
    private static final int BUST_WIDTH = 40;
    private static final int TEXT_GAP = 3;
    /** The red delete button in a hovered card's top-right corner. */
    private static final int ROW_DELETE_SIZE = 13;
    private static final int ROW_DELETE_LIP = 2;
    private static final int ROW_DELETE_FACE = 0xFFB83030;
    private static final int ROW_DELETE_FACE_HOVER = 0xFFD84040;
    private static final int ROW_DELETE_BORDER = 0xFFE06060;
    private static final int ROW_DELETE_BORDER_HOVER = 0xFFFF9090;
    private static final int MODEL_COLOR = 0xFFAAAAAA;
    private static final int CPM_COLOR = 0xFF55AAFF;
    private static final int RESOLUTION_COLOR = 0xFF8A8A8A;
    private static final int HD_COLOR = 0xFF55FF55;

    // Seven-pixel icons, one string per row: the arm width or a CPM model, and the delete button's bin
    private static final String[] ICON_WIDE = {"..##...", "..##...", "######.", "######.", "#.##.#.", "..##...", ".#..#.."};
    private static final String[] ICON_SLIM = {"..##...", "..##...", ".####..", "#.##.#.", "#.##.#.", "..##...", ".#..#.."};
    private static final String[] ICON_CPM = {"...#...", ".##.##.", "#.....#", "##...##", "#.##.##", "#..#..#", ".##.##."};
    private static final String[] ICON_BIN = {"..###..", "#######", ".#####.", ".#.#.#.", ".#.#.#.", ".#.#.#.", ".#####."};

    /**
     * Draws the skin as a row of the list, with its top-left corner at {@code (left, top)}: a card
     * with the skin's 3D bust on the left and, beside it, the name over a hairline and a line with
     * the arm width (or CPM) and the resolution. The selected card has a coloured border and that
     * colour behind its bust; a hovered card shows a delete button in its top-right corner, except
     * on the player's own skin.
     */
    public void renderRow(
            //? if <26.1 {
            GuiGraphics graphics,
            //?} else {
            GuiGraphicsExtractor graphics,
            //?}
            int left, int top, int width, int height, int mouseX, int mouseY, boolean isHovered, boolean isSelected) {
        int right = left + width;
        int bottom = top + height;
        ClientConfig config = ClientConfig.getInstance();
        boolean isPlayerOwnSkin = config.enablePlayerOwnSkinSystem && metadata.hash().equals(config.playerOwnSkinHash);

        // The card
        graphics.fill(left, top, right, bottom, CARD_OUTLINE);
        int faceBottom = bottom - 1 - CARD_LIP_HEIGHT;
        graphics.fill(left + 1, faceBottom, right - 1, bottom - 1, CARD_LIP);
        int stateColor = isPlayerOwnSkin ? OWN_SKIN_COLOR : SELECTED_COLOR;
        int border = isSelected ? stateColor : isHovered ? CARD_BORDER_HOVER : CARD_BORDER;
        graphics.fill(left + 1, top + 1, right - 1, faceBottom, border);
        graphics.fill(left + 2, top + 2, right - 2, faceBottom - 1, isHovered ? CARD_FACE_HOVER : CARD_FACE);

        // Colour behind the bust, fading toward the text: strong only on the selected card
        int glowColor = isSelected || isPlayerOwnSkin ? stateColor : IDLE_COLOR;
        int glow = isSelected ? SELECTED_GLOW : isPlayerOwnSkin ? OWN_SKIN_GLOW : IDLE_GLOW;
        int glowWidth = Math.min(GLOW_WIDTH, width - 4);
        for (int k = 0; k < glowWidth; k++) {
            int alpha = glow * (GLOW_WIDTH - k) / GLOW_WIDTH;
            if (alpha > 0) {
                graphics.fill(left + 2 + k, top + 2, left + 3 + k, faceBottom - 1,
                        alpha << 24 | (glowColor & 0x00FFFFFF));
            }
        }

        // The bust, cut off at the face's bottom edge
        int bustWidth = Math.min(BUST_WIDTH, width / 3);
        int bustHeight = faceBottom - 1 - (top + 2);
        renderBust(graphics, left + 3, top + 2, bustWidth, bustHeight);

        // The delete button, top right
        this.isDeleteHovered = false;
        boolean showDelete = isHovered && !isPlayerOwnSkin;
        int deleteLeft = right - 3 - ROW_DELETE_SIZE;
        int deleteTop = top + 4;
        if (showDelete) {
            this.isDeleteHovered = mouseX >= deleteLeft && mouseX < deleteLeft + ROW_DELETE_SIZE
                    && mouseY >= deleteTop && mouseY < deleteTop + ROW_DELETE_SIZE;
            int deleteBottom = deleteTop + ROW_DELETE_SIZE;
            graphics.fill(deleteLeft, deleteTop, deleteLeft + ROW_DELETE_SIZE, deleteBottom, CARD_OUTLINE);
            graphics.fill(deleteLeft + 1, deleteBottom - 1 - ROW_DELETE_LIP, deleteLeft + ROW_DELETE_SIZE - 1,
                    deleteBottom - 1, CARD_LIP);
            graphics.fill(deleteLeft + 1, deleteTop + 1, deleteLeft + ROW_DELETE_SIZE - 1, deleteBottom - 1 - ROW_DELETE_LIP,
                    this.isDeleteHovered ? ROW_DELETE_BORDER_HOVER : ROW_DELETE_BORDER);
            graphics.fill(deleteLeft + 2, deleteTop + 2, deleteLeft + ROW_DELETE_SIZE - 2, deleteBottom - 2 - ROW_DELETE_LIP,
                    this.isDeleteHovered ? ROW_DELETE_FACE_HOVER : ROW_DELETE_FACE);
            drawIcon(graphics, ICON_BIN, deleteLeft + 3, deleteTop + 2, 0xFFFFFFFF);
        }

        // The name, a hairline, then the arm width and the resolution
        int textLeft = left + 3 + bustWidth + TEXT_GAP;
        int textRight = right - 6;
        int middle = (top + faceBottom) / 2;
        String name = fit(getSortName(), (showDelete ? deleteLeft - 3 : textRight) - textLeft);
        drawText(graphics, name, textLeft, middle - 11, 0xFFFFFFFF);
        graphics.fill(textLeft, middle, textRight, middle + 1, 0x38FFFFFF);

        String resolution = metadata.resolution() == null ? "" : metadata.resolution().getWidth() + "x";
        int resolutionWidth = mc.font.width(resolution);
        drawText(graphics, resolution, textRight - resolutionWidth + 1, middle + 4,
                metadata.resolution() != null && metadata.resolution().isHD() ? HD_COLOR : RESOLUTION_COLOR);
        String[] icon;
        String model;
        int modelColor;
        if (metadata.isCpmModel()) {
            icon = ICON_CPM;
            model = "CPM";
            modelColor = CPM_COLOR;
        } else {
            boolean slim = isSlim();
            icon = slim ? ICON_SLIM : ICON_WIDE;
            model = net.minecraft.network.chat.Component.translatable(
                    slim ? "quickskin.preview.model.slim" : "quickskin.preview.model.wide").getString();
            modelColor = MODEL_COLOR;
        }
        drawIcon(graphics, icon, textLeft, middle + 4, modelColor);
        // A narrow card keeps the icon alone rather than squeezing the word against the resolution
        if (mc.font.width(model) <= textRight - resolutionWidth - 6 - (textLeft + 10)) {
            drawText(graphics, model, textLeft + 10, middle + 4, modelColor);
        }
    }

    private boolean isSlim() {
        return "slim".equals(LocalAssetManager.getInstance().getSkinModelPreference(metadata.hash()));
    }

    private void renderBust(
            //? if <26.1 {
            GuiGraphics graphics,
            //?} else {
            GuiGraphicsExtractor graphics,
            //?}
            int x, int y, int width, int height) {
        if (metadata.isCpmModel()) {
            // A CPM model has an icon, not a skin: show it like the grid does, centred
            if (textureLocation != null) {
                int size = Math.min(width, height) - 8;
                //? if <1.21.5 {
                RenderSystem.enableBlend();
                //?}
                GuiCompat.blit(graphics, textureLocation, x + (width - size) / 2, y + (height - size) / 2,
                        size, size, 0, 0, 64, 64, 64, 64);
                //? if <1.21.5 {
                RenderSystem.disableBlend();
                //?}
            }
            return;
        }
        // The bust is drawn at the window's own pixels, so its texels stay sharp
        int scale = Math.max(1, (int) Math.round(mc.getWindow().getGuiScale()));
        int pixelWidth = width * scale;
        int pixelHeight = height * scale;
        var bust = SkinBustTextures.get(metadata.hash(), isSlim(), pixelWidth, pixelHeight);
        if (bust == null) {
            return;
        }
        //? if <1.21.5 {
        RenderSystem.enableBlend();
        //?}
        GuiCompat.blit(graphics, bust, x, y, width, height, 0, 0, pixelWidth, pixelHeight, pixelWidth, pixelHeight);
        //? if <1.21.5 {
        RenderSystem.disableBlend();
        //?}
    }

    private String fit(String text, int maxWidth) {
        if (mc.font.width(text) <= maxWidth) {
            return text;
        }
        return mc.font.plainSubstrByWidth(text, Math.max(0, maxWidth - mc.font.width("..."))) + "...";
    }

    private void drawText(
            //? if <26.1 {
            GuiGraphics graphics,
            //?} else {
            GuiGraphicsExtractor graphics,
            //?}
            String text, int x, int y, int color) {
        //? if <26.1 {
        graphics.drawString(mc.font, text, x, y, color, true);
        //?} else {
        graphics.text(mc.font, text, x, y, color, true);
        //?}
    }

    /** A seven-pixel icon from fills, with the same drop shadow as text. */
    private static void drawIcon(
            //? if <26.1 {
            GuiGraphics graphics,
            //?} else {
            GuiGraphicsExtractor graphics,
            //?}
            String[] rows, int x, int y, int color) {
        int shadow = 0xFF000000 | (color >> 2 & 0x003F3F3F);
        for (int pass = 0; pass < 2; pass++) {
            int offset = pass == 0 ? 1 : 0;
            for (int row = 0; row < rows.length; row++) {
                for (int column = 0; column < rows[row].length(); column++) {
                    if (rows[row].charAt(column) == '#') {
                        int px = x + column + offset;
                        int py = y + row + offset;
                        graphics.fill(px, py, px + 1, py + 1, pass == 0 ? shadow : color);
                    }
                }
            }
        }
    }

    /**
     * A left click on this tile or card, as last drawn: deletes from the delete button, or selects
     * the skin.
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
