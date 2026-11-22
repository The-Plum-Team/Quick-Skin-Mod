package com.quickskin.mod.client.rendering;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
//? if <1.20.2 {
import com.quickskin.mod.client.services.CapeAnimationHelper;
import net.minecraft.resources.ResourceLocation;
//?} else if <1.21.2 {
import com.quickskin.mod.client.services.CapeAnimationHelper;
import net.minecraft.resources.ResourceLocation;
//?} else if <1.21.6 {
import com.quickskin.mod.client.services.CapeAnimationHelper;
import net.minecraft.resources.ResourceLocation;
//?} else if <1.21.9 {
import com.quickskin.mod.client.services.CapeAnimationHelper;
import net.minecraft.resources.ResourceLocation;
//?} else if <26.1 {
import com.quickskin.mod.client.services.CapeAnimationHelper;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
//? if <1.21.11 {
import net.minecraft.resources.ResourceLocation;
//?} else {
import net.minecraft.resources.Identifier;
//?}
//?} else {
import com.quickskin.mod.client.services.CapeAnimationHelper;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.resources.Identifier;
//?}
import org.jetbrains.annotations.Nullable;

/**
 * The cape a screen of the immersive view shows on the player in the world, in place of the
 * screen's own model: the cape menu the cape under the mouse or the one picked, the cape editor
 * the cape being edited, as it is cropped and as it plays. On the Elytra tab of the menu, and in
 * the editor's Elytra view, the player shows that texture on elytra wings instead, worn or not.
 * The cape menu opened for one skin shows that skin on the player too, with its model
 * ({@code skinLook}, asked by the player's skin lookups): on 1.20.1 and from 1.21.6. 1.21.1 to
 * 1.21.5 have no such lookup and show the look's cape on the skin the player wears.
 *
 * <p>Nothing the player wears changes: only this client's drawing of them in the world, and only
 * while the screen that shows the look, or a dialog over it, is open. Once the player is back on
 * another screen of the view (the skin menu), or the view has ended, the cape they wear is drawn
 * again, so closing a screen without picking anything leaves the player exactly as they were.
 * The cape goes to the cape layer through the same per-draw binding the screens' own previews use
 * ({@link PlayerModelRenderer#consumePreviewCape}), so an animated cape plays and a cape hidden by
 * the screen ("None") is hidden in the world too.
 *
 * <p>A draw is named by what the game's renderer is handed ({@code who}: the entity before 1.21.2,
 * its render state from then). The cape is bound to the player it draws to 1.21.5 and to the draw's
 * render state from 1.21.6, by which Quick Skin's cape layer looks it up. Every version family
 * dresses the player in its own block below; {@link #turnUnposed} is shared.
 */
public final class WorldLook {
    @Nullable
    private static PreviewPlayerData shown;
    @Nullable
    private static Object owner;
    private static int shownSession = -1;
    /** Whether the look shows the preview's skin too, not only its cape. */
    private static boolean shownSkin;
    /** The draw the look dresses going on ({@code who}), and its player, or null. */
    @Nullable
    private static Object dressed;
    @Nullable
    private static Object dressedPlayer;
//? if <1.21 {
    /** The wings of the draw going on, or null while it shows a cape. */
    @Nullable
    private static ResourceLocation wings;
    private static final ResourceLocation VANILLA_WINGS = new ResourceLocation("textures/entity/elytra.png");
//?}

    private WorldLook() {
    }

    /**
     * Shows {@code preview}'s cape on the player in the world during the immersive view
     * {@code session}, for as long as {@code screen} is open. The screen says so when it is built
     * and in every frame it floats, so the look follows its preview at once.
     */
    public static void show(int session, Object screen, PreviewPlayerData preview) {
        show(session, screen, preview, false);
    }

    /** {@link #show}, with the preview's skin and model too when {@code withSkin} is set. */
    public static void show(int session, Object screen, PreviewPlayerData preview, boolean withSkin) {
        shown = preview;
        owner = screen;
        shownSession = session;
        shownSkin = withSkin;
    }

    /** The look is over: the player wears their own cape again. */
    public static void end() {
        shown = null;
        owner = null;
        shownSession = -1;
        shownSkin = false;
    }

