package com.quickskin.mod.e2e.scenario;

import com.quickskin.mod.client.gui.widget.PlayerWidget;
import com.quickskin.mod.client.services.PlayerAppearanceService;
import com.quickskin.mod.common.data.PlayerAppearance;
import com.quickskin.mod.config.ClientConfig;
import com.quickskin.mod.e2e.Step;
import com.quickskin.mod.e2e.VanillaShim;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;

import java.awt.image.BufferedImage;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Title-menu evidence with explicit reference-skin setup and a two-frame z-order control. */
final class TitleMenuSteps {
    private final FullScenario owner;

    TitleMenuSteps(FullScenario owner) {
        this.owner = owner;
    }

    Step build(Minecraft mc, UUID uuid, PlayerAppearanceService svc, String prefix, String suffix) {
        // 12. Title screen: the preview stays in front of the vanilla splash ----------------------
        //
        // The z-order regression this guards is invisible to a screenshot threshold: "the splash
        // moved from behind the model to in front of it" and "the splash string changed" are the
        // same handful of differing pixels. So this step reads pixels, and reads them twice.
        //
        // Frame 1 is the title screen with the preview left where the mod puts it, away from a
        // harness-pinned yellow splash. Its pixels are located in that frame - no vanilla layout
        // constant is assumed - and become the control: they prove a splash is being drawn, and
        // where. Frame 2 is the same screen with the preview moved onto those exact pixels. The
        // model must have taken the region over completely.
        //
        // The pair is what makes it a z-order assertion rather than a "something is drawn" one. A
        // build that never draws the model would fail frame 2's control (the splash would still be
        // there); a build that draws it behind the splash fails frame 2; and a build with no splash
        // at all fails frame 1 instead of passing vacuously.
        final String probeBefore = prefix + "full_12a_title_probe_splash" + suffix;
        final String probeAfter = prefix + "full_12b_title_probe_covered" + suffix;
        final AtomicInteger titlePhase = new AtomicInteger(0);
        final AtomicInteger titleHold = new AtomicInteger(0);
        final AtomicReference<int[]> splashRegion = new AtomicReference<>();
        final AtomicReference<int[]> splashPixels = new AtomicReference<>();
        final AtomicReference<String> titleFailure = new AtomicReference<>();
        final boolean essentialOwnsTitlePreview =
                com.quickskin.mod.client.compat.EssentialCompatIntegration.isAvailable();
        return Step.of("title_screen_splash_order")
                .action(() -> {
                    if (owner.skinHash == null) {
                        titleFailure.set("title probe has no reference skin from local_skin_apply");
                        return;
                    }
                    // Own the complete appearance/config setup. This step can run immediately
                    // after the reference import, without any cape, catalog, settings or HUD flow.
                    svc.applyLook(uuid, "local_skin:" + owner.skinHash, "", "classic");
                    ClientConfig c = ClientConfig.getInstance();
                    c.activeSkinHash = owner.skinHash;
                    c.activeCapeHash = "";
                    // Keep the frame to just the panorama, the vanilla chrome and the preview: the
                    // HUD overlay would put a second model on screen and the debug border would put
                    // mod text over the one being measured.
                    c.showSkinPreviewOverlay = false;
                    c.enablePlayerPreviewCustomization = false;
                    c.positionOffsetXTitleScreen = 0;
                    c.positionOffsetYTitleScreen = 0;
                    c.sizeModelPreviewPercentageTitleScreen = TITLE_PROBE_SIZE_PERCENT;
                    c.save();
                    TitleScreen probeScreen = new TitleScreen();
                    String splashFailure = VanillaShim.installDeterministicSplash(
                            probeScreen, TITLE_PROBE_SPLASH
                    );
                    if (splashFailure != null) titleFailure.set(splashFailure);
                    VanillaShim.setScreen(mc, probeScreen);
                })
                .minTicks(TITLE_HOLD_TICKS)
                .ready(() -> titleFailure.get() != null || (essentialOwnsTitlePreview
                        ? VanillaShim.currentScreen(mc) instanceof TitleScreen
                        : titleProbeReady(mc, titlePhase, titleHold, splashRegion, splashPixels,
                                titleFailure, probeBefore, probeAfter)))
                .timeoutTicks(600)
                .screenshot(prefix + "full_12_title_splash_order" + suffix)
                .assertion(() -> {
                    String failure = titleFailure.get();
                    if (failure != null) return Step.Result.fail(failure);
                    if (essentialOwnsTitlePreview) {
                        return verifyEssentialTitleReplacement(mc);
                    }
                    if (!(VanillaShim.currentScreen(mc) instanceof TitleScreen))
                        return Step.Result.fail("title screen not open: " + FullScenario.screenName(mc));
                    int[] counts = splashPixels.get();
                    int[] region = splashRegion.get();
                    if (counts == null || region == null)
                        return Step.Result.fail("title screen probe never completed");
                    if (counts[0] < MIN_SPLASH_PIXELS)
                        return Step.Result.fail("control frame found only " + counts[0]
                                + " splash pixels in " + java.util.Arrays.toString(region)
                                + "; expected at least " + MIN_SPLASH_PIXELS
                                + " (no splash means the covered frame proves nothing)");
                    if (counts[1] != 0)
                        return Step.Result.fail("splash still draws over the player model: "
                                + counts[1] + " of " + counts[0] + " splash pixels survived in "
                                + java.util.Arrays.toString(region)
                                + " with the model on top of them");
                    return Step.Result.pass("model covers the splash: " + counts[0]
                            + " splash pixels in the control frame, 0 left once the model is over "
                            + "them (region " + java.util.Arrays.toString(region) + ")");
                });
    }

