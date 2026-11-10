package com.quickskin.mod.client.gui.fan;

import com.quickskin.mod.client.gui.GuiCompat;
import com.quickskin.mod.common.data.AssetMetadata;
import com.quickskin.mod.client.gui.fan.FanMotion.Phase;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
//? if <26.3 {
import net.minecraft.world.InteractionHand;
//?}

import java.util.List;

/**
 * The favourites fan: a key opens a hand of the player's favourite skins above the hotbar, the
 * mouse points at one while the player keeps walking, a click puts it on, and the key again, Esc
 * or a right click closes it. The camera stays still while it is open ({@link #steer}, fed by a
 * mixin on the mouse handler) and its clicks never reach the game.
 */
@Environment(EnvType.CLIENT)
public final class FavouritesFan {
    private static final float NAME_SECONDS = 2.0f;

    private static Phase phase = Phase.CLOSED;
    private static long phaseStart;
    private static List<AssetMetadata> favourites = List.of();
    private static int page;
    private static int chosen = -1;
    private static boolean worn;
    private static int hovered = -1;
    private static float pointerX;
    private static float pointerY;
    private static float moved;
    private static float[] hover = new float[FanMotion.PAGE_SIZE];
    private static long lastFrame;
    /** The name shown above the hotbar after a skin is put on, as Minecraft names a picked item. */
    private static Component wornName;
    private static long wornNameAt;

    private FavouritesFan() {
    }

    /** The key: opens the fan, or closes it when it is open. */
    static void toggle(Minecraft minecraft) {
        if (phase == Phase.OPENING || phase == Phase.OPEN) {
            close();
            return;
        }
        if (minecraft.player == null || GuiCompat.currentScreen() != null) return;
        List<AssetMetadata> list = FavouriteSkins.list();
        if (list.isEmpty()) {
            notice(minecraft, Component.translatable("quickskin.fan.no_favourites"));
            return;
        }
        favourites = List.copyOf(list);
        page = Math.min(page, pages() - 1);
        chosen = -1;
        worn = false;
        hovered = -1;
        hover = new float[FanMotion.PAGE_SIZE];
        pointerX = minecraft.getWindow().getGuiScaledWidth() / 2f;
        pointerY = minecraft.getWindow().getGuiScaledHeight() * 0.4f;
        moved = 0;
        setPhase(Phase.OPENING);
        FanSounds.play(FanSounds.OPEN, 0.8f);
    }

    /** Whether mouse movement and clicks belong to the fan instead of the game. */
    public static boolean capturesMouse() {
        return phase == Phase.OPENING || phase == Phase.OPEN;
    }

    /** Mouse movement, in window pixels, that would have turned the camera. */
    public static void steer(double dx, double dy) {
        Minecraft minecraft = Minecraft.getInstance();
        int screenWidth = Math.max(1, minecraft.getWindow().getScreenWidth());
        int screenHeight = Math.max(1, minecraft.getWindow().getScreenHeight());
        float width = minecraft.getWindow().getGuiScaledWidth();
        float height = minecraft.getWindow().getGuiScaledHeight();
        float gx = (float) (dx * width / screenWidth);
        float gy = (float) (dy * height / screenHeight);
        pointerX = FanMotion.clamp(pointerX + gx, 0, width);
        pointerY = FanMotion.clamp(pointerY + gy, 0, height);
        moved += Math.abs(gx) + Math.abs(gy);
    }

    /** A left click: puts the pointed skin on, or closes the fan when the pointer is by the hotbar. */
    static void click(Minecraft minecraft) {
        if (!capturesMouse()) return;
        if (hovered < 0) {
            close();
            return;
        }
        int index = page * FanMotion.PAGE_SIZE + hovered;
        if (index >= favourites.size()) return;
        long cooldown = FavouriteSkins.cooldownSeconds(minecraft);
        if (cooldown > 0 && !FavouriteSkins.isWorn(favourites.get(index))) {
            notice(minecraft, Component.translatable("quickskin.fan.cooldown", cooldown));
            close();
            return;
        }
        chosen = hovered;
        worn = false;
        setPhase(Phase.CHOOSING);
    }

    public static void close() {
        if (phase == Phase.OPENING || phase == Phase.OPEN) {
            setPhase(Phase.CLOSING);
        }
    }

    /** The mouse wheel turns the page when there are more favourites than one page holds. */
    static void scroll(double amount) {
        if (!capturesMouse() || pages() < 2 || amount == 0) return;
        page = Math.floorMod(page + (amount < 0 ? 1 : -1), pages());
        hovered = -1;
        hover = new float[FanMotion.PAGE_SIZE];
        FanSounds.play(FanSounds.PAGE, 0.7f);
    }

    /** Every tick: a screen that opens, or leaving the world, puts the fan away at once. */
    static void tick(Minecraft minecraft) {
        if (phase != Phase.CLOSED && (minecraft.player == null || GuiCompat.currentScreen() != null)) {
            reset();
        }
    }