    /**
     * Whether the look shown still holds: in the same view, and with its own screen open, or a
     * dialog over it, as opposed to no screen or another screen of the view ({@code viewScreen}).
     */
    private static boolean holds(int session, @Nullable Object screen, boolean viewScreen) {
        return WorldLookRules.holds(shown != null, shownSession, owner, session, screen, viewScreen);
    }

//? if <1.21 {
    /**
     * Dresses the player in the world in the look shown, for the draw {@code who} of {@code entity}
     * about to start. {@code screen} is the open screen and {@code viewScreen} whether it is one of
     * the immersive view's own screens: the look stays while its screen, or a dialog over it, is
     * open, and ends once another screen of the view, or none, is.
     */
    public static void beforeDraw(int session, Object who, LivingEntity entity, @Nullable Object screen,
                                  boolean viewScreen) {
        drawn();
        PreviewPlayerData preview = shown;
        if (preview == null) {
            return;
        }
        if (!holds(session, screen, viewScreen)) {
            end();
            return;
        }
        if (entity != Minecraft.getInstance().player || !preview.isCapeAuthoritative()) {
            return;
        }
        dressed = who;
        dressedPlayer = entity;
        ResourceLocation atlas = preview.getCapeLocation();
        ResourceLocation texture = atlas == null ? null
                : CapeAnimationHelper.resolveVisibleFrame(atlas, preview.getCapeId());
        if (preview.isElytraPreview()) {
            // The texture on wings and no cape, as the screen's own model shows it
            wings = texture != null ? texture : VANILLA_WINGS;
            PlayerModelRenderer.bindWorldCape(entity, null);
        } else {
            wings = null;
            PlayerModelRenderer.bindWorldCape(entity, texture);
        }
    }

    /**
     * The preview whose skin and model the player in the world shows, or null for their own: asked
     * by the player's skin and model lookups, which the world's draw makes before it starts.
     */
    @Nullable
    public static PreviewPlayerData skinLook(int session, Object entity, @Nullable Object screen, boolean viewScreen) {
        PreviewPlayerData preview = shown;
        if (!shownSkin || preview == null || entity != Minecraft.getInstance().player
                || !holds(session, screen, viewScreen) || preview.getSkinLocation() == null) {
            return null;
        }
        return preview;
    }

    /** The texture of the wings the look puts on the draw {@code who}, or null for none. */
    @Nullable
    public static ResourceLocation wings(Object who) {
        return who == dressed ? wings : null;
    }

    /**
     * Whether the look leaves the wings the player wears out of the draw {@code who}: a look
     * showing a cape (or none), as the screen's own model does, which wears nothing. A worn elytra
     * would otherwise cover the cape the screen shows on the player's back.
     */
    public static boolean hidesWings(Object who) {
        return who == dressed && wings == null;
    }
//?} else if <1.21.2 {
    /** The wings of the draw going on, or null while it shows a cape. */
    @Nullable
    private static ResourceLocation wings;
    private static final ResourceLocation VANILLA_WINGS = ResourceLocation.withDefaultNamespace("textures/entity/elytra.png");

    /**
     * Dresses the player in the world in the look shown, for the draw {@code who} of {@code entity}
     * (null when the draw is not of a player) about to start. {@code screen} is the open screen and
     * {@code viewScreen} whether it is one of the immersive view's own screens: the look stays
     * while its screen, or a dialog over it, is open, and ends once another screen of the view, or
     * none, is.
     */
    public static void beforeDraw(int session, Object who, @Nullable LivingEntity entity, @Nullable Object screen,
                                  boolean viewScreen) {
        release();
        drawn();
        PreviewPlayerData preview = shown;
        if (preview == null) {
            return;
        }
        if (!holds(session, screen, viewScreen)) {
            end();
            return;
        }
        if (entity == null || entity != Minecraft.getInstance().player || !preview.isCapeAuthoritative()) {
            return;
        }
        dressed = who;
        dressedPlayer = entity;
        ResourceLocation atlas = preview.getCapeLocation();
        ResourceLocation texture = atlas == null ? null
                : CapeAnimationHelper.resolveVisibleFrame(atlas, preview.getCapeId());
        if (preview.isElytraPreview()) {
            // The texture on wings and no cape, as the screen's own model shows it
            wings = texture != null ? texture : VANILLA_WINGS;
            PlayerModelRenderer.bindWorldCape(entity, null);
        } else {
            wings = null;
            PlayerModelRenderer.bindWorldCape(entity, texture);
        }
    }

