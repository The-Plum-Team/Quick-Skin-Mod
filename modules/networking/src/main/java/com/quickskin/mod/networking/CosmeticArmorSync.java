package com.quickskin.mod.networking;

import com.quickskin.mod.platform.QuickSkinInfo;
//? if <1.20.2 {
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
//?} else {
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
//?}
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.UUID;
import java.util.function.ToIntFunction;

/**
 * Cosmetic Armor Reworked's slot sync as other players receive it, where a player hides a
 * cosmetic piece through Quick Skin (ADR 0017): the copy every other player gets goes without the
 * cosmetic item, so their Cosmetic Armor draws the armour piece in its place, which the equipment
 * filter hides in turn if the player hides that too. The player's own copy passes unchanged, so
 * that mod's inventory keeps the item.
 *
 * <p>The mod is reached by reflection, so it is no dependency: on Forge 1.20.1 its sync is the
 * bytes {@link CosmeticArmorSyncMessage} reads, and from 1.20.5 a payload record of its own (the
 * original's and its fork's alike). When the player changes what they hide, the mod is asked to
 * send their slots again ({@link #resend}), through the same filter. Should the installed one not
 * match, its sync passes unchanged and others keep seeing the cosmetic piece.
 */
final class CosmeticArmorSync {
    private static final String PAYLOAD = "lain.mods.cos.impl.network.payload.PayloadSyncCosArmor";
    private static final String API = "lain.mods.cos.api.CosArmorAPI";
    private static final String OBJECTS = "lain.mods.cos.impl.ModObjects";
    private static final int SLOTS = 4;

    /** The payload record's accessors and constructor; null until one is seen, {@link #UNKNOWN} if it does not fit. */
    private record Sync(Method player, Method slot, Method hidden, Constructor<?> create) {
    }

    private static final Sync UNKNOWN = new Sync(null, null, null, null);
    @Nullable
    private static volatile Sync sync;
    private static volatile boolean resendFailed;

    private CosmeticArmorSync() {
    }

    /**
     * The packet as {@code recipient} may receive it: another player's slot sync without the
     * cosmetic item while that player hides the piece ({@code hidden} gives a player's pieces, as
     * {@link HiddenArmor} names them). Everything else passes unchanged.
     */
    static Packet<?> filter(Packet<?> packet, ServerPlayer recipient, ToIntFunction<UUID> hidden) {
        if (!(packet instanceof ClientboundCustomPayloadPacket custom)) return packet;
//? if <1.20.2 {
        if (!CosmeticArmorSyncMessage.CHANNEL.equals(custom.getIdentifier().toString())) return packet;
        FriendlyByteBuf data = custom.getData();
        byte[] bytes = new byte[data.readableBytes()];
        data.getBytes(data.readerIndex(), bytes);
        CosmeticArmorSyncMessage.Slot slot = CosmeticArmorSyncMessage.read(bytes);
        if (slot == null || slot.player().equals(recipient.getUUID())
                || !HiddenArmor.hides(hidden.applyAsInt(slot.player()), HiddenArmor.cosmetic(slot.piece()))) {
            return packet;
        }
        return new ClientboundCustomPayloadPacket(custom.getIdentifier(),
                new FriendlyByteBuf(Unpooled.wrappedBuffer(CosmeticArmorSyncMessage.withoutCosmetic(bytes))));
//?} else {
        Object payload = custom.payload();
        if (payload == null || !PAYLOAD.equals(payload.getClass().getName())) return packet;
        Sync fields = sync(payload.getClass());
        if (fields == UNKNOWN) return packet;
        try {
            UUID player = (UUID) fields.player().invoke(payload);
            int slot = ((Number) fields.slot().invoke(payload)).intValue();
            if (player == null || player.equals(recipient.getUUID()) || slot < 0 || slot >= SLOTS
                    || !HiddenArmor.hides(hidden.applyAsInt(player), HiddenArmor.cosmetic(1 << slot))) {
                return packet;
            }
            Object without = fields.create().newInstance(player, slot, fields.hidden().invoke(payload), ItemStack.EMPTY);
            return new ClientboundCustomPayloadPacket((CustomPacketPayload) without);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            sync = UNKNOWN;
            QuickSkinInfo.LOGGER.warn("Quick Skin cannot hide Cosmetic Armor Reworked's pieces from other players", error);
            return packet;
        }
//?}
    }

    /**
     * Has the mod send every player {@code subject}'s slots again, as it does when one changes, so
     * each copy passes {@link #filter} with what the player hides now.
     */
    static void resend(ServerPlayer subject) {
        if (resendFailed) return;
        ClassLoader loader = CosmeticArmorSync.class.getClassLoader();
        try {
            Class<?> api;
            try {
                api = Class.forName(API, false, loader);
            } catch (ClassNotFoundException absent) {
                return;
            }
            UUID player = subject.getUUID();
            Object inventory = api.getMethod("getCAStacks", UUID.class).invoke(null, player);
            Object manager = Class.forName(OBJECTS, false, loader).getField("invMan").get(null);
            Method changed = null;
            for (Class<?> type = manager.getClass(); type != null && changed == null; type = type.getSuperclass()) {
                for (Method method : type.getDeclaredMethods()) {
                    if (method.getName().equals("onInventoryChanged") && method.getParameterCount() == 3
                            && method.getParameterTypes()[0] == UUID.class && method.getParameterTypes()[2] == int.class
                            && method.getParameterTypes()[1].isInstance(inventory)) {
                        changed = method;
                    }
                }
            }
            if (changed == null) throw new NoSuchMethodException("onInventoryChanged");
            changed.setAccessible(true);
            for (int slot = 0; slot < SLOTS; slot++) {
                changed.invoke(manager, player, inventory, slot);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            resendFailed = true;
            QuickSkinInfo.LOGGER.warn("Quick Skin cannot have Cosmetic Armor Reworked show other players what a player hides now", error);
        }
    }

//? if >=1.20.2 {
    /** The accessors and constructor of the mod's slot sync record: player, slot, hidden flag, item. */
    private static Sync sync(Class<?> type) {
        Sync resolved = sync;
        if (resolved != null) return resolved;
        try {
            RecordComponent[] components = type.getRecordComponents();
            if (components == null || components.length != 4 || components[0].getType() != UUID.class
                    || components[1].getType() != int.class || components[2].getType() != boolean.class
                    || components[3].getType() != ItemStack.class) {
                throw new NoSuchMethodException(type.getName() + " is not (UUID, int, boolean, ItemStack)");
            }
            Constructor<?> create = type.getDeclaredConstructor(UUID.class, int.class, boolean.class, ItemStack.class);
            create.setAccessible(true);
            resolved = new Sync(components[0].getAccessor(), components[1].getAccessor(),
                    components[2].getAccessor(), create);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            QuickSkinInfo.LOGGER.warn("Quick Skin cannot hide Cosmetic Armor Reworked's pieces from other players", error);
            resolved = UNKNOWN;
        }
        sync = resolved;
        return resolved;
    }
//?}
}
