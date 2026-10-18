package com.quickskin.mod.client.importing;

import com.quickskin.mod.client.services.LocalAssetManager;
import com.quickskin.mod.client.services.MojangApiService;
import com.quickskin.mod.common.data.AssetMetadata;
import com.quickskin.mod.platform.QuickSkinInfo;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;

import java.awt.image.BufferedImage;

/** Owns the local Mojang-skin import and its client-lifetime asynchronous work. */
@Environment(EnvType.CLIENT)
public final class PlayerOwnSkinBootstrap {
    private static volatile boolean closed;
    private static java.util.concurrent.CompletableFuture<?> playerOwnSkinTask;
    private static volatile boolean playerOwnSkinBootstrapped;

    private PlayerOwnSkinBootstrap() {
    }

    public static synchronized void initialize() {
        closed = false;
    }

    /** Stops the import before the composition root closes its stores and services. */
    public static synchronized void close() {
        closed = true;
        if (playerOwnSkinTask != null) {
            playerOwnSkinTask.cancel(true);
            playerOwnSkinTask = null;
        }
    }

    /**
     * Ensure player's own skin exists in the list
     * Downloads it from Mojang if not present
     * Can be called at any time (even before joining a world)
     *
     * <p>Idempotent and cheap to call repeatedly: it runs at most one bootstrap per client
     * session. Client entry points do not agree on when the session user becomes readable -
     * FML constructs mods before {@link Minecraft} exists, so the very first attempt has no
     * user to look up - therefore the attempt stays pending instead of being consumed, and the
     * client tick retries it as soon as the session is available.
     */
    public static void ensurePlayerOwnSkinExists() {
        if (playerOwnSkinBootstrapped || closed) {
            return;
        }
        startPlayerOwnSkinBootstrap();
    }

    private static synchronized void startPlayerOwnSkinBootstrap() {
        if (playerOwnSkinBootstrapped || closed) {
            return;
        }

        com.quickskin.mod.config.ClientConfig config = com.quickskin.mod.config.ClientConfig.getInstance();
        if (!config.enablePlayerOwnSkinSystem) {
            playerOwnSkinBootstrapped = true;
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.getUser() == null) {
            // No session yet; leave the bootstrap pending for the next client tick.
            return;
        }

        String playerName = minecraft.getUser().getName();
        playerOwnSkinBootstrapped = true;

        // The own skin belongs to the launcher account it was downloaded for. One that predates
        // this record goes to the first account seen; another account downloads its own.
        String account = com.quickskin.mod.config.AccountSkinPreferences.key(
                minecraft.getUser().getProfileId(), playerName);
        if (account != null && !account.equals(config.playerOwnSkinAccount)) {
            if (!config.playerOwnSkinAccount.isEmpty()) {
                config.playerOwnSkinHash = "";
                config.playerOwnSkinTexture = "";
            }
            config.playerOwnSkinAccount = config.playerOwnSkinHash.isEmpty() ? "" : account;
        }

        // Download the account's skin on every start (async, won't block startup), even when an
        // own skin is already listed: Mojang may serve another one by now, changed elsewhere or
        // retouched by Mojang after an upload, and the own skin is the one other players see.
        playerOwnSkinTask = com.quickskin.mod.client.services.MojangApiService.getInstance()
                .fetchSkinByUsername(playerName)
                .thenAccept(skinData -> {
                    if (!closed && minecraft != null) {
                        minecraft.execute(() -> {
                            if (!closed && skinData != null) {
                                handlePlayerOwnSkinFetched(skinData, account, null);
                            }
                        });
                    }
                })
                .exceptionally(throwable -> {
                    Throwable cause = throwable instanceof java.util.concurrent.CompletionException
                            && throwable.getCause() != null ? throwable.getCause() : throwable;
                    if (!(cause instanceof java.util.concurrent.CancellationException)
                            && MojangApiService.findFailure(throwable) == null) {
                        QuickSkinInfo.LOGGER.warn("Could not download the local player's Mojang skin", throwable);
                    }
                    return null;
                });
    }