    /** The texture of the wings the look puts on the draw {@code who}, or null for none. */
    @Nullable
    public static ResourceLocation wings(Object who) {
        return who == dressed ? wings : null;
    }

    /**
     * Whether the look leaves the wings the player wears out of the draw {@code who}: a look
     * showing a cape (or none), as the screen's own model does, which wears nothing. A worn elytra
     * would otherwise cover the cape the screen shows on the player's back.
     */
    public static boolean hidesWings(Object who) {
        return who == dressed && wings == null;
    }

    /**
     * Takes the look off the draw going on: the cape bound for it, should the cape layer not have
     * taken it, and its wings. {@link #drawn} then ends the draw.
     */
    public static void release() {
        if (dressed != null) {
            PlayerModelRenderer.releaseWorldCape(dressedPlayer);
            wings = null;
        }
    }
//?} else if <1.21.6 {
    /** The wings of the draw going on, or null while it shows a cape. */
    @Nullable
    private static ResourceLocation wings;
    private static final ResourceLocation VANILLA_WINGS = ResourceLocation.withDefaultNamespace("textures/entity/equipment/wings/elytra.png");

    /**
     * Dresses the player in the world in the look shown, for the draw {@code who} of {@code entity}
     * (null when the draw is not of a player) about to start. {@code screen} is the open screen and
     * {@code viewScreen} whether it is one of the immersive view's own screens: the look stays
     * while its screen, or a dialog over it, is open, and ends once another screen of the view, or
     * none, is. From 1.21.2 {@code who} is the render state the
     * renderer is handed, and the cape is still bound to the player, by which the cape layer finds it.
     */
    public static void beforeDraw(int session, Object who, @Nullable LivingEntity entity, @Nullable Object screen,
                                  boolean viewScreen) {
        release();
        drawn();
        PreviewPlayerData preview = shown;
        if (preview == null) {
            return;
        }
        if (!holds(session, screen, viewScreen)) {
            end();
            return;
        }
        if (entity == null || entity != Minecraft.getInstance().player || !preview.isCapeAuthoritative()) {
            return;
        }
        dressed = who;
        dressedPlayer = entity;
        ResourceLocation atlas = preview.getCapeLocation();
        ResourceLocation texture = atlas == null ? null
                : CapeAnimationHelper.resolveVisibleFrame(atlas, preview.getCapeId());
        if (preview.isElytraPreview()) {
            // The texture on wings and no cape, as the screen's own model shows it
            wings = texture != null ? texture : VANILLA_WINGS;
            PlayerModelRenderer.bindWorldCape(entity, null);
        } else {
            wings = null;
            PlayerModelRenderer.bindWorldCape(entity, texture);
        }
    }

    /** The texture of the wings the look puts on the draw {@code who}, or null for none. */
    @Nullable
    public static ResourceLocation wings(Object who) {
        return who == dressed ? wings : null;
    }

    /**
     * Whether the look leaves the wings the player wears out of the draw {@code who}: a look
     * showing a cape (or none), as the screen's own model does, which wears nothing. A worn elytra
     * would otherwise cover the cape the screen shows on the player's back.
     */
    public static boolean hidesWings(Object who) {
        return who == dressed && wings == null;
    }

    /**
     * Takes the look off the draw going on: the cape bound for it, should the cape layer not have
     * taken it, and its wings. {@link #drawn} then ends the draw.
     */
    public static void release() {
        if (dressed != null) {
            PlayerModelRenderer.releaseWorldCape(dressedPlayer);
            wings = null;
        }
    }
//?} else if <1.21.9 {
    /** The wings of the draw going on, or null while it shows a cape. */
    @Nullable
    private static ResourceLocation wings;
    private static final ResourceLocation VANILLA_WINGS =
            ResourceLocation.withDefaultNamespace("textures/entity/equipment/wings/elytra.png");

