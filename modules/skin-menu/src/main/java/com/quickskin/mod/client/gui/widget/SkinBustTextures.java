package com.quickskin.mod.client.gui.widget;

import com.mojang.blaze3d.platform.NativeImage;
import com.quickskin.mod.client.concurrent.ClientIoExecutor;
import com.quickskin.mod.client.services.LocalAssetManager;
import com.quickskin.mod.common.data.TextureQuality;
import com.quickskin.mod.common.util.SafeImageReader;
import com.quickskin.mod.common.util.SkinBustRasterizer;
import com.quickskin.mod.config.ClientConfig;
import com.quickskin.mod.platform.MinecraftCompat;
import com.quickskin.mod.platform.MinecraftTextureUploads;
import com.quickskin.mod.platform.QuickSkinInfo;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
//? if <1.21.11 {
import net.minecraft.resources.ResourceLocation;
//?} else {
import net.minecraft.resources.Identifier;
//?}
import org.jetbrains.annotations.Nullable;

import java.awt.image.BufferedImage;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

/**
 * The 3D busts of the skin list's rows, as textures.
 *
 * <p>A bust is drawn by {@link SkinBustRasterizer} on the client I/O workers, from the skin's
 * catalogued texture, and uploaded on the render thread. Each is made once per skin, arm width,
 * transparency setting and pixel size, and kept in a small least-recently-used set whose evicted
 * textures are released. Until its texture is ready a row shows nothing in the bust's place.
 *
 * <p>The delete mode draws a skin marked for deletion in grey: its bust, and for the grid its
 * face. A grey copy is made on the render thread, as soon as it is asked for, from pixels already
 * in memory: the colour bust's, which its slot keeps, or the loaded skin texture's. It shows in
 * the same frame, so marking a skin never leaves a gap while a copy is made.
 */
@Environment(EnvType.CLIENT)
public final class SkinBustTextures {

    /**
     * Busts kept as textures: a few screens of rows, at about 70 KiB each at GUI scale 3, and as
     * much again for a colour bust's pixels.
     */
    private static final int MAX_TEXTURES = 48;
    /** Busts being drawn at once, so a long catalog does not fill the shared worker queue. */
    private static final int MAX_PENDING = 3;

    private static final Map<String, Slot> SLOTS = new LinkedHashMap<>(16, 0.75f, true);
    private static int pending;

    private SkinBustTextures() {
    }

    /** A bust's state: drawing, ready with its texture, or failed for good. */
    private static final class Slot {
        //? if <1.21.11 {
        @Nullable ResourceLocation location;
        //?} else {
        @Nullable Identifier location;
        //?}
        /** A colour bust's ARGB pixels, kept for its grey copy. */
        int @Nullable [] pixels;
        boolean failed;
        boolean removed;
    }