    /**
     * Handle the fetched player's own skin data
     * Smart mode: checks if skin already exists before saving a duplicate
     */
    private static void handlePlayerOwnSkinFetched(
            com.quickskin.mod.client.services.MojangApiService.MojangSkinData skinData, String account,
            @org.jetbrains.annotations.Nullable String replacedSkin) {
        try {
            // The same Mojang texture as the listed own skin: nothing changed. Its stored copy
            // may have been flattened by the transparency setting, so the texture's address is
            // compared rather than a hash of the pixels.
            com.quickskin.mod.config.ClientConfig ownConfig = com.quickskin.mod.config.ClientConfig.getInstance();
            boolean wearsReplaced = replacedSkin != null && replacedSkin.equals(ownConfig.activeSkinHash);
            if (!wearsReplaced && !skinData.textureUrl.isEmpty()
                    && skinData.textureUrl.equals(ownConfig.playerOwnSkinTexture)
                    && LocalAssetManager.getInstance().getMetadata(ownConfig.playerOwnSkinHash) != null) {
                return;
            }

            // Process the image to get its final form before hashing and saving.
            // This ensures the hash we check against is the same as the one that will be generated from the saved file.
            BufferedImage image = skinData.image;

            // Convert legacy 64x32 skins to modern 64x64 format
            if (image.getHeight() == image.getWidth() / 2) {
                image = com.quickskin.mod.common.util.HDTextureProcessor.convertLegacyToModern(image);
            }

            // Apply transparency settings if needed
            if (com.quickskin.mod.config.ClientConfig.getInstance().shouldDisableSkinTransparency()) {
                image = com.quickskin.mod.common.util.HDTextureProcessor.removeTransparency(image);
            }

            // Convert the (potentially modified) image to a byte array to compute its definitive hash.
            byte[] processedImageBytes = com.quickskin.mod.common.util.HDTextureProcessor.imageToPng(image);
            if (processedImageBytes == null) {
                return;
            }

            String finalHash = com.quickskin.mod.common.util.HashUtil.computeAssetContentId(
                    processedImageBytes, "skin");
            if (finalHash == null) {
                return;
            }

            LocalAssetManager assetManager = LocalAssetManager.getInstance();
            com.quickskin.mod.config.ClientConfig config = com.quickskin.mod.config.ClientConfig.getInstance();
            String previousOwnSkin = config.playerOwnSkinHash;
            if (!wearsReplaced && finalHash.equals(previousOwnSkin) && assetManager.getMetadata(finalHash) != null) {
                // Mojang still serves the listed own skin; remember which texture it is
                config.playerOwnSkinTexture = skinData.textureUrl;
                config.save();
                return;
            }
            AssetMetadata existingMetadata = assetManager.getMetadata(finalHash);

            if (existingMetadata == null) {
                java.nio.file.Path saved = com.quickskin.mod.client.gui.util.SkinImporter
                        .saveSkinImage(image, skinData.username);
                if (saved == null) return;

                // Reload assets to recognize the new file.
                assetManager.reload();
                if (assetManager.getMetadata(finalHash) == null) {
                    QuickSkinInfo.LOGGER.warn("Downloaded Mojang skin was saved with an unexpected content hash");
                    return;
                }
            }

            // Now that the skin is guaranteed to be in the asset manager, set its hash in the config.
            // A previous own skin stays in the list as an ordinary skin.
            config.playerOwnSkinHash = finalHash;
            config.playerOwnSkinTexture = skinData.textureUrl;
            config.playerOwnSkinAccount = account != null ? account : "";

            // Whoever wore the previous own skin wears the new one: it is the same account skin
            // as Mojang now serves it. So does whoever wore the skin just uploaded, which Mojang
            // may have retouched.
            boolean woreOwnSkin = config.activeCpmModelHash.isEmpty() && !config.activeSkinHash.equals(finalHash)
                    && (config.activeSkinHash.equals(previousOwnSkin) || config.activeSkinHash.equals(replacedSkin))
                    && !config.activeSkinHash.isEmpty();
            String accountSelection = account != null ? config.accountSkins.skinHash(account) : "";
            if (!accountSelection.isEmpty() && !accountSelection.equals(finalHash)
                    && (accountSelection.equals(previousOwnSkin) || accountSelection.equals(replacedSkin))) {
                config.accountSkins.select(account, finalHash);
            }
            if ((config.activeSkinHash.isEmpty() && config.activeCpmModelHash.isEmpty()) || woreOwnSkin) {
                config.activeSkinHash = finalHash;

                // Apply it to the player if they're in a world.
                net.minecraft.client.player.LocalPlayer player = net.minecraft.client.Minecraft.getInstance().player;
                if (player != null) {
                    AssetMetadata metadata = assetManager.getMetadata(finalHash);
                    if (metadata != null) {
                        String skinId = "local_skin:" + finalHash;
                        String modelType = assetManager.getSkinModelPreference(finalHash);

                        com.quickskin.mod.client.services.PlayerAppearanceService.getInstance()
                                .applySkin(player.getUUID(), skinId, modelType);
                    }
                }
            }

            config.save();

        } catch (Exception e) {
            QuickSkinInfo.LOGGER.error("Could not import the local player's Mojang skin", e);
        }
    }

    /**
     * After a skin is uploaded to Mojang, downloads the texture Mojang kept for it and makes it
     * the own skin, as the start-up check would on the next launch. Mojang may retouch an
     * upload, so this is the skin other players see; whoever wore the uploaded skin wears it.
     * Runs from the upload's worker thread.
     */
    public static void adoptUploadedSkin(String textureUrl, String model, @org.jetbrains.annotations.Nullable AssetMetadata uploaded) {
        if (closed || !com.quickskin.mod.config.ClientConfig.getInstance().enablePlayerOwnSkinSystem) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.getUser() == null) {
            return;
        }
        String playerName = minecraft.getUser().getName();
        java.util.UUID profileId = minecraft.getUser().getProfileId();
        String account = com.quickskin.mod.config.AccountSkinPreferences.key(profileId, playerName);
        String uploadedHash = uploaded != null ? uploaded.hash() : null;
        MojangApiService.getInstance().downloadSkinImage(textureUrl)
                .thenAccept(image -> minecraft.execute(() -> {
                    if (!closed && image != null) {
                        handlePlayerOwnSkinFetched(new MojangApiService.MojangSkinData(
                                playerName, profileId, image, model, textureUrl), account, uploadedHash);
                    }
                }))
                .exceptionally(throwable -> {
                    QuickSkinInfo.LOGGER.warn("Could not download the skin Mojang kept after the upload: {}",
                            throwable.toString());
                    return null;
                });
    }

    /**
     * Auto-select player's own skin if no skin is currently selected
     * Called during initialization to ensure base skin is always selected
     */
    public static void autoSelectPlayerOwnSkin() {
        com.quickskin.mod.config.ClientConfig config = com.quickskin.mod.config.ClientConfig.getInstance();

        if (config.activeSkinHash.isEmpty() && config.activeCpmModelHash.isEmpty() && !config.playerOwnSkinHash.isEmpty()) {
            LocalAssetManager assetManager = LocalAssetManager.getInstance();
            AssetMetadata metadata = assetManager.getMetadata(config.playerOwnSkinHash);

            if (metadata != null) {
                // Auto-select the player's own skin
                config.activeSkinHash = config.playerOwnSkinHash;
                config.save();
            }
        }
    }

}
