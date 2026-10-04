package com.quickskin.mod.client.compat;

import com.quickskin.mod.common.data.AssetMetadata;
import com.quickskin.mod.config.ClientConfig;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Which of Quick Skin and CPM made the latest look choice for the local player. That choice is
 * the look, locally and for every other player.
 *
 * <p>Every Quick Skin choice also writes CPM's own selection ({@code selectedModel}): a skin
 * clears it and a model sets it. Both configurations persist, so the latest choice is derived
 * from them at any time, after a relog or a restart too. Only a model the server assigned
 * (/cpm setskin) is remembered for the connection, from the moment it arrives.</p>
 */
@Environment(EnvType.CLIENT)
public final class CpmLook {
    private static final Logger CPMLOG = LoggerFactory.getLogger("QuickSkin-CPM");
    private static final Session SESSION = new Session();

    /** Who chose the local player's current look. */
    public enum Owner {
        /** A Quick Skin skin, or no skin; a CPM model embedded in that skin comes with it. */
        QUICK_SKIN,
        /** A CPM model chosen in Quick Skin. */
        QUICK_SKIN_MODEL,
        /** CPM's own choice (its Models screen, its commands) or a model the server assigned. */
        CPM,
        /** CPM's state or Quick Skin's model cannot be read yet: nothing is decided. */
        UNKNOWN;

        /** While a CPM model is the look, the local player wears no Quick Skin skin. */
        public boolean withholdsQuickSkinSkin() {
            return this == QUICK_SKIN_MODEL || this == CPM;
        }
    }

    private CpmLook() {
    }

    /**
     * The latest look choice, in this order: without CPM, Quick Skin's; while a skin chosen in
     * Quick Skin is still resetting CPM, Quick Skin's; when CPM's selection or Quick Skin's model
     * file cannot be read, unknown; a CPM selection equal to Quick Skin's model, Quick Skin's
     * model; any other CPM selection, CPM's; no CPM selection but a model the server assigned,
     * CPM's; otherwise Quick Skin's.
     */
    static Owner decide(
            boolean cpmInstalled,
            boolean quickSkinResetPending,
            boolean selectionReadable,
            String selectedModel,
            boolean quickSkinHasModel,
            String quickSkinModel,
            boolean serverAssigned) {
        if (!cpmInstalled || quickSkinResetPending) {
            return Owner.QUICK_SKIN;
        }
        if (!selectionReadable || (quickSkinHasModel && quickSkinModel == null)) {
            return Owner.UNKNOWN;
        }
        if (selectedModel != null) {
            return quickSkinHasModel && selectedModel.equals(quickSkinModel)
                    ? Owner.QUICK_SKIN_MODEL
                    : Owner.CPM;
        }
        return serverAssigned ? Owner.CPM : Owner.QUICK_SKIN;
    }

    /** The latest look choice now. Client thread. */
    public static Owner owner() {
        if (!CPMCompatIntegration.isAvailable()) {
            return Owner.QUICK_SKIN;
        }
        ClientConfig config = ClientConfig.getInstance();
        boolean readable = CPMCompatIntegration.canReadSelectedModel();
        String selected = readable ? normalized(CPMCompatIntegration.selectedModel()) : null;
        String quickSkinHash = config.activeCpmModelHash;
        boolean quickSkinHasModel = quickSkinHash != null && !quickSkinHash.isEmpty();
        boolean serverAssigned;
        synchronized (SESSION) {
            serverAssigned = SESSION.serverAssigned;
        }
        return decide(true, quickSkinResetPending(config), readable, selected,
                quickSkinHasModel, quickSkinHasModel ? quickSkinModelName(quickSkinHash) : null,
                serverAssigned);
    }

    /**
     * Client tick. Notes a model the server assigned since the last tick (an arrival is a look
     * choice), derives the latest choice and drops Quick Skin's model selection once CPM's own
     * choice replaced it or CPM's Reset removed it, so leaving it later never resets CPM.
     */
    public static Owner observe() {
        if (!CPMCompatIntegration.isAvailable()) {
            return Owner.QUICK_SKIN;
        }
        ClientConfig config = ClientConfig.getInstance();
        boolean pending = quickSkinResetPending(config);
        if (CPMCompatIntegration.canReadSelectedModel()) {
            String selected = CPMCompatIntegration.selectedModel();
            byte[] serverModel = CPMCompatIntegration.localServerModel();
            synchronized (SESSION) {
                SESSION.observe(selected, pending, serverModel,
                        CPMCompatIntegration::isLocalServerModelForced);
            }
            Owner owner = owner();
            String quickSkinHash = config.activeCpmModelHash;
            if (quickSkinHash != null && !quickSkinHash.isEmpty() && !pending
                    && (owner == Owner.CPM || (owner == Owner.QUICK_SKIN && selected == null))) {
                config.activeCpmModelHash = "";
                config.save();
                CPMLOG.info("Quick Skin's CPM model {} is no longer the look ({}); selection cleared",
                        quickSkinHash, owner == Owner.CPM ? "CPM's own choice replaced it" : "CPM was reset");
            }
            return owner;
        }
        return owner();
    }