    /**
     * Dresses the player in the world in the look shown, for the draw of the render state
     * {@code who} of {@code entity} about to start. {@code screen} is the open screen and
     * {@code viewScreen} whether it is one of the immersive view's own screens: the look stays
     * while its screen, or a dialog over it, is open, and ends once another screen of the view, or
     * none, is. The cape is bound to the render state, which the cape layer looks it up by; the
     * renderer hands every draw of a player that same state, so {@link #drawn(Object)} lets it go
     * as this draw ends.
     */
    public static void beforeDraw(int session, Object who, @Nullable LivingEntity entity, @Nullable Object screen,
                                  boolean viewScreen) {
        undress();
        drawn();
        PreviewPlayerData preview = shown;
        if (preview == null) {
            return;
        }
        if (!holds(session, screen, viewScreen)) {
            end();
            return;
        }
        if (entity == null || entity != Minecraft.getInstance().player || !preview.isCapeAuthoritative()) {
            return;
        }
        dressed = who;
        dressedPlayer = entity;
        ResourceLocation atlas = preview.getCapeLocation();
        ResourceLocation texture = atlas == null ? null
                : CapeAnimationHelper.resolveVisibleFrame(atlas, preview.getCapeId());
        if (preview.isElytraPreview()) {
            // The texture on wings and no cape, as the screen's own model shows it
            wings = texture != null ? texture : VANILLA_WINGS;
            PlayerModelRenderer.bindWorldCape(who, null);
        } else {
            wings = null;
            PlayerModelRenderer.bindWorldCape(who, texture);
        }
    }

    /** Takes the look off the draw it dresses, if any, without ending the look. */
    private static void undress() {
        if (dressed != null) {
            PlayerModelRenderer.releaseWorldCape(dressed);
            wings = null;
        }
    }

    /** The draw of the render state {@code who} is over: nothing else drawn takes the look. */
    public static void drawn(Object who) {
        if (who == dressed) {
            undress();
            drawn();
        }
    }

    /**
     * The preview whose skin and model the player in the world shows, or null for their own: asked
     * by the player's skin lookup, which the world's draw makes before it starts (the renderer is
     * chosen by the skin's model, and the render state takes the skin).
     */
    @Nullable
    public static PreviewPlayerData skinLook(int session, Object entity, @Nullable Object screen, boolean viewScreen) {
        PreviewPlayerData preview = shown;
        if (!shownSkin || preview == null || entity != Minecraft.getInstance().player
                || !holds(session, screen, viewScreen) || preview.getSkinLocation() == null) {
            return null;
        }
        return preview;
    }

    /** The texture of the wings the look puts on the draw {@code who}, or null for none. */
    @Nullable
    public static ResourceLocation wings(Object who) {
        return who == dressed ? wings : null;
    }

    /**
     * Whether the look leaves the wings the player wears out of the draw {@code who}: a look
     * showing a cape (or none), as the screen's own model does, which wears nothing. A worn elytra
     * would otherwise cover the cape the screen shows on the player's back.
     */
    public static boolean hidesWings(Object who) {
        return who == dressed && wings == null;
    }
//?} else if <26.1 {
    /**
     * The wings of the submit going on, or null while it shows a cape. From 1.21.2 the elytra
     * texture lives with the equipment assets.
     */
//? if <1.21.11 {
    @Nullable
    private static ResourceLocation wings;
    private static final ResourceLocation VANILLA_WINGS =
            ResourceLocation.withDefaultNamespace("textures/entity/equipment/wings/elytra.png");
//?} else {
    @Nullable
    private static Identifier wings;
    private static final Identifier VANILLA_WINGS =
            Identifier.withDefaultNamespace("textures/entity/equipment/wings/elytra.png");
//?}

