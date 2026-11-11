package com.quickskin.mod.client.gui.fan;

//? if <1.21.2 {
import com.mojang.blaze3d.systems.RenderSystem;
//?}
//? if <1.21.6 {
import com.mojang.math.Axis;
//?}
import com.quickskin.mod.client.gui.GuiCompat;
import com.quickskin.mod.client.gui.widget.SkinBustTextures;
import com.quickskin.mod.client.gui.util.UiText;
import com.quickskin.mod.client.services.LocalAssetManager;
import com.quickskin.mod.common.data.AssetMetadata;
import com.quickskin.mod.common.data.TextureQuality;
import com.quickskin.mod.common.util.SkinBustRasterizer;
import com.quickskin.mod.client.gui.fan.FanMotion.Phase;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
//? if <26.1 {
import net.minecraft.client.gui.GuiGraphics;
//?} else {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?}
//? if >=1.21.6 {
import net.minecraft.client.renderer.RenderPipelines;
//?} else if >=1.21.2 {
import net.minecraft.client.renderer.RenderType;
//?}
import net.minecraft.network.chat.Component;
//? if <1.21.11 {
import net.minecraft.resources.ResourceLocation;
//?} else {
import net.minecraft.resources.Identifier;
//?}

import java.util.List;

/**
 * Draws the favourites fan on the HUD, behind a vignette. Each card is a skin with no card under
 * it, only its shadow on the floor: a 3D picture of the player, drawn once per turn and size, so it
 * leans, fades and turns over with its card on every Minecraft version. The skin pointed at stands
 * in a soft light, sways from side to side and shows its name while the others fade; a chosen skin
 * turns over, showing its back halfway. A CPM model shows its icon.
 */
@Environment(EnvType.CLIENT)
final class FanRenderer {
    private static final FanMotion.Card[] CARDS = new FanMotion.Card[FanMotion.PAGE_SIZE];
    private static final float TEXT_ALPHA = 0.05f;
    /** The pointed card's skin turns this many degrees each way... */
    private static final int SWAY_DEGREES = 21;
    /** ...in steps this many degrees apart, each its own picture. */
    private static final int SWAY_STEP = 3;
    /** A pointed card is drawn this much larger, and a chosen one at most this much. */
    private static final float HOT_SCALE = 1.1f;
    private static final float CHOSEN_SCALE = 1.8f;

    static {
        for (int i = 0; i < CARDS.length; i++) CARDS[i] = new FanMotion.Card();
    }

    private FanRenderer() {
    }

