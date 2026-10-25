package com.quickskin.mod.client.gui.util;

import com.quickskin.mod.platform.MinecraftCompat;

//? if <1.21.2 {
import com.mojang.blaze3d.systems.RenderSystem;
//?}
import com.quickskin.mod.client.gui.StarPatternCache;
//? if <1.21 {
import com.quickskin.mod.client.gui.GuiCompat;
//?} else if <1.21.11 {
//?} else {
import com.quickskin.mod.client.gui.GuiCompat;
//?}
import com.quickskin.mod.client.gui.effect.BlurHandler;
import com.quickskin.mod.config.ClientConfig;
import com.quickskin.mod.common.data.BackgroundStyle;
//? if <1.21 {
//?} else if <1.21.11 {
//?} else {
//?}
import net.minecraft.client.Minecraft;
//? if <26.1 {
import net.minecraft.client.gui.GuiGraphics;
//?} else {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?}
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.CubeMap;
//? if <26.1 {
import net.minecraft.client.renderer.PanoramaRenderer;
//?} else {
import net.minecraft.client.renderer.Panorama;
//?}
//? if >=1.21.6 {
import net.minecraft.client.renderer.RenderPipelines;
//?} else if >=1.21.2 {
import net.minecraft.client.renderer.RenderType;
//?}
//? if <1.21.11 {
import net.minecraft.resources.ResourceLocation;
//?} else {
import net.minecraft.resources.Identifier;
//?}

public class BackgroundRenderer {
    private static final boolean DETERMINISTIC_E2E_RENDER =
        Boolean.getBoolean("quickskin.e2e.enabled");
    private static final int E2E_FIXED_GUI_TICK = 120;

    //? if <1.21 {
    private static final ResourceLocation VIGNETTE_LOCATION =
        new ResourceLocation("textures/misc/vignette.png");
    //?} else if <1.21.11 {
    private static final ResourceLocation VIGNETTE_LOCATION =
        ResourceLocation.withDefaultNamespace("textures/misc/vignette.png");
    //?} else {
    private static final Identifier VIGNETTE_LOCATION =
        Identifier.withDefaultNamespace("textures/misc/vignette.png");
    //?}

    // Panorama renderer instance (reused across renders)
    //? if <26.1 {
    private static PanoramaRenderer panoramaRenderer = null;
    //?} else {
    private static Panorama panoramaRenderer = null;
    //?}
    // The panorama the title screen last drew, which a mod such as Aquamirae may have replaced
    //? if <26.1 {
    private static volatile PanoramaRenderer titlePanorama = null;
    //?} else {
    private static volatile Panorama titlePanorama = null;
    //?}
    //? if <1.21 {
    private static final ResourceLocation PANORAMA_OVERLAY = new ResourceLocation("textures/gui/title/background/panorama_overlay.png");
    private static final CubeMap PANORAMA_CUBE_MAP = new CubeMap(new ResourceLocation("textures/gui/title/background/panorama"));
    //?} else if <1.21.11 {
    private static final ResourceLocation PANORAMA_OVERLAY = ResourceLocation.withDefaultNamespace("textures/gui/title/background/panorama_overlay.png");
    private static final CubeMap PANORAMA_CUBE_MAP = new CubeMap(ResourceLocation.withDefaultNamespace("textures/gui/title/background/panorama"));
    //?} else {
    private static final Identifier PANORAMA_OVERLAY = Identifier.withDefaultNamespace("textures/gui/title/background/panorama_overlay.png");
    private static final CubeMap PANORAMA_CUBE_MAP = new CubeMap(Identifier.withDefaultNamespace("textures/gui/title/background/panorama"));
    //?}

