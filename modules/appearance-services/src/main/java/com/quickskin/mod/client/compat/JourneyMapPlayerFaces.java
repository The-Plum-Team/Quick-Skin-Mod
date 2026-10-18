package com.quickskin.mod.client.compat;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.quickskin.mod.client.services.PlayerAppearanceService;
import com.quickskin.mod.platform.MinecraftCompat;
import com.quickskin.mod.platform.MinecraftTextureUploads;
import com.quickskin.mod.platform.QuickSkinInfo;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
//? if <1.21.11 {
import net.minecraft.resources.ResourceLocation;
//?} else {
import net.minecraft.resources.Identifier;
//?}
import org.jetbrains.annotations.Nullable;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.IntBinaryOperator;

/**
 * Player head icons for JourneyMap, cut from the Quick Skin skin a player wears.
 *
 * <p>JourneyMap draws player icons from a face it crops itself, and on every Minecraft version it
 * misses Quick Skin skins in one of two ways: it reads a texture class Quick Skin does not use
 * (Mojang's {@code HttpTexture} on 1.21.1 to 1.21.3, a Mojang session download on 1.20.1), or it
 * caches the first face it sees per player for the whole session, so the Steve or Mojang face
 * read before a Quick Skin appearance arrives never changes. Its mixins ask this class first and
 * fall back to JourneyMap's own face whenever it answers {@code null}.</p>
 *
 * <p>Each face is registered with the texture manager under one stable id per player, which owns
 * it from then on: a new skin registers a new face under the same id, and the texture manager
 * closes the one it replaces. Only the render thread touches these faces.</p>
 */
@Environment(EnvType.CLIENT)
public final class JourneyMapPlayerFaces {
    static final int MAX_FACES = 256;
    /** Every JourneyMap build draws its player faces from a 24x24 image (3x the 8x8 face). */
    static final int FACE_SIZE = 24;

    private static final Map<UUID, Face> FACES = new LinkedHashMap<>(16, 0.75f, true);

    private JourneyMapPlayerFaces() {
    }

    /** The face JourneyMap should draw for this player, or {@code null} to keep JourneyMap's own. */
    @Nullable
    public static DynamicTexture face(@Nullable UUID playerId) {
        Face face = currentFace(playerId);
        return face != null ? face.texture : null;
    }

