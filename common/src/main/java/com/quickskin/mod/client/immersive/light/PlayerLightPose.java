package com.quickskin.mod.client.immersive.light;

/** Carries a player's world light with copied poses through deferred feature rendering. */
public interface PlayerLightPose {
    PlayerFaceLight quickskin$faceLight();
    void quickskin$faceLight(PlayerFaceLight light);
}
