package com.quickskin.mod.e2e.scenario;

import com.quickskin.mod.client.services.LocalAssetManager;
import com.quickskin.mod.client.services.PlayerAppearanceService;
import com.quickskin.mod.common.data.TextureQuality;
import com.quickskin.mod.config.ClientConfig;
import com.quickskin.mod.e2e.E2ELog;
import com.quickskin.mod.e2e.Step;
import com.quickskin.mod.e2e.VanillaShim;
import net.minecraft.client.Minecraft;

import java.lang.reflect.Field;
import java.util.UUID;
import java.util.List;

/** HUD evidence owns its appearance and layout setup independently of menu/editor flows. */
final class HudPreviewSteps {
    private final FullScenario owner;
    private boolean hudOverlayPositioned;

    HudPreviewSteps(FullScenario owner) {
        this.owner = owner;
    }

    List<Step> build(Minecraft mc, UUID uuid, PlayerAppearanceService svc, String prefix, String suffix) {
        // 11. HUD preview overlay -----------------------------------------------------------------
        Step disabled = Step.of("hud_preview_disabled")
                .action(() -> {
                    owner.enterWorldView(mc); // closes any leftover dialog; 3rd-person world frame
                    if (owner.skinHash != null) {
                        svc.applyLook(uuid, "local_skin:" + owner.skinHash, "", "classic");
                    }
                    ClientConfig c = ClientConfig.getInstance();
                    c.activeCapeHash = "";
                    c.showSkinPreviewOverlay = false;
                    c.enablePlayerPreviewCustomization = false;
                    // Keep the thumbnail in the authored lower-right evidence region on every era.
                    // The legacy HUD starts at the hotbar while modern HUDs start at a corner, so
                    // derive offsets from their respective production bases instead of hard-coding
                    // a loader/version-specific coordinate.
                    c.sizeModelPreviewPercentageHudOverlay = 15;
                    c.positionOffsetXHudOverlay = 0;
                    c.positionOffsetYHudOverlay = 0;
                    hudOverlayPositioned = false;
                    c.hudOverlayRotation = 20.0f;
                    if (owner.skinHash != null) c.activeSkinHash = owner.skinHash; // show the custom skin in the overlay
                    c.save();
                })
                .minTicks(30)
                .ready(() -> owner.skinHash != null && svc.getSkinLocation(uuid) != null
                        && VanillaShim.currentScreen(mc) == null)
                .settleTicks(12)
                .timeoutTicks(200)
                .screenshot(prefix + "full_11a_hud_disabled" + suffix)
                .assertion(() -> {
                    if (ClientConfig.getInstance().showSkinPreviewOverlay)
                        return Step.Result.fail("HUD control did not disable the overlay");
                    if (owner.skinHash == null || svc.getAppearance(uuid) == null
                            || !("local_skin:" + owner.skinHash).equals(svc.getAppearance(uuid).getSkinId()))
                        return Step.Result.fail("HUD control has no reference appearance");
                    return Step.Result.pass("reference appearance held with HUD preview disabled");
                });
        Step enabled = Step.of("hud_preview_overlay")
                .action(() -> {
                    ClientConfig c = ClientConfig.getInstance();
                    c.showSkinPreviewOverlay = true;
                    c.save();
                    overlayForceResolve();
                })
                .minTicks(30) // several RENDER_HUD frames so the overlay draws + caches its state
                .ready(() -> hudOverlayReady(mc))
                .timeoutTicks(200)
                .screenshot(prefix + "full_11_hud_overlay" + suffix)
                .assertion(() -> {
                    if (!ClientConfig.getInstance().showSkinPreviewOverlay)
                        return Step.Result.fail("showSkinPreviewOverlay not set");
                    if (!overlayRendered())
                        return Step.Result.fail("SkinPreviewOverlay.render did not run (cachedScale==0)");
                    String geometryFailure = overlayGeometryFailure(mc);
                    if (geometryFailure != null)
                        return Step.Result.fail(geometryFailure);
                    Object loc = overlayCachedSkinLocation();
                    Object expectedLoc = owner.skinHash == null
                            ? null
                            : LocalAssetManager.getInstance().getTextureLocation(
                                    owner.skinHash, TextureQuality.FULL);
                    if (expectedLoc == null || loc == null
                            || !String.valueOf(expectedLoc).equals(String.valueOf(loc))) {
                        return Step.Result.fail("HUD overlay cachedSkinLocation=" + loc
                                + " expected custom skin " + expectedLoc);
                    }
                    return Step.Result.pass("HUD overlay rendered in the configured lower-right "
                            + "evidence region wearing the custom skin; "
                            + overlayGeometryDescription(mc) + "; cachedSkinLocation=" + loc);
                });
        return List.of(disabled, enabled);
    }

