package com.quickskin.mod.networking;

import com.quickskin.mod.client.api.ClientNetworkActions.NicknameAnswer;
import com.quickskin.mod.client.api.ClientNetworkActions.NicknameDelivery;
import com.quickskin.mod.config.AccountSkinPreferences;
import com.quickskin.mod.config.ClientConfig;
import com.quickskin.mod.config.ServerConfig;
import com.quickskin.mod.networking.protocol.ProtocolCapability;
import com.quickskin.mod.networking.protocol.ProtocolProfile;
import com.quickskin.mod.networking.protocol.ProtocolSessions;
//? if >=1.21 {
import com.quickskin.mod.networking.payloads.SetNicknamePayload;
//?}
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Gives the connected server the player's nickname: once per session as soon as it negotiates
 * {@code nicknames} (an empty one too, so the server forgets a nickname removed meanwhile), and
 * again whenever the player changes it. A refusal for changing too often is retried when the
 * wait the server named is over, with whatever nickname the player wants by then. Nothing is
 * sent while a replay plays.
 */
@Environment(EnvType.CLIENT)
public final class NicknameSyncService {
    private static final NicknameSyncService INSTANCE = new NicknameSyncService();
    private static final long RETRY_MARGIN_MILLIS = 250L;
    /** Why a client profile stays local while its hello waits for the server's answer. */
    private static final String AWAITING_ACKNOWLEDGEMENT = "awaiting-protocol-ack";

    @Nullable
    private Object connection;
    @Nullable
    private UUID playerId;
    /** The nickname this session should have, or null while nothing may be sent (a replay). */
    @Nullable
    private String desired;
    /** The nickname the server says the player has, once it answered. */
    @Nullable
    private String confirmed;
    private boolean negotiated;
    private int nextRequestId = 1;
    private int pendingRequestId;
    private long retryAtMillis;
    private int answers;
    @Nullable
    private NicknameAnswer lastAnswer;
    /** Why the player cannot change their nickname now (an admin's name or lock), or null. */
    @Nullable
    private NicknameAnswer.Outcome fixedBy;

    private NicknameSyncService() {
    }

    public static NicknameSyncService getInstance() {
        return INSTANCE;
    }

    /** A new connection: its nickname is the joining account's, none during a replay. */
    public synchronized void beginSession(UUID localPlayerId, Object sessionIdentity, boolean replay) {
        clearSession();
        connection = sessionIdentity;
        playerId = localPlayerId;
        desired = replay ? null : accountNickname();
    }

    /** The exact connection negotiated v2: it learns the nickname now if it takes nicknames. */
    synchronized void onProtocolAcknowledged(Object negotiatedConnection) {
        if (negotiatedConnection == null || negotiatedConnection != connection || negotiated) return;
        negotiated = true;
        if (desired != null && supportsNicknames()) send(desired);
    }

    /** The player chose a new nickname in the menu. */
    public synchronized NicknameDelivery update(String nickname) {
        Object current = currentConnection();
        if (current == null || current != connection || desired == null) {
            return NicknameDelivery.NOT_CONNECTED;
        }
        desired = nickname;
        if (!negotiated) {
            // A Quick Skin server that was greeted negotiates soon, and gets the nickname then
            ProtocolProfile profile = ProtocolSessions.getInstance().clientProfile(connection);
            return profile.mode() == ProtocolProfile.Mode.LOCAL_ONLY
                    && AWAITING_ACKNOWLEDGEMENT.equals(profile.reason())
                    ? NicknameDelivery.PENDING : NicknameDelivery.UNSUPPORTED;
        }
        if (!supportsNicknames()) return NicknameDelivery.UNSUPPORTED;
        ServerConfig serverConfig = ClientConfig.getInstance().getServerOverride();
        if (serverConfig != null && !serverConfig.allowNicknames) return NicknameDelivery.DISABLED;
        // A scheduled retry sends the latest wish when the server's wait is over
        if (retryAtMillis == 0L) send(nickname);
        return NicknameDelivery.SENT;
    }

