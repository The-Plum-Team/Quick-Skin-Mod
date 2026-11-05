package com.quickskin.mod.client.rendering;

import com.quickskin.mod.platform.QuickSkinInfo;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
//? if <1.21.5 {
import net.minecraft.core.NonNullList;
//?}
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Cosmetic Armor Reworked's armour on the skin menu's model. In the world that mod shows a
 * player's cosmetic armour by putting it in the player's armour slots for the length of each draw
 * of the player, a piece hidden for the skin as an empty slot, and putting the worn armour back
 * after. The preview draws the player's equipment itself, so it does the same around its own
 * draws, by the mod's own rules: nothing while the player turned the mod off on this client, an
 * empty slot for a piece hidden for the skin, and the cosmetic piece in a slot that has one,
 * unless the player hides that cosmetic piece through Quick Skin.
 *
 * <p>The skin menu treats such a slot as two pieces (ADR 0017): the cosmetic piece
 * ({@code HiddenArmor.cosmetic}, {@link #cosmeticStack}) and the armour piece under it, which the
 * cosmetic piece covers while it is shown ({@link #cosmeticShown}). Each hides on its own through
 * Quick Skin, a state the mod itself lacks: the mod's own hidden-for-the-skin toggle hides both
 * ({@link #hides}). The world follows: a cosmetic piece the player hides is not put in the slot
 * ({@link #drawnCosmetic}, from a hook in the mod's render handler), and the server leaves it out
 * of what other players are told. Where the server does not hide cosmetic pieces, a click on one
 * flips the mod's toggle instead ({@link #toggle}), as the toggle under the mod's own slots does,
 * so the mod stores and syncs it.
 *
 * <p>Before 1.21.2 the mod swaps the pieces for the first-person hand too and puts the worn ones
 * back only at its next draw, so a menu open over the world finds its swap still in the slots;
 * every draw here first puts them back ({@link #showWorn}).
 *
 * <p>The mod is reached by reflection through its API, so it is no dependency; should the
 * installed one not match, the preview draws the armour actually worn, as before. It exists for
 * Forge 1.20.1 (Cosmetic Armor Reworked) and Fabric and NeoForge 1.21.1 (its fork, whose client
 * sends through its own platform helper). From 1.21.5 a player's armour is no longer an inventory
 * list, and nothing is swapped.
 */
@Environment(EnvType.CLIENT)
public final class CosmeticArmorPreview {
    private static final String API = "lain.mods.cos.api.CosArmorAPI";
    private static final String STACKS = "lain.mods.cos.api.inventory.CAStacksBase";
    private static final String RENDER_HANDLER = "lain.mods.cos.impl.client.PlayerRenderHandler";
    /** How the original mod's client tells its server: {@code ModObjects.network.sendToServer(packet)}. */
    private static final String FORGE_OBJECTS = "lain.mods.cos.impl.ModObjects";
    private static final String FORGE_PACKET = "lain.mods.cos.impl.network.packet.PacketSetSkinArmor";
    /** ...and the fork's: {@code PlatformHelper.sendToServer(payload)}. */
    private static final String FORK_PLATFORM = "lain.mods.cos.impl.platform.PlatformHelper";
    private static final String FORK_PAYLOAD = "lain.mods.cos.impl.network.payload.PayloadSetSkinArmor";
    private static final int COSMETICS = 15 << 4;

    /** The mod's API as this game has it; null until resolved, and {@link #MISSING} without it. */
    @Nullable
    private static volatile Api api;
    private static final Api MISSING = new Api(null, null, null, null, null, null);
    /** How a hiding reaches the mod's server; null until resolved, and {@link #NO_SENDER} without one. */
    @Nullable
    private static volatile Sender sender;
    private static final Sender NO_SENDER = new Sender(null, null, null, null);

    /** The mod's API, its client switch and the queues of worn pieces its render handler puts back. */
    private record Api(Method stacksOf, Method stackInSlot, Method isSkinArmor, @Nullable Field disabled,
                       @Nullable Object putBack, @Nullable Method putBackOf) {
    }

    /** {@code CAStacksBase.setSkinArmor}, and the packet that tells the server: {@code send.invoke(target, packet(slot, hidden))}. */
    private record Sender(Method setSkinArmor, Constructor<?> packet, @Nullable Object target, Method send) {
    }

    private CosmeticArmorPreview() {
    }

    /** Runs a draw of {@code player}'s equipment with the cosmetic armour in its slots. */
    static void wearing(Player player, Runnable draw) {
        wearing(player, 0, draw);
    }

    /**
     * Runs a draw of {@code player}'s equipment with the cosmetic armour in its slots, the piece
     * {@code shown} drawn whether it is hidden or not, alone in its slot (0 for none): a cosmetic
     * piece puts its item in the slot, an armour piece leaves the worn one there. How the skin
     * menu draws a piece's ghost and traces its outline.
     */
    static void wearing(Player player, int shown, Runnable draw) {
        wearing(player, shown, () -> {
            draw.run();
            return null;
        });
    }

    /** Runs a draw of {@code player}'s equipment, or reads it, with the cosmetic armour in its slots. */
    static <T> T wearing(Player player, Supplier<T> draw) {
        return wearing(player, 0, draw);
    }

    /** As {@link #wearing(Player, int, Runnable)}, returning what the draw returns. */
    static <T> T wearing(Player player, int shown, Supplier<T> draw) {
//? if <1.21.5 {
        ItemStack[] worn = dress(player, shown);
        try {
            return draw.get();
        } finally {
            if (worn != null) {
                undress(player, worn);
            }
        }
//?} else {
        return draw.get();
//?}
    }

    /**
     * The slot of a piece, as its armour piece's {@code HiddenArmor} bit: a cosmetic piece's slot,
     * an armour piece itself, and anything else (a Curios accessory) as it is.
     */
    public static int slotOf(int piece) {
        return piece > 0 && piece < 16 ? piece : (piece & COSMETICS) == piece && piece != 0 ? piece >> 4 : piece;
    }

    /** Whether {@code piece} is a cosmetic piece ({@code HiddenArmor.cosmetic}). */
    public static boolean isCosmetic(int piece) {
        return piece != 0 && (piece & COSMETICS) == piece;
    }

    /** The cosmetic item in the slot of {@code piece}, hidden or not, or an empty stack. */
    public static ItemStack cosmeticStack(@Nullable Player player, int piece) {
//? if <1.21.5 {
        Object stacks = stacks(player);
        if (stacks == null) {
            return ItemStack.EMPTY;
        }
        try {
            Object cosmetic = api.stackInSlot().invoke(stacks, slot(slotOf(piece)));
            return cosmetic instanceof ItemStack stack ? stack : ItemStack.EMPTY;
        } catch (ReflectiveOperationException | RuntimeException error) {
            fail(error);
            return ItemStack.EMPTY;
        }
//?} else {
        return ItemStack.EMPTY;
//?}
    }

    /**
     * Whether the slot of {@code piece} shows its cosmetic piece now, over the armour piece: it
     * has one, the mod does not hide the slot for the skin, and the player does not hide the
     * cosmetic piece where the server hides it.
     */
    public static boolean cosmeticShown(@Nullable Player player, int piece) {
        int slot = slotOf(piece);
        return slot > 0 && slot < 16 && (hiddenCosmetics() & slot << 4) == 0
                && !cosmeticStack(player, slot).isEmpty() && !hides(player, slot);
    }

    /** The armour pieces of {@code player} whose slot shows a cosmetic piece now, as {@code HiddenArmor} bits. */
    public static int cosmeticsShown(@Nullable Player player) {
        int shown = 0;
        for (int slot = 1; slot < 16; slot <<= 1) {
            if (cosmeticShown(player, slot)) {
                shown |= slot;
            }
        }
        return shown;
    }

    /**
     * What the mod's render handler puts in slot {@code slot} (0 for the feet up to 3 for the head)
     * of the player whose slots {@code stacks} are: its cosmetic item, or nothing for the local
     * player's cosmetic piece hidden through Quick Skin, so the worn piece stays. The handler's
     * own read of the slot, which a hook replaces with this one.
     */
    public static ItemStack drawnCosmetic(Object stacks, int slot) {
        ItemStack cosmetic;
        try {
            cosmetic = api() == MISSING ? ItemStack.EMPTY : (ItemStack) api.stackInSlot().invoke(stacks, slot);
        } catch (ReflectiveOperationException | RuntimeException error) {
            fail(error);
            return ItemStack.EMPTY;
        }
        if (cosmetic == null || cosmetic.isEmpty() || slot < 0 || slot > 3 || (hiddenCosmetics() & 1 << slot + 4) == 0) {
            return cosmetic == null ? ItemStack.EMPTY : cosmetic;
        }
        Player local = net.minecraft.client.Minecraft.getInstance().player;
        return local != null && stacks == stacks(local) ? ItemStack.EMPTY : cosmetic;
    }

    /** Whether the player hides the slot of a piece for the skin through the mod, both its pieces. */
    public static boolean hides(@Nullable Player player, int piece) {
//? if <1.21.5 {
        Object stacks = stacks(player);
        if (stacks == null) {
            return false;
        }
        try {
            return (Boolean) api.isSkinArmor().invoke(stacks, slot(slotOf(piece)));
        } catch (ReflectiveOperationException | RuntimeException error) {
            fail(error);
            return false;
        }
//?} else {
        return false;
//?}
    }

    /** The local player's cosmetic pieces nobody sees now, as {@code HiddenArmor} bits. */
    private static int hiddenCosmetics() {
        return com.quickskin.mod.client.services.LocalHiddenArmor.inEffect() & COSMETICS;
    }

    /**
     * Hides an armour piece ({@code HiddenArmor} bit) for the skin, or shows it again, as the
     * toggle under the mod's slot does: on this client and on the server, which tells everyone.
     * False when the mod is missing, turned off on this client or cannot be told, and nothing
     * changed.
     */
    public static boolean toggle(@Nullable Player player, int piece) {
        return setHidden(player, piece, !hides(player, piece));
    }

    /**
     * Hides the slot of a piece for the skin through the mod, or shows it again, as {@link #toggle};
     * false when nothing changed.
     */
    public static boolean setHidden(@Nullable Player player, int piece, boolean hide) {
//? if <1.21.5 {
        Object stacks = stacks(player);
        Sender sender = stacks == null ? NO_SENDER : sender();
        if (sender == NO_SENDER) {
            return false;
        }
        int slot = slot(slotOf(piece));
        try {
            if (hide == (Boolean) api.isSkinArmor().invoke(stacks, slot)) {
                return false;
            }
            sender.send().invoke(sender.target(), sender.packet().newInstance(slot, hide));
            sender.setSkinArmor().invoke(stacks, slot, hide);
            return true;
        } catch (ReflectiveOperationException | RuntimeException error) {
            CosmeticArmorPreview.sender = NO_SENDER;
            QuickSkinInfo.LOGGER.warn("The skin menu cannot hide Cosmetic Armor Reworked's armour", error);
            return false;
        }
//?} else {
        return false;
//?}
    }

    /**
     * Puts back the worn armour pieces the mod's render handler left in {@code player}'s slots
     * after it drew them, as the handler itself does at its next draw.
     */
    public static void showWorn(@Nullable Player player) {
        Api api = api();
        if (player == null || api.putBackOf() == null) {
            return;
        }
        try {
            if (api.putBackOf().invoke(api.putBack(), player) instanceof java.util.Deque<?> queue) {
                Object putBack;
                while ((putBack = queue.poll()) != null) {
                    if (putBack instanceof Runnable runnable) {
                        runnable.run();
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException error) {
            fail(error);
        }
    }

    /** The mod's armour slot of an armour piece ({@code HiddenArmor} bit): 0 for the feet up to 3 for the head. */
    private static int slot(int piece) {
        return Integer.numberOfTrailingZeros(piece & 15);
    }

    /** The mod's slots of {@code player} as this client has them, or null while it shows nothing. */
    @Nullable
    private static Object stacks(@Nullable Player player) {
        Api api = api();
        if (api == MISSING || player == null) {
            return null;
        }
        try {
            if (api.disabled() != null && api.disabled().getBoolean(null)) {
                return null;
            }
            return api.stacksOf().invoke(null, player.getUUID());
        } catch (ReflectiveOperationException | RuntimeException error) {
            fail(error);
            return null;
        }
    }

//? if <1.21.5 {
    /** Puts the cosmetic pieces in the armour slots; what was there before, or null when nothing changed. */
    @Nullable
    private static ItemStack[] dress(Player player, int shownPiece) {
        Object stacks = stacks(player);
        if (stacks == null) {
            return null;
        }
        // The worn pieces, not the mod's swap left over from its last draw
        showWorn(player);
        NonNullList<ItemStack> armor = player.getInventory().armor;
        ItemStack[] worn = null;
        int hiddenCosmetics = hiddenCosmetics();
        try {
            for (int slot = 0; slot < armor.size(); slot++) {
                int piece = 1 << slot;
                ItemStack shown;
                if (shownPiece == piece) {
                    // The armour piece alone: the worn one stays
                    continue;
                } else if (shownPiece != piece << 4 && (Boolean) api.isSkinArmor().invoke(stacks, slot)) {
                    shown = ItemStack.EMPTY;
                } else {
                    Object cosmetic = api.stackInSlot().invoke(stacks, slot);
                    if (!(cosmetic instanceof ItemStack stack) || stack.isEmpty()
                            || shownPiece != piece << 4 && (hiddenCosmetics & piece << 4) != 0) {
                        continue;
                    }
                    shown = stack;
                }
                if (worn == null) {
                    worn = armor.toArray(new ItemStack[0]);
                }
                armor.set(slot, shown);
            }
            return worn;
        } catch (ReflectiveOperationException | RuntimeException error) {
            if (worn != null) {
                undress(player, worn);
            }
            fail(error);
            return null;
        }
    }

    private static void undress(Player player, ItemStack[] worn) {
        NonNullList<ItemStack> armor = player.getInventory().armor;
        for (int slot = 0; slot < worn.length && slot < armor.size(); slot++) {
            armor.set(slot, worn[slot]);
        }
    }
//?}

    private static Api api() {
        Api resolved = api;
        if (resolved == null) {
            resolved = resolve();
            api = resolved;
        }
        return resolved;
    }

    /** Gives up on the mod for the session, so the preview draws the armour actually worn. */
    private static void fail(Throwable error) {
        if (api != MISSING) {
            api = MISSING;
            QuickSkinInfo.LOGGER.warn("The skin menu cannot show Cosmetic Armor Reworked's armour; it shows the armour worn", error);
        }
    }

    private static Api resolve() {
        ClassLoader loader = CosmeticArmorPreview.class.getClassLoader();
        try {
            Class<?> apiClass = Class.forName(API, false, loader);
            Class<?> stacks = Class.forName(STACKS, false, loader);
            Field disabled = null;
            Object putBack = null;
            Method putBackOf = null;
            try {
                Class<?> handler = Class.forName(RENDER_HANDLER, false, loader);
                disabled = handler.getField("Disabled");
                // The handler's one instance keeps, per player, what to put back after its draw
                Object instance = handler.isEnum() ? handler.getEnumConstants()[0] : null;
                for (Field field : handler.getDeclaredFields()) {
                    if (instance != null && !Modifier.isStatic(field.getModifiers())
                            && com.google.common.cache.Cache.class.isAssignableFrom(field.getType())
                            && field.getGenericType().getTypeName().contains("Deque")) {
                        field.setAccessible(true);
                        putBack = field.get(instance);
                        putBackOf = com.google.common.cache.Cache.class.getMethod("getIfPresent", Object.class);
                    }
                }
            } catch (ReflectiveOperationException | LinkageError | RuntimeException absent) {
                // Without the client toggle the cosmetic armour always shows
            }
            return new Api(apiClass.getMethod("getCAStacksClient", UUID.class),
                    stacks.getMethod("getStackInSlot", int.class),
                    stacks.getMethod("isSkinArmor", int.class),
                    disabled, putBack, putBack == null ? null : putBackOf);
        } catch (ClassNotFoundException absent) {
            return MISSING;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException changed) {
            QuickSkinInfo.LOGGER.warn("The skin menu cannot show Cosmetic Armor Reworked's armour; it shows the armour worn", changed);
            return MISSING;
        }
    }

    private static Sender sender() {
        Sender resolved = sender;
        if (resolved == null) {
            resolved = resolveSender();
            sender = resolved;
        }
        return resolved;
    }

    private static Sender resolveSender() {
        ClassLoader loader = CosmeticArmorPreview.class.getClassLoader();
        try {
            Method setSkinArmor = Class.forName(STACKS, false, loader).getMethod("setSkinArmor", int.class, boolean.class);
            try {
                // The fork
                Constructor<?> payload = Class.forName(FORK_PAYLOAD, false, loader).getConstructor(int.class, boolean.class);
                return new Sender(setSkinArmor, payload, null, staticSend(Class.forName(FORK_PLATFORM, false, loader)));
            } catch (ClassNotFoundException original) {
                Constructor<?> packet = Class.forName(FORGE_PACKET, false, loader).getConstructor(int.class, boolean.class);
                Object network = Class.forName(FORGE_OBJECTS, false, loader).getField("network").get(null);
                for (Method method : network.getClass().getMethods()) {
                    if (method.getName().equals("sendToServer") && method.getParameterCount() == 1
                            && method.getParameterTypes()[0].isAssignableFrom(packet.getDeclaringClass())) {
                        return new Sender(setSkinArmor, packet, network, method);
                    }
                }
                throw new NoSuchMethodException("sendToServer");
            }
        } catch (ReflectiveOperationException | LinkageError | RuntimeException changed) {
            QuickSkinInfo.LOGGER.warn("The skin menu cannot hide Cosmetic Armor Reworked's armour", changed);
            return NO_SENDER;
        }
    }

    /** The fork's static {@code sendToServer(payload)}. */
    private static Method staticSend(Class<?> platform) throws NoSuchMethodException {
        for (Method method : platform.getMethods()) {
            if (method.getName().equals("sendToServer") && method.getParameterCount() == 1
                    && Modifier.isStatic(method.getModifiers())) {
                return method;
            }
        }
        throw new NoSuchMethodException("sendToServer");
    }
}