    /**
     * Dresses the player in the world in the look shown, for the submit about to start, named by
     * its render state {@code who}: the cape layer finds the cape by that state, the cape it is
     * handed. {@code screen} is the open screen and {@code viewScreen} whether it is one of the
     * immersive view's own screens: the look stays while its screen, or a dialog over it, is
     * open, and ends once another screen of the view, or none, is.
     */
    public static void beforeDraw(int session, Object who, @Nullable Object screen, boolean viewScreen) {
        drawEnds();
        PreviewPlayerData preview = shown;
        if (preview == null) {
            return;
        }
        if (!holds(session, screen, viewScreen)) {
            end();
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || !(who instanceof AvatarRenderState state)
                || state.id != minecraft.player.getId() || !preview.isCapeAuthoritative()) {
            return;
        }
        dressed = who;
        dressedPlayer = minecraft.player;
        var atlas = preview.getCapeLocation();
        var texture = atlas == null ? null : CapeAnimationHelper.resolveVisibleFrame(atlas, preview.getCapeId());
        if (preview.isElytraPreview()) {
            // The texture on wings and no cape, as the screen's own model shows it
            wings = texture != null ? texture : VANILLA_WINGS;
            PlayerModelRenderer.bindWorldCape(who, null);
        } else {
            wings = null;
            PlayerModelRenderer.bindWorldCape(who, texture);
        }
    }

    /**
     * The preview whose skin and model the player in the world shows, or null for their own: asked
     * by the player's skin lookup, which the world's extraction makes before the submit.
     */
    @Nullable
    public static PreviewPlayerData skinLook(int session, Object entity, @Nullable Object screen, boolean viewScreen) {
        PreviewPlayerData preview = shown;
        if (!shownSkin || preview == null || entity != Minecraft.getInstance().player
                || !holds(session, screen, viewScreen) || preview.getSkinLocation() == null) {
            return null;
        }
        return preview;
    }

    /**
     * {@link #skinLook} for the render state {@code who} the renderer is handed, by the same rule,
     * so a renderer-level skin lookup (Quick Skin's own, for mods that replace {@code getSkin})
     * agrees with the player's skin lookup, which filled that state: the look, or null for a state
     * that is not the local player's or a player wearing their own skin.
     */
    @Nullable
    public static PreviewPlayerData skinLookOf(int session, Object who, @Nullable Object screen, boolean viewScreen) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || !(who instanceof AvatarRenderState state)
                || state.id != minecraft.player.getId()) {
            return null;
        }
        return skinLook(session, minecraft.player, screen, viewScreen);
    }

    /**
     * The texture of the wings the look puts on the submit {@code who}, or null for none: the
     * wings layer then draws an elytra, worn or not, with it.
     */
    @Nullable
