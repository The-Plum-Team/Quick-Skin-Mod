package com.quickskin.mod.common.data;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The immersive poses the connected server says other players play now (ADR 0019): which of the
 * skin menu's poses, the way they face, and their skin-change moves. Written by the network
 * handler on the client thread, read when players are drawn, cleared with the session; bounded.
 * Each pose remembers the world it was told in, so one told before the player changed world is
 * not played in the next.
 */
@Environment(EnvType.CLIENT)
public final class RemoteImmersivePoses {
    private static final RemoteImmersivePoses INSTANCE = new RemoteImmersivePoses();
    static final int MAX_POSES = 1024;

    /** The pose ids and flags of {@code com.quickskin.mod.networking.ImmersivePose}, which this module cannot see. */
    public static final int NONE = 0;
    public static final int IDLE = 1;
    public static final int WALK = 2;
    public static final int SIT = 3;
    public static final int CLOSING = 1;
    public static final int SETTLED = 2;

    /**
     * One player's pose, as {@code com.quickskin.mod.networking.ImmersivePose} carries it.
     *
     * @param world the client's world when it was told, compared by identity
     * @param run   which unbroken run of the player's poses this one carries on, numbered by
     *              {@link #set} from the first pose after none or in another world
     * @param toldNanos when this client was told it, on {@link System#nanoTime()}; set by {@link #set},
     *              so a change is played from when it was told even if the player is drawn later
     * @param runStartNanos when this client was told the first pose of the run, on the same clock;
     *              a player first drawn long after the run began is not seen turning in again
     */
    public record Pose(int pose, float yaw, int flags, int swaps, int swapVariant, Object world, long run,
                       long toldNanos, long runStartNanos) {
        public Pose(int pose, float yaw, int flags, int swaps, int swapVariant, Object world) {
            this(pose, yaw, flags, swaps, swapVariant, world, 0L, 0L, 0L);
        }
    }

    /** The skin menu's name of a pose id: {@code idle}, {@code walk} or {@code sit}. */
    public static String animationOf(int pose) {
        return switch (pose) {
            case WALK -> "walk";
            case SIT -> "sit";
            default -> "idle";
        };
    }

    private final Map<UUID, Pose> poses = new ConcurrentHashMap<>();
    private volatile boolean empty = true;
    private long runs;

    private RemoteImmersivePoses() {
    }

    public static RemoteImmersivePoses getInstance() {
        return INSTANCE;
    }

    /** The pose {@code playerId} plays in {@code world} now, or null. */
    @Nullable
    public Pose pose(@Nullable UUID playerId, @Nullable Object world) {
        if (empty || playerId == null) return null;
        Pose pose = poses.get(playerId);
        return pose != null && pose.world() == world ? pose : null;
    }

    /** Whether any player is told to play a pose, a cheap test for every draw of a player. */
    public boolean isEmpty() {
        return empty;
    }

    /**
     * Records what the server said; a pose of 0 (none) ends the player's. A pose that carries on
     * the player's poses in the same world keeps their run.
     */
    public synchronized void set(UUID playerId, Pose told) {
        if (playerId == null) return;
        if (told == null || told.pose() == NONE || told.world() == null) {
            poses.remove(playerId);
        } else {
            Pose previous = poses.get(playerId);
            long now = System.nanoTime();
            boolean carriesOn = previous != null && previous.world() == told.world();
            long run = carriesOn ? previous.run() : ++runs;
            Pose pose = new Pose(told.pose(), told.yaw(), told.flags(), told.swaps(), told.swapVariant(), told.world(), run,
                    now, carriesOn ? previous.runStartNanos() : now);
            if (!poses.containsKey(playerId) && poses.size() >= MAX_POSES) {
                // Poses told in a world the client has left go first, then any
                poses.values().removeIf(other -> other.world() != pose.world());
                if (poses.size() >= MAX_POSES) {
                    var eldest = poses.keySet().iterator();
                    if (eldest.hasNext()) poses.remove(eldest.next());
                }
            }
            poses.put(playerId, pose);
        }
        empty = poses.isEmpty();
    }

    /**
     * The client is in {@code world} now: the poses told in any other are forgotten, since the
     * server tells no client that left a world the end of the poses played there.
     */
    public synchronized void keepOnly(@Nullable Object world) {
        if (empty) return;
        poses.values().removeIf(pose -> pose.world() != world);
        empty = poses.isEmpty();
    }

    public synchronized void clear() {
        poses.clear();
        empty = true;
    }

    int size() {
        return poses.size();
    }
}
