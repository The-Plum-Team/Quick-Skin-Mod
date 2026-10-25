package com.quickskin.mod.client.rendering;

import com.quickskin.mod.platform.QuickSkinInfo;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Curios' accessories on the skin menu's model, each one a piece of its own that the menu can
 * ghost and hide like armour. Hiding one flips Curios' own render toggle, the eye of its
 * inventory, so Curios stores it, syncs it and other players see it hidden as they would after
 * that eye. Before 1.21.2 only, where Curios draws an accessory from the entity.
 *
 * <p>Curios is reached by reflection, so it is no dependency. Its layer's own loop is repeated
 * here: a cosmetic stack is always drawn, the stack in the slot only while its render toggle is
 * on. Only the latter is a piece. Should any of it not match the installed Curios, the preview
 * draws Curios' layer as before.
 */
@Environment(EnvType.CLIENT)
public final class CuriosPreview {
    /** Piece numbers of accessories start here, above the armour bits and the held items. */
    public static final int FIRST_PIECE = 256;
    /** At most this many accessories are pieces; any further one is drawn but not hideable. */
    private static final int MAX_PIECES = 64;
    private static final String LAYER = "top.theillusivec4.curios.client.render.CuriosLayer";

//? if <1.21.2 {
    private static final boolean ERA = true;
//?} else {
    private static final boolean ERA = false;
//?}

    /** One accessory the layer would draw, or a hidden one: its slot, index and stack. */
    record Accessory(String slot, int index, ItemStack stack, boolean cosmetic, boolean shown, Object renderer) {
    }

    /** Curios' API as this game has it; null until resolved, and {@link #MISSING} without it. */
    @Nullable
    private static volatile Api api;
    private static final Api MISSING = new Api(null, null, null, null, null, null, null, null, null, null,
            null, null, null, null);

    private CuriosPreview() {
    }

    /** Whether a layer is Curios', which {@link #render} replaces where accessories are pieces. */
    static boolean drawsInstead(Object layer) {
        return ERA && LAYER.equals(layer.getClass().getName()) && api() != MISSING;
    }

    /** Whether a piece number is an accessory's. */
    public static boolean isAccessory(int piece) {
        return piece >= FIRST_PIECE;
    }

    /** The accessories the player wears that are pieces, as piece numbers, shown or hidden. */
    public static int[] pieces(@Nullable LivingEntity player) {
        if (!ERA || player == null) {
            return new int[0];
        }
        int count = 0;
        for (Accessory accessory : accessories(player)) {
            if (!accessory.cosmetic() && count < MAX_PIECES) {
                count++;
            }
        }
        int[] pieces = new int[count];
        for (int i = 0; i < count; i++) {
            pieces[i] = FIRST_PIECE + i;
        }
        return pieces;
    }

    /** The stack of an accessory piece, or an empty stack. */
    public static ItemStack stack(@Nullable LivingEntity player, int piece) {
        Accessory accessory = piece(player, piece);
        return accessory == null ? ItemStack.EMPTY : accessory.stack();
    }

    /** Whether an accessory piece is drawn on the player, its render toggle on. */
    public static boolean shown(@Nullable LivingEntity player, int piece) {
        Accessory accessory = piece(player, piece);
        return accessory != null && accessory.shown();
    }

    /**
     * Flips an accessory piece's render toggle, as the eye of Curios' inventory does: Curios'
     * server flips it and syncs it back.
     */
    public static boolean toggle(@Nullable LivingEntity player, int piece) {
        Accessory accessory = piece(player, piece);
        Api curios = api();
        if (accessory == null || curios == MISSING) {
            return false;
        }
        try {
            Object packet = curios.togglePacket.newInstance(accessory.slot(), accessory.index());
            if (curios.forgeChannel != null) {
                curios.forgeSend.invoke(curios.forgeChannel, packet);
            } else {
                Object others = java.lang.reflect.Array.newInstance(curios.neoSend.getParameterTypes()[1].getComponentType(), 0);
                curios.neoSend.invoke(null, packet, others);
            }
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            fail(error);
            return false;
        }
    }

