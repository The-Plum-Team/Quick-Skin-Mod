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
 * empty slot for a piece hidden for the skin, and the cosmetic piece in a slot that has one.
 *
 * <p>The skin menu treats such a slot as one piece: it shows the cosmetic piece where there is
 * one ({@link #stack}), lays a slot hidden for the skin on the floor ({@link #hides}), and a click
 * flips that hiding as the toggle under the mod's own slots does ({@link #toggle}), so the mod
 * stores and syncs it.
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

    /** The mod's API as this game has it; null until resolved, and {@link #MISSING} without it. */
    @Nullable
    private static volatile Api api;
    private static final Api MISSING = new Api(null, null, null, null);
    /** How a hiding reaches the mod's server; null until resolved, and {@link #NO_SENDER} without one. */
    @Nullable
    private static volatile Sender sender;
    private static final Sender NO_SENDER = new Sender(null, null, null, null);

    private record Api(Method stacksOf, Method stackInSlot, Method isSkinArmor, @Nullable Field disabled) {
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
     * {@code shown} ({@code HiddenArmor} bit, 0 for none) as it would be were it not hidden for the
     * skin: how the skin menu draws a hidden piece's ghost and traces its outline.
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
     * What the skin menu shows of an armour piece ({@code HiddenArmor} bit) whose slot holds
     * {@code worn}: the cosmetic piece in that slot when there is one, hidden or not, and else what
     * is worn.
     */
    public static ItemStack stack(@Nullable Player player, int piece, ItemStack worn) {
//? if <1.21.5 {
        Object stacks = stacks(player);
        if (stacks == null) {
            return worn;
        }
        try {
            Object cosmetic = api.stackInSlot().invoke(stacks, slot(piece));
            return cosmetic instanceof ItemStack stack && !stack.isEmpty() ? stack : worn;
        } catch (ReflectiveOperationException | RuntimeException error) {
            fail(error);
            return worn;
        }
//?} else {
        return worn;
//?}
    }

    /** Whether the player hides an armour piece ({@code HiddenArmor} bit) for the skin, through the mod. */
    public static boolean hides(@Nullable Player player, int piece) {
//? if <1.21.5 {
        Object stacks = stacks(player);
        if (stacks == null) {
            return false;
        }
        try {
            return (Boolean) api.isSkinArmor().invoke(stacks, slot(piece));
        } catch (ReflectiveOperationException | RuntimeException error) {
            fail(error);
            return false;
        }
//?} else {
        return false;
//?}
    }

    /**
     * Hides an armour piece ({@code HiddenArmor} bit) for the skin, or shows it again, as the
     * toggle under the mod's slot does: on this client and on the server, which tells everyone.
     * False when the mod is missing, turned off on this client or cannot be told, and nothing
     * changed.
     */
    public static boolean toggle(@Nullable Player player, int piece) {
//? if <1.21.5 {
        Object stacks = stacks(player);
        Sender sender = stacks == null ? NO_SENDER : sender();
        if (sender == NO_SENDER) {
            return false;
        }
        int slot = slot(piece);
        try {
            boolean hide = !(Boolean) api.isSkinArmor().invoke(stacks, slot);
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
        NonNullList<ItemStack> armor = player.getInventory().armor;
        ItemStack[] worn = null;
        try {
            for (int slot = 0; slot < armor.size(); slot++) {
                ItemStack shown;
                if (shownPiece != 1 << slot && (Boolean) api.isSkinArmor().invoke(stacks, slot)) {
                    shown = ItemStack.EMPTY;
                } else {
                    Object cosmetic = api.stackInSlot().invoke(stacks, slot);
                    if (!(cosmetic instanceof ItemStack stack) || stack.isEmpty()) {
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
            try {
                disabled = Class.forName(RENDER_HANDLER, false, loader).getField("Disabled");
            } catch (ReflectiveOperationException | LinkageError absent) {
                // Without the client toggle the cosmetic armour always shows
            }
            return new Api(apiClass.getMethod("getCAStacksClient", UUID.class),
                    stacks.getMethod("getStackInSlot", int.class),
                    stacks.getMethod("isSkinArmor", int.class),
                    disabled);
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