    /**
     * The bust of a skin at this many pixels, in grey when asked, or {@code null} while it is being
     * drawn or when it cannot be. Call from the render thread.
     */
    //? if <1.21.11 {
    @Nullable
    public static ResourceLocation get(String hash, boolean slim, int width, int height, boolean gray) {
    //?} else {
    @Nullable
    public static Identifier get(String hash, boolean slim, int width, int height, boolean gray) {
    //?}
        if (width < 1 || height < 1 || width > SkinBustRasterizer.MAX_SIZE || height > SkinBustRasterizer.MAX_SIZE) {
            return null;
        }
        boolean opaque = ClientConfig.getInstance().shouldDisableSkinTransparency();
        String key = hash + (slim ? "_slim" : "_wide") + (opaque ? "_opaque" : "") + "_" + width + "x" + height;
        if (!gray) {
            return get(key, width, height, () -> draw(hash, slim, width, height));
        }
        String grayKey = key + "_gray";
        Slot existing = SLOTS.get(grayKey);
        if (existing != null) {
            return existing.location;
        }
        // The grey copy comes from the colour bust, which is drawn first when it is not yet
        Slot colour = SLOTS.get(key);
        if (colour == null || colour.pixels == null) {
            get(key, width, height, () -> draw(hash, slim, width, height));
            return null;
        }
        return putReady(grayKey, gray(colour.pixels.clone()), width, height);
    }

    /**
     * A skin's face with its hat layer, eight pixels square, in grey, from the skin texture the
     * game has loaded; {@code null} when that texture keeps no pixels to read. Call from the
     * render thread.
     */
    //? if <1.21.11 {
    @Nullable
    public static ResourceLocation grayFace(String hash, ResourceLocation skin) {
    //?} else {
    @Nullable
    public static Identifier grayFace(String hash, Identifier skin) {
    //?}
        String key = hash + "_face_gray";
        Slot existing = SLOTS.get(key);
        if (existing != null) {
            return existing.location;
        }
        int[] face = face(skin);
        if (face == null) {
            // Not loaded yet, or a texture without pixels: remembered only once it is loaded
            if (Minecraft.getInstance().getTextureManager().getTexture(skin) instanceof DynamicTexture) {
                Slot failed = new Slot();
                failed.failed = true;
                SLOTS.put(key, failed);
                trim();
            }
            return null;
        }
        return putReady(key, gray(face), 8, 8);
    }

    /** Uploads pixels made on the render thread as a ready slot. */
    //? if <1.21.11 {
    @Nullable
    private static ResourceLocation putReady(String key, int[] pixels, int width, int height) {
    //?} else {
    @Nullable
    private static Identifier putReady(String key, int[] pixels, int width, int height) {
    //?}
        Slot slot = new Slot();
        slot.location = upload(key, pixels, width, height);
        slot.failed = slot.location == null;
        SLOTS.put(key, slot);
        trim();
        return slot.location;
    }

    //? if <1.21.11 {
    @Nullable
    private static ResourceLocation get(String key, int width, int height, Supplier<int @Nullable []> drawer) {
    //?} else {
    @Nullable
    private static Identifier get(String key, int width, int height, Supplier<int @Nullable []> drawer) {
    //?}
        Slot slot = SLOTS.get(key);
        if (slot != null) {
            return slot.location;
        }
        if (pending >= MAX_PENDING) {
            return null;
        }
        Slot created = new Slot();
        SLOTS.put(key, created);
        pending++;
        Minecraft minecraft = Minecraft.getInstance();
        ClientIoExecutor.supplyAsync(drawer::get)
                .whenComplete((pixels, error) -> minecraft.execute(() -> {
                    pending--;
                    if (created.removed) {
                        return;
                    }
                    if (error instanceof RejectedExecutionException) {
                        // The workers were busy: forget the slot, so the next frame asks again
                        SLOTS.remove(key, created);
                        return;
                    }
                    if (pixels == null) {
                        created.failed = true;
                        return;
                    }
                    created.location = upload(key, pixels, width, height);
                    created.failed = created.location == null;
                    created.pixels = created.failed ? null : pixels;
                    trim();
                }));
        trim();
        return null;
    }

    /** Draws the bust from the catalogued skin; {@code null} when the skin cannot be read. */
    private static int @Nullable [] draw(String hash, boolean slim, int width, int height) {
        BufferedImage image = readSkin(hash);
        if (image == null) {
            return null;
        }
        try {
            int[] skin = image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
            return SkinBustRasterizer.render(skin, image.getWidth(), image.getHeight(), slim, width, height);
        } catch (IllegalArgumentException e) {
            QuickSkinInfo.LOGGER.debug("Unable to draw the list bust of {}", hash, e);
            return null;
        }
    }

    /** The catalogued skin, decoded; {@code null} when it cannot be read. */
    @Nullable
    private static BufferedImage readSkin(String hash) {
        try {
            byte[] png = LocalAssetManager.getInstance().loadTexture(hash, TextureQuality.PREVIEW);
            return png == null ? null : SafeImageReader.readPng(png);
        } catch (java.io.IOException | IllegalArgumentException e) {
            QuickSkinInfo.LOGGER.debug("Unable to read the skin {}", hash, e);
            return null;
        }
    }

    /**
     * The face and the hat over it, eight by eight pixels, from a loaded skin texture's pixels;
     * {@code null} when the texture keeps none or is not a skin layout.
     */
    //? if <1.21.11 {
    private static int @Nullable [] face(ResourceLocation skin) {
    //?} else {
    private static int @Nullable [] face(Identifier skin) {
    //?}
        if (!(Minecraft.getInstance().getTextureManager().getTexture(skin) instanceof DynamicTexture dynamic)) {
            return null;
        }
        NativeImage image = dynamic.getPixels();
        if (image == null || image.getWidth() < 64 || image.getWidth() % 64 != 0
                || image.getHeight() < image.getWidth() / 2) {
            return null;
        }
        int texel = image.getWidth() / 64;
        int[] face = new int[64];
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                // MinecraftCompat reads ABGR; compositing and greying treat red and blue alike
                int base = MinecraftCompat.INSTANCE.getPixel(image, (8 + x) * texel, (8 + y) * texel) | 0xFF000000;
                int hat = MinecraftCompat.INSTANCE.getPixel(image, (40 + x) * texel, (8 + y) * texel);
                face[y * 8 + x] = SkinBustRasterizer.over(hat, base);
            }
        }
        return face;
    }

    /**
     * Grey, a little darker than the colours' brightness, keeping each pixel's alpha. Green weighs
     * double and red and blue the same, so it reads ARGB and ABGR alike.
     */
    private static int[] gray(int[] pixels) {
        for (int i = 0; i < pixels.length; i++) {
            int pixel = pixels[i];
            int value = ((pixel >> 16 & 0xFF) + 2 * (pixel >> 8 & 0xFF) + (pixel & 0xFF)) * 3 / 16;
            pixels[i] = (pixel & 0xFF000000) | value << 16 | value << 8 | value;
        }
        return pixels;
    }

    //? if <1.21.11 {
    @Nullable
    private static ResourceLocation upload(String key, int[] pixels, int width, int height) {
        ResourceLocation location = null;
    //?} else {
    @Nullable
    private static Identifier upload(String key, int[] pixels, int width, int height) {
        Identifier location = null;
    //?}
        NativeImage image = new NativeImage(width, height, true);
        DynamicTexture texture = null;
        try {
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int argb = pixels[y * width + x];
                    // MinecraftCompat takes the pixel as ABGR
                    int abgr = (argb & 0xFF00FF00) | (argb & 0xFF) << 16 | (argb >> 16) & 0xFF;
                    MinecraftCompat.INSTANCE.setPixel(image, x, y, abgr);
                }
            }
            texture = MinecraftTextureUploads.create(() -> "quickskin_bust_" + key, image);
            String path = "bust/" + key.toLowerCase(Locale.ROOT);
            //? if <1.21 {
            location = new ResourceLocation(QuickSkinInfo.MOD_ID, path);
            //?} else if <1.21.11 {
            location = ResourceLocation.fromNamespaceAndPath(QuickSkinInfo.MOD_ID, path);
            //?} else {
            location = Identifier.fromNamespaceAndPath(QuickSkinInfo.MOD_ID, path);
            //?}
            Minecraft.getInstance().getTextureManager().register(location, texture);
            return location;
        } catch (RuntimeException e) {
            QuickSkinInfo.LOGGER.debug("Unable to upload the list bust {}", key, e);
            if (texture != null) {
                texture.close();
            } else {
                image.close();
            }
            return null;
        }
    }

    /** Releases the least recently shown busts beyond the limit. */
    private static void trim() {
        Iterator<Map.Entry<String, Slot>> iterator = SLOTS.entrySet().iterator();
        while (SLOTS.size() > MAX_TEXTURES && iterator.hasNext()) {
            Slot slot = iterator.next().getValue();
            iterator.remove();
            slot.removed = true;
            if (slot.location != null) {
                Minecraft.getInstance().getTextureManager().release(slot.location);
            }
        }
    }
}