    void overlayForceResolve() {
        try {
            Field f = Class.forName("com.quickskin.mod.client.gui.overlay.SkinPreviewOverlay")
                    .getDeclaredField("lastCheckedSkinHash");
            f.setAccessible(true);
            f.set(null, null);
        } catch (Throwable t) {
            E2ELog.warn("overlayForceResolve: " + t);
        }
    }

    /** Wait for production geometry, then move its centre into the stable authored review region. */
    boolean hudOverlayReady(Minecraft mc) {
        if (!overlayRendered()) return false;
        if (!hudOverlayPositioned) {
            if (!positionHudOverlayForEvidence(mc, ClientConfig.getInstance())) return false;
            return false; // require a later render pass to publish the corrected cached geometry
        }
        return overlayGeometryFailure(mc) == null;
    }

    private boolean positionHudOverlayForEvidence(Minecraft mc, ClientConfig config) {
        int screenWidth = mc.getWindow().getGuiScaledWidth();
        int screenHeight = mc.getWindow().getGuiScaledHeight();
        int targetCenterX = Math.round(screenWidth * 0.89f);
        int targetCenterY = Math.round(screenHeight * 0.96f);
        int currentCenterX = overlayCachedInt("cachedModelCenterX");
        int currentCenterY = overlayCachedInt("cachedModelCenterY");
        if (currentCenterX == Integer.MIN_VALUE || currentCenterY == Integer.MIN_VALUE) {
            return false;
        }
        config.positionOffsetXHudOverlay += targetCenterX
                - currentCenterX;
        config.positionOffsetYHudOverlay += targetCenterY
                - currentCenterY;
        config.save();
        hudOverlayPositioned = true;
        return true;
    }

    /** Return a fail-closed explanation when the renderer cached geometry outside its target. */
    String overlayGeometryFailure(Minecraft mc) {
        int centerX = overlayCachedInt("cachedModelCenterX");
        int centerY = overlayCachedInt("cachedModelCenterY");
        float scale = overlayCachedScale();
        int screenWidth = mc.getWindow().getGuiScaledWidth();
        int screenHeight = mc.getWindow().getGuiScaledHeight();
        int modelHeight = (int)(scale * 2.0f);
        int modelWidth = (int)(modelHeight * 0.6f);
        int left = centerX - modelWidth / 2;
        int right = centerX + modelWidth / 2;
        int top = centerY - modelHeight;
        float normalizedX = centerX / (float)screenWidth;
        float normalizedY = centerY / (float)screenHeight;
        if (normalizedX < 0.86f || normalizedX > 0.92f
                || normalizedY < 0.93f || normalizedY > 0.99f) {
            return "HUD overlay centre outside lower-right evidence target: "
                    + overlayGeometryDescription(mc);
        }
        if (left < 0 || right > screenWidth || top < 0 || centerY > screenHeight) {
            return "HUD overlay bounds clipped outside the GUI: " + overlayGeometryDescription(mc);
        }
        return null;
    }

    String overlayGeometryDescription(Minecraft mc) {
        return "center=(" + overlayCachedInt("cachedModelCenterX") + ","
                + overlayCachedInt("cachedModelCenterY") + ")/"
                + mc.getWindow().getGuiScaledWidth() + "x"
                + mc.getWindow().getGuiScaledHeight() + ", scale=" + overlayCachedScale();
    }

    int overlayCachedInt(String fieldName) {
        try {
            Field f = Class.forName("com.quickskin.mod.client.gui.overlay.SkinPreviewOverlay")
                    .getDeclaredField(fieldName);
            f.setAccessible(true);
            return f.getInt(null);
        } catch (Throwable t) {
            return Integer.MIN_VALUE;
        }
    }

    float overlayCachedScale() {
        try {
            Field f = Class.forName("com.quickskin.mod.client.gui.overlay.SkinPreviewOverlay")
                    .getDeclaredField("cachedScale");
            f.setAccessible(true);
            return f.getFloat(null);
        } catch (Throwable t) {
            return Float.NaN;
        }
    }

    /** True once {@code SkinPreviewOverlay.render} has executed at least once (cachedScale set). */
    boolean overlayRendered() {
        return overlayCachedScale() > 0f;
    }

    Object overlayCachedSkinLocation() {
        try {
            Field f = Class.forName("com.quickskin.mod.client.gui.overlay.SkinPreviewOverlay")
                    .getDeclaredField("cachedSkinLocation");
            f.setAccessible(true);
            return f.get(null);
        } catch (Throwable t) {
            return null;
        }
    }
}