    /** Where {@link #face} is registered, for JourneyMap paths that draw by texture id. */
    @Nullable
    //? if <1.21.11 {
    public static ResourceLocation faceLocation(@Nullable UUID playerId) {
    //?} else {
    public static Identifier faceLocation(@Nullable UUID playerId) {
    //?}
        Face face = currentFace(playerId);
        return face != null ? face.location : null;
    }

    /** Releases every face; the session is over and its players' skins with it. */
    public static void clear() {
        if (!RenderSystem.isOnRenderThread()) {
            Minecraft.getInstance().execute(JourneyMapPlayerFaces::clear);
            return;
        }
        Iterator<Face> faces = FACES.values().iterator();
        while (faces.hasNext()) {
            release(faces.next());
            faces.remove();
        }
    }

    @Nullable
    private static Face currentFace(@Nullable UUID playerId) {
        if (playerId == null || !RenderSystem.isOnRenderThread()) {
            return null;
        }
        PlayerAppearanceService service = PlayerAppearanceService.getInstance();
        if (service == null || !service.hasActiveSkin(playerId)) {
            Face stale = FACES.remove(playerId);
            if (stale != null) {
                release(stale);
            }
            return null;
        }
        var skin = service.getSkinLocation(playerId);
        if (skin == null) {
            return null;
        }
        Face cached = FACES.get(playerId);
        if (cached != null && cached.skin.equals(skin)) {
            return cached;
        }
        // A skin that has not finished loading yet keeps JourneyMap's own face until it has.
        AbstractTexture skinTexture = Minecraft.getInstance().getTextureManager().getTexture(skin);
        if (!(skinTexture instanceof DynamicTexture dynamicTexture) || dynamicTexture.getPixels() == null) {
            return null;
        }
        Face face = createFace(playerId, skin, dynamicTexture.getPixels());
        if (face == null) {
            return null;
        }
        FACES.put(playerId, face);
        evictOldest();
        return face;
    }

    @Nullable
    private static Face createFace(UUID playerId, Object skin, NativeImage skinPixels) {
        int width = skinPixels.getWidth();
        int height = skinPixels.getHeight();
        int[] pixels = composeFace(width, height,
                (x, y) -> MinecraftCompat.INSTANCE.getPixel(skinPixels, x, y));
        if (pixels == null) {
            return null;
        }
        NativeImage faceImage = new NativeImage(FACE_SIZE, FACE_SIZE, true);
        for (int y = 0; y < FACE_SIZE; y++) {
            for (int x = 0; x < FACE_SIZE; x++) {
                MinecraftCompat.INSTANCE.setPixel(faceImage, x, y, pixels[y * FACE_SIZE + x]);
            }
        }
        DynamicTexture texture = MinecraftTextureUploads.create(() -> "quickskin_journeymap_face", faceImage);
        String path = "journeymap_face/" + playerId.toString().toLowerCase(Locale.ROOT);
        //? if <1.21.11 {
            //? if <1.21 {
        ResourceLocation location = new ResourceLocation(QuickSkinInfo.MOD_ID, path);
            //?} else {
        ResourceLocation location = ResourceLocation.fromNamespaceAndPath(QuickSkinInfo.MOD_ID, path);
            //?}
        //?} else {
        Identifier location = Identifier.fromNamespaceAndPath(QuickSkinInfo.MOD_ID, path);
        //?}
        Minecraft.getInstance().getTextureManager().register(location, texture);
        return new Face(skin, texture, location);
    }

    private static void evictOldest() {
        Iterator<Face> faces = FACES.values().iterator();
        while (FACES.size() > MAX_FACES && faces.hasNext()) {
            release(faces.next());
            faces.remove();
        }
    }

    private static void release(Face face) {
        Minecraft.getInstance().getTextureManager().release(face.location);
    }

    /**
     * The head's front face with the hat layer drawn over it, scaled to {@link #FACE_SIZE} and
     * returned as row-major ABGR pixels, or {@code null} when the image is too small to be a skin.
     * Each pixel samples the nearest texel of the face at the skin's own resolution.
     */
    @Nullable
    static int[] composeFace(int skinWidth, int skinHeight, IntBinaryOperator abgrAt) {
        if (skinWidth < 64 || skinHeight < skinWidth / 2) {
            return null;
        }
        int scale = skinWidth / 64;
        int sourceSize = 8 * scale;
        int[] face = new int[FACE_SIZE * FACE_SIZE];
        for (int y = 0; y < FACE_SIZE; y++) {
            int sourceY = 8 * scale + y * sourceSize / FACE_SIZE;
            for (int x = 0; x < FACE_SIZE; x++) {
                int sourceX = x * sourceSize / FACE_SIZE;
                int head = abgrAt.applyAsInt(8 * scale + sourceX, sourceY);
                int hat = abgrAt.applyAsInt(40 * scale + sourceX, sourceY);
                face[y * FACE_SIZE + x] = over(hat, head);
            }
        }
        return face;
    }

    /** Draws ABGR {@code top} over ABGR {@code bottom} (straight alpha). */
    static int over(int top, int bottom) {
        int topAlpha = top >>> 24;
        if (topAlpha == 0xFF) {
            return top;
        }
        if (topAlpha == 0) {
            return bottom;
        }
        int bottomAlpha = bottom >>> 24;
        int outAlpha = topAlpha + bottomAlpha * (255 - topAlpha) / 255;
        if (outAlpha == 0) {
            return 0;
        }
        int result = outAlpha << 24;
        for (int shift = 0; shift <= 16; shift += 8) {
            int topChannel = (top >>> shift) & 0xFF;
            int bottomChannel = (bottom >>> shift) & 0xFF;
            int channel = (topChannel * topAlpha
                    + bottomChannel * bottomAlpha * (255 - topAlpha) / 255) / outAlpha;
            result |= Math.min(255, channel) << shift;
        }
        return result;
    }

    private record Face(
            Object skin,
            DynamicTexture texture,
            //? if <1.21.11 {
            ResourceLocation location
            //?} else {
            Identifier location
            //?}
    ) {
    }
}
