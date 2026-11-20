package com.quickskin.mod.client.gui.screen;

import com.quickskin.mod.client.rendering.WorldArmor;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;
import java.util.function.IntPredicate;

/**
 * The skin menu's floor in the immersive view: the pieces nobody sees lie on the real ground round
 * the player's feet, as they lie round the flat menu's turntable ring ({@link PedestalDrops}, which
 * lays them out, drops them and lifts them back here too), and the world's draw of the player
 * draws them there ({@link WorldArmor#show}).
 *
 * <p>The floor is seen from the camera's resting place, so it keeps facing the camera as the
 * camera is turned round the player, as the flat menu's ring stays in front while its model
 * turns. {@code PedestalDrops} works in the flat menu's units here, as many to a block as the
 * menu draws the player, with x to the camera's right, y down from the ground and z towards the
 * camera; the ring is a circle on the ground, its front towards the camera.
 */
final class WorldPedestal {
    /** How far in front of the middle of the player's body, in blocks, an item leaves it or reaches it. */
    private static final float IN_FRONT = 0.2f;
    private final PedestalDrops drops = new PedestalDrops();
    private float pixelsPerBlock = 1.0f;
    private float size = 16.0f;
    /** The camera's resting yaw, and its right and the way back towards it, along the ground. */
    private float cameraYaw;
    private float rightX = 1.0f;
    private float rightZ;
    private float towardsX;
    private float towardsZ = 1.0f;

    /**
     * Brings the floor up to {@code now}, as the flat menu's {@code PedestalDrops.update}: {@code
     * pieces} lie on it, spaced for {@code room}, items {@code size} units wide round a ring
     * {@code radius} units across that reach at most {@code reach} to either side, with {@code
     * pixelsPerBlock} units to a block, seen from a camera resting at {@code cameraYaw}.
     */
    void update(int[] pieces, int room, float radius, float size, float reach, float pixelsPerBlock,
                float cameraYaw, long now) {
        this.pixelsPerBlock = Math.max(1.0f, pixelsPerBlock);
        this.size = size;
        this.cameraYaw = cameraYaw;
        double yaw = Math.toRadians(cameraYaw);
        // The way the camera looks is (-sin, cos) along the ground; its right is a quarter turn on
        float forwardX = (float) -Math.sin(yaw);
        float forwardZ = (float) Math.cos(yaw);
        rightX = -forwardZ;
        rightZ = forwardX;
        towardsX = -forwardX;
        towardsZ = -forwardZ;
        drops.update(pieces, slot -> {
            // The ring's band on the GUI becomes a circle on the ground: its depth is the GUI's down.
            // Each place lies on the ground found under it, which is not always level with the feet
            float[] place = PedestalDrops.place(slot, Math.max(room, slot + 1), 0.0f, 0.0f, radius, radius, size, reach);
            float[] world = toWorld(place[0], 0.0f, place[1]);
            return new float[]{place[0], -WorldArmor.groundAt(world[0], world[2]) * this.pixelsPerBlock, place[1]};
        }, size, this::wornAt, now);
    }

    /** Where the player wears a piece, as an item's place would be given, or null. */
    @Nullable
    private float[] wornAt(int piece) {
        float[] worn = WorldArmor.wornPoint(piece);
        if (worn == null) {
            return null;
        }
        // The item is drawn over the middle of the piece, as the flat menu draws it, and in front
        // of the player, whose body would hide it
        return new float[]{(worn[0] * rightX + worn[2] * rightZ) * pixelsPerBlock,
                -worn[1] * pixelsPerBlock + size * PedestalDrops.LIFT,
                (worn[0] * towardsX + worn[2] * towardsZ + IN_FRONT) * pixelsPerBlock + size / 2.0f};
    }

    /** A point of the floor's units, in blocks from the feet along the world's axes. */
    private float[] toWorld(float x, float y, float z) {
        return new float[]{(x * rightX + z * towardsX) / pixelsPerBlock, -y / pixelsPerBlock,
                (x * rightZ + z * towardsZ) / pixelsPerBlock};
    }

