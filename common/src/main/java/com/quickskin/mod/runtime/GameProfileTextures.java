package com.quickskin.mod.runtime;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.quickskin.mod.server.vanilla.SignedTextures;
//? if >=1.21.9 {
import com.google.common.collect.ImmutableMultimap;
import com.mojang.authlib.properties.PropertyMap;
import java.util.Map;
//?}

import java.util.Collection;

/** Reads and replaces the signed {@code textures} property of a vanilla profile. */
public final class GameProfileTextures {
    public static final String TEXTURES = "textures";

    private GameProfileTextures() {
    }

    /** The profile's signed textures, or {@code null} when it has none, several or an unsigned one. */
    public static SignedTextures read(GameProfile profile) {
        if (profile == null) return null;
        //? if <1.21.9 {
        Collection<Property> values = profile.getProperties().get(TEXTURES);
        //?} else {
        Collection<Property> values = profile.properties().get(TEXTURES);
        //?}
        if (values.size() != 1) return null;
        Property property = values.iterator().next();
        try {
            //? if <1.21 {
            return new SignedTextures(property.getValue(), property.getSignature());
            //?} else {
            return new SignedTextures(property.value(), property.signature());
            //?}
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    /**
     * Returns the profile carrying exactly {@code textures} as its one {@code textures} property and
     * every other property unchanged. Before authlib 7 the profile is mutable and is updated in
     * place; from Minecraft 1.21.9 a new profile is returned and the caller must install it.
     */
    public static GameProfile withTextures(GameProfile profile, SignedTextures textures) {
        if (profile == null || textures == null) throw new IllegalArgumentException("Missing profile or textures");
        Property replacement = new Property(TEXTURES, textures.value(), textures.signature());
        //? if <1.21.9 {
        profile.getProperties().removeAll(TEXTURES);
        profile.getProperties().put(TEXTURES, replacement);
        return profile;
        //?} else {
        ImmutableMultimap.Builder<String, Property> properties = ImmutableMultimap.builder();
        for (Map.Entry<String, Property> entry : profile.properties().entries()) {
            if (!TEXTURES.equals(entry.getKey())) properties.put(entry.getKey(), entry.getValue());
        }
        properties.put(TEXTURES, replacement);
        return new GameProfile(profile.id(), profile.name(), new PropertyMap(properties.build()));
        //?}
    }
}
