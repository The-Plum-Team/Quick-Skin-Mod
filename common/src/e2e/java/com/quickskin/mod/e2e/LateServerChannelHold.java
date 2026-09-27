package com.quickskin.mod.e2e;

import com.quickskin.mod.networking.protocol.ProtocolProfile;
import com.quickskin.mod.networking.protocol.ProtocolSessions;
import dev.architectury.event.events.client.ClientTickEvent;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;

/**
 * Delivers the server's Architectury channel list only after Quick Skin has joined, as a busy
 * modpack does.
 *
 * <p>Architectury on Forge fills {@code NetworkManager.canServerReceive} from
 * {@code architectury:sync_ids}, which the server sends from its own login event. With few mods the
 * client handles that payload on the network thread and usually wins the race against the join
 * callback. In a large pack it is queued on the client thread behind other work (JEI took 40
 * seconds), so every server channel still looks absent when Quick Skin joins. This hold replaces
 * that one S2C receiver with a wrapper that copies the payload and hands it to the original
 * receiver only when the scenario calls {@link #release}, on the client thread. Quick Skin must
 * nevertheless negotiate at the join from the FML login handshake, which completes before it.</p>
 *
 * <p>The receiver map exists only in Architectury's Forge implementation; on every other loader
 * the hold is not applicable and does nothing. On Forge any reflective drift, including a missing
 * implementation class, is recorded and fails the scenario's evidence instead of silently testing
 * the fast path.</p>
 */
public final class LateServerChannelHold {

    /** Present only on Forge; Architectury for NeoForge ships a same-named impl without the map. */
    private static final String FORGE_LOADER = "net.minecraftforge.network.NetworkHooks";
    private static final String FORGE_IMPL = "dev.architectury.networking.forge.NetworkManagerImpl";
    private static final String RECEIVER = "dev.architectury.networking.NetworkManager$NetworkReceiver";
    /** Safety valve so a failed step cannot keep the channel list away for the rest of the run. */
    private static final int AUTO_RELEASE_TICKS_IN_WORLD = 20 * 60;

    private static boolean applicable;
    private static String installError;
    private static Object originalReceiver;
    private static volatile Pending pending;
    private static int ticksInWorld;
    private static int releasedAfterTicks = -1;
    private static boolean negotiatedBeforeRelease;
    private static String releaseReason;

    private record Pending(Method receive, ByteBuf payload, Object context) {}

    private LateServerChannelHold() {}

    /** Installs the hold before the client connects. Called once from the harness entry point. */
    public static synchronized void install() {
        try {
            Class.forName(FORGE_LOADER, false, LateServerChannelHold.class.getClassLoader());
        } catch (ClassNotFoundException notForge) {
            E2ELog.info("late server channel hold: not applicable on this loader");
            return;
        }
        applicable = true;
        try {
            Class<?> impl = Class.forName(FORGE_IMPL);
            Field s2cField = impl.getDeclaredField("S2C");
            Field idField = impl.getDeclaredField("SYNC_IDS");
            s2cField.setAccessible(true);
            idField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<Object, Object> receivers = (Map<Object, Object>) s2cField.get(null);
            Object syncIds = idField.get(null);
            Object original = receivers.get(syncIds);
            if (original == null) throw new IllegalStateException("no receiver registered for " + syncIds);
            Class<?> receiverType = Class.forName(RECEIVER, false, impl.getClassLoader());
            InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
                case "receive" -> {
                    hold(method, args);
                    yield null;
                }
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                case "toString" -> "LateServerChannelHold(" + original + ")";
                default -> method.invoke(original, args);
            };
            Object wrapper = Proxy.newProxyInstance(
                    receiverType.getClassLoader(), new Class<?>[]{receiverType}, handler);
            originalReceiver = original;
            receivers.put(syncIds, wrapper);
            ClientTickEvent.CLIENT_POST.register(LateServerChannelHold::tick);
            E2ELog.info("late server channel hold: installed for " + syncIds);
        } catch (Throwable error) {
            installError = error.toString();
            E2ELog.error("late server channel hold: could not install", error);
        }
    }

    /** Network or client thread: keep a private copy; the caller's buffer is released afterwards. */
    private static void hold(Method receive, Object[] args) {
        ByteBuf in = (ByteBuf) args[0];
        ByteBuf copy = Unpooled.copiedBuffer(in);
        in.skipBytes(in.readableBytes());
        pending = new Pending(receive, copy, args[1]);
        E2ELog.info("late server channel hold: holding architectury:sync_ids ("
                + copy.readableBytes() + " bytes)");
    }

    private static void tick(Minecraft mc) {
        // The payload can arrive on the network thread before the client thread has handled the
        // login packet, so only a disconnect after being in world discards it.
        if (mc.player == null || mc.getConnection() == null) {
            if (ticksInWorld > 0) {
                ticksInWorld = 0;
                pending = null;
            }
            return;
        }
        ticksInWorld++;
        if (ticksInWorld >= AUTO_RELEASE_TICKS_IN_WORLD && pending != null) {
            release("safety timeout");
        }
    }

    /** Client thread: delivers the held channel list to Architectury's original receiver. */
    public static synchronized void release(String reason) {
        Pending held = pending;
        if (!applicable || held == null || releaseReason != null) return;
        pending = null;
        Minecraft mc = Minecraft.getInstance();
        negotiatedBeforeRelease = mc.player != null && mc.getConnection() != null
                && clientProfile(mc).negotiated();
        releasedAfterTicks = ticksInWorld;
        releaseReason = reason;
        try {
            held.receive().invoke(originalReceiver, new FriendlyByteBuf(held.payload()), held.context());
            E2ELog.info("late server channel hold: released after " + ticksInWorld
                    + " ticks in world (" + reason + ")");
        } catch (Throwable error) {
            installError = "release failed: " + error;
            E2ELog.error("late server channel hold: release failed", error);
        }
    }

    /** Whether the loader delivers the channel list through the held Architectury receiver. */
    public static boolean applicable() {
        return applicable;
    }

    /** Whether the server's channel list has arrived and is still being held from Architectury. */
    public static boolean stillHeld() {
        return applicable && pending != null && releaseReason == null;
    }

    public static ProtocolProfile clientProfile(Minecraft mc) {
        return ProtocolSessions.getInstance().clientProfile(mc.getConnection());
    }

    /**
     * Fails unless the list really arrived late, held until the scenario released it rather than
     * the safety valve, and Quick Skin had already negotiated without it.
     */
    public static Step.Result verifyReleased() {
        if (!applicable) return Step.Result.pass("channel list hold not applicable on this loader");
        if (installError != null) return Step.Result.fail("channel list hold broken: " + installError);
        if (releaseReason == null) return Step.Result.fail("architectury:sync_ids was never held and released");
        if (!"scenario".equals(releaseReason)) {
            return Step.Result.fail("architectury:sync_ids released by " + releaseReason);
        }
        if (!negotiatedBeforeRelease) {
            return Step.Result.fail("Quick Skin had not negotiated before the held channel list arrived");
        }
        return Step.Result.pass("architectury:sync_ids delivered " + releasedAfterTicks
                + " ticks after joining; the protocol was already negotiated from the login handshake");
    }
}
