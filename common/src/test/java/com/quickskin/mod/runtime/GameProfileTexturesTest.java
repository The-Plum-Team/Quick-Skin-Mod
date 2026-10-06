package com.quickskin.mod.runtime;

import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Multimap;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import com.quickskin.mod.server.vanilla.SignedTextures;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Runs against every matrix target's authlib (4 for 1.20.1, 6 for 1.21.1 to 1.21.8, 7 and later
 * from 1.21.9, where profiles are immutable records); every version returns a new profile. This source is not preprocessed, so the
 * version-specific profile API is reached by reflection here.
 */
class GameProfileTexturesTest {
    private static final UUID PLAYER = UUID.fromString("0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0");
    private static final String SIGNATURE = Base64.getEncoder().encodeToString(new byte[512]);

    @Test
    void replacesTheTexturesPropertyAndKeepsEveryOtherProperty() throws Exception {
        GameProfile original = profile(new Property("textures", value("old"), SIGNATURE),
                new Property("custom", "kept"));
        SignedTextures fresh = new SignedTextures(value("new"), SIGNATURE);

        GameProfile updated = GameProfileTextures.withTextures(original, fresh);

        assertEquals(fresh, GameProfileTextures.read(updated));
        assertEquals(1, properties(updated).get("textures").size());
        assertEquals(1, properties(updated).get("custom").size());
        assertEquals(PLAYER, id(updated));
        assertEquals("Tester", name(updated));
    }

    @Test
    void neverModifiesTheProfileItWasGiven() throws Exception {
        Property old = new Property("textures", value("old"), SIGNATURE);
        GameProfile original = profile(old, new Property("custom", "kept"));

        GameProfile updated = GameProfileTextures.withTextures(
                original, new SignedTextures(value("new"), SIGNATURE));

        assertNotSame(original, updated, "a queued packet may still be encoding the original");
        assertEquals(new SignedTextures(value("old"), SIGNATURE), GameProfileTextures.read(original));
        assertEquals(2, properties(original).size());
        assertEquals("Tester", GameProfileTextures.name(updated));
    }

    @Test
    void addsTexturesToAProfileThatHadNone() throws Exception {
        SignedTextures fresh = new SignedTextures(value("first"), SIGNATURE);

        GameProfile updated = GameProfileTextures.withTextures(profile(), fresh);

        assertEquals(fresh, GameProfileTextures.read(updated));
    }

    @Test
    void replacingTwiceNeverAccumulatesProperties() throws Exception {
        GameProfile profile = profile();
        profile = GameProfileTextures.withTextures(profile, new SignedTextures(value("a"), SIGNATURE));
        SignedTextures second = new SignedTextures(value("b"), SIGNATURE);
        profile = GameProfileTextures.withTextures(profile, second);

        assertEquals(1, properties(profile).get("textures").size());
        assertEquals(second, GameProfileTextures.read(profile));
    }

    @Test
    void unsignedOrMissingTexturesReadAsNone() throws Exception {
        assertNull(GameProfileTextures.read(profile()));
        assertNull(GameProfileTextures.read(profile(new Property("textures", value("unsigned")))));
        assertNull(GameProfileTextures.read(null));
        assertThrows(IllegalArgumentException.class,
                () -> GameProfileTextures.withTextures(profile(), null));
    }

    private static String value(String marker) {
        return Base64.getEncoder().encodeToString(
                ("{\"profileId\":\"x\",\"marker\":\"" + marker + "\"}").getBytes(StandardCharsets.UTF_8));
    }

    private static GameProfile profile(Property... properties) throws Exception {
        Multimap<String, Property> values = LinkedHashMultimap.create();
        for (Property property : properties) values.put(propertyName(property), property);
        try {
            // authlib 7 and later: an immutable record built from its properties.
            Constructor<PropertyMap> map = PropertyMap.class.getConstructor(Multimap.class);
            return GameProfile.class.getConstructor(UUID.class, String.class, PropertyMap.class)
                    .newInstance(PLAYER, "Tester", map.newInstance(values));
        } catch (NoSuchMethodException older) {
            GameProfile profile = GameProfile.class.getConstructor(UUID.class, String.class)
                    .newInstance(PLAYER, "Tester");
            properties(profile).putAll(values);
            return profile;
        }
    }

    @SuppressWarnings("unchecked")
    private static Multimap<String, Property> properties(GameProfile profile) throws Exception {
        return (Multimap<String, Property>) invoke(profile, "properties", "getProperties");
    }

    private static UUID id(GameProfile profile) throws Exception {
        return (UUID) invoke(profile, "id", "getId");
    }

    private static String name(GameProfile profile) throws Exception {
        return (String) invoke(profile, "name", "getName");
    }

    private static String propertyName(Property property) throws Exception {
        return (String) invoke(property, "name", "getName");
    }

    private static Object invoke(Object target, String modern, String legacy) throws Exception {
        try {
            return target.getClass().getMethod(modern).invoke(target);
        } catch (NoSuchMethodException ignored) {
            return target.getClass().getMethod(legacy).invoke(target);
        }
    }
}
