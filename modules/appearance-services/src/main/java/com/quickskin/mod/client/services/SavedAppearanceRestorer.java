package com.quickskin.mod.client.services;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.util.UUID;

/** Applies the saved local appearance after the composition root admits a joined session. */
@Environment(EnvType.CLIENT)
public final class SavedAppearanceRestorer {
    private SavedAppearanceRestorer() {
    }

    public static void restore(UUID targetPlayerId) {
        com.quickskin.mod.config.ClientConfig config = com.quickskin.mod.config.ClientConfig.getInstance();
        com.quickskin.mod.client.services.LocalAssetManager assetManager =
                com.quickskin.mod.client.services.LocalAssetManager.getInstance();
        //? if >=1.21 {

        String skinId = null;
        String modelType = null;
        String capeId = null;
        //?}

        // Check if there's a saved skin
        if (!config.activeSkinHash.isEmpty()) {
            com.quickskin.mod.common.data.AssetMetadata metadata = assetManager.getMetadata(config.activeSkinHash);

            if (metadata != null) {
                //? if <1.21 {
                String skinId = "local_skin:" + metadata.hash();
                String modelType = assetManager.getSkinModelPreference(config.activeSkinHash);
                com.quickskin.mod.client.services.PlayerAppearanceService.getInstance()
                        .applySkin(targetPlayerId, skinId, modelType);
                //?} else {
                // Prepare the saved skin with the saved model type preference for this skin
                skinId = "local_skin:" + metadata.hash();
                modelType = assetManager.getSkinModelPreference(config.activeSkinHash);
                //?}
            }
        } else if (!config.playerOwnSkinHash.isEmpty() && config.activeCpmModelHash.isEmpty()) {
            // No skin selected, but player's own skin exists - auto-select it
            com.quickskin.mod.common.data.AssetMetadata metadata = assetManager.getMetadata(config.playerOwnSkinHash);

            if (metadata != null) {
                // Auto-select and apply the player's own skin
                config.activeSkinHash = config.playerOwnSkinHash;
                config.save();

                //? if <1.21 {
                String skinId = "local_skin:" + metadata.hash();
                String modelType = assetManager.getSkinModelPreference(config.playerOwnSkinHash);
                //?} else {
                skinId = "local_skin:" + metadata.hash();
                modelType = assetManager.getSkinModelPreference(config.playerOwnSkinHash);
                //?}

                // If auto mode, use the detected model from the skin
                if ("auto".equals(modelType)) {
                    modelType = metadata.skinModel();
                }
                //? if <1.21 {
                com.quickskin.mod.client.services.PlayerAppearanceService.getInstance()
                        .applySkin(targetPlayerId, skinId, modelType);
                //?}
            }
        }

        // Check if there's a saved cape
        if (!config.activeCapeHash.isEmpty()) {
            //? if <1.21 {
            String capeId = config.activeCapeHash;
            //?} else {
            capeId = config.activeCapeHash;
        }
            //?}

        //? if >=1.21 {
        // Apply both skin and cape together in a single call to avoid multiple syncs
        if (skinId != null || capeId != null) {
        //?}
            com.quickskin.mod.client.services.PlayerAppearanceService.getInstance()
                    //? if <1.21 {
                    .applyCape(targetPlayerId, capeId);
                    //?} else {
                    .applyLook(targetPlayerId, skinId, capeId, modelType);
                    //?}
        }
    }

}