    //? if <26.1 {
    static void render(GuiGraphics graphics) {
    //?} else {
    static void render(GuiGraphicsExtractor graphics) {
    //?}
        Minecraft minecraft = Minecraft.getInstance();
        int width = minecraft.getWindow().getGuiScaledWidth();
        int height = minecraft.getWindow().getGuiScaledHeight();
        Font font = minecraft.font;
        renderWornName(graphics, font, width, height);
        if (FavouritesFan.phase() == Phase.CLOSED) {
            return;
        }
        List<AssetMetadata> items = FavouritesFan.pageItems();
        int count = items.size();
        if (count == 0) return;
        float hudTop = height - 50;
        FanMotion.Layout layout = FanMotion.layout(count, width, height, hudTop);
        float progress = FavouritesFan.advance(minecraft, layout, CARDS);
        Phase phase = FavouritesFan.phase();
        if (phase == Phase.CLOSED) return;
        int unit = Math.max(1, (int) Math.round(minecraft.getWindow().getGuiScale()));
        float time = System.nanoTime() / 1e9f;

        drawBackdrop(graphics, width, height, presence(phase, progress));

        // Cards that fall leave behind the hotbar
        boolean clip = phase == Phase.CLOSING || phase == Phase.CHOOSING;
        if (clip) graphics.enableScissor(0, 0, width, height - 23);
        int front = -1;
        for (int i = 0; i < count; i++) {
            if (CARDS[i].chosen || CARDS[i].hover > 0.5f) front = i;
        }
        for (int i = 0; i < count; i++) {
            if (i != front) drawCard(graphics, font, layout, CARDS[i], items.get(i), unit, time);
        }
        if (front >= 0) drawCard(graphics, font, layout, CARDS[front], items.get(front), unit, time);
        if (clip) graphics.disableScissor();

        if (FavouritesFan.pages() > 1 && FavouritesFan.capturesMouse()) {
            float top = layout.pivotY() - layout.radius() - layout.cardHeight() * 0.62f;
            String pageText = "‹ " + (FavouritesFan.page() + 1) + " / " + FavouritesFan.pages() + " ›";
            int y = Math.max(4, Math.round(top) - 16);
            int half = font.width(pageText) / 2 + 6;
            rounded(graphics, width / 2 - half, y - 3, width / 2 + half, y + 11, argb(0.55f, 8, 6, 16));
            UiText.drawCentered(graphics, font, pageText, width / 2, y, 0xFFFFFFFF);
        }
    }

    /** How much of the fan is there: it grows in as it opens and goes as it is put away. */
    private static float presence(Phase phase, float progress) {
        return switch (phase) {
            case OPENING -> progress;
            case CHOOSING -> 1f - FanMotion.clamp((progress - 0.3f) / 0.7f, 0f, 1f);
            case CLOSING -> 1f - progress;
            default -> 1f;
        };
    }

    /** A vignette behind the fan, so the skins stand out from the world without hiding it. */
    private static void drawBackdrop(
            //? if <26.1 {
            GuiGraphics graphics,
            //?} else {
            GuiGraphicsExtractor graphics,
            //?}
            int width, int height, float alpha) {
        if (alpha <= 0.01f) return;
        var shade = FanTextures.backdrop(width, height);
        if (shade == null) return;
        blit(graphics, shade, 0, 0, width, height, width, height, alpha);
    }

    private static void drawCard(
            //? if <26.1 {
            GuiGraphics graphics,
            //?} else {
            GuiGraphicsExtractor graphics,
            //?}
            Font font, FanMotion.Layout layout, FanMotion.Card card, AssetMetadata metadata, int unit, float time) {
        if (card.alpha <= 0.01f || card.scale <= 0.01f) return;
        int w = Math.round(layout.cardWidth());
        int h = Math.round(layout.cardHeight());
        int left = -w / 2, top = -h / 2, right = left + w, bottom = top + h;
        float alpha = card.alpha;
        float hot = card.chosen ? 1f : card.hover;
        // Turning over, the skin narrows to its edge and widens again showing its back
        float width = Math.max(0.04f, Math.abs(card.flip));
        boolean back = card.flip < 0;

        push(graphics, card.x, card.y, card.angle, card.scale * width, card.scale);

        var face = FanTextures.face(w * unit, h * unit, unit, false);
        if (face != null) blit(graphics, face, left, top, w, h, w * unit, h * unit, alpha);
        if (hot > 0.01f) {
            var lit = FanTextures.face(w * unit, h * unit, unit, true);
            if (lit != null) blit(graphics, lit, left, top, w, h, w * unit, h * unit, alpha * hot);
        }
        // The skins not pointed at fade
        drawFigure(graphics, metadata, card, w, h, unit, alpha * (1f - card.dim * 0.7f), hot, time, back);

        // The name, under the skin that is pointed at; the others overlap where it would go
        float nameAlpha = alpha * hot;
        if (nameAlpha > TEXT_ALPHA) {
            String name = font.plainSubstrByWidth(metadata.friendlyName(), w - 6);
            int band = Math.round(h * 0.16f);
            UiText.drawCentered(graphics, font, name, 0, bottom - band / 2 - 4, argb(nameAlpha, 255, 255, 255));
        }
        if (FavouriteSkins.isWorn(metadata)) {
            drawWornBadge(graphics, right - 9, top + 2, alpha);
        }
        pop(graphics);

        if (card.bigName * alpha > TEXT_ALPHA) {
            float s = 1.5f;
            String name = metadata.friendlyName();
            push(graphics, card.x, card.y + h * card.scale * 0.6f, 0f, s, s);
            int half = font.width(name) / 2 + 5;
            rounded(graphics, -half, -3, half, 11, argb(card.bigName * alpha * 0.5f, 8, 6, 16));
            UiText.drawCentered(graphics, font, name, 0, 0, argb(card.bigName * alpha, 255, 255, 255));
            pop(graphics);
        }
    }

    /**
     * The skin standing on its card. The pointed card's skin is drawn a little larger, turning
     * from side to side, and the chosen one larger still, seen from behind while it turns over,
     * each from pictures made for that size and turn.
     */
    private static void drawFigure(
            //? if <26.1 {
            GuiGraphics graphics,
            //?} else {
            GuiGraphicsExtractor graphics,
            //?}
            AssetMetadata metadata, FanMotion.Card card, int w, int h, int unit, float alpha, float hot, float time,
            boolean back) {
        int figureHeight = Math.round(h * 0.74f);
        int figureWidth = Math.round(figureHeight * 0.6f);
        int top = -h / 2 + Math.round(h * 0.05f);
        String hash = metadata.hash();
        // A CPM model stands on its card like a skin, drawn from the model itself
        java.nio.file.Path model = metadata.isCpmModel() ? metadata.path() : null;
        if (model != null && picture(hash, model, false, figureWidth, figureHeight, unit, 1f, 0) == null) {
            // Until that picture is made, or when CPM cannot draw the model: its icon
            var icon = LocalAssetManager.getInstance().getTextureLocation(metadata.hash(), TextureQuality.PREVIEW);
            if (icon == null) return;
            int size = Math.round(w * 0.78f);
            blit(graphics, icon, -size / 2, -size / 2 - Math.round(h * 0.06f), size, size, 64, 64, alpha);
            return;
        }
        boolean slim = model == null && "slim".equals(FavouriteSkins.modelType(metadata));

        float scale = 1f;
        int turn = 0;
        if (card.chosen) {
            scale = CHOSEN_SCALE;
            if (back) turn = 180;
        } else if (hot > 0.3f) {
            scale = HOT_SCALE;
            turn = Math.round(SWAY_DEGREES * hot * (float) Math.sin(time * 1.8) / SWAY_STEP) * SWAY_STEP;
            // Every turn of the sway, and the chosen size from both sides, are made while the card
            // is pointed at
            for (int t = -SWAY_DEGREES; t <= SWAY_DEGREES; t += SWAY_STEP) {
                picture(hash, model, slim, figureWidth, figureHeight, unit, HOT_SCALE, t);
            }
            picture(hash, model, slim, figureWidth, figureHeight, unit, CHOSEN_SCALE, 0);
            picture(hash, model, slim, figureWidth, figureHeight, unit, CHOSEN_SCALE, 180);
        }
        var picture = picture(hash, model, slim, figureWidth, figureHeight, unit, scale, turn);
        if (picture == null && scale != 1f) {
            // Until its own picture is made, the resting one of the same side stands in
            scale = 1f;
            picture = picture(hash, model, slim, figureWidth, figureHeight, unit, 1f, back ? 180 : 0);
        }
        if (picture == null) return;
        blit(graphics, picture, -figureWidth / 2, top, figureWidth, figureHeight,
                Math.round(figureWidth * unit * scale), Math.round(figureHeight * unit * scale), alpha);
    }

    //? if <1.21.11 {
    private static ResourceLocation picture(
    //?} else {
    private static Identifier picture(
    //?}
            String hash, java.nio.file.Path model, boolean slim, int width, int height, int unit, float scale,
            int turn) {
        int pixelWidth = Math.round(width * unit * scale);
        int pixelHeight = Math.round(height * unit * scale);
        int yaw = (int) SkinBustRasterizer.DEFAULT_YAW + turn;
        return model != null
                ? SkinBustTextures.getModelBody(hash, model, pixelWidth, pixelHeight, yaw)
                : SkinBustTextures.getBody(hash, slim, pixelWidth, pixelHeight, yaw);
    }

    /** A green tick in the corner of the look being worn, seven GUI pixels square. */
    private static void drawWornBadge(
            //? if <26.1 {
            GuiGraphics graphics,
            //?} else {
            GuiGraphicsExtractor graphics,
            //?}
            int x, int y, float alpha) {
        rounded(graphics, x, y, x + 7, y + 7, argb(alpha, 63, 191, 90));
        int white = argb(alpha, 255, 255, 255);
        graphics.fill(x + 1, y + 3, x + 2, y + 4, white);
        graphics.fill(x + 2, y + 4, x + 4, y + 5, white);
        graphics.fill(x + 4, y + 3, x + 5, y + 4, white);
        graphics.fill(x + 5, y + 2, x + 6, y + 3, white);
    }

    private static void renderWornName(
            //? if <26.1 {
            GuiGraphics graphics,
            //?} else {
            GuiGraphicsExtractor graphics,
            //?}
            Font font, int width, int height) {
        Component name = FavouritesFan.wornName();
        float age = FavouritesFan.wornNameAge();
        if (name == null || age >= 1f) return;
        // Where Minecraft names the item just picked, fading out the same way
        float alpha = age < 0.7f ? 1f : 1f - (age - 0.7f) / 0.3f;
        if (alpha <= TEXT_ALPHA) return;
        UiText.drawCentered(graphics, font, name, width / 2, height - 59, argb(alpha, 255, 255, 255));
    }

    static void reset() {
        FanTextures.reset();
    }

    // ---- Drawing helpers ----

    /** A rectangle with one pixel cut off each corner. */
    private static void rounded(
            //? if <26.1 {
            GuiGraphics graphics,
            //?} else {
            GuiGraphicsExtractor graphics,
            //?}
            int left, int top, int right, int bottom, int color) {
        graphics.fill(left + 1, top, right - 1, bottom, color);
        graphics.fill(left, top + 1, left + 1, bottom - 1, color);
        graphics.fill(right - 1, top + 1, right, bottom - 1, color);
    }

    /** A whole texture stretched over a rectangle at the given opacity. */
    private static void blit(
            //? if <26.1 {
            GuiGraphics graphics,
            //?} else {
            GuiGraphicsExtractor graphics,
            //?}
            //? if <1.21.11 {
            ResourceLocation texture,
            //?} else {
            Identifier texture,
            //?}
            int x, int y, int width, int height, int textureWidth, int textureHeight, float alpha) {
        if (alpha <= 0.004f || width <= 0 || height <= 0 || textureWidth <= 0 || textureHeight <= 0) {
            return;
        }
        //? if <1.21.2 {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, Math.min(1f, alpha));
        GuiCompat.blit(graphics, texture, x, y, width, height,
                0.0f, 0.0f, textureWidth, textureHeight, textureWidth, textureHeight);
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        RenderSystem.disableBlend();
        //?} else {
        int color = Math.round(Math.min(1f, alpha) * 255.0f) << 24 | 0xFFFFFF;
            //? if <1.21.6 {
        graphics.blit(RenderType::guiTextured, texture, x, y, 0.0f, 0.0f, width, height,
                textureWidth, textureHeight, textureWidth, textureHeight, color);
            //?} else {
        graphics.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, 0.0f, 0.0f, width, height,
                textureWidth, textureHeight, textureWidth, textureHeight, color);
            //?}
        //?}
    }

    private static void push(
            //? if <26.1 {
            GuiGraphics graphics,
            //?} else {
            GuiGraphicsExtractor graphics,
            //?}
            float x, float y, float radians, float scaleX, float scaleY) {
        //? if <1.21.6 {
        graphics.pose().pushPose();
        graphics.pose().translate(x, y, 0);
        if (radians != 0) graphics.pose().mulPose(Axis.ZP.rotation(radians));
        graphics.pose().scale(scaleX, scaleY, 1);
        //?} else {
        graphics.pose().pushMatrix();
        graphics.pose().translate(x, y);
        if (radians != 0) graphics.pose().rotate(radians);
        graphics.pose().scale(scaleX, scaleY);
        //?}
    }

    private static void pop(
            //? if <26.1 {
            GuiGraphics graphics
            //?} else {
            GuiGraphicsExtractor graphics
            //?}
    ) {
        //? if <1.21.6 {
        graphics.pose().popPose();
        //?} else {
        graphics.pose().popMatrix();
        //?}
    }

    private static int argb(float alpha, float r, float g, float b) {
        int a = Math.max(0, Math.min(255, Math.round(alpha * 255)));
        return a << 24 | Math.round(r) << 16 | Math.round(g) << 8 | Math.round(b);
    }
}