    /** A new connection: a model the server assigned to the previous one no longer applies. */
    public static void resetSession() {
        synchronized (SESSION) {
            SESSION.reset();
        }
    }

    /**
     * Quick Skin has just reset CPM to skin mode for a skin chosen in Quick Skin. The server's
     * reply to that reset is not a new choice; a forced server model stays the look.
     */
    static void onQuickSkinReset() {
        byte[] serverModel = CPMCompatIntegration.localServerModel();
        synchronized (SESSION) {
            SESSION.acknowledge(serverModel, CPMCompatIntegration::isLocalServerModelForced);
        }
    }

    private static boolean quickSkinResetPending(ClientConfig config) {
        return config.pendingCpmSkinModeReset || CPMCompatIntegration.isSkinModeResetInProgress();
    }

    private static String normalized(String selectedModel) {
        if (selectedModel == null) {
            return null;
        }
        String normalized = CPMCompatIntegration.normalizeRelativeModelName(selectedModel);
        return normalized != null ? normalized : selectedModel;
    }

    /**
     * The selectedModel Quick Skin wrote for its catalogued model, computed as
     * {@link CpmModelWorkflow#activateModel} does, or null when the model is not catalogued (yet).
     */
    private static String quickSkinModelName(String modelHash) {
        if (!CPMCompatIntegration.assetsConfigured()) {
            return null;
        }
        try {
            AssetMetadata metadata = CPMCompatIntegration.assets().metadata(modelHash);
            if (metadata == null || !metadata.isCpmModel()) {
                return null;
            }
            Path modelsDirectory = CPMCompatIntegration.getCPMModelsDirectory().toAbsolutePath().normalize();
            Path modelPath = metadata.path().toAbsolutePath().normalize();
            if (!modelPath.startsWith(modelsDirectory)) {
                return null;
            }
            return CPMCompatIntegration.normalizeRelativeModelName(
                    modelsDirectory.relativize(modelPath).toString().replace('\\', '/'));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Per-connection memory of a model the server assigned to the local player. */
    static final class Session {
        /**
         * Ticks a server model may outlive a request to drop it before it counts as kept by the
         * server. CPM's server answers an accepted drop at once; it keeps only a forced model.
         */
        static final int SERVER_KEEP_TICKS = 100;

        boolean serverAssigned;
        private byte[] lastServerModel;
        private boolean lastServerModelForced;
        private String lastSelectedModel;
        private boolean selectionSeen;
        private int dropWaitTicks = -1;

        /**
         * One client tick. Server model data whose content is new while CPM has no selection of
         * its own and no Quick Skin reset is running is a model the server assigned: a look choice
         * made when it arrives. When CPM's own Reset removed its selection, only a model the
         * server keeps stays the look. Removed server data ends the assignment.
         */
        void observe(String selectedModel, boolean quickSkinResetPending, byte[] serverModel,
                     BooleanSupplier serverModelForced) {
            if (selectionSeen && lastSelectedModel != null && selectedModel == null && !quickSkinResetPending) {
                acknowledge(serverModel, serverModelForced);
            }
            if (serverModel == null) {
                serverAssigned = false;
                lastServerModel = null;
                lastServerModelForced = false;
                dropWaitTicks = -1;
            } else if (!Arrays.equals(serverModel, lastServerModel)) {
                lastServerModel = serverModel;
                // CPM sets this flag only when the data arrives and loses it with its player cache.
                lastServerModelForced = serverModelForced.getAsBoolean();
                dropWaitTicks = -1;
                if (selectedModel == null && !quickSkinResetPending) {
                    serverAssigned = true;
                }
            } else if (dropWaitTicks >= 0 && ++dropWaitTicks >= SERVER_KEEP_TICKS) {
                // The server refused to drop it (a forced model): it stays the look.
                dropWaitTicks = -1;
                lastServerModelForced = true;
                if (selectedModel == null && !quickSkinResetPending) {
                    serverAssigned = true;
                }
            }
            lastSelectedModel = selectedModel;
            selectionSeen = true;
        }

        /**
         * The local selection was dropped and CPM's server was asked to drop its model. Server data
         * still present is the old echo or a model the server keeps: a model known to be forced
         * keeps the look at once, any other one only if the server still has it after
         * {@link #SERVER_KEEP_TICKS}.
         */
        void acknowledge(byte[] serverModel, BooleanSupplier serverModelForced) {
            boolean knownForced = serverModel != null && lastServerModelForced
                    && Arrays.equals(serverModel, lastServerModel);
            lastServerModel = serverModel;
            lastServerModelForced = serverModel != null
                    && (knownForced || Objects.requireNonNull(serverModelForced).getAsBoolean());
            serverAssigned = lastServerModelForced;
            dropWaitTicks = serverModel != null && !serverAssigned ? 0 : -1;
            lastSelectedModel = null;
            selectionSeen = true;
        }

        void reset() {
            serverAssigned = false;
            lastServerModel = null;
            lastServerModelForced = false;
            lastSelectedModel = null;
            selectionSeen = false;
            dropWaitTicks = -1;
        }
    }
}