    /** Network handler, client thread: the server's answer to a request of this exact session. */
    synchronized void onAnswer(Object answeringConnection, int requestId, NicknameStatus status,
                               String nickname, long retryAfterMillis) {
        if (answeringConnection != connection) return;
        if (requestId == NicknameStatus.NOTICE_REQUEST_ID) {
            // The server says unasked whether the player may change their nickname
            confirmed = nickname;
            fixedBy = fixedBy(status);
            return;
        }
        if (requestId != pendingRequestId) return;
        pendingRequestId = 0;
        confirmed = nickname;
        fixedBy = fixedBy(status);
        if (status == NicknameStatus.COOLDOWN) {
            long wait = Math.max(0L, Math.min(retryAfterMillis, 60_000L));
            retryAtMillis = System.currentTimeMillis() + wait + RETRY_MARGIN_MILLIS;
        }
        NicknameAnswer.Outcome outcome = switch (status) {
            case ACCEPTED -> NicknameAnswer.Outcome.ACCEPTED;
            case UNCHANGED -> NicknameAnswer.Outcome.UNCHANGED;
            case INVALID -> NicknameAnswer.Outcome.INVALID;
            case TAKEN -> NicknameAnswer.Outcome.TAKEN;
            case COOLDOWN -> NicknameAnswer.Outcome.COOLDOWN;
            case DISABLED -> NicknameAnswer.Outcome.DISABLED;
            case LOCKED -> NicknameAnswer.Outcome.LOCKED;
            case ASSIGNED -> NicknameAnswer.Outcome.ASSIGNED;
        };
        lastAnswer = new NicknameAnswer(++answers, outcome, nickname, retryAfterMillis);
    }

    @Nullable
    public synchronized NicknameAnswer lastAnswer() {
        return lastAnswer;
    }

    /**
     * Why the player cannot change their nickname on the connected server for now
     * ({@code ASSIGNED} or {@code LOCKED}), or null when they can or the server did not say.
     */
    @Nullable
    public synchronized NicknameAnswer.Outcome fixedByAdmin() {
        if (!negotiated || currentConnection() != connection || !supportsNicknames()) return null;
        return fixedBy;
    }

    @Nullable
    private static NicknameAnswer.Outcome fixedBy(NicknameStatus status) {
        return switch (status) {
            case ASSIGNED -> NicknameAnswer.Outcome.ASSIGNED;
            case LOCKED -> NicknameAnswer.Outcome.LOCKED;
            default -> null;
        };
    }

    /** Network handler, client thread: the server announced the local player's own nickname. */
    synchronized void onOwnNickname(Object announcingConnection, String nickname) {
        if (announcingConnection == connection) confirmed = nickname;
    }

    /**
     * The nickname the connected server shows for the player ({@code ""}: their account name),
     * or null when the server shows no nicknames or has not said yet. A server with nicknames
     * off still shows one an admin gave.
     */
    @Nullable
    public synchronized String shownByServer() {
        if (!negotiated || currentConnection() != connection || !supportsNicknames()) return null;
        return confirmed;
    }

    /** Client tick: resends the wished nickname once a cooldown the server named is over. */
    public synchronized void tick() {
        if (retryAtMillis == 0L || System.currentTimeMillis() < retryAtMillis) return;
        retryAtMillis = 0L;
        if (desired != null && negotiated && !desired.equals(confirmed)
                && currentConnection() == connection && supportsNicknames()) {
            send(desired);
        }
    }

    public synchronized void clearSession() {
        connection = null;
        playerId = null;
        desired = null;
        confirmed = null;
        negotiated = false;
        pendingRequestId = 0;
        retryAtMillis = 0L;
        lastAnswer = null;
        fixedBy = null;
    }

    private boolean supportsNicknames() {
        if (connection == null) return false;
        ProtocolProfile profile = ProtocolSessions.getInstance().clientProfile(connection);
        return profile.negotiated() && profile.supports(ProtocolCapability.NICKNAMES);
    }

    private void send(String nickname) {
        if (playerId == null || !NetworkSecurity.isValidNickname(nickname)) return;
        int requestId = nextRequestId++;
        if (nextRequestId <= 0) nextRequestId = 1;
        //? if <1.21 {
        NetworkTransport.INSTANCE.sendNicknameToServer(playerId, requestId, nickname);
        //?} else {
        if (!NetworkTransport.INSTANCE.canServerReceive(SetNicknamePayload.TYPE)) return;
        NetworkTransport.INSTANCE.sendToServer(new SetNicknamePayload(playerId, requestId, nickname));
        //?}
        pendingRequestId = requestId;
    }

    @Nullable
    private static Object currentConnection() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft == null ? null : minecraft.getConnection();
    }

    /** The joining launcher account's nickname, kept per account like its skin selection. */
    private static String accountNickname() {
        var user = Minecraft.getInstance().getUser();
        String account = user == null ? null : AccountSkinPreferences.key(user.getProfileId(), user.getName());
        return ClientConfig.getInstance().accountNicknames.nickname(account);
    }
}
