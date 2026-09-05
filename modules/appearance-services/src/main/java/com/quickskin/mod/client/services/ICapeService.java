package com.quickskin.mod.client.services;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
//? if <1.21.11 {
import net.minecraft.resources.ResourceLocation;
//?} else {
import net.minecraft.resources.Identifier;
//?}

import org.jetbrains.annotations.Nullable;
import java.util.UUID;

/**
 * Service interface for managing player capes
 */
@Environment(EnvType.CLIENT)
public interface ICapeService {

    /**
     * Gets the Identifier for a player's cape
     * @param playerId The player's UUID
     * @param capeId The cape ID (e.g., "local_cape:hash", "known:minecon2016", or "username")
     * @return The Identifier for the cape, or null if not available
     */
    @Nullable
    //? if <1.21.11 {
    ResourceLocation getCapeLocation(UUID playerId, String capeId);
    //?} else {
    Identifier getCapeLocation(UUID playerId, String capeId);
    //?}

    /**
     * Loads a cape from the Mojang API
     * @param username The player's username
     * @return The Identifier for the cape, or null if failed
     */
    @Nullable
    //? if <1.21.11 {
    ResourceLocation loadMojangCape(String username);
    //?} else {
    Identifier loadMojangCape(String username);
    //?}

    /**
     * Loads a local cape from storage
     * @param hash The cape's hash
     * @return The Identifier for the cape, or null if not found
     */
    @Nullable
    //? if <1.21.11 {
    ResourceLocation loadLocalCape(String hash);
    //?} else {
    Identifier loadLocalCape(String hash);
    //?}

    /**
     * Loads a known cape (e.g., Minecon capes)
     * @param capeId The known cape ID (e.g., "minecon2016")
     * @return The Identifier for the cape, or null if not found
     */
    @Nullable
    //? if <1.21.11 {
    ResourceLocation loadKnownCape(String capeId);
    //?} else {
    Identifier loadKnownCape(String capeId);
    //?}

    /**
     * Checks if a cape is animated
     * @param capeId The cape ID
     * @return true if the cape is animated
     */
    boolean isAnimated(String capeId);

}
