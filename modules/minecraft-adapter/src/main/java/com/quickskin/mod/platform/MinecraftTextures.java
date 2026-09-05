package com.quickskin.mod.platform;

//? if <1.21.11 {
import net.minecraft.resources.ResourceLocation;
//?} else {
import net.minecraft.resources.Identifier;
//?}

/** Minecraft boundary for resource identifiers; domain objects retain only TextureReference. */
public final class MinecraftTextures {
    private MinecraftTextures() {
    }

    //? if <1.21.11 {
    public static TextureReference reference(ResourceLocation location) {
    //?} else {
    public static TextureReference reference(Identifier location) {
    //?}
        return location == null ? null : new Reference(location);
    }

    //? if <1.21.11 {
    public static ResourceLocation location(TextureReference reference) {
    //?} else {
    public static Identifier location(TextureReference reference) {
    //?}
        if (reference == null) return null;
        // Preserve the existing allocation-free cached lookup on the render path.
        if (reference instanceof Reference nativeReference) return nativeReference.location();
        //? if <1.21 {
        return new ResourceLocation(reference.namespace(), reference.path());
        //?} else if <1.21.11 {
        return ResourceLocation.fromNamespaceAndPath(reference.namespace(), reference.path());
        //?} else {
        return Identifier.fromNamespaceAndPath(reference.namespace(), reference.path());
        //?}
    }

    //? if <1.21.11 {
    private record Reference(ResourceLocation location) implements TextureReference {
    //?} else {
    private record Reference(Identifier location) implements TextureReference {
    //?}
        @Override
        public String namespace() {
            return location.getNamespace();
        }

        @Override
        public String path() {
            return location.getPath();
        }
    }
}
