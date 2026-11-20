package com.quickskin.mod.client.immersive;

/**
 * A screen that opens the immersive view in a world: the camera leaves the player's eyes to look
 * at the player, and the screen floats round them instead of covering the world.
 */
public interface ImmersiveScreen {
    /** Where the player stands in this screen and which of its parts swing; read every frame. */
    ImmersiveLayout immersiveLayout();

    /**
     * How far the player turns away from the camera while this screen is shown, in degrees: 0
     * faces the camera, about 180 shows the player's back, where a cape is. Read every frame; the
     * player turns there smoothly, and back again under a screen that asks for less.
     */
    default float immersivePlayerTurn() {
        return 0.0f;
    }
}
