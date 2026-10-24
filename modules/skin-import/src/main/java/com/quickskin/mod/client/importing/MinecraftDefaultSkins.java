package com.quickskin.mod.client.importing;

import com.quickskin.mod.common.util.SafeImageReader;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
//? if <1.21.11 {
import net.minecraft.resources.ResourceLocation;
//?} else {
import net.minecraft.resources.Identifier;
//?}
import org.jetbrains.annotations.Nullable;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The skins Minecraft gives an account that has none of its own, as the game ships them. Mojang
 * serves such an account one of these, the same pixels; it is no skin the player chose.
 */
@Environment(EnvType.CLIENT)
final class MinecraftDefaultSkins {
    private static final String[] NAMES = {"alex", "ari", "efe", "kai", "makena", "noor", "steve", "sunny", "zuri"};
    private static final int SIZE = 64;
    /** The default skins' pixels, read from the game's jar the first time they are needed. */
    @Nullable
    private static volatile List<int[]> defaults;

    private MinecraftDefaultSkins() {
    }

    /** Whether {@code image} is one of Minecraft's default skins; false whenever that cannot be told. */
    static boolean matches(@Nullable BufferedImage image) {
        try {
            return matches(image, defaults());
        } catch (RuntimeException | LinkageError unknown) {
            return false;
        }
    }

    /** Whether {@code image} has the pixels of one of {@code skins}; fully transparent pixels compare equal. */
    static boolean matches(@Nullable BufferedImage image, List<int[]> skins) {
        if (image == null || image.getWidth() != SIZE || image.getHeight() != SIZE) {
            return false;
        }
        int[] pixels = pixels(image);
        for (int[] skin : skins) {
            if (Arrays.equals(skin, pixels)) {
                return true;
            }
        }
        return false;
    }

    /** A 64x64 image's ARGB pixels, every fully transparent pixel as 0. */
    static int[] pixels(BufferedImage image) {
        int[] argb = image.getRGB(0, 0, SIZE, SIZE, null, 0, SIZE);
        for (int i = 0; i < argb.length; i++) {
            if ((argb[i] >>> 24) == 0) {
                argb[i] = 0;
            }
        }
        return argb;
    }

    private static List<int[]> defaults() {
        List<int[]> known = defaults;
        if (known != null) {
            return known;
        }
        List<int[]> read = new ArrayList<>();
        for (String model : new String[]{"slim", "wide"}) {
            for (String name : NAMES) {
                int[] skin = read("textures/entity/player/" + model + "/" + name + ".png");
                if (skin != null) {
                    read.add(skin);
                }
            }
        }
        List<int[]> skins = List.copyOf(read);
        // Kept once all of them are read; a later check may read what this one could not
        if (skins.size() == 2 * NAMES.length) {
            defaults = skins;
        }
        return skins;
    }

    /**
     * One of the game's own textures as its jar holds it, which is what Mojang serves: a resource
     * pack's replacement is not. The resources stand in only once they are loaded, since the
     * start-up check can run before the game has any.
     */
    @Nullable
    private static int[] read(String path) {
        try (InputStream input = open(path)) {
            if (input == null) {
                return null;
            }
            BufferedImage image = SafeImageReader.readPng(input);
            return image != null && image.getWidth() == SIZE && image.getHeight() == SIZE ? pixels(image) : null;
        } catch (Exception unreadable) {
            return null;
        }
    }

    @Nullable
    private static InputStream open(String path) throws java.io.IOException {
        InputStream bundled = Minecraft.class.getResourceAsStream("/assets/minecraft/" + path);
        if (bundled != null) {
            return bundled;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.getResourceManager() == null) {
            return null;
        }
//? if <1.21 {
        ResourceLocation id = new ResourceLocation("minecraft", path);
//?} else if <1.21.11 {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("minecraft", path);
//?} else {
        Identifier id = Identifier.fromNamespaceAndPath("minecraft", path);
//?}
        var resource = minecraft.getResourceManager().getResource(id);
        return resource.isPresent() ? resource.get().open() : null;
    }
}
