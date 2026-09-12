package com.quickskin.mod.client.services;

import com.quickskin.mod.common.data.TextureQuality;
import com.quickskin.mod.common.util.SkinModelDetector;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import org.jetbrains.annotations.Nullable;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for managing player model types (classic/slim)
 */
@Environment(EnvType.CLIENT)
public class ModelService implements IModelService {
    private static final int MAX_MODEL_OVERRIDES = 4096;
    private static ModelService instance;

    private final Map<UUID, String> modelOverrides = new ConcurrentHashMap<>();

    private ModelService() {}

    public static ModelService getInstance() {
        if (instance == null) {
            instance = new ModelService();
        }
        return instance;
    }

    public static void init() {
        getInstance();
    }

    @Override
    public String getModelType(UUID playerId, String skinId, String requestedModel) {
        // If a specific model is explicitly requested (not "auto"), honor that request
        if (requestedModel != null && !"auto".equals(requestedModel.toLowerCase(Locale.ROOT))) {
            return requestedModel;
        }

        // If auto mode, get the pre-detected model from metadata
        if ("auto".equals(requestedModel != null ? requestedModel.toLowerCase(Locale.ROOT) : null)) {
            if (skinId != null && skinId.startsWith("local_skin:")) {
                String hash = skinId.substring("local_skin:".length());

                // Get metadata from the asset manager which has the pre-detected model type
                com.quickskin.mod.common.data.AssetMetadata metadata =
                    LocalAssetManager.getInstance().getMetadata(hash);

                if (metadata != null && metadata.skinModel() != null) {
                    // Return the cached model type! This avoids all file I/O.
                    return metadata.skinModel();
                }
            }

            // Default to classic if detection fails or skinId is not local
            return "classic";
        }

        // Fallback: check for override
        if (playerId != null && modelOverrides.containsKey(playerId)) {
            String override = modelOverrides.get(playerId);
            if (!"auto".equals(override != null ? override.toLowerCase(Locale.ROOT) : null)) {
                return override;
            }
        }

        return "classic";
    }

    @Override
    public String detectModelType(byte[] skinData) {
        if (skinData == null) {
            return "classic";
        }

        try {
            return SkinModelDetector.detectSkinModel(skinData);
        } catch (Exception e) {
            return "classic";
        }
    }

    @Override
    public synchronized void setModelOverride(UUID playerId, String model) {
        if (playerId == null || !("auto".equals(model)
                || "classic".equals(model) || "slim".equals(model))) {
            return;
        }
        if (!modelOverrides.containsKey(playerId)
                && modelOverrides.size() >= MAX_MODEL_OVERRIDES) {
            var eldest = modelOverrides.keySet().iterator();
            if (eldest.hasNext()) modelOverrides.remove(eldest.next());
        }
        modelOverrides.put(playerId, model);
    }

    @Override
    @Nullable
    public String getModelOverride(@Nullable UUID playerId) {
        if (playerId == null) return null;
        return modelOverrides.get(playerId);
    }

    @Override
    public boolean hasModelOverride(@Nullable UUID playerId) {
        if (playerId == null) return false;
        return modelOverrides.containsKey(playerId);
    }

    /**
     * Clears all model overrides (e.g., when disconnecting)
     */
    public void clearAll() {
        modelOverrides.clear();
    }
}
