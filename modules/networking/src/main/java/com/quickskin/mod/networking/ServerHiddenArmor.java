package com.quickskin.mod.networking;

import com.mojang.datafixers.util.Pair;
import com.quickskin.mod.config.ServerConfig;
import com.quickskin.mod.networking.protocol.ProtocolCapability;
import com.quickskin.mod.networking.protocol.ProtocolProfile;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The armour pieces each connected player hides from everyone (ADR 0017). Every packet the server
 * sends passes {@link #filter} on its way to one player: the equipment of another player who hides
 * a piece goes out with that piece empty, so no client, Quick Skin or not, draws it. The item stays
 * on the player and protects as usual; only what others are told changes. With
 * {@code allowHiddenArmor} off nothing is filtered.
 */
public final class ServerHiddenArmor {
    private static final Logger LOGGER = LoggerFactory.getLogger(ServerHiddenArmor.class);
    private static final EquipmentSlot[] ARMOR = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    /** The session that told the server what it hides, and what that is. */
    private record Hidden(Object connection, int mask) {
    }

    /** Read on every packet sent, possibly off the server thread, so concurrent. */
    private static final Map<UUID, Hidden> HIDDEN = new ConcurrentHashMap<>();

    private ServerHiddenArmor() {
    }

    /** Whether {@code sender}'s client may say what {@code playerId} hides: only its own pieces. */
    static boolean admits(ServerPlayer sender, UUID playerId) {
        return sender != null && admits(ProtocolNetwork.profile(sender), sender.getUUID(), playerId);
    }

    static boolean admits(ProtocolProfile senderProfile, UUID senderId, UUID playerId) {
        return senderProfile != null
                && ProtocolNetwork.acceptsV2(senderProfile)
                && senderProfile.supports(ProtocolCapability.HIDDEN_ARMOR)
                && senderId != null
                && senderId.equals(playerId);
    }

    /** Server thread: the player's client says which pieces it hides from now on. */
    static void request(ServerPlayer player, int mask) {
        if (player == null || !HiddenArmor.isValid(mask)) return;
        Hidden previous = mask == HiddenArmor.NONE
                ? HIDDEN.remove(player.getUUID())
                : HIDDEN.put(player.getUUID(), new Hidden(player.connection, mask));
        int before = previous == null ? HiddenArmor.NONE : previous.mask();
        if (before == mask) return;
        LOGGER.debug("{} hides armour pieces {}", player.getScoreboardName(), mask);
        if (ServerConfig.getInstance().allowHiddenArmor) resend(player);
    }

    /** The pieces {@code playerId} hides, as other players see them now. */
    public static int hidden(UUID playerId) {
        if (!ServerConfig.getInstance().allowHiddenArmor) return HiddenArmor.NONE;
        Hidden hidden = HIDDEN.get(playerId);
        return hidden == null ? HiddenArmor.NONE : hidden.mask();
    }

    /**
     * The packet as {@code recipient} may receive it: another player's equipment without the
     * pieces that player hides, also inside a bundle (the equipment sent when a player comes into
     * view). The player's own equipment and everything else pass unchanged.
     */
    public static Packet<?> filter(Packet<?> packet, ServerPlayer recipient) {
        if (HIDDEN.isEmpty() || recipient == null) return packet;
        if (packet instanceof ClientboundSetEquipmentPacket equipment) {
            return filterEquipment(equipment, recipient);
        }
        if (packet instanceof ClientboundBundlePacket bundle) {
            return filterBundle(bundle, recipient);
        }
        return packet;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Packet<?> filterBundle(ClientboundBundlePacket bundle, ServerPlayer recipient) {
        List<Packet> packets = new ArrayList<>();
        boolean changed = false;
        for (Object sub : bundle.subPackets()) {
            Packet<?> original = (Packet<?>) sub;
            Packet<?> filtered = original instanceof ClientboundSetEquipmentPacket equipment
                    ? filterEquipment(equipment, recipient) : original;
            changed |= filtered != original;
            packets.add(filtered);
        }
        return changed ? new ClientboundBundlePacket((Iterable) packets) : bundle;
    }

    private static Packet<?> filterEquipment(ClientboundSetEquipmentPacket packet, ServerPlayer recipient) {
        int entityId = packet.getEntity();
        if (entityId == recipient.getId() || !ServerConfig.getInstance().allowHiddenArmor) return packet;
        Entity entity = recipient.level().getEntity(entityId);
        if (!(entity instanceof ServerPlayer subject)) return packet;
        Hidden hidden = HIDDEN.get(subject.getUUID());
        if (hidden == null) return packet;
        List<Pair<EquipmentSlot, ItemStack>> slots = new ArrayList<>();
        boolean changed = false;
        for (Pair<EquipmentSlot, ItemStack> slot : packet.getSlots()) {
            if (HiddenArmor.hides(hidden.mask(), piece(slot.getFirst())) && !slot.getSecond().isEmpty()) {
                slots.add(Pair.of(slot.getFirst(), ItemStack.EMPTY));
                changed = true;
            } else {
                slots.add(slot);
            }
        }
        return changed ? new ClientboundSetEquipmentPacket(entityId, slots) : packet;
    }

    /** The {@link HiddenArmor} bit of an armour slot, or none for the hands and animal slots. */
    private static int piece(EquipmentSlot slot) {
        if (slot == EquipmentSlot.HEAD) return HiddenArmor.HEAD;
        if (slot == EquipmentSlot.CHEST) return HiddenArmor.CHEST;
        if (slot == EquipmentSlot.LEGS) return HiddenArmor.LEGS;
        if (slot == EquipmentSlot.FEET) return HiddenArmor.FEET;
        return HiddenArmor.NONE;
    }

    /**
     * Tells every other player in the subject's world what armour the subject shows now. Each
     * copy passes {@link #filter} for its recipient; a client that does not see the subject
     * ignores it.
     */
    private static void resend(ServerPlayer subject) {
        List<Pair<EquipmentSlot, ItemStack>> slots = new ArrayList<>();
        for (EquipmentSlot slot : ARMOR) slots.add(Pair.of(slot, subject.getItemBySlot(slot).copy()));
        ClientboundSetEquipmentPacket packet = new ClientboundSetEquipmentPacket(subject.getId(), slots);
        for (ServerPlayer other : ServerNicknames.server(subject).getPlayerList().getPlayers()) {
            if (other != subject && other.level() == subject.level()) other.connection.send(packet);
        }
    }

    /** The exact session ended: what it hid no longer applies. */
    static void playerLeft(UUID playerId, Object connection) {
        Hidden hidden = HIDDEN.get(playerId);
        if (hidden != null && hidden.connection() == connection) HIDDEN.remove(playerId, hidden);
    }

    static void clear() {
        HIDDEN.clear();
    }
}