    /**
     * The items for the world's draw of the player, as the flat menu draws them: lying ones turn
     * slowly and bob, the one under the mouse ({@code hovered}) faces the camera, holds still and
     * grows by {@code hoverScale}; a covered one is darker, with what covers it as a badge {@code
     * badgeShare} of its size; those put back on fly up to the player. {@code still} holds them
     * all still, as the packaged tests draw them.
     */
    List<WorldArmor.Drop> laid(int hovered, IntFunction<ItemStack> stacks, IntPredicate covered,
                               IntFunction<ItemStack> badges, float hoverScale, float badgeShare, boolean still, long now) {
        List<WorldArmor.Drop> laid = new ArrayList<>();
        float facing = 180.0f - cameraYaw;
        for (PedestalDrops.Item item : drops.items()) {
            ItemStack stack = stacks.apply(item.piece);
            if (stack.isEmpty()) {
                continue;
            }
            boolean held = still || item.piece == hovered || item.dropping();
            float bob = held ? 0.0f : PedestalDrops.bob(now, item.piece);
            float spin = held ? 0.0f : 360.0f * ((now % PedestalDrops.TURN_MILLIS) / (float) PedestalDrops.TURN_MILLIS
                    + item.piece * 0.29f);
            float scale = item.piece == hovered ? hoverScale : 1.0f;
            float[] middle = toWorld(item.x, item.y - size * PedestalDrops.LIFT - bob, item.z);
            boolean dark = covered.test(item.piece);
            ItemStack badge = dark && !item.dropping() ? badges.apply(item.piece) : ItemStack.EMPTY;
            laid.add(new WorldArmor.Drop(stack, middle[0], middle[1], middle[2], size * scale / pixelsPerBlock,
                    facing + spin, dark && item.piece != hovered, badge, size * badgeShare / pixelsPerBlock));
        }
        for (PedestalDrops.Rise rise : drops.rises()) {
            ItemStack stack = stacks.apply(rise.piece());
            if (!stack.isEmpty()) {
                float[] at = rise.at(now);
                float[] middle = toWorld(at[0], at[1] - size * PedestalDrops.LIFT, rise.z(now));
                laid.add(new WorldArmor.Drop(stack, middle[0], middle[1], middle[2],
                        size * rise.scale(now) / pixelsPerBlock, facing, false, ItemStack.EMPTY, 0.0f));
            }
        }
        return laid;
    }

    /** Where a lying item's middle is seen on the GUI: {x, y, GUI units to one of the floor's}, or null. */
    @Nullable
    private float[] seen(PedestalDrops.Item item) {
        float[] middle = toWorld(item.x, item.y - size * PedestalDrops.LIFT, item.z);
        float[] gui = WorldArmor.toGui(middle[0], middle[1], middle[2]);
        if (gui == null) {
            return null;
        }
        gui[2] /= pixelsPerBlock;
        return gui;
    }

    /** The lying piece whose item covers a GUI point, as {@code PedestalDrops.pieceAt} has it, or 0. */
    int pieceAt(double x, double y) {
        int found = 0;
        for (PedestalDrops.Item item : drops.items()) {
            float[] gui = item.dropping() ? null : seen(item);
            if (gui != null && Math.abs(x - gui[0]) <= size * 0.55f * gui[2]
                    && Math.abs(y - gui[1]) <= size * 0.6f * gui[2]) {
                // A later item is drawn over an earlier one
                found = item.piece;
            }
        }
        return found;
    }

    /**
     * The box round a lying item on the GUI, {left, top, right, bottom}, as big as it is drawn
     * under the mouse ({@code hoverScale}); null when it is not lying there.
     */
    @Nullable
    int[] box(int piece, float hoverScale) {
        PedestalDrops.Item item = drops.item(piece);
        float[] gui = item == null || item.dropping() ? null : seen(item);
        if (gui == null) {
            return null;
        }
        float half = size * hoverScale / 2.0f * gui[2];
        return new int[]{(int) Math.floor(gui[0] - half), (int) Math.floor(gui[1] - half),
                (int) Math.ceil(gui[0] + half), (int) Math.ceil(gui[1] + half)};
    }
}