    // ===== title-screen splash z-order probe ===================================================

    /** Preview size (config percentage) used for the probe, so the model reliably covers the splash. */
    private static final int TITLE_PROBE_SIZE_PERCENT = 70;

    /** Rendered ticks the title screen must hold before each probe grab, so the fade-in has finished. */
    private static final int TITLE_HOLD_TICKS = 25;

    /**
     * How far below the measured splash the model's feet are placed, as a multiple of its scale.
     *
     * <p>The preview grows upward from its centre point, which is the feet, so the target has to sit
     * above it. Just under one scale unit puts the splash around the model's chest.
     */
    private static final float TITLE_PROBE_BODY_DROP = 0.9f;

    /** Splash pixels the control frame must find, below which the covered frame proves nothing. */
    private static final int MIN_SPLASH_PIXELS = 40;

    /** Plain text deliberately chosen to avoid vanilla's rare formatted or seasonal renderers. */
    private static final String TITLE_PROBE_SPLASH = "Quick Skin E2E splash probe";

    /**
     * The harness-pinned vanilla splash's colour, and nothing else on the title screen.
     *
     * <p>{@code SplashRenderer} draws at {@code 0xFFFF00} with a drop shadow at a quarter of that, so
     * only the glyph cores match; the panorama, the logo and the button chrome are all far away from
     * saturated yellow with no blue at all.
     */
    static boolean isSplashYellow(int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        return r >= 200 && g >= 200 && b <= 90;
    }

    /**
     * Drives the two-frame probe, one phase per poll, and reports done to the step's {@code ready}.
     *
     * <p>Phases: hold the untouched title screen and grab the control frame; locate the splash in it
     * and shrink to the middle of its bounding box; move the preview onto that point; let the widget
     * republish its layout; hold again and grab the covered frame; count splash pixels in both. Any
     * step that cannot proceed records a message and reports ready so the assertion fails loudly
     * rather than the step timing out with no explanation.
     */
    private boolean titleProbeReady(Minecraft mc, AtomicInteger phase, AtomicInteger hold,
                                    AtomicReference<int[]> region, AtomicReference<int[]> counts,
                                    AtomicReference<String> failure,
                                    String probeBefore, String probeAfter) {
        if (failure.get() != null) return true;
        if (!(VanillaShim.currentScreen(mc) instanceof TitleScreen)) {
            return false;
        }
        switch (phase.get()) {
            case 0: // hold the untouched screen, then grab the control frame
                if (hold.incrementAndGet() < TITLE_HOLD_TICKS) return false;
                if (!VanillaShim.screenshot(mc, probeBefore)) {
                    failure.set("could not grab the control frame " + probeBefore);
                    return true;
                }
                phase.set(1);
                return false;
            case 1: { // locate the splash in the control frame
                BufferedImage shot = FullScenario.readShot(probeBefore);
                if (shot == null) return false; // async write still in flight
                int[] gui = locateSplash(mc, shot);
                if (gui == null) {
                    failure.set("no splash pixels in the control frame " + probeBefore
                            + " (" + shot.getWidth() + "x" + shot.getHeight() + ")");
                    return true;
                }
                region.set(gui);
                if (!placePreviewOver(mc, gui)) {
                    failure.set("the title screen has no Quick Skin preview widget to move");
                    return true;
                }
                hold.set(0);
                phase.set(2);
                return false;
            }
            case 2: // hold the moved model, then grab the covered frame
                if (hold.incrementAndGet() < TITLE_HOLD_TICKS) return false;
                if (!VanillaShim.screenshot(mc, probeAfter)) {
                    failure.set("could not grab the covered frame " + probeAfter);
                    return true;
                }
                phase.set(3);
                return false;
            default: { // count splash pixels in both frames over the same region
                BufferedImage before = FullScenario.readShot(probeBefore);
                BufferedImage after = FullScenario.readShot(probeAfter);
                if (before == null || after == null) return false;
                if (before.getWidth() != after.getWidth() || before.getHeight() != after.getHeight()) {
                    failure.set("probe frames differ in size: " + before.getWidth() + "x"
                            + before.getHeight() + " vs " + after.getWidth() + "x" + after.getHeight());
                    return true;
                }
                int[] gui = region.get();
                counts.set(new int[] {
                        countSplashPixels(mc, before, gui),
                        countSplashPixels(mc, after, gui)});
                return true;
            }
        }
    }

