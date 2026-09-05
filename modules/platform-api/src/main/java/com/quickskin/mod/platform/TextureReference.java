package com.quickskin.mod.platform;

/**
 * Immutable texture identity shared by features and Minecraft adapters.
 * Implementations may retain a native resource identifier, but never expose it through the API.
 * This reference does not own GPU resources; texture registration retains that lifetime.
 */
public interface TextureReference {
    String namespace();

    String path();
}
