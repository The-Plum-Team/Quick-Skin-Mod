package com.quickskin.mod.client.gui.widget;

//? if <1.21.2 {
import com.mojang.blaze3d.systems.RenderSystem;
//?}
import com.quickskin.mod.client.gui.GuiCompat;
import com.quickskin.mod.platform.QuickSkinInfo;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
//? if <26.1 {
import net.minecraft.client.gui.GuiGraphics;
//?} else {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?}
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
//? if >=1.21.6 {
import net.minecraft.client.renderer.RenderPipelines;
//?} else if >=1.21.2 {
import net.minecraft.client.renderer.RenderType;
//?}
import net.minecraft.network.chat.Component;
//? if <1.21.11 {
import net.minecraft.resources.ResourceLocation;
import net.minecraft.Util;
//?} else {
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
//?}

/**
 * The turntable halo around the player model's feet: a white, rounded ring lying on the floor in
 * perspective, as games draw it under a character you can drag to turn.
 *
 * <p>The ring and its glow come from two pre-rendered textures with linear filtering, drawn whole
 * before the model ({@link #renderRing}): in perspective the front of the ring lies below the feet,
 * so only its back passes behind the legs and the player stands inside it.
 *
 * <p>The glow is animated. When the menu first opens the ring grows into place with a flash that
 * settles. At rest the glow holds still. While the mouse is on the ring or the model turns, the
 * glow eases into a slow, brighter breath, and when the mouse leaves it eases back to rest; the
 * glow blends between the two states rather than adding them. The skin menu turns the model when
 * a drag starts on the ring, and eases it back to face the viewer when the drag lets go.
 */
@Environment(EnvType.CLIENT)
public class RotationArcWidget extends AbstractWidget {
    private static final boolean DETERMINISTIC_E2E_RENDER = Boolean.getBoolean("quickskin.e2e.enabled");

    // Geometry of the textures, in texture pixels
    private static final int TEXTURE_WIDTH = 512;
    private static final int TEXTURE_HEIGHT = 160;
    private static final float CENTER_U = 256.0f;
    private static final float CENTER_V = 64.0f;
    private static final float RADIUS_X = 222.0f;
    private static final float RADIUS_Y = 44.0f;
    private static final float BAND = 15.0f;
    private static final float RIM = 7.0f;

    // Animation timing, in milliseconds, and glow levels (0..1)
    private static final long INTRO_MILLIS = 550L;
    private static final float INTRO_START_SCALE = 0.82f;
    private static final float INTRO_FLASH = 1.0f;
    private static final float REST_GLOW = 0.35f;
    /** The hover breath swings the glow between these levels. */
    private static final float BREATH_LOW = 0.5f;
    private static final float BREATH_HIGH = 0.9f;
    private static final double BREATH_PERIOD_MILLIS = 2200.0;
    /** How fast the hover blend follows the mouse: the fraction of the gap closed per millisecond. */
    private static final float HOVER_RATE = 0.008f;

    //? if <1.21 {
    private static final ResourceLocation RING = new ResourceLocation(QuickSkinInfo.MOD_ID, "textures/gui/turntable/ring.png");
    private static final ResourceLocation GLOW = new ResourceLocation(QuickSkinInfo.MOD_ID, "textures/gui/turntable/ring_glow.png");
    //?} else if <1.21.11 {
    private static final ResourceLocation RING = ResourceLocation.fromNamespaceAndPath(QuickSkinInfo.MOD_ID, "textures/gui/turntable/ring.png");
    private static final ResourceLocation GLOW = ResourceLocation.fromNamespaceAndPath(QuickSkinInfo.MOD_ID, "textures/gui/turntable/ring_glow.png");
    //?} else {
    private static final Identifier RING = Identifier.fromNamespaceAndPath(QuickSkinInfo.MOD_ID, "textures/gui/turntable/ring.png");
    private static final Identifier GLOW = Identifier.fromNamespaceAndPath(QuickSkinInfo.MOD_ID, "textures/gui/turntable/ring_glow.png");
    //?}

    /** Extra reach around the ring band that still counts as touching it. */
    private static final double HIT_MARGIN = 4.0;

    private final int centerX;
    private final int centerY;
    /** GUI pixels per texture pixel. */
    private final float scale;
    private final long createdAt;
    private final boolean intro;
    private boolean highlighted;
    private float hoverLevel;
    private long lastFrameAt;
    /** When the breath started, so it always begins from its lowest point. */
    private long breathStartedAt;

    /**
     * A ring centred on {@code (centerX, centerY)} whose outer edge reaches {@code radiusX} to
     * either side. With {@code intro} it grows into place and flashes when first drawn.
     */
    public RotationArcWidget(int centerX, int centerY, int radiusX, boolean intro) {
        super(0, 0, 0, 0, Component.translatable("quickskin.preview.rotate"));
        this.centerX = centerX;
        this.centerY = centerY;
        this.scale = radiusX / RADIUS_X;
        setX(Math.round(centerX - CENTER_U * scale));
        setY(Math.round(centerY - CENTER_V * scale));
        this.width = Math.round(TEXTURE_WIDTH * scale);
        this.height = Math.round(TEXTURE_HEIGHT * scale);
        this.createdAt = Util.getMillis();
        this.lastFrameAt = createdAt;
        this.intro = intro && !DETERMINISTIC_E2E_RENDER;
        // Clicks go to the screen, which turns the model.
        this.active = false;
    }

    /** Lights the ring up while the model is being turned. */
    public void setHighlighted(boolean highlighted) {
        this.highlighted = highlighted;
    }

    /** The ring's centre and the radii of its band's middle line, in GUI pixels: {x, y, radiusX, radiusY}. */
    public float[] band() {
        return new float[]{centerX, centerY, (RADIUS_X - BAND / 2) * scale, (RADIUS_Y - BAND / 2) * scale};
    }