    /**
     * The middle of the splash, in GUI coordinates, as {@code {x0, y0, x1, y1}}.
     *
     * <p>Measured rather than assumed: the splash's anchor, rotation and pulsing scale are vanilla
     * internals that have already been renamed once across the supported eras. The harness pins
     * the string and colour, but deliberately keeps measuring vanilla's placement and pulse.
     *
     * <p>The splash is not the only saturated yellow on a title screen, though. The panorama can
     * contain bees and thousands of yellow flower pixels - 1.21.10 exposed exactly that false
     * positive. Vanilla's splash is title chrome in the upper-right quadrant on every supported
     * lane, so only that deliberately broad area is eligible. Its pixels are then clustered into
     * bands, by row and then by column, and the densest eligible cluster wins.
     */
    int[] locateSplash(Minecraft mc, BufferedImage shot) {
        int height = shot.getHeight();
        int width = shot.getWidth();
        int scanTop = 0;
        int scanBottom = Math.max(1, height / 2);
        int scanLeft = width / 2;
        int scanRight = width;
        int[] perRow = new int[height];
        int total = 0;
        for (int y = scanTop; y < scanBottom; y++) {
            for (int x = scanLeft; x < scanRight; x++) {
                if (isSplashYellow(shot.getRGB(x, y))) {
                    perRow[y]++;
                    total++;
                }
            }
        }
        if (total < MIN_SPLASH_PIXELS) return null;
        int[] rows = ImageProbeClusters.densest(perRow, 10, MIN_SPLASH_PIXELS);
        if (rows == null) return null;

        int[] perColumn = new int[width];
        for (int y = rows[0]; y <= rows[1]; y++) {
            for (int x = scanLeft; x < scanRight; x++) {
                if (isSplashYellow(shot.getRGB(x, y))) perColumn[x]++;
            }
        }
        int[] columns = ImageProbeClusters.densest(perColumn, 10, MIN_SPLASH_PIXELS);
        if (columns == null) return null;

        double scaleX = (double) width / Math.max(1, mc.getWindow().getGuiScaledWidth());
        double scaleY = (double) height / Math.max(1, mc.getWindow().getGuiScaledHeight());
        // Keep the middle half of the box: the tails of a long splash reach past what a single
        // model silhouette can cover, and covering the middle is what the ordering rule decides.
        int qx = (columns[1] - columns[0]) / 4;
        int qy = (rows[1] - rows[0]) / 4;
        return new int[] {
                (int) ((columns[0] + qx) / scaleX), (int) ((rows[0] + qy) / scaleY),
                (int) ((columns[1] - qx) / scaleX), (int) ((rows[1] - qy) / scaleY)};
    }