//? if <1.21.11 {
    public static ResourceLocation wings(Object who) {
//?} else {
    public static Identifier wings(Object who) {
//?}
        return who == dressed ? wings : null;
    }

    /**
     * Whether the look leaves the wings the player wears out of the submit {@code who}: a look
     * showing a cape (or none), as the screen's own model does, which wears nothing. A worn elytra
     * would otherwise cover the cape the screen shows on the player's back.
     */
    public static boolean hidesWings(Object who) {
        return who == dressed && wings == null;
    }

    /**
     * The player's submit is over: a cape the cape layer did not take (another mod drew the cape
     * instead) is let go, and nothing else drawn takes the look.
     */
    public static void drawEnds() {
        if (dressed != null) {
            PlayerModelRenderer.releaseWorldCape(dressed);
            wings = null;
        }
        drawn();
    }
//?} else {
    /** The wings of the submit going on, or null while it shows a cape; the elytra texture is an equipment asset. */
    @Nullable
    private static Identifier wings;
    private static final Identifier VANILLA_WINGS =
            Identifier.withDefaultNamespace("textures/entity/equipment/wings/elytra.png");

    /**
     * Dresses the player in the world in the look shown, for the submit about to start, named by
     * its render state {@code who}: the cape layer, which runs inside that submit, finds the cape
     * by that state. {@code screen} is the open screen and {@code viewScreen} whether it is one of
     * the immersive view's own screens: the look stays while its screen, or a dialog over it, is
     * open, and ends once another screen of the view, or none, is.
     */
    public static void beforeDraw(int session, Object who, @Nullable Object screen, boolean viewScreen) {
        drawEnds();
        PreviewPlayerData preview = shown;
        if (preview == null) {
            return;
        }
        if (!holds(session, screen, viewScreen)) {
            end();
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || !(who instanceof AvatarRenderState state)
                || state.id != minecraft.player.getId() || !preview.isCapeAuthoritative()) {
            return;
        }
        dressed = who;
        dressedPlayer = minecraft.player;
        Identifier atlas = preview.getCapeLocation();
        Identifier texture = atlas == null ? null : CapeAnimationHelper.resolveVisibleFrame(atlas, preview.getCapeId());
        if (preview.isElytraPreview()) {
            // The texture on wings and no cape, as the screen's own model shows it
            wings = texture != null ? texture : VANILLA_WINGS;
            PlayerModelRenderer.bindWorldCape(who, null);
        } else {
            wings = null;
            PlayerModelRenderer.bindWorldCape(who, texture);
        }
    }

    /**
     * The preview whose skin and model the player in the world shows, or null for their own: asked
     * by the player's skin lookup, which the game makes as it extracts the player's render state,
     * before the submit.
     */
    @Nullable
    public static PreviewPlayerData skinLook(int session, Object entity, @Nullable Object screen, boolean viewScreen) {
        PreviewPlayerData preview = shown;
        if (!shownSkin || preview == null || entity != Minecraft.getInstance().player
                || !holds(session, screen, viewScreen) || preview.getSkinLocation() == null) {
            return null;
        }
        return preview;
    }

    /**
     * The texture of the wings the look puts on the submit {@code who}, or null for none: the
     * wings layer then draws an elytra with it, worn or not, the chest piece hidden or not.
     */
    @Nullable
    public static Identifier wings(Object who) {
        return who == dressed ? wings : null;
    }

    /**
     * Whether the look leaves the wings the player wears out of the submit {@code who}: a look
     * showing a cape (or none), as the screen's own model does, which wears nothing. A worn elytra
     * would otherwise cover the cape the screen shows on the player's back.
     */
    public static boolean hidesWings(Object who) {
        return who == dressed && wings == null;
    }

    /**
     * The player's submit is over: a cape the cape layer did not take (another mod drew the cape
     * instead) is let go, and nothing else drawn takes the look.
     */
    public static void drawEnds() {
        if (dressed != null) {
            PlayerModelRenderer.releaseWorldCape(dressed);
            wings = null;
        }
        drawn();
    }
//?}

    /**
     * Turns the player in the world as far as the open screen asks ({@code screenTurn}, see
     * {@code ImmersiveScreen.immersivePlayerTurn}) when the skin menu's pose, which turns them
     * otherwise, is not played: when riding or crouching, under a CPM look, or with no pose named.
     * The cape screens show the player's back this way whatever they are doing. As far as the turn
     * has come, the body also faces the camera's resting place ({@code restYaw}), as the menu's
     * pose has it; a player lying down, gliding, swimming or spinning keeps the game's own stance,
     * whose model is not upright. Called in the model's own space, before it is drawn, with the
     * way the game drew the body this frame ({@code bodyYaw}). Every version shares it.
     */
    public static void turnUnposed(LivingEntity entity, float bodyYaw, PoseStack poseStack, float restYaw,
                                   float screenTurn, float settle) {
        if (screenTurn == 0.0f || entity != Minecraft.getInstance().player || entity.isSleeping()
                || entity.isFallFlying() || entity.isVisuallySwimming() || entity.isAutoSpinAttack()) {
            return;
        }
//? if <26.3 {
        poseStack.mulPose(Axis.YP.rotationDegrees(WorldLookRules.unposedTurn(restYaw, bodyYaw, screenTurn, settle)));
//?} else {
        poseStack.rotate(Axis.YP.rotationDegrees(WorldLookRules.unposedTurn(restYaw, bodyYaw, screenTurn, settle)));
//?}
    }

    /**
     * Whether the look puts wings on {@code entity} in this draw, which it does whatever the chest
     * slot holds, a piece the player hides included.
     */
    public static boolean dressesWithWings(Object entity) {
//? if <1.21.2 {
        return entity != null && entity == dressed && wings != null;
//?} else {
        return false;
//?}
    }

    /** The player's draw is over: nothing else drawn takes the look. */
    public static void drawn() {
        if (dressed != null) {
//? if <1.20.2 {
            PlayerModelRenderer.releaseWorldCape(dressedPlayer);
            wings = null;
//?}
            dressed = null;
            dressedPlayer = null;
        }
    }
}
