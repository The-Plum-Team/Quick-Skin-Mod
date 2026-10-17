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

/**
 * The 3D busts of the skin list's rows, as textures.
 *
 * <p>A bust is drawn by {@link SkinBustRasterizer} on the client I/O workers, from the skin's
 * catalogued texture, and uploaded on the render thread. Each is made once per skin, arm width,
 * transparency setting and pixel size, and kept in a small least-recently-used set whose evicted
 * textures are released. Until its texture is ready a row shows nothing in the bust's place.
 */
@Environment(EnvType.CLIENT)
public final class SkinBustTextures {

    /** Busts kept as textures: a few screens of rows, at about 70 KiB each at GUI scale 3. */
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
        boolean failed;
        boolean removed;
    }

    /**
     * The bust of a skin at this many pixels, or {@code null} while it is being drawn or when it
     * cannot be. Call from the render thread.
     */
    //? if <1.21.11 {
    @Nullable
    public static ResourceLocation get(String hash, boolean slim, int width, int height) {
    //?} else {
    @Nullable
    public static Identifier get(String hash, boolean slim, int width, int height) {
    //?}
        if (width < 1 || height < 1 || width > SkinBustRasterizer.MAX_SIZE || height > SkinBustRasterizer.MAX_SIZE) {
            return null;
        }
        boolean opaque = ClientConfig.getInstance().shouldDisableSkinTransparency();
        String key = hash + (slim ? "_slim" : "_wide") + (opaque ? "_opaque" : "") + "_" + width + "x" + height;
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
        ClientIoExecutor.supplyAsync(() -> draw(hash, slim, width, height))
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
                    trim();
                }));
        trim();
        return null;
    }

    /** Draws the bust from the catalogued skin; {@code null} when the skin cannot be read. */
    private static int @Nullable [] draw(String hash, boolean slim, int width, int height) {
        try {
            byte[] png = LocalAssetManager.getInstance().loadTexture(hash, TextureQuality.PREVIEW);
            if (png == null) {
                return null;
            }
            BufferedImage image = SafeImageReader.readPng(png);
            if (image == null) {
                return null;
            }
            int[] skin = image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
            return SkinBustRasterizer.render(skin, image.getWidth(), image.getHeight(), slim, width, height);
        } catch (java.io.IOException | IllegalArgumentException e) {
            QuickSkinInfo.LOGGER.debug("Unable to draw the list bust of {}", hash, e);
            return null;
        }
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