    /** Splash-coloured pixels inside a GUI-coordinate region of a captured frame. */
    int countSplashPixels(Minecraft mc, BufferedImage shot, int[] guiRegion) {
        double scaleX = (double) shot.getWidth() / Math.max(1, mc.getWindow().getGuiScaledWidth());
        double scaleY = (double) shot.getHeight() / Math.max(1, mc.getWindow().getGuiScaledHeight());
        int px0 = Math.max(0, (int) Math.floor(guiRegion[0] * scaleX));
        int py0 = Math.max(0, (int) Math.floor(guiRegion[1] * scaleY));
        int px1 = Math.min(shot.getWidth() - 1, (int) Math.ceil(guiRegion[2] * scaleX));
        int py1 = Math.min(shot.getHeight() - 1, (int) Math.ceil(guiRegion[3] * scaleY));
        int count = 0;
        for (int y = py0; y <= py1; y++) {
            for (int x = px0; x <= px1; x++) {
                if (isSplashYellow(shot.getRGB(x, y))) count++;
            }
        }
        return count;
    }

    /**
     * Move the title screen's preview so its body covers a GUI-coordinate region.
     *
     * <p>Drives the same config offsets the user's own reposition writes, and measures the current
     * placement from the widget's cached layout - both mod-owned names, so both survive remapping -
     * rather than reimplementing the widget's anchor rules here.
     */
    private boolean placePreviewOver(Minecraft mc, int[] guiRegion) {
        PlayerWidget widget = titlePreviewWidget(mc);
        if (widget == null) return false;
        Integer centerX = (Integer) FullScenario.screenField(widget, "cachedModelCenterX");
        Integer centerY = (Integer) FullScenario.screenField(widget, "cachedModelCenterY");
        Float modelScale = (Float) FullScenario.screenField(widget, "cachedScale");
        if (centerX == null || centerY == null || modelScale == null) return false;
        int targetX = (guiRegion[0] + guiRegion[2]) / 2;
        int targetY = (guiRegion[1] + guiRegion[3]) / 2;
        int feetY = targetY + (int) (modelScale * TITLE_PROBE_BODY_DROP);
        ClientConfig c = ClientConfig.getInstance();
        c.positionOffsetXTitleScreen += targetX - centerX;
        c.positionOffsetYTitleScreen += feetY - centerY;
        c.save();
        return true;
    }

    /** The Quick Skin preview the mod injected into the open title screen, or {@code null}. */
    PlayerWidget titlePreviewWidget(Minecraft mc) {
        Screen screen = VanillaShim.currentScreen(mc);
        if (screen == null) return null;
        for (GuiEventListener child : screen.children()) {
            if (child instanceof PlayerWidget widget) return widget;
        }
        return null;
    }

    /** Verify the intentional Essential-owned alternative to Quick Skin's title preview. */
    private Step.Result verifyEssentialTitleReplacement(Minecraft mc) {
        Screen screen = VanillaShim.currentScreen(mc);
        if (!(screen instanceof TitleScreen)) {
            return Step.Result.fail("Essential title screen not open: " + FullScenario.screenName(mc));
        }
        if (titlePreviewWidget(mc) != null) {
            return Step.Result.fail(
                    "Quick Skin rendered a duplicate PlayerWidget beside Essential's model");
        }
        boolean quickSkinActionPresent = false;
        for (GuiEventListener child : screen.children()) {
            if (child instanceof com.quickskin.mod.client.gui.widget.IconActionButton) {
                quickSkinActionPresent = true;
                break;
            }
        }
        if (!quickSkinActionPresent) {
            return Step.Result.fail(
                    "Essential title screen has no Quick Skin action button");
        }
        if (com.quickskin.mod.client.compat.EssentialCompatIntegration
                .findBottomEssentialWidget(screen) == null) {
            return Step.Result.fail("Essential title widgets were not detected");
        }

        String activeHash = ClientConfig.getInstance().activeSkinHash;
        if (activeHash == null || activeHash.isEmpty()) {
            return Step.Result.fail("Essential title replacement has no active Quick Skin hash");
        }
        UUID profileId = mc.getUser() == null ? null : mc.getUser().getProfileId();
        PlayerAppearance appearance = profileId == null
                ? null
                : PlayerAppearanceService.getInstance().getAppearance(profileId);
        String expectedSkinId = "local_skin:" + activeHash;
        if (appearance == null || !expectedSkinId.equals(appearance.getSkinId())) {
            return Step.Result.fail(
                    "Essential title replacement did not retain " + expectedSkinId);
        }
        return Step.Result.pass(
                "Essential owns the title player model; Quick Skin suppressed its duplicate, "
                        + "kept its action button, and registered " + expectedSkinId);
    }

}
