package com.quickskin.mod.client.gui.fan;

import com.mojang.blaze3d.platform.NativeImage;
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

import java.util.HashMap;
import java.util.Map;

/**
 * The favourites fan's painted surfaces, made on the render thread at the exact pixel size they
 * are shown at: the card's face, the skin's floor shadow, alone at rest and with a light behind
 * the skin when pointed at; and the vignette behind the fan. A card's size changes only with the
 * window, so a new size replaces the textures of the last one.
 */
@Environment(EnvType.CLIENT)
final class FanTextures {
    //? if <1.21.11 {
    private static final Map<String, ResourceLocation> TEXTURES = new HashMap<>();
    //?} else {
    private static final Map<String, Identifier> TEXTURES = new HashMap<>();
    //?}
    private static String cardSize = "";
    private static String backdropSize = "";

    private FanTextures() {
    }

    /** The card's face, {@code width} by {@code height} pixels, a GUI pixel being {@code unit} of them. */
    //? if <1.21.11 {
    @Nullable
    static ResourceLocation face(int width, int height, int unit, boolean hot) {
    //?} else {
    @Nullable
    static Identifier face(int width, int height, int unit, boolean hot) {
    //?}
        forgetOtherSizes(width, height, unit);
        return get("face_" + (hot ? "hot_" : "") + width + "x" + height, width, height,
                (x, y) -> facePixel(x, y, width, height, unit, hot));
    }

    /**
     * A vignette over the whole screen: its edges and corners darkened, its middle, where the fan
     * stands, clear; one texel per GUI pixel so its steps never show.
     */
    //? if <1.21.11 {
    @Nullable
    static ResourceLocation backdrop(int width, int height) {
    //?} else {
    @Nullable
    static Identifier backdrop(int width, int height) {
    //?}
        String size = width + "x" + height;
        if (!size.equals(backdropSize)) {
            backdropSize = size;
            release("backdrop");
        }
        return get("backdrop", width, height, (x, y) -> {
            double dx = ((x + 0.5) / width * 2 - 1) * 0.85;
            double dy = (y + 0.5) / height * 2 - 1;
            return argb(0.72 * smooth(0.45, 1.35, Math.sqrt(dx * dx + dy * dy)), 6, 4, 14);
        });
    }

    private static void release(String key) {
        var location = TEXTURES.remove(key);
        if (location != null) Minecraft.getInstance().getTextureManager().release(location);
    }

    static void reset() {
        for (var location : TEXTURES.values()) {
            if (location != null) Minecraft.getInstance().getTextureManager().release(location);
        }
        TEXTURES.clear();
        cardSize = "";
        backdropSize = "";
    }

    private static void forgetOtherSizes(int width, int height, int unit) {
        String size = width + "x" + height + "@" + unit;
        if (size.equals(cardSize)) return;
        cardSize = size;
        var iterator = TEXTURES.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (!entry.getKey().equals("backdrop")) {
                if (entry.getValue() != null) Minecraft.getInstance().getTextureManager().release(entry.getValue());
                iterator.remove();
            }
        }
    }

    private interface Painter {
        int argb(int x, int y);
    }

    //? if <1.21.11 {
    @Nullable
    private static ResourceLocation get(String key, int width, int height, Painter painter) {
    //?} else {
    @Nullable
    private static Identifier get(String key, int width, int height, Painter painter) {
    //?}
        if (TEXTURES.containsKey(key)) return TEXTURES.get(key);
        var location = width < 1 || height < 1 || width > 2048 || height > 2048 ? null : upload(key, width, height, painter);
        // A texture that cannot be made is not tried again every frame
        TEXTURES.put(key, location);
        return location;
    }

    // ---- Painting ----

    /**
     * What stands behind a skin, there being no card: its soft shadow on the floor and, when it is
     * pointed at, a lilac light that fades out from behind it.
     */
    private static int facePixel(int x, int y, int width, int height, int unit, boolean hot) {
        double u = (x + 0.5) / width, v = (y + 0.5) / height;
        double sx = (u - 0.5) / 0.32, sy = (v - 0.79) / 0.04;
        double shadow = 1 - smooth(0.2, 1.0, Math.sqrt(sx * sx + sy * sy));
        if (!hot) return argb(shadow * 0.55, 0, 0, 0);
        double lx = (u - 0.5) / 0.62, ly = (v - 0.45) / 0.62;
        double light = 1 - smooth(0.0, 1.0, Math.sqrt(lx * lx + ly * ly));
        double alpha = 0.55 * light * light;
        double r = lerp(140, 230, light), g = lerp(108, 220, light), b = lerp(232, 255, light);
        if (shadow > 0.05) {
            double dark = 1 - shadow;
            return argb(Math.max(alpha, shadow * 0.55), r * dark, g * dark, b * dark);
        }
        return argb(alpha, r, g, b);
    }

    private static double smooth(double from, double to, double value) {
        double t = Math.max(0, Math.min(1, (value - from) / (to - from)));
        return t * t * (3 - 2 * t);
    }

    private static double lerp(double a, double b, double k) {
        return a + (b - a) * k;
    }

    private static int argb(double alpha, double r, double g, double b) {
        return channel(alpha * 255) << 24 | channel(r) << 16 | channel(g) << 8 | channel(b);
    }

    private static int channel(double value) {
        return (int) Math.max(0, Math.min(255, Math.round(value)));
    }

    //? if <1.21.11 {
    @Nullable
    private static ResourceLocation upload(String key, int width, int height, Painter painter) {
        ResourceLocation location;
    //?} else {
    @Nullable
    private static Identifier upload(String key, int width, int height, Painter painter) {
        Identifier location;
    //?}
        NativeImage image = new NativeImage(width, height, true);
        DynamicTexture texture = null;
        try {
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int argb = painter.argb(x, y);
                    // MinecraftCompat takes the pixel as ABGR
                    int abgr = (argb & 0xFF00FF00) | (argb & 0xFF) << 16 | (argb >> 16) & 0xFF;
                    MinecraftCompat.INSTANCE.setPixel(image, x, y, abgr);
                }
            }
            texture = MinecraftTextureUploads.create(() -> "quickskin_fan_" + key, image);
            String path = "fan/" + key;
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
            QuickSkinInfo.LOGGER.debug("Unable to make the favourites fan's texture {}", key, e);
            if (texture != null) {
                texture.close();
            } else {
                image.close();
            }
            return null;
        }
    }
}
