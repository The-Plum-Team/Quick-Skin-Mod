package com.quickskin.mod.client.compat;

import com.quickskin.mod.common.data.AssetMetadata;
import com.quickskin.mod.config.AccountSkinPreferences;
import com.quickskin.mod.config.AccountSkinSession;
import com.quickskin.mod.config.ClientConfig;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/** Version-neutral selection and persisted-state transitions for CPM models. */
public final class CpmModelWorkflow {
    private static final Logger CPMLOG = LoggerFactory.getLogger("QuickSkin-CPM");
    private static final AtomicBoolean unavailableStateLogged = new AtomicBoolean();

    private CpmModelWorkflow() {
    }

    public static boolean isModelFile(Path path) {
        return path != null
                && path.getFileName() != null
                && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".cpmmodel");
    }

    public static AssetMetadata getPersistedActiveModel() {
        ClientConfig config = ClientConfig.getInstance();
        if (config.activeCpmModelHash == null || config.activeCpmModelHash.isEmpty()) {
            return null;
        }
        AssetMetadata metadata = CPMCompatIntegration.assets().metadata(config.activeCpmModelHash);
        return metadata != null && metadata.isCpmModel() ? metadata : null;
    }

    /**
     * Clears a stale CPM-only selection after the optional mod is removed, so
     * normal/player-own skin fallback is not blocked by an invisible asset.
     */
    public static boolean sanitizeUnavailableState() {
        if (CPMCompatIntegration.isAvailable()) {
            return false;
        }
        ClientConfig config = ClientConfig.getInstance();
        if (config.activeCpmModelHash == null || config.activeCpmModelHash.isEmpty()) {
            return false;
        }
        String staleHash = config.activeCpmModelHash;
        config.activeCpmModelHash = "";
        config.pendingCpmSkinModeReset = true;
        config.save();
        if (unavailableStateLogged.compareAndSet(false, true)) {
            CPMLOG.info("Cleared inactive CPM model selection {} because CPM is not installed", staleHash);
        }
        return true;
    }

    /** Completes a reset that could not run while CPM was absent. */
    public static boolean reconcilePendingSkinModeReset() {
        ClientConfig config = ClientConfig.getInstance();
        if (!config.pendingCpmSkinModeReset || !CPMCompatIntegration.isAvailable()) {
            return false;
        }
        if (!CPMCompatIntegration.resetToSkinMode()) {
            return false;
        }
        config.pendingCpmSkinModeReset = false;
        config.save();
        CPMLOG.info("Completed pending CPM skin-mode reset after CPM became available");
        return true;
    }

    /** Clears an active hash whose model file disappeared outside QuickSkin. */
    public static boolean sanitizeMissingActiveModel() {
        ClientConfig config = ClientConfig.getInstance();
        if (config.activeCpmModelHash == null || config.activeCpmModelHash.isEmpty()) {
            return false;
        }
        AssetMetadata metadata = CPMCompatIntegration.assets().metadata(config.activeCpmModelHash);
        if (metadata != null && metadata.isCpmModel()) {
            return false;
        }
        String staleHash = config.activeCpmModelHash;
        config.activeCpmModelHash = "";
        boolean reset = CPMCompatIntegration.isAvailable()
                && CPMCompatIntegration.resetToSkinMode();
        config.pendingCpmSkinModeReset = !reset;
        config.save();
        CPMLOG.info("Cleared missing CPM model selection {}", staleHash);
        return true;
    }

    /** Selects CPM's relative file and atomically makes hashes mutually exclusive. */
    public static boolean activateModel(AssetMetadata metadata) {
        if (metadata == null || !metadata.isCpmModel() || !CPMCompatIntegration.isAvailable()) {
            return false;
        }
        Path modelsDirectory = CPMCompatIntegration.getCPMModelsDirectory().toAbsolutePath().normalize();
        Path modelPath = metadata.path().toAbsolutePath().normalize();
        if (!modelPath.startsWith(modelsDirectory)) {
            return false;
        }
        String relativeName = modelsDirectory.relativize(modelPath).toString().replace('\\', '/');
        if (!CPMCompatIntegration.selectModel(relativeName)) {
            return false;
        }

        ClientConfig config = ClientConfig.getInstance();
        config.activeSkinHash = "";
        config.activeCpmModelHash = metadata.hash();
        config.pendingCpmSkinModeReset = false;
        config.accountSkins.remove(currentAccount());
        AccountSkinSession.getInstance().selected();
        config.save();
        return true;
    }

    /** Persists the inverse transition before a normal QuickSkin skin is applied. */
    public static void activateSkin(String skinHash) {
        ClientConfig config = ClientConfig.getInstance();
        config.activeSkinHash = skinHash != null ? skinHash : "";
        config.activeCpmModelHash = "";
        if (CPMCompatIntegration.isAvailable()) {
            config.pendingCpmSkinModeReset = !CPMCompatIntegration.resetToSkinMode();
        } else {
            config.pendingCpmSkinModeReset = true;
        }
        if (config.activeSkinHash.isEmpty()) {
            config.accountSkins.remove(currentAccount());
        } else {
            config.accountSkins.select(currentAccount(), config.activeSkinHash);
        }
        AccountSkinSession.getInstance().selected();
        config.save();
    }

    /**
     * The launcher account that owns a selection made now, or null when it cannot be read. A
     * selection outranks a server record its session may still be waiting for.
     */
    private static String currentAccount() {
        Minecraft minecraft = Minecraft.getInstance();
        var user = minecraft != null ? minecraft.getUser() : null;
        return user != null ? AccountSkinPreferences.key(user.getProfileId(), user.getName()) : null;
    }

    /** Clears dangling state and returns CPM to skin mode after an active model is deleted. */
    public static void onModelDeleted(AssetMetadata metadata) {
        if (metadata == null || !metadata.isCpmModel()) {
            return;
        }
        ClientConfig config = ClientConfig.getInstance();
        if (!metadata.hash().equals(config.activeCpmModelHash)) {
            return;
        }
        config.activeCpmModelHash = "";
        boolean reset = CPMCompatIntegration.isAvailable()
                && CPMCompatIntegration.resetToSkinMode();
        config.pendingCpmSkinModeReset = !reset;
        config.save();
    }
}
