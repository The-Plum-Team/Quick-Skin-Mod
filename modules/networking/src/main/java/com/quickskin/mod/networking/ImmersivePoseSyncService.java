package com.quickskin.mod.networking;

import com.quickskin.mod.config.ClientConfig;
import com.quickskin.mod.config.ServerConfig;
import com.quickskin.mod.networking.protocol.ProtocolCapability;
import com.quickskin.mod.networking.protocol.ProtocolProfile;
import com.quickskin.mod.networking.protocol.ProtocolSessions;
//? if >=1.21 {
import com.quickskin.mod.networking.payloads.ImmersivePosePayload;
//?}
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Tells the connected server the pose the player plays while their immersive skin menu floats
 * round them (ADR 0019), so other Quick Skin players see it: on a session that negotiated
 * {@code immersive-poses}, whenever the pose changes (at most every {@link #MIN_INTERVAL_NANOS},
 * except its end, which goes at once), and again every {@link #KEEP_ALIVE_NANOS} while it lasts,
 * since the server ends a pose it stops hearing of. Nothing is sent during a replay or while the
 * server does not share poses.
 */
@Environment(EnvType.CLIENT)
public final class ImmersivePoseSyncService {
    static final long MIN_INTERVAL_NANOS = 250_000_000L;
    static final long KEEP_ALIVE_NANOS = 5_000_000_000L;
    private static final ImmersivePoseSyncService INSTANCE = new ImmersivePoseSyncService();

    @Nullable
    private Object connection;
    @Nullable
    private UUID playerId;
    private boolean replay;
    private boolean negotiated;
    /** What the server was last told, {@link ImmersivePose#OFF} at first. */
    private ImmersivePose told = ImmersivePose.OFF;
    private long toldAtNanos;
    private int sequence;

    private ImmersivePoseSyncService() {
    }

    public static ImmersivePoseSyncService getInstance() {
        return INSTANCE;
    }

    /** A new connection: it has been told no pose. */
    public synchronized void beginSession(UUID localPlayerId, Object sessionIdentity, boolean replay) {
        clearSession();
        connection = sessionIdentity;
        playerId = localPlayerId;
        this.replay = replay;
    }

    /** The exact connection negotiated v2: the pose is told from the next tick if it shares poses. */
    synchronized void onProtocolAcknowledged(Object negotiatedConnection) {
        if (negotiatedConnection != null && negotiatedConnection == connection) negotiated = true;
    }

    /**
     * Client thread, every tick: the pose the player plays now, {@link ImmersivePose#OFF} for none.
     * Sends what the server must hear of it.
     */
    public synchronized void update(ImmersivePose pose) {
        if (!sends()) {
            // The session ended or never could pose: nothing is owed to the server
            return;
        }
        ImmersivePose wanted = pose == null || !sharesPoses() ? ImmersivePose.OFF : pose;
        long now = System.nanoTime();
        if (!wanted.equals(told)) {
            // Opening, closing and the end go at once; a pose picked, or a move made, waits its turn
            if (!wanted.active() || !told.active() || wanted.closing() != told.closing()
                    || now - toldAtNanos >= MIN_INTERVAL_NANOS) {
                send(wanted, now);
            }
        } else if (wanted.active() && now - toldAtNanos >= KEEP_ALIVE_NANOS) {
            send(wanted, now);
        }
    }

    public synchronized void clearSession() {
        connection = null;
        playerId = null;
        replay = false;
        negotiated = false;
        told = ImmersivePose.OFF;
        toldAtNanos = 0L;
        sequence = 0;
    }

    private boolean sends() {
        return connection != null && !replay && negotiated && currentConnection() == connection
                && supports(ProtocolCapability.IMMERSIVE_POSES);
    }

    /** Whether the server shares poses: an older server, which does not say, does since it negotiated them. */
    private static boolean sharesPoses() {
        ServerConfig serverConfig = ClientConfig.getInstance().getServerOverride();
        return serverConfig == null || serverConfig.allowImmersivePoses;
    }

    private boolean supports(ProtocolCapability capability) {
        ProtocolProfile profile = ProtocolSessions.getInstance().clientProfile(connection);
        return profile.negotiated() && profile.supports(capability);
    }

    private void send(ImmersivePose pose, long now) {
        if (playerId == null) return;
        int next = sequence + 1;
        //? if <1.21 {
        NetworkTransport.INSTANCE.sendImmersivePoseToServer(playerId, next, pose);
        //?} else {
        if (!NetworkTransport.INSTANCE.canServerReceive(ImmersivePosePayload.TYPE)) return;
        NetworkTransport.INSTANCE.sendToServer(new ImmersivePosePayload(playerId, next, pose));
        //?}
        sequence = next;
        told = pose;
        toldAtNanos = now;
    }

    @Nullable
    private static Object currentConnection() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft == null ? null : minecraft.getConnection();
    }
}
