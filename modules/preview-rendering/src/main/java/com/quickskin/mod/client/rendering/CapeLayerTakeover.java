package com.quickskin.mod.client.rendering;

/**
 * The length of one call of vanilla's cape layer in which Quick Skin has drawn the player's cape
 * itself, or decided that none shows, so that vanilla's own layer must add nothing.
 *
 * <p>Quick Skin never returns early from that layer. Another mod may have put a hook at its start
 * and its match at its end (a pushed pose and its pop, a turned body and its turn back), and a
 * call cut short runs the first without the second: the game then stops with "Pose stack not
 * empty" the next time the world is drawn. Instead the layer is told, for this call, that the
 * player's cape part is off, takes its own way out, and every hook around it stays paired.
 *
 * <p>Before 1.21.2 the layer asks the player entity, so the player is held here and
 * {@code Player.isModelPartShown} answers for it. Later versions ask the render state, which the
 * layer's mixin changes and puts back on its own. The player is held by identity and only on the
 * thread that opened the call: the integrated server reads its own player on its own thread.
 */
public final class CapeLayerTakeover {
    private static Object wearer;
    private static Thread thread;

    private CapeLayerTakeover() {
    }

    /** Opens the call for {@code player}; whatever an earlier call left open is closed first. */
    public static void begin(Object player) {
        wearer = player;
        thread = player == null ? null : Thread.currentThread();
    }

    /** Closes the call; also at the start of the next one, for a call another mod cut short. */
    public static void end() {
        wearer = null;
        thread = null;
    }

    /** Whether vanilla's cape layer is, right now, drawing nothing for {@code player}. */
    public static boolean covers(Object player) {
        return player != null && wearer == player && thread == Thread.currentThread();
    }
}