    /** The lowest point the ring is drawn at, the bottom of its front rim, in GUI pixels. */
    public float bottom() {
        return centerY + (RADIUS_Y + RIM) * scale;
    }

    /** Whether a point is on the ring's band, front or back. */
    public boolean isOnArc(double mouseX, double mouseY) {
        if (!visible) {
            return false;
        }
        double bandRadiusX = (RADIUS_X - BAND / 2) * scale;
        double bandRadiusY = (RADIUS_Y - BAND / 2) * scale;
        double dx = (mouseX - centerX) / bandRadiusX;
        if (Math.abs(dx) > 1.0) {
            return false;
        }
        double offset = bandRadiusY * Math.sqrt(1.0 - dx * dx);
        double halfBand = BAND / 2 * scale + HIT_MARGIN;
        // The front band, with the rim below it, or the back band
        double frontY = centerY + offset;
        double backY = centerY - offset;
        return (mouseY >= frontY - halfBand && mouseY <= frontY + halfBand + RIM * scale)
                || Math.abs(mouseY - backY) <= halfBand;
    }

    /**
     * Draws the animated ring. Call before the model so the model stands inside it.
     */
    //? if <26.1 {
    public void renderRing(GuiGraphics graphics, int mouseX, int mouseY) {
    //?} else {
    public void renderRing(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
    //?}
        if (!visible) {
            return;
        }
        long now = Util.getMillis();
        long frameMillis = Math.max(0L, Math.min(100L, now - lastFrameAt));
        lastFrameAt = now;

        // Hover eases in and out instead of switching. A breath that has fully faded restarts
        // from its lowest point, so it always grows in smoothly.
        float hoverTarget = highlighted || isOnArc(mouseX, mouseY) ? 1.0f : 0.0f;
        if (hoverLevel < 0.001f) {
            breathStartedAt = now;
        }
        float follow = 1.0f - (float) Math.pow(1.0f - HOVER_RATE, frameMillis);
        hoverLevel += (hoverTarget - hoverLevel) * follow;

        // Intro: grow from INTRO_START_SCALE with an ease-out, and flash the glow, then settle
        float introProgress = intro ? Math.min(1.0f, (now - createdAt) / (float) INTRO_MILLIS) : 1.0f;
        float eased = 1.0f - (float) Math.pow(1.0f - introProgress, 3);
        float sizeFactor = INTRO_START_SCALE + (1.0f - INTRO_START_SCALE) * eased;
        float ringAlpha = Math.min(1.0f, introProgress * 2.5f);
        float flash = intro ? INTRO_FLASH * (float) Math.sin(Math.PI * introProgress) : 0.0f;

        // The hover breath, starting from its lowest point (held still for deterministic E2E frames)
        double phase = DETERMINISTIC_E2E_RENDER ? 0.0 : (now - breathStartedAt) / BREATH_PERIOD_MILLIS * 2.0 * Math.PI;
        float breath = BREATH_LOW + (BREATH_HIGH - BREATH_LOW) * (0.5f - 0.5f * (float) Math.cos(phase));

        // Blend rest and breath by how far the hover has eased in; never add them
        float glow = REST_GLOW + (breath - REST_GLOW) * hoverLevel;
        float glowAlpha = Math.max(0.0f, Math.min(1.0f, glow * ringAlpha + flash));

        float drawScale = scale * sizeFactor;
        int drawWidth = Math.round(TEXTURE_WIDTH * drawScale);
        int drawHeight = Math.round(TEXTURE_HEIGHT * drawScale);
        int drawX = Math.round(centerX - CENTER_U * drawScale);
        int drawY = Math.round(centerY - CENTER_V * drawScale);
        blitTinted(graphics, GLOW, drawX, drawY, drawWidth, drawHeight, glowAlpha);
        blitTinted(graphics, RING, drawX, drawY, drawWidth, drawHeight, ringAlpha);
    }

    @Override
    //? if <26.1 {
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    //?} else {
    protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
    //?}
        // Drawn by renderRing before the model; nothing goes on top of it
    }

    /** Draws a whole texture stretched over a rectangle at the given opacity. */
    //? if <26.1 {
    //? if <1.21.11 {
    private static void blitTinted(GuiGraphics graphics, ResourceLocation texture,
    //?} else {
    private static void blitTinted(GuiGraphics graphics, Identifier texture,
    //?}
    //?} else {
    private static void blitTinted(GuiGraphicsExtractor graphics, Identifier texture,
    //?}
                                   int x, int y, int width, int height, float alpha) {
        if (alpha <= 0.004f || width <= 0 || height <= 0) {
            return;
        }
        //? if <1.21.2 {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, alpha);
        GuiCompat.blit(graphics, texture, x, y, width, height,
                0.0f, 0.0f, TEXTURE_WIDTH, TEXTURE_HEIGHT, TEXTURE_WIDTH, TEXTURE_HEIGHT);
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        RenderSystem.disableBlend();
        //?} else {
        int color = Math.round(alpha * 255.0f) << 24 | 0xFFFFFF;
            //? if <1.21.6 {
        graphics.blit(RenderType::guiTextured, texture, x, y, 0.0f, 0.0f, width, height,
                TEXTURE_WIDTH, TEXTURE_HEIGHT, TEXTURE_WIDTH, TEXTURE_HEIGHT, color);
            //?} else {
        graphics.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, 0.0f, 0.0f, width, height,
                TEXTURE_WIDTH, TEXTURE_HEIGHT, TEXTURE_WIDTH, TEXTURE_HEIGHT, color);
            //?}
        //?}
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
        narrationElementOutput.add(net.minecraft.client.gui.narration.NarratedElementType.TITLE, this.getMessage());
    }
}