    public static void reset() {
        phase = Phase.CLOSED;
        chosen = -1;
        hovered = -1;
        favourites = List.of();
        wornName = null;
        FavouriteSkins.reset();
    }

    // ---- Frame ----

    static Phase phase() {
        return phase;
    }

    static int page() {
        return page;
    }

    static int pages() {
        return Math.max(1, (favourites.size() + FanMotion.PAGE_SIZE - 1) / FanMotion.PAGE_SIZE);
    }

    static List<AssetMetadata> pageItems() {
        int from = Math.min(favourites.size(), page * FanMotion.PAGE_SIZE);
        int to = Math.min(favourites.size(), from + FanMotion.PAGE_SIZE);
        return favourites.subList(from, to);
    }

    static Component wornName() {
        return wornName;
    }

    /** How far the name of the skin just put on has faded, 1 when it is gone; shown once the fan has closed. */
    static float wornNameAge() {
        if (wornName == null || phase != Phase.CLOSED) return 1f;
        return (System.nanoTime() - wornNameAt) / 1e9f / NAME_SECONDS;
    }

    /** Advances the fan to this frame and places its cards; returns the phase's progress. */
    static float advance(Minecraft minecraft, FanMotion.Layout layout, FanMotion.Card[] cards) {
        long now = System.nanoTime();
        float dt = lastFrame == 0 ? 0f : Math.min(0.1f, (now - lastFrame) / 1e9f);
        lastFrame = now;
        float elapsed = (now - phaseStart) / 1e9f;
        int count = pageItems().size();
        float progress = switch (phase) {
            case OPENING -> elapsed / FanMotion.OPEN_SECONDS;
            case CHOOSING -> elapsed / FanMotion.CHOOSE_SECONDS;
            case CLOSING -> elapsed / FanMotion.CLOSE_SECONDS;
            default -> 1f;
        };
        if (phase == Phase.OPENING && progress >= 1f) {
            setPhase(Phase.OPEN);
            progress = 1f;
        }
        if (phase == Phase.CHOOSING && !worn && progress >= FanMotion.WEAR_AT) {
            worn = true;
            wearChosen(minecraft);
        }
        if ((phase == Phase.CHOOSING || phase == Phase.CLOSING) && progress >= 1f) {
            // The name the chosen card carried stays above the hotbar once the card has gone
            if (phase == Phase.CHOOSING) wornNameAt = now;
            phase = Phase.CLOSED;
            return 1f;
        }

        if (capturesMouse()) {
            int pointed = moved < 4f ? -1 : FanMotion.pick(layout, count, pointerX, pointerY);
            if (pointed != hovered) {
                if (pointed >= 0) FanSounds.play(FanSounds.POINT, 0.5f);
                hovered = pointed;
            }
        }
        for (int i = 0; i < count; i++) {
            float target = phase == Phase.OPEN || phase == Phase.OPENING
                    ? (i == hovered ? 1f : 0f)
                    : hover[i];
            hover[i] += (target - hover[i]) * Math.min(1f, dt * 14f);
        }
        FanMotion.place(layout, count, phase, Math.min(1f, progress), hover, chosen, cards);
        return Math.min(1f, progress);
    }

    private static void wearChosen(Minecraft minecraft) {
        int index = page * FanMotion.PAGE_SIZE + chosen;
        if (chosen < 0 || index >= favourites.size()) return;
        AssetMetadata metadata = favourites.get(index);
        switch (FavouriteSkins.wear(minecraft, metadata)) {
            case WORN -> {
                FanSounds.play(FanSounds.WEAR, 0.9f);
                //? if <26.3 {
                // The hand lowers and comes back up wearing the new skin, as it does for a new item
                minecraft.gameRenderer.itemInHandRenderer.itemUsed(InteractionHand.MAIN_HAND);
                //?}
                wornName = Component.literal(metadata.friendlyName());
                wornNameAt = System.nanoTime();
            }
            case ALREADY_WORN -> {
                wornName = Component.literal(metadata.friendlyName());
                wornNameAt = System.nanoTime();
            }
            case COOLDOWN -> notice(minecraft, Component.translatable(
                    "quickskin.fan.cooldown", FavouriteSkins.cooldownSeconds(minecraft)));
            case FAILED -> notice(minecraft, Component.translatable("quickskin.error.cpm_select_failed"));
        }
    }

    private static void setPhase(Phase next) {
        phase = next;
        phaseStart = System.nanoTime();
    }

    private static void notice(Minecraft minecraft, Component message) {
        if (minecraft.player == null) return;
        //? if <26.1 {
        minecraft.player.displayClientMessage(message, true);
        //?} else {
        minecraft.player.sendOverlayMessage(message);
        //?}
    }
}