    /**
     * Renders menu background based on config setting
     * @param screen The screen rendering the background
     * @param graphics Graphics context
     * @param partialTick Partial tick for animations
     */
    //? if <26.1 {
    public static void renderBackground(Screen screen, GuiGraphics graphics, float partialTick) {
    //?} else {
    public static void renderBackground(Screen screen, GuiGraphicsExtractor graphics, float partialTick) {
    //?}
        BackgroundStyle style = ClientConfig.getInstance().getMenuBackgroundStyle();

        switch (style) {
            case OPAQUE_STARS:
                renderOpaqueStarsBackground(screen, graphics, partialTick);
                break;
            case VANILLA_BLUR:
                renderBlurredBackground(screen, graphics, partialTick);
                break;
        }
    }

    /**
     * Renders opaque black background with star pattern and vignette
     */
    //? if <26.1 {
    private static void renderOpaqueStarsBackground(Screen screen, GuiGraphics graphics, float partialTick) {
    //?} else {
    private static void renderOpaqueStarsBackground(Screen screen, GuiGraphicsExtractor graphics, float partialTick) {
    //?}
        // 1. Base black fill
        graphics.fill(0, 0, screen.width, screen.height, 0xFF000000);

        // 2. Star pattern
        renderStarPattern(screen, graphics, partialTick);

        //? if <1.21.2 {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(0.0F, 0.0F, 0.0F, 0.75F);
        //? if <1.21 {
        GuiCompat.blit(graphics, VIGNETTE_LOCATION, 0, 0, 0, 0.0f, 0.0f, screen.width, screen.height, screen.width, screen.height);
        //?} else {
        MinecraftCompat.INSTANCE.blit(graphics, VIGNETTE_LOCATION, 0, 0, 0, 0.0f, 0.0f, screen.width, screen.height, screen.width, screen.height);
        //?}
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.disableBlend();
        //?} else if <1.21.6 {
        // GuiGraphics is batched from 1.21.2 onward, so keep the tint on each queued vertex.
        int vignetteColor = 0xBF000000;
        graphics.blit(RenderType::guiTextured, VIGNETTE_LOCATION, 0, 0, 0.0f, 0.0f,
                screen.width, screen.height, screen.width, screen.height, vignetteColor);
        //?} else {
        // 3. Vignette overlay — pass ARGB color (black at 75% opacity)
        int vignetteColor = (191 << 24) | 0; // 0xBF000000
        graphics.blit(RenderPipelines.GUI_TEXTURED, VIGNETTE_LOCATION, 0, 0, 0.0f, 0.0f,
            screen.width, screen.height, screen.width, screen.height, vignetteColor);
        //?}
    }

    /**
     * Renders scrolling star pattern overlay
     */
    //? if <26.1 {
    private static void renderStarPattern(Screen screen, GuiGraphics graphics, float partialTick) {
    //?} else {
    private static void renderStarPattern(Screen screen, GuiGraphicsExtractor graphics, float partialTick) {
    //?}
        double pixelsPerSecond = 5.0;
        int tileSize = StarPatternCache.getTileSize();

        //? if <1.21.2 {
        ResourceLocation starTexture = StarPatternCache.getTextureLocation();
        int texWidth = StarPatternCache.getTextureWidth();
        int texHeight = StarPatternCache.getTextureHeight();
        //?} else if <1.21.11 {
        ResourceLocation starTexture = StarPatternCache.getTextureLocation();
        int cacheWidth = StarPatternCache.getTextureWidth();
        int cacheHeight = StarPatternCache.getTextureHeight();
        //?} else {
        Identifier starTexture = StarPatternCache.getTextureLocation();
        int cacheWidth = StarPatternCache.getTextureWidth();
        int cacheHeight = StarPatternCache.getTextureHeight();
        //?}

        // Calculate smooth scrolling offset
        Minecraft minecraft = Minecraft.getInstance();
        //? if <26.2 {
        int tickCount = minecraft != null && minecraft.gui != null ? minecraft.gui.getGuiTicks() : 0;
        //?} else {
        int tickCount = minecraft != null && minecraft.gui != null ? minecraft.gui.hud.getGuiTicks() : 0;
        //?}
        double smoothTime = DETERMINISTIC_E2E_RENDER
            ? E2E_FIXED_GUI_TICK / 20.0
            : (tickCount + partialTick) / 20.0;
        double offsetX = (smoothTime * pixelsPerSecond) % tileSize;

        //? if <1.21.2 {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 0.15f);
        //?} else {
        // Star opacity as ARGB color (white with 15% opacity)
        int argbColor = (38 << 24) | (255 << 16) | (255 << 8) | 255; // 0x26FFFFFF
        //?}

        //? if <1.21.2 {
        float u0 = (float)offsetX / (float)texWidth;
        float v0 = 0.0f;
        float u1 = u0 + ((float)screen.width / (float)texWidth);
        float v1 = (float)screen.height / (float)texHeight;
        //?} else {
        // Ensure linear filtering for smooth sub-pixel scrolling
        StarPatternCache.ensureLinearFiltering();
        //?}

        //? if <1.21.2 {
        var pose = graphics.pose();
        pose.pushPose();
        RenderSystem.setShaderTexture(0, starTexture);
        //? if <1.21.2 {
        RenderSystem.setShader(net.minecraft.client.renderer.GameRenderer::getPositionTexShader);
        //?}
        com.mojang.blaze3d.vertex.Tesselator tesselator = com.mojang.blaze3d.vertex.Tesselator.getInstance();
        //? if <1.21 {
        com.mojang.blaze3d.vertex.BufferBuilder bufferBuilder = tesselator.getBuilder();
        bufferBuilder.begin(
            com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS,
            com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_TEX
        );
        bufferBuilder.vertex(pose.last().pose(), 0, screen.height, 0).uv(u0, v1).endVertex();
        bufferBuilder.vertex(pose.last().pose(), screen.width, screen.height, 0).uv(u1, v1).endVertex();
        bufferBuilder.vertex(pose.last().pose(), screen.width, 0, 0).uv(u1, v0).endVertex();
        bufferBuilder.vertex(pose.last().pose(), 0, 0, 0).uv(u0, v0).endVertex();
        tesselator.end();
        //?} else {
        com.mojang.blaze3d.vertex.BufferBuilder bufferBuilder = tesselator.begin(
            com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS,
            com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_TEX
        );
        bufferBuilder.addVertex(pose.last().pose(), 0, screen.height, 0).setUv(u0, v1);
        bufferBuilder.addVertex(pose.last().pose(), screen.width, screen.height, 0).setUv(u1, v1);
        bufferBuilder.addVertex(pose.last().pose(), screen.width, 0, 0).setUv(u1, v0);
        bufferBuilder.addVertex(pose.last().pose(), 0, 0, 0).setUv(u0, v0);
        com.mojang.blaze3d.vertex.BufferUploader.drawWithShader(bufferBuilder.buildOrThrow());
        //?}
        pose.popPose();
        RenderSystem.disableBlend();
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        //?} else if <1.21.6 {
        // Keep the black fill, stars, and vignette in GuiGraphics' ordered buffer.
        graphics.blit(RenderType::guiTextured, starTexture, 0, 0, (float) offsetX, 0.0f,
                screen.width, screen.height, cacheWidth, cacheHeight, argbColor);
        //?} else {
        // Render with blit passing ARGB color for opacity
        // Use GUI_TEXTURED instead of guiTexturedOverlay for Sodium compatibility
        graphics.blit(RenderPipelines.GUI_TEXTURED, starTexture, 0, 0, (float) offsetX, 0.0f,
            screen.width, screen.height, cacheWidth, cacheHeight, argbColor);
        //?}
    }

    /**
     * Renders the default vanilla-style background: the world in-game, or the panorama on the
     * title screen, blurred and then darkened under a translucent overlay
     */
    //? if <26.1 {
    private static void renderBlurredBackground(Screen screen, GuiGraphics graphics, float partialTick) {
    //?} else {
    private static void renderBlurredBackground(Screen screen, GuiGraphicsExtractor graphics, float partialTick) {
    //?}
        Minecraft minecraft = Minecraft.getInstance();

        // Check if player is in a world
        if (minecraft != null && minecraft.player != null && minecraft.level != null) {
            // In-game: blur the world behind, then a semi-transparent overlay over it
            BlurHandler.blurBehindMenu(graphics);
            graphics.fill(0, 0, screen.width, screen.height, 0x90000000);
        } else {
            // Title screen: render the panorama background, the one the title screen draws
            if (panoramaRenderer == null) {
                //? if <26.1 {
                panoramaRenderer = new PanoramaRenderer(PANORAMA_CUBE_MAP);
                //?} else {
                panoramaRenderer = new Panorama();
                //?}
            }
            //? if <26.1 {
            PanoramaRenderer panorama = titlePanorama != null ? titlePanorama : panoramaRenderer;
            //?} else {
            Panorama panorama = titlePanorama != null ? titlePanorama : panoramaRenderer;
            //?}

            // The renderer mixin turns this panorama with PanoramaTimeSync's shared clock, the
            // same one the title screen's panorama uses, so switching screens does not jump.
            float panoramaPartialTick = DETERMINISTIC_E2E_RENDER ? 0.0F : partialTick;

            //? if <1.21 {
            GuiCompat.renderPanorama(panorama, panoramaPartialTick);
            //?} else if <1.21.6 {
            panorama.render(graphics, screen.width, screen.height, 1.0F, panoramaPartialTick);
            //?} else if <1.21.11 {
            panorama.render(graphics, screen.width, screen.height, true);
            //?} else {
                //? if <26.1 {
            GuiCompat.renderPanorama(panorama, graphics, screen.width, screen.height);
                //?} else {
            GuiCompat.extractPanorama(panorama, graphics, screen.width, screen.height);
                //?}
            //?}

            BlurHandler.blurBehindMenu(graphics);

            // Render panorama overlay (darkens the panorama like in title screen)
            //? if <1.21 {
            RenderSystem.enableBlend();
            graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
            GuiCompat.blit(graphics, PANORAMA_OVERLAY, 0, 0, 0, 0.0F, 0.0F, screen.width, screen.height, 16, 128);
            //?} else if <1.21.2 {
            graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
            graphics.blit(PANORAMA_OVERLAY, 0, 0, 0, 0.0F, 0.0F, screen.width, screen.height, 16, 128);
            //?} else if <1.21.11 {
            MinecraftCompat.INSTANCE.blit(graphics, PANORAMA_OVERLAY, 0, 0, 0, 0.0F, 0.0F,
                    screen.width, screen.height, 16, 128);
            //?} else {
            GuiCompat.blit(graphics, PANORAMA_OVERLAY, 0, 0, 0, 0.0F, 0.0F, screen.width, screen.height, 16, 128);
            //?}

            // Add additional dark overlay for better UI readability
            graphics.fill(0, 0, screen.width, screen.height, 0x60000000);
        }
    }

    /**
     * Remembers the panorama the title screen draws, so Quick Skin's screens show the same one:
     * a mod may draw its own there, and a resource pack may change its pictures.
     */
    //? if <26.1 {
    public static void rememberTitlePanorama(PanoramaRenderer panorama) {
    //?} else {
    public static void rememberTitlePanorama(Panorama panorama) {
    //?}
        titlePanorama = panorama;
    }

    /**
     * Cleans up resources when screen is closed.
     * MUST be called from screen's removed() or onClose() method.
     */
    public static void cleanup() {
        BlurHandler.cleanup();
        // Don't reset panoramaRenderer - keep it for consistent time accumulation
    }
}
