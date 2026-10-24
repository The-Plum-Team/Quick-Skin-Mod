package com.quickskin.mod.networking;

import com.quickskin.mod.client.api.ClientNetworkActions.ArmorHiding;
import com.quickskin.mod.config.AccountSkinPreferences;
import com.quickskin.mod.config.ClientConfig;
import com.quickskin.mod.config.ServerConfig;
import com.quickskin.mod.networking.protocol.ProtocolCapability;
import com.quickskin.mod.networking.protocol.ProtocolProfile;
import com.quickskin.mod.networking.protocol.ProtocolSessions;
//? if >=1.21 {
import com.quickskin.mod.networking.payloads.ArmorVisibilityPayload;
//?}
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Tells the connected server which armour pieces the player hides from everyone (ADR 0017): once
 * per session as soon as it negotiates {@code hidden-armor}, and again whenever the player changes
 * it. The pieces are the joining launcher account's. Nothing is sent while a replay plays.
 */
@Environment(EnvType.CLIENT)
public final class HiddenArmorSyncService {
    private static final HiddenArmorSyncService INSTANCE = new HiddenArmorSyncService();

    @Nullable
    private Object connection;
    @Nullable
    private UUID playerId;
    /** The pieces this session hides, or -1 while nothing may be sent (a replay). */
    private int desired = -1;
    private boolean negotiated;

    private HiddenArmorSyncService() {
    }

    public static HiddenArmorSyncService getInstance() {
        return INSTANCE;
    }

    /** A new connection: it hides the joining account's pieces, none during a replay. */
    public synchronized void beginSession(UUID localPlayerId, Object sessionIdentity, boolean replay) {
        clearSession();
        connection = sessionIdentity;
        playerId = localPlayerId;
        desired = replay ? -1 : accountHidden();
    }

    /** The exact connection negotiated v2: it learns the hidden pieces now if it hides armour. */
    synchronized void onProtocolAcknowledged(Object negotiatedConnection) {
        if (negotiatedConnection == null || negotiatedConnection != connection || negotiated) return;
        negotiated = true;
        if (desired > HiddenArmor.NONE && supportsHiddenArmor()) send(desired);
    }

    /** The player chose which pieces to hide. */
    public synchronized void update(int hidden) {
        if (desired < 0 || currentConnection() != connection) return;
        desired = hidden & HiddenArmor.ALL;
        if (negotiated && supportsHiddenArmor()) send(desired);
    }

    /** Whether hiding armour works where the player is now, and if not, why. */
    public synchronized ArmorHiding hiding() {
        if (connection == null || currentConnection() != connection) return ArmorHiding.NOT_CONNECTED;
        if (!negotiated || !supportsHiddenArmor()) return ArmorHiding.UNSUPPORTED;
        ServerConfig serverConfig = ClientConfig.getInstance().getServerOverride();
        if (serverConfig != null && !serverConfig.allowHiddenArmor) return ArmorHiding.NOT_ALLOWED;
        return ArmorHiding.ACTIVE;
    }

    /** The pieces nobody sees now: those the player hides, where the server hides them. */
    public synchronized int inEffect() {
        return desired > HiddenArmor.NONE && hiding() == ArmorHiding.ACTIVE ? desired : HiddenArmor.NONE;
    }

    public synchronized void clearSession() {
        connection = null;
        playerId = null;
        desired = -1;
        negotiated = false;
    }

    private boolean supportsHiddenArmor() {
        if (connection == null) return false;
        ProtocolProfile profile = ProtocolSessions.getInstance().clientProfile(connection);
        return profile.negotiated() && profile.supports(ProtocolCapability.HIDDEN_ARMOR);
    }

    private void send(int hidden) {
        if (playerId == null) return;
        //? if <1.21 {
        NetworkTransport.INSTANCE.sendArmorVisibilityToServer(playerId, hidden);
        //?} else {
        if (!NetworkTransport.INSTANCE.canServerReceive(ArmorVisibilityPayload.TYPE)) return;
        NetworkTransport.INSTANCE.sendToServer(new ArmorVisibilityPayload(playerId, hidden));
        //?}
    }

    @Nullable
    private static Object currentConnection() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft == null ? null : minecraft.getConnection();
    }

    /** The joining launcher account's hidden pieces, kept per account like its skin selection. */
    private static int accountHidden() {
        var user = Minecraft.getInstance().getUser();
        String account = user == null ? null : AccountSkinPreferences.key(user.getProfileId(), user.getName());
        return ClientConfig.getInstance().accountHiddenArmor.hidden(account);
    }
}
