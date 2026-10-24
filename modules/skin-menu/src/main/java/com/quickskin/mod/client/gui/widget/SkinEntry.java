package com.quickskin.mod.client.gui.widget;

import com.quickskin.mod.client.gui.util.UiText;
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
 * bust, name and details in the list. A hovered tile or card shows a favorite star, except on the
 * player's own skin. In the skin menu's delete mode a skin marked for deletion turns red and grey.
 * A deleted skin is drawn the same way while it falls out of the column, from grey copies taken
 * before its files and texture went away.
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

    /** Size of the favorite star's button in a hovered tile's top-left corner. */
    private static final int CORNER_BUTTON_SIZE = 8;

    private boolean isFavoriteHovered;
    /** Whether the player marked this skin as a favorite; the list is rebuilt when that changes. */
    private final boolean favorite;
    /** Whether this skin was deleted and is only drawn while it leaves the column. */
    private boolean departing;
    /** The grey face a deleted tile shows, taken while its texture was still loaded. */
    //? if <1.21.11 {
    private ResourceLocation departingFace;
    //?} else {
    private Identifier departingFace;
    //?}
    /** The arm width of the bust drawn last, kept for a deleted card whose preferences are gone. */
    private boolean departingSlim;
    /** The bust's size in window pixels when it was drawn last, or 0 before. */
    private int bustPixelWidth;
    private int bustPixelHeight;
    /** The size the favorite star is drawn at: 1, except while it pops in on a new favorite. */
    private float starScale = 1f;

    public SkinEntry(SkinListWidget parentList, AssetMetadata metadata) {
        this.parentList = parentList;
        this.metadata = metadata;
        this.mc = Minecraft.getInstance();
        this.favorite = LocalAssetManager.getInstance().isFavorite(metadata.hash());

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

    /** Whether this is a favorite: never the player's own skin, which is pinned first anyway. */
    public boolean isFavorite() {
        return favorite && !isPlayerOwnSkin();
    }

    private boolean isPlayerOwnSkin() {
        ClientConfig config = ClientConfig.getInstance();
        return config.enablePlayerOwnSkinSystem && metadata.hash().equals(config.playerOwnSkinHash);
    }

    /**
     * Takes the grey face and bust this skin shows while it falls out of the column, before its
     * deletion releases its texture. Call from the render thread.
     */
    void prepareDeparture() {
        if (metadata.isCpmModel()) {
            return;
        }
        if (textureLocation != null) {
            departingFace = SkinBustTextures.grayFace(metadata.hash(), textureLocation);
        }
        departingSlim = isSlim();
        if (bustPixelWidth > 0) {
            SkinBustTextures.get(metadata.hash(), departingSlim, bustPixelWidth, bustPixelHeight, true);
        }
    }

    /** From now on this skin is drawn as deleted, red and grey, with no star. */
    void depart() {
        departing = true;
    }

    void setStarScale(float scale) {
        this.starScale = scale;
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

        boolean isFavorite = isFavorite() && !departing;
        boolean deleteMode = parentList.isDeleteMode();
        boolean marked = departing || deleteMode && parentList.isMarked(this);
        graphics.fill(tileLeft, tileTop, tileRight, tileBottom, 0x80000000);
        int borderColor;
        if (marked) {
            // Red while marked for deletion
            graphics.fill(tileLeft, tileTop, tileRight, tileBottom, 0x60C03030);
            borderColor = MARKED_COLOR;
        } else if (isSelected) {
            // Purple for the player's own skin, gold for a favorite, blue for any other selection
            graphics.fill(tileLeft, tileTop, tileRight, tileBottom,
                    isPlayerOwnSkin ? 0x60A020F0 : isFavorite ? 0x60C89018 : 0x60308CC0);
            borderColor = isPlayerOwnSkin ? 0xFFA020F0 : isFavorite ? FAVORITE_COLOR : 0xFF4080FF;
        } else if (isHovered) {
            borderColor = 0xC0FFFFFF;
        } else {
            borderColor = isPlayerOwnSkin ? 0x80A020F0 : isFavorite ? 0x80FFC23A : 0x50FFFFFF;
        }
        drawOutline(graphics, tileLeft, tileTop, tileSize, tileSize, borderColor);
        if (isSelected || marked) {
            drawOutline(graphics, tileLeft + 1, tileTop + 1, tileSize - 2, tileSize - 2, borderColor);
        }

        // Render skin face preview
        int facePadding = 4;
        int faceSize = tileSize - facePadding * 2;
        int faceX = tileLeft + facePadding;
        int faceY = tileTop + facePadding;

        if (departing) {
            // Its texture is gone: only the grey face taken before, when there was one
            if (departingFace != null) {
                //? if <1.21.5 {
                RenderSystem.enableBlend();
                //?}
                GuiCompat.blit(graphics, departingFace, faceX, faceY, faceSize, faceSize, 0, 0, 8, 8, 8, 8);
                //? if <1.21.5 {
                RenderSystem.disableBlend();
                //?}
            }
        } else if (textureLocation != null) {
            //? if <1.21.5 {
            RenderSystem.enableBlend();
            //?}

            var grayFace = marked && !metadata.isCpmModel()
                    ? SkinBustTextures.grayFace(metadata.hash(), textureLocation) : null;
            if (metadata.isCpmModel()) {
                GuiCompat.blit(graphics, textureLocation, faceX, faceY, faceSize, faceSize,
                    0, 0, 64, 64, 64, 64);
                if (marked) {
                    graphics.fill(faceX, faceY, faceX + faceSize, faceY + faceSize, MARKED_DIM);
                }
            } else if (grayFace != null) {
                // Marked for deletion: the face in grey
                GuiCompat.blit(graphics, grayFace, faceX, faceY, faceSize, faceSize, 0, 0, 8, 8, 8, 8);
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
            UiText.drawCentered(graphics, mc.font, "?", faceX + faceSize / 2, faceY + faceSize / 2 - 4, 0xFFFFFFFF);
        }

        // The favorite star in the top-left corner: a button on hover, outside the delete mode, and
        // kept on a favorite
        this.isFavoriteHovered = false;
        boolean favoriteButton = isHovered && !deleteMode && !departing;
        if (!isPlayerOwnSkin && (favoriteButton || isFavorite)) {
            int buttonLeft = tileLeft + 2;
            int buttonTop = tileTop + 2;
            if (favoriteButton) {
                this.isFavoriteHovered = mouseX >= buttonLeft && mouseX < buttonLeft + CORNER_BUTTON_SIZE
                        && mouseY >= buttonTop && mouseY < buttonTop + CORNER_BUTTON_SIZE;
                graphics.fill(buttonLeft, buttonTop, buttonLeft + CORNER_BUTTON_SIZE, buttonTop + CORNER_BUTTON_SIZE,
                        this.isFavoriteHovered ? 0xF0705418 : 0xD0181820);
            }
            drawStar(graphics, ICON_STAR_SMALL, buttonLeft + 2, buttonTop + 2,
                    isFavorite || this.isFavoriteHovered ? FAVORITE_COLOR : 0xFFE0E0E0);
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
    private static final int FAVORITE_COLOR = 0xFFFFC23A;
    /** A skin marked for deletion in the delete mode: a red frame, a red name and a grey picture. */
    private static final int MARKED_COLOR = 0xFFE04040;
    private static final int MARKED_NAME_COLOR = 0xFFFF9090;
    private static final int MARKED_GLOW = 0x55;
    /** Darkens a CPM model's icon, which has no skin to draw in grey. */
    private static final int MARKED_DIM = 0xA0303030;
    /** The colour behind an unselected bust. */
    private static final int IDLE_COLOR = 0xFF8D8E93;
    /** How strong that colour starts at the card's left edge, before it fades out over this width. */
    private static final int SELECTED_GLOW = 0x70;
    private static final int OWN_SKIN_GLOW = 0x22;
    private static final int FAVORITE_GLOW = 0x2A;
    private static final int IDLE_GLOW = 0x40;
    private static final int GLOW_WIDTH = 70;
    /** The bust's width, and the gap between the card's left edge and the text. */
    private static final int BUST_WIDTH = 40;
    private static final int TEXT_GAP = 3;
    /** The favorite button near a hovered card's top-right corner: gold while the skin is a favorite. */
    private static final int ROW_BUTTON_SIZE = 13;
    private static final int ROW_BUTTON_LIP = 2;
    private static final int ROW_FAVORITE_FACE = 0xFFB07C14;
    private static final int ROW_FAVORITE_FACE_HOVER = 0xFFC89018;
    private static final int ROW_FAVORITE_BORDER = 0xFFE0A830;
    private static final int ROW_FAVORITE_BORDER_HOVER = 0xFFFFD060;
    private static final int MODEL_COLOR = 0xFFAAAAAA;
    private static final int CPM_COLOR = 0xFF55AAFF;
    private static final int RESOLUTION_COLOR = 0xFF8A8A8A;
    private static final int HD_COLOR = 0xFF55FF55;

    // Seven-pixel icons, one string per row: the arm width or a CPM model, and the favorite star
    private static final String[] ICON_WIDE = {"..##...", "..##...", "######.", "######.", "#.##.#.", "..##...", ".#..#.."};
    private static final String[] ICON_SLIM = {"..##...", "..##...", ".####..", "#.##.#.", "#.##.#.", "..##...", ".#..#.."};
    private static final String[] ICON_CPM = {"...#...", ".##.##.", "#.....#", "##...##", "#.##.##", "#..#..#", ".##.##."};
    private static final String[] ICON_STAR = {"...#...", "..###..", "#######", ".#####.", "..###..", ".##.##.", "##...##"};
    private static final String[] ICON_STAR_SMALL = {"..#..", "#####", ".###.", ".#.#.", "#...#"};

    /**
     * Draws the skin as a row of the list, with its top-left corner at {@code (left, top)}: a card
     * with the skin's 3D bust on the left and, beside it, the name over a hairline and a line with
     * the arm width (or CPM) and the resolution. The selected card has a coloured border and that
     * colour behind its bust, gold for a favorite. A hovered card shows a favorite star near its
     * top-right corner, which a favorite keeps, except on the player's own skin. In the delete mode
     * a card marked for deletion has a red border and glow, a red name and its bust in grey.
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
        boolean isFavorite = isFavorite() && !departing;
        boolean deleteMode = parentList.isDeleteMode();
        boolean marked = departing || deleteMode && parentList.isMarked(this);
        int stateColor = marked ? MARKED_COLOR : isPlayerOwnSkin ? OWN_SKIN_COLOR : isFavorite ? FAVORITE_COLOR : SELECTED_COLOR;
        int border = isSelected || marked ? stateColor : isHovered ? CARD_BORDER_HOVER : CARD_BORDER;
        graphics.fill(left + 1, top + 1, right - 1, faceBottom, border);
        graphics.fill(left + 2, top + 2, right - 2, faceBottom - 1, isHovered ? CARD_FACE_HOVER : CARD_FACE);

        // Colour behind the bust, fading toward the text: strong only on the selected card
        int glowColor = isSelected || marked || isPlayerOwnSkin || isFavorite ? stateColor : IDLE_COLOR;
        int glow = marked ? MARKED_GLOW : isSelected ? SELECTED_GLOW : isPlayerOwnSkin ? OWN_SKIN_GLOW
                : isFavorite ? FAVORITE_GLOW : IDLE_GLOW;
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
        renderBust(graphics, left + 3, top + 2, bustWidth, bustHeight, marked);

        // The favorite button: shown on hover outside the delete mode, and its star kept on a favorite
        this.isFavoriteHovered = false;
        boolean favoriteButton = isHovered && !deleteMode && !departing;
        boolean showFavorite = !isPlayerOwnSkin && (favoriteButton || isFavorite);
        int favoriteLeft = right - 3 - ROW_BUTTON_SIZE;
        int favoriteTop = top + 4;
        if (showFavorite && favoriteButton) {
            this.isFavoriteHovered = mouseX >= favoriteLeft && mouseX < favoriteLeft + ROW_BUTTON_SIZE
                    && mouseY >= favoriteTop && mouseY < favoriteTop + ROW_BUTTON_SIZE;
            int favoriteBottom = favoriteTop + ROW_BUTTON_SIZE;
            graphics.fill(favoriteLeft, favoriteTop, favoriteLeft + ROW_BUTTON_SIZE, favoriteBottom, CARD_OUTLINE);
            graphics.fill(favoriteLeft + 1, favoriteBottom - 1 - ROW_BUTTON_LIP, favoriteLeft + ROW_BUTTON_SIZE - 1,
                    favoriteBottom - 1, CARD_LIP);
            int favoriteBorder = isFavorite
                    ? (this.isFavoriteHovered ? ROW_FAVORITE_BORDER_HOVER : ROW_FAVORITE_BORDER)
                    : (this.isFavoriteHovered ? CARD_BORDER_HOVER : CARD_BORDER);
            int favoriteFace = isFavorite
                    ? (this.isFavoriteHovered ? ROW_FAVORITE_FACE_HOVER : ROW_FAVORITE_FACE)
                    : (this.isFavoriteHovered ? CARD_FACE_HOVER : CARD_FACE);
            graphics.fill(favoriteLeft + 1, favoriteTop + 1, favoriteLeft + ROW_BUTTON_SIZE - 1,
                    favoriteBottom - 1 - ROW_BUTTON_LIP, favoriteBorder);
            graphics.fill(favoriteLeft + 2, favoriteTop + 2, favoriteLeft + ROW_BUTTON_SIZE - 2,
                    favoriteBottom - 2 - ROW_BUTTON_LIP, favoriteFace);
            drawStar(graphics, ICON_STAR, favoriteLeft + 3, favoriteTop + 2,
                    isFavorite ? 0xFFFFFFFF : this.isFavoriteHovered ? FAVORITE_COLOR : 0xFFC8C8C8);
        } else if (showFavorite) {
            drawStar(graphics, ICON_STAR, favoriteLeft + 3, favoriteTop + 2, FAVORITE_COLOR);
        }

        // The name, a hairline, then the arm width and the resolution
        int textLeft = left + 3 + bustWidth + TEXT_GAP;
        int textRight = right - 6;
        int middle = (top + faceBottom) / 2;
        String name = fit(getSortName(), (showFavorite ? favoriteLeft - 3 : textRight) - textLeft);
        drawText(graphics, name, textLeft, middle - 11, marked ? MARKED_NAME_COLOR : 0xFFFFFFFF);
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
            boolean slim = departing ? departingSlim : isSlim();
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
            int x, int y, int width, int height, boolean gray) {
        if (metadata.isCpmModel()) {
            // A CPM model has an icon, not a skin: show it like the grid does, centred
            if (textureLocation != null && !departing) {
                int size = Math.min(width, height) - 8;
                //? if <1.21.5 {
                RenderSystem.enableBlend();
                //?}
                int iconX = x + (width - size) / 2;
                int iconY = y + (height - size) / 2;
                GuiCompat.blit(graphics, textureLocation, iconX, iconY, size, size, 0, 0, 64, 64, 64, 64);
                if (gray) {
                    graphics.fill(iconX, iconY, iconX + size, iconY + size, MARKED_DIM);
                }
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
        bustPixelWidth = pixelWidth;
        bustPixelHeight = pixelHeight;
        boolean slim = departing ? departingSlim : isSlim();
        var bust = SkinBustTextures.get(metadata.hash(), slim, pixelWidth, pixelHeight, gray);
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
        UiText.draw(graphics, mc.font, text, x, y, color);
    }

    /** The favorite star, without a shadow, at {@link #starScale} about its centre. */
    private void drawStar(
            //? if <26.1 {
            GuiGraphics graphics,
            //?} else {
            GuiGraphicsExtractor graphics,
            //?}
            String[] rows, int x, int y, int color) {
        if (starScale == 1f) {
            drawIcon(graphics, rows, x, y, color, false);
            return;
        }
        float centre = rows.length / 2f;
        GuiTransform.push(graphics, x + centre, y + centre, 0, 0, starScale, 0);
        drawIcon(graphics, rows, x, y, color, false);
        GuiTransform.pop(graphics);
    }

    /** A seven-pixel icon from fills, with the same drop shadow as text. */
    private static void drawIcon(
            //? if <26.1 {
            GuiGraphics graphics,
            //?} else {
            GuiGraphicsExtractor graphics,
            //?}
            String[] rows, int x, int y, int color) {
        drawIcon(graphics, rows, x, y, color, true);
    }

    /** A seven-pixel icon from fills, with or without the drop shadow of text. */
    static void drawIcon(
            //? if <26.1 {
            GuiGraphics graphics,
            //?} else {
            GuiGraphicsExtractor graphics,
            //?}
            String[] rows, int x, int y, int color, boolean withShadow) {
        int shadow = 0xFF000000 | (color >> 2 & 0x003F3F3F);
        for (int pass = withShadow ? 0 : 1; pass < 2; pass++) {
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
     * A left click on this tile or card, as last drawn: in the delete mode, marks or unmarks the
     * skin for deletion; otherwise marks or unmarks a favorite from the star, or selects the skin.
     */
    public boolean click() {
        if (parentList.isDeleteMode()) {
            parentList.toggleMarked(this);
            return true;
        }
        if (this.isFavoriteHovered) {
            mc.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK.value(), 1.0f, 0.25f
            ));
            parentList.toggleFavorite(this);
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