    /**
     * Draws the accessories the layer would draw on {@code player}, but {@code leftOut}, which
     * the ghost draws (0 for none).
     */
    static void render(LivingEntity player, Object renderer, Object poseStack, Object buffers, int light, int leftOut) {
        List<Accessory> accessories = accessories(player);
        int piece = FIRST_PIECE;
        for (Accessory accessory : accessories) {
            int number = accessory.cosmetic() ? 0 : piece++;
            if ((accessory.cosmetic() || accessory.shown()) && (number == 0 || number != leftOut)) {
                draw(player, accessory, renderer, poseStack, buffers, light);
            }
        }
    }

    /** Draws one accessory piece, shown or hidden, as its ghost or outline needs it. */
    static void renderPiece(LivingEntity player, int piece, Object renderer, Object poseStack, Object buffers, int light) {
        Accessory accessory = piece(player, piece);
        if (accessory != null) {
            draw(player, accessory, renderer, poseStack, buffers, light);
        }
    }

    private static void draw(LivingEntity player, Accessory accessory, Object renderer, Object poseStack,
                             Object buffers, int light) {
        Api curios = api();
        try {
            Object context = curios.slotContext.newInstance(accessory.slot(), player, accessory.index(),
                    accessory.cosmetic(), true);
            curios.render.invoke(accessory.renderer(), accessory.stack(), context, poseStack, renderer, buffers,
                    light, 0.0F, 0.0F, 1.0F, (float) player.tickCount, 0.0F, 0.0F);
        } catch (ReflectiveOperationException error) {
            Throwable cause = error.getCause() != null ? error.getCause() : error;
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof LinkageError linkage) {
                throw linkage;
            }
            throw new IllegalStateException(cause);
        }
    }

    @Nullable
    private static Accessory piece(@Nullable LivingEntity player, int piece) {
        if (!ERA || player == null || !isAccessory(piece)) {
            return null;
        }
        int wanted = piece - FIRST_PIECE;
        int at = 0;
        for (Accessory accessory : accessories(player)) {
            if (!accessory.cosmetic()) {
                if (at == wanted) {
                    return accessory;
                }
                at++;
            }
        }
        return null;
    }

    /** What Curios' layer loops over, in slot order, with a renderer each; empty without Curios. */
    @SuppressWarnings("unchecked")
    private static List<Accessory> accessories(LivingEntity player) {
        Api curios = api();
        if (curios == MISSING) {
            return Collections.emptyList();
        }
        try {
            Object inventory = curios.inventory.invoke(null, player);
            Optional<?> handler = inventory instanceof Optional<?> optional ? optional
                    : (Optional<?>) inventory.getClass().getMethod("resolve").invoke(inventory);
            if (handler.isEmpty()) {
                return Collections.emptyList();
            }
            Map<String, ?> curiosBySlot = new TreeMap<>((Map<String, ?>) curios.curios.invoke(handler.get()));
            List<Accessory> accessories = new ArrayList<>();
            for (Map.Entry<String, ?> entry : curiosBySlot.entrySet()) {
                Object handlerOfSlot = entry.getValue();
                Object stacks = curios.stacks.invoke(handlerOfSlot);
                Object cosmetics = curios.cosmetics.invoke(handlerOfSlot);
                List<Boolean> renders = (List<Boolean>) curios.renders.invoke(handlerOfSlot);
                int slots = (int) curios.slots.invoke(stacks);
                for (int i = 0; i < slots; i++) {
                    boolean shown = renders.size() > i && Boolean.TRUE.equals(renders.get(i));
                    ItemStack stack = (ItemStack) curios.stackInSlot.invoke(cosmetics, i);
                    boolean cosmetic = !stack.isEmpty();
                    if (!cosmetic) {
                        stack = (ItemStack) curios.stackInSlot.invoke(stacks, i);
                    }
                    if (stack.isEmpty()) {
                        continue;
                    }
                    Optional<?> renderer = (Optional<?>) curios.renderer.invoke(null, stack.getItem());
                    if (renderer.isPresent()) {
                        accessories.add(new Accessory(entry.getKey(), i, stack, cosmetic, cosmetic || shown, renderer.get()));
                    }
                }
            }
            return accessories;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            fail(error);
            return Collections.emptyList();
        }
    }

    private static Api api() {
        Api resolved = api;
        if (resolved == null) {
            resolved = ERA ? resolve() : MISSING;
            api = resolved;
        }
        return resolved;
    }

    /** Gives up on Curios for the session, so its layer draws as before. */
    private static void fail(Throwable error) {
        if (api != MISSING) {
            api = MISSING;
            QuickSkinInfo.LOGGER.warn("The skin menu cannot hide Curios accessories; it draws them as Curios does", error);
        }
    }

    private static Api resolve() {
        try {
            ClassLoader loader = CuriosPreview.class.getClassLoader();
            Class<?> curiosApi = Class.forName("top.theillusivec4.curios.api.CuriosApi", false, loader);
            Class<?> itemHandler = Class.forName("top.theillusivec4.curios.api.type.capability.ICuriosItemHandler", false, loader);
            Class<?> stacksHandler = Class.forName("top.theillusivec4.curios.api.type.inventory.ICurioStacksHandler", false, loader);
            Class<?> dynamicHandler = Class.forName("top.theillusivec4.curios.api.type.inventory.IDynamicStackHandler", false, loader);
            Class<?> registry = Class.forName("top.theillusivec4.curios.api.client.CuriosRendererRegistry", false, loader);
            Class<?> curioRenderer = Class.forName("top.theillusivec4.curios.api.client.ICurioRenderer", false, loader);
            Class<?> slotContext = Class.forName("top.theillusivec4.curios.api.SlotContext", false, loader);
            Class<?> toggle = Class.forName("top.theillusivec4.curios.common.network.client.CPacketToggleRender", false, loader);
            Method render = null;
            for (Method method : curioRenderer.getMethods()) {
                if (method.getName().equals("render") && method.getParameterCount() == 12) {
                    render = method;
                }
            }
            if (render == null) {
                return MISSING;
            }
            // Forge's Curios sends through its own channel, NeoForge's through the distributor
            Object forgeChannel = null;
            Method forgeSend = null;
            Method neoSend = null;
            try {
                Class<?> network = Class.forName("top.theillusivec4.curios.common.network.NetworkHandler", false, loader);
                Field instance = network.getField("INSTANCE");
                forgeChannel = instance.get(null);
                forgeSend = forgeChannel == null ? null : forgeChannel.getClass().getMethod("sendToServer", Object.class);
            } catch (ClassNotFoundException | NoSuchFieldException | NoSuchMethodException notForge) {
                forgeChannel = null;
            }
            if (forgeChannel == null || forgeSend == null) {
                forgeChannel = null;
                Class<?> distributor = Class.forName("net.neoforged.neoforge.network.PacketDistributor", false, loader);
                for (Method method : distributor.getMethods()) {
                    if (method.getName().equals("sendToServer") && method.getParameterCount() == 2) {
                        neoSend = method;
                    }
                }
                if (neoSend == null) {
                    return MISSING;
                }
            }
            return new Api(curiosApi.getMethod("getCuriosInventory", LivingEntity.class),
                    itemHandler.getMethod("getCurios"), stacksHandler.getMethod("getStacks"),
                    stacksHandler.getMethod("getCosmeticStacks"), stacksHandler.getMethod("getRenders"),
                    dynamicHandler.getMethod("getSlots"), dynamicHandler.getMethod("getStackInSlot", int.class),
                    registry.getMethod("getRenderer", net.minecraft.world.item.Item.class), render,
                    slotContext.getConstructor(String.class, LivingEntity.class, int.class, boolean.class, boolean.class),
                    toggle.getConstructor(String.class, int.class), forgeChannel, forgeSend, neoSend);
        } catch (ClassNotFoundException absent) {
            return MISSING;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError changed) {
            QuickSkinInfo.LOGGER.warn("The skin menu cannot hide Curios accessories; it draws them as Curios does", changed);
            return MISSING;
        }
    }

    /** Curios' methods this preview calls. */
    private record Api(Method inventory, Method curios, Method stacks, Method cosmetics, Method renders,
                       Method slots, Method stackInSlot, Method renderer, Method render,
                       Constructor<?> slotContext, Constructor<?> togglePacket, @Nullable Object forgeChannel,
                       @Nullable Method forgeSend, @Nullable Method neoSend) {
    }
}
