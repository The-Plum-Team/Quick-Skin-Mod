package com.quickskin.mod.client.services;

import com.quickskin.mod.client.compat.CPMCompatIntegration;
import com.quickskin.mod.client.compat.CpmLook;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * Keeps the local player's look on the latest choice between Quick Skin and CPM
 * ({@link CpmLook}). While a CPM model is the look, the player wears no Quick Skin skin, here and
 * on Quick Skin's server, so every other player sees the model (or, without CPM, the vanilla
 * skin); the cape stays. When the look returns to Quick Skin, the saved Quick Skin look is
 * applied again. Replays are left alone.
 */
@Environment(EnvType.CLIENT)
public final class CpmLookArbiter {
    private static final Logger CPMLOG = LoggerFactory.getLogger("QuickSkin-CPM");
    private static volatile BooleanSupplier liveSession = () -> true;
    private static Object connection;
    private static CpmLook.Owner lastOwner;

    private CpmLookArbiter() {
    }

    /** Bound by the composition root: false while a replay plays back. */
    public static void bindLiveSession(BooleanSupplier live) {
        liveSession = Objects.requireNonNull(live, "live");
    }

    /** Client tick. */
    public static void tick() {
        if (!CPMCompatIntegration.isAvailable() || !isLiveSession()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        Object current = minecraft.getConnection();
        if (current != connection) {
            connection = current;
            lastOwner = null;
            CpmLook.resetSession();
        }
        CpmLook.Owner owner = CpmLook.observe();
        if (owner == CpmLook.Owner.UNKNOWN) {
            return;
        }
        if (owner != lastOwner) {
            CPMLOG.info("Latest look choice: {} (was {})", owner, lastOwner);
        }
        if (minecraft.player != null && minecraft.level != null) {
            UUID local = minecraft.player.getUUID();
            PlayerAppearanceService appearances = PlayerAppearanceService.getInstance();
            if (owner.withholdsQuickSkinSkin()) {
                if (appearances.hasActiveSkin(local)) {
                    CPMLOG.info("A CPM model is the look ({}); withdrawing the Quick Skin skin", owner);
                    appearances.applySkin(local, "", null);
                }
            } else if (lastOwner != null && lastOwner.withholdsQuickSkinSkin()
                    && !appearances.hasActiveSkin(local)) {
                CPMLOG.info("The look is Quick Skin's again; restoring the saved Quick Skin look");
                SavedAppearanceRestorer.restore(local);
            }
        }
        lastOwner = owner;
    }

    /**
     * Whether a Quick Skin skin applied to this player now must be withheld: the live local
     * player while a CPM model is the latest look choice. A skin chosen in Quick Skin passes,
     * because choosing it made Quick Skin's the latest choice first.
     */
    public static boolean withholdsSkin(UUID playerId) {
        if (playerId == null || !CPMCompatIntegration.isAvailable() || !isLiveSession()) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.player == null || !playerId.equals(minecraft.player.getUUID())) {
            return false;
        }
        return CpmLook.owner().withholdsQuickSkinSkin();
    }

    /**
     * Whether a CPM model is the local player's look, for Quick Skin's previews of that look, on
     * the title screen too: the latest decision of {@link #tick()}, or the decision itself before
     * the first one of a connection. False without CPM, in a replay and while nothing is decided.
     * Cheap enough to ask every frame.
     */
    public static boolean cpmModelIsTheLook() {
        if (!CPMCompatIntegration.isAvailable() || !isLiveSession()) {
            return false;
        }
        CpmLook.Owner owner = lastOwner;
        return (owner != null ? owner : CpmLook.owner()).withholdsQuickSkinSkin();
    }

    private static boolean isLiveSession() {
        try {
            return liveSession.getAsBoolean();
        } catch (RuntimeException | LinkageError e) {
            return true;
        }
    }
}
