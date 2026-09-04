package com.quickskin.mod.e2e.scenario;

import com.quickskin.mod.client.gui.screen.CapeAdjustScreen;
import com.quickskin.mod.client.gui.screen.CapeEntry;
import com.quickskin.mod.client.gui.screen.PlayerCapeMenuScreen;
import com.quickskin.mod.client.gui.util.CapeImportProcessor;
import com.quickskin.mod.client.services.LocalAssetManager;
import com.quickskin.mod.client.services.PlayerAppearanceService;
import com.quickskin.mod.common.data.AssetMetadata;
import com.quickskin.mod.common.data.TextureQuality;
import com.quickskin.mod.common.util.BoundedFileReader;
import com.quickskin.mod.common.util.CapeElytraSilhouette;
import com.quickskin.mod.common.util.CapeOpaqueFill;
import com.quickskin.mod.common.util.SafeImageReader;
import com.quickskin.mod.common.util.TextureAlphaDetector;
import com.quickskin.mod.e2e.E2ELog;
import com.quickskin.mod.e2e.Step;
import com.quickskin.mod.e2e.TestAssets;
import com.quickskin.mod.e2e.VanillaShim;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

/**
 * Cape editor and cape import checkpoints of the {@code full} scenario.
 *
 * <p>Every widget here is driven the way a user drives it: the real toggle, fill, Snap, Mirror,
 * Reset Position, Apply and Cancel buttons are pressed through {@link VanillaShim#press}, the
 * hex field is typed into through its own responder, the drag goes through the public mouse
 * overrides, and imports go through {@link PlayerCapeMenuScreen#onFilesDrop(List)} so the real
 * {@code CapeImportWorkflow} owns the editor round-trip. Private state is only ever read.</p>
 */
final class CapeEditorSteps {

    /** Read cap for hashing or decoding an installed cape; far above anything this class writes. */
    private static final int MAX_CAPE_BYTES = 8 * 1024 * 1024;
    /** The Snap entry the drag step selects; a coarse grid makes the rounding visible in offsets. */
    private static final int SNAP_TARGET = 4;
    private static final String SNAP_TARGET_LABEL = "Snap: " + SNAP_TARGET + "px";
    private static final String SNAP_LABEL_PREFIX = "Snap: ";
    private static final String MIRROR_ON_LABEL = "Mirror: ON";
    private static final String MIRROR_OFF_LABEL = "Mirror: OFF";
    /** Most presses needed to cycle the Snap button back to any entry (its list has five). */
    private static final int MAX_SNAP_PRESSES = 8;
    /**
     * Drag distance in cape pixels, pulled left/up so the reset 320x180 source keeps covering both
     * cape faces. Neither value is near a multiple of {@link #SNAP_TARGET}, so the snapped result
     * is unambiguous: -7.3 rounds to -8 and -5.6 rounds to -4 whatever multiple of 4 it starts on.
     */
    private static final double DRAG_CAPE_DX = -7.3;
    private static final double DRAG_CAPE_DY = -5.6;
    /** One file over the workflow's bounded batch, so its first recorded error is the batch cap. */
    private static final int OVERSIZED_BATCH_FILES = 17;
    private static final String BATCH_LIMIT_ERROR = "Cape import batches are limited to 16 files";
    /** Ticks a settled import message must hold before the non-capture cancel step advances. */
    private static final int MESSAGE_HOLD_TICKS = 5;
    /** Per-channel slack for the one translucent pixel that passes through Java2D compositing. */
    private static final int CHANNEL_TOLERANCE = 2;

    private static final int TEAL_RGB = 0x1199AA;
    private static final int BORDER_ARGB = 0xFFEECC22;
    private static final int IMPORTED_MESSAGE_ARGB = 0xFF55FF55;
    private static final int ERROR_MESSAGE_ARGB = 0xFFFF5555;

    private final FullScenario owner;
    private volatile String translucentCapeHash;

    CapeEditorSteps(FullScenario owner) {
        this.owner = owner;
    }

    /** Hash of the cape imported by {@code cape_import_standard} ({@code null} until then). */
    String translucentCapeHash() {
        return translucentCapeHash;
    }

    // ===== cape_fill_color_picker ==============================================================

    /** Inserted right after {@code cape_adjust_opaque_on}. */
    Step buildFillColorPicker(Minecraft mc, String prefix, String suffix) {
        final AtomicInteger phase = new AtomicInteger();
        final AtomicInteger hold = new AtomicInteger();
        final AtomicReference<BufferedImage> applied = new AtomicReference<>();
        final AtomicReference<String> failure = new AtomicReference<>();
        return Step.of("cape_fill_color_picker")
                .action(() -> {
                    phase.set(0);
                    hold.set(0);
                    applied.set(null);
                    failure.set(null);
                    owner.enterWorldView(mc);
                    BufferedImage src = TestAssets.makeTransparentCapeImage();
                    VanillaShim.setScreen(mc, new CapeAdjustScreen(null, src, applied::set));
                })
                .minTicks(25)
                .ready(() -> fillPickerReady(mc, phase, hold, failure))
                .timeoutTicks(300)
                .screenshot(prefix + "full_05e2_cape_fill_picker" + suffix)
                .assertion(() -> assertFillPicker(mc, applied, failure));
    }

    private boolean fillPickerReady(
            Minecraft mc, AtomicInteger phase, AtomicInteger hold, AtomicReference<String> failure) {
        if (failure.get() != null) return true; // fail fast in the assertion
        if (!(VanillaShim.currentScreen(mc) instanceof CapeAdjustScreen s)) return false;
        try {
            if (phase.get() == 0) {
                Button toggle = (Button) FullScenario.adjustScreenObject(s, "opaqueToggleButton");
                Button fill = (Button) FullScenario.adjustScreenObject(s, "fillColorButton");
                EditBox hex = (EditBox) FullScenario.adjustScreenObject(s, "hexField");
                if (toggle == null || fill == null || hex == null) return false; // init() pending
                if (adjustBoolean(s, "opaqueFill")) {
                    failure.set("a fresh editor must open with the opaque fill off");
                    return true;
                }
                if (!VanillaShim.press(toggle) || !adjustBoolean(s, "opaqueFill")) {
                    failure.set("pressing the real opaque toggle did not enable the fill");
                    return true;
                }
                if (!fill.active) {
                    failure.set("the fill-colour button stayed disabled after enabling the fill");
                    return true;
                }
                if (!VanillaShim.press(fill) || !adjustBoolean(s, "pickerOpen")) {
                    failure.set("pressing the real fill-colour button did not open the picker");
                    return true;
                }
                // The responder accepts "#RRGGBB" or "RRGGBB"; the field itself shows the former.
                hex.setValue(CapeOpaqueFill.toHex(FullScenario.OPAQUE_FILL_RGB));
                if (adjustInt(s, "opaqueFillRgb") != FullScenario.OPAQUE_FILL_RGB) {
                    failure.set("typing into the real hex field did not adopt the colour: "
                            + Integer.toHexString(adjustInt(s, "opaqueFillRgb")));
                    return true;
                }
                phase.set(1);
            }
            boolean open = adjustBoolean(s, "pickerOpen")
                    && adjustBoolean(s, "opaqueFill")
                    && adjustInt(s, "opaqueFillRgb") == FullScenario.OPAQUE_FILL_RGB;
            if (!open) {
                hold.set(0);
                return false;
            }
            return hold.incrementAndGet() >= FullScenario.PREVIEW_HOLD_TICKS;
        } catch (Exception e) {
            E2ELog.warn("cape_fill_color_picker ready: " + e);
            return false;
        }
    }

    private Step.Result assertFillPicker(
            Minecraft mc, AtomicReference<BufferedImage> applied, AtomicReference<String> failure) throws Exception {
        if (failure.get() != null) return Step.Result.fail(failure.get());
        if (!(VanillaShim.currentScreen(mc) instanceof CapeAdjustScreen s))
            return Step.Result.fail("cape adjust not open: " + FullScenario.screenName(mc));
        if (!adjustBoolean(s, "pickerOpen")) return Step.Result.fail("pickerOpen is false");
        if (!adjustBoolean(s, "opaqueFill")) return Step.Result.fail("opaqueFill is false");
        int rgb = adjustInt(s, "opaqueFillRgb");
        if (rgb != FullScenario.OPAQUE_FILL_RGB)
            return Step.Result.fail("opaqueFillRgb=" + Integer.toHexString(rgb)
                    + " expected " + Integer.toHexString(FullScenario.OPAQUE_FILL_RGB));

        String[] sliderNames = {"redSlider", "greenSlider", "blueSlider"};
        String[] sliderLabels = {"R: 255", "G: 0", "B: 255"};
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < sliderNames.length; i++) {
            Object slider = FullScenario.adjustScreenObject(s, sliderNames[i]);
            if (!(slider instanceof AbstractWidget widget))
                return Step.Result.fail(sliderNames[i] + " was not built");
            if (!widget.visible) return Step.Result.fail(sliderNames[i] + " is not visible");
            String label = widget.getMessage().getString();
            if (!sliderLabels[i].equals(label))
                return Step.Result.fail(sliderNames[i] + " reads '" + label
                        + "' expected '" + sliderLabels[i] + "'");
            labels.add(label);
        }
        EditBox hex = (EditBox) FullScenario.adjustScreenObject(s, "hexField");
        if (hex == null || !hex.visible) return Step.Result.fail("hex field is not visible");
        String expectedHex = CapeOpaqueFill.toHex(FullScenario.OPAQUE_FILL_RGB);
        if (!expectedHex.equalsIgnoreCase(hex.getValue()))
            return Step.Result.fail("hex field reads '" + hex.getValue()
                    + "' expected '" + expectedHex + "'");
        Button toggle = (Button) FullScenario.adjustScreenObject(s, "opaqueToggleButton");
        Button fill = (Button) FullScenario.adjustScreenObject(s, "fillColorButton");
        String toggleLabel = toggle == null ? null : toggle.getMessage().getString();
        String fillLabel = fill == null ? null : fill.getMessage().getString();
        String expectedToggle = Component.translatable("quickskin.cape.adjust_opaque_on").getString();
        String expectedFill = Component.translatable("quickskin.cape.adjust_fill", expectedHex).getString();
        if (!expectedToggle.equals(toggleLabel))
            return Step.Result.fail("opaque toggle reads '" + toggleLabel + "' expected '"
                    + expectedToggle + "'");
        if (!expectedFill.equals(fillLabel))
            return Step.Result.fail("fill button reads '" + fillLabel + "' expected '"
                    + expectedFill + "'");

        int expectedPixel = 0xFF000000 | FullScenario.OPAQUE_FILL_RGB;
        BufferedImage composed = FullScenario.composeCapeNow(mc);
        if (composed == null) return Step.Result.fail("composeCapeImage unavailable");
        int window = composed.getRGB(TestAssets.TRANSPARENT_WINDOW_X + 1,
                TestAssets.TRANSPARENT_WINDOW_Y + 1);
        if (window != expectedPixel)
            return Step.Result.fail("composed window pixel " + Integer.toHexString(window)
                    + " expected the fill " + Integer.toHexString(expectedPixel));
        String landmark = FullScenario.checkOpaqueLandmark(composed, 0, "picker fill");
        if (landmark != null) return Step.Result.fail(landmark);
        BufferedImage preview = FullScenario.composePreviewFrameNow(mc);
        if (preview == null) return Step.Result.fail("composeFrame unavailable");
        if (preview.getRGB(TestAssets.TRANSPARENT_WINDOW_X + 1,
                TestAssets.TRANSPARENT_WINDOW_Y + 1) != expectedPixel)
            return Step.Result.fail("preview frame does not carry the picked fill colour");

        // Leave through the real Apply button so the picked colour reaches onApply.
        String applyLabel = Component.translatable("quickskin.cape.adjust_apply").getString();
        if (!FullScenario.pressActiveButton(mc, applyLabel))
            return Step.Result.fail("could not press the '" + applyLabel + "' button");
        BufferedImage out = applied.get();
        if (out == null) return Step.Result.fail("Apply did not deliver an image to onApply");
        if (out.getRGB(TestAssets.TRANSPARENT_WINDOW_X + 1,
                TestAssets.TRANSPARENT_WINDOW_Y + 1) != expectedPixel)
            return Step.Result.fail("applied cape does not carry the picked fill colour");
        if (VanillaShim.currentScreen(mc) instanceof CapeAdjustScreen)
            return Step.Result.fail("Apply did not close the editor");
        return Step.Result.pass("picker open with opaque fill " + expectedHex + ": sliders "
                + labels + ", hex field '" + hex.getValue() + "', fill button '" + fillLabel
                + "'; composed/preview/applied window pixel = " + Integer.toHexString(expectedPixel)
                + "; Apply closed the editor");
    }

    // ===== import block =========================================================================

    /**
     * Inserted right after {@code cape_adjust_zoom_in}, in this order: cape_editor_snap_mirror,
     * cape_import_standard, translucent_cape_worn, translucent_cape_elytra, cape_import_cancel.
     */
    List<Step> buildImportBlock(
            Minecraft mc, UUID uuid, PlayerAppearanceService svc, String prefix, String suffix) {
        List<Step> steps = new ArrayList<>();
        steps.add(buildSnapMirror(mc, prefix, suffix));
        steps.add(buildImportStandard(mc, prefix, suffix));
        steps.add(buildTranslucentWorn(mc, uuid, svc, prefix, suffix));
        steps.add(buildTranslucentElytra(mc, uuid, svc, prefix, suffix));
        steps.add(buildImportCancel(mc));
        return steps;
    }

    // ----- cape_editor_snap_mirror ---------------------------------------------------------------

    /** Values recorded while driving the editor, read back by the assertion. */
    private static final class DragRecord {
        volatile double openScale = Double.NaN;
        volatile double startX = Double.NaN;   // snapped offsets right after selecting 4px snap
        volatile double startY = Double.NaN;
        volatile double expectedX = Double.NaN; // what the snapped drag must land on
        volatile double expectedY = Double.NaN;
        volatile double displayScale = Double.NaN;
        volatile double dragDx = Double.NaN;   // GUI pixels actually delivered
        volatile double dragDy = Double.NaN;
    }

    private Step buildSnapMirror(Minecraft mc, String prefix, String suffix) {
        final AtomicInteger phase = new AtomicInteger();
        final AtomicInteger hold = new AtomicInteger();
        final AtomicBoolean applyCalled = new AtomicBoolean();
        final AtomicReference<String> failure = new AtomicReference<>();
        final DragRecord record = new DragRecord();
        return Step.of("cape_editor_snap_mirror")
                .action(() -> {
                    phase.set(0);
                    hold.set(0);
                    applyCalled.set(false);
                    failure.set(null);
                    owner.enterWorldView(mc);
                    BufferedImage src = TestAssets.makeZoomSourceImage();
                    VanillaShim.setScreen(mc, new CapeAdjustScreen(
                            null, src, img -> applyCalled.set(true)));
                })
                .minTicks(25)
                .ready(() -> snapMirrorReady(mc, phase, hold, failure, record))
                .timeoutTicks(300)
                .screenshot(prefix + "full_05g2_cape_snap_mirror" + suffix)
                .assertion(() -> assertSnapMirror(mc, applyCalled, failure, record));
    }

    private boolean snapMirrorReady(
            Minecraft mc, AtomicInteger phase, AtomicInteger hold,
            AtomicReference<String> failure, DragRecord record) {
        if (failure.get() != null) return true;
        if (!(VanillaShim.currentScreen(mc) instanceof CapeAdjustScreen s)) return false;
        try {
            if (phase.get() == 0) {
                if (findButton(mc, label -> label.startsWith(SNAP_LABEL_PREFIX)) == null) {
                    return false; // init() has not built the widgets yet
                }
                record.openScale = FullScenario.adjustScreenDouble(s, "imgScale");

                // Cycle the real Snap button to the 4px entry.
                boolean snapped = false;
                for (int i = 0; i < MAX_SNAP_PRESSES && !snapped; i++) {
                    Button snap = findButton(mc, label -> label.startsWith(SNAP_LABEL_PREFIX));
                    if (snap == null) break;
                    if (SNAP_TARGET_LABEL.equals(snap.getMessage().getString())) {
                        snapped = true;
                    } else {
                        VanillaShim.press(snap);
                    }
                }
                if (!snapped || snapSize(s) != SNAP_TARGET) {
                    failure.set("could not select '" + SNAP_TARGET_LABEL + "' on the real Snap button"
                            + " (snap=" + snapSize(s) + ")");
                    return true;
                }
                record.startX = FullScenario.adjustScreenDouble(s, "imgOffsetX");
                record.startY = FullScenario.adjustScreenDouble(s, "imgOffsetY");

                // Flip the real Mirror button on.
                Button mirror = findButton(mc, MIRROR_OFF_LABEL::equals);
                if (mirror == null || !VanillaShim.press(mirror)
                        || !adjustBoolean(s, "mirrorFrontBack")) {
                    failure.set("could not turn the real Mirror button on");
                    return true;
                }

                // Drag from the grid centre through the public mouse overrides.
                double displayScale = FullScenario.adjustScreenDouble(s, "displayScale");
                double cx = FullScenario.adjustScreenInt(s, "gridX")
                        + FullScenario.adjustScreenInt(s, "gridW") / 2.0;
                double cy = FullScenario.adjustScreenInt(s, "gridY")
                        + FullScenario.adjustScreenInt(s, "gridH") / 2.0;
                double mx = cx + DRAG_CAPE_DX * displayScale;
                double my = cy + DRAG_CAPE_DY * displayScale;
                String pressed = VanillaShim.mousePress(mc, cx, cy, 0);
                if (pressed != null || !adjustBoolean(s, "isDragging")) {
                    failure.set("a click at the grid centre did not start a drag"
                            + (pressed == null ? "" : ": " + pressed));
                    return true;
                }
                String dragged = VanillaShim.mouseDragTo(mc, mx, my, 0, cx, cy);
                if (dragged != null) {
                    failure.set("the editor ignored the drag: " + dragged);
                    return true;
                }
                String released = VanillaShim.mouseRelease(mc, mx, my, 0);
                if (released != null || adjustBoolean(s, "isDragging")) {
                    failure.set("the release did not end the drag"
                            + (released == null ? "" : ": " + released));
                    return true;
                }
                // Replicate the editor's own arithmetic in the same order for exact expectations.
                double deltaX = (mx - cx) / displayScale;
                double deltaY = (my - cy) / displayScale;
                record.displayScale = displayScale;
                record.dragDx = mx - cx;
                record.dragDy = my - cy;
                record.expectedX = Math.round((record.startX + deltaX) / SNAP_TARGET) * SNAP_TARGET;
                record.expectedY = Math.round((record.startY + deltaY) / SNAP_TARGET) * SNAP_TARGET;
                phase.set(1);
            }
            double offsetX = FullScenario.adjustScreenDouble(s, "imgOffsetX");
            double offsetY = FullScenario.adjustScreenDouble(s, "imgOffsetY");
            boolean stable = adjustBoolean(s, "mirrorFrontBack")
                    && snapSize(s) == SNAP_TARGET
                    && snappedOffsetAgrees(offsetX, record.expectedX)
                    && snappedOffsetAgrees(offsetY, record.expectedY);
            if (!stable) {
                hold.set(0);
                return false;
            }
            return hold.incrementAndGet() >= FullScenario.PREVIEW_HOLD_TICKS;
        } catch (Exception e) {
            E2ELog.warn("cape_editor_snap_mirror ready: " + e);
            return false;
        }
    }

    private Step.Result assertSnapMirror(
            Minecraft mc, AtomicBoolean applyCalled, AtomicReference<String> failure,
            DragRecord record) throws Exception {
        if (failure.get() != null) return Step.Result.fail(failure.get());
        if (!(VanillaShim.currentScreen(mc) instanceof CapeAdjustScreen s))
            return Step.Result.fail("cape adjust not open: " + FullScenario.screenName(mc));
        if (FullScenario.adjustScreenInt(s, "selectedResolution") != 0)
            return Step.Result.fail("the editor left its default 64x32 resolution");

        // Snap: the offsets are stored in cape-space pixels and applySnap() rounds each to the
        // nearest multiple of the selected grid size.
        int snap = snapSize(s);
        if (snap != SNAP_TARGET) return Step.Result.fail("snap size=" + snap + " expected " + SNAP_TARGET);
        double offX = FullScenario.adjustScreenDouble(s, "imgOffsetX");
        double offY = FullScenario.adjustScreenDouble(s, "imgOffsetY");
        if (offX % SNAP_TARGET != 0 || offY % SNAP_TARGET != 0)
            return Step.Result.fail("offsets (" + offX + "," + offY + ") are not multiples of " + SNAP_TARGET);
        if (offX == record.startX || offY == record.startY)
            return Step.Result.fail("the drag left an offset unchanged: start=(" + record.startX
                    + "," + record.startY + ") now=(" + offX + "," + offY + ")");
        if (!snappedOffsetAgrees(offX, record.expectedX)
                || !snappedOffsetAgrees(offY, record.expectedY))
            return Step.Result.fail("snapped drag landed on (" + offX + "," + offY + ") expected ("
                    + record.expectedX + "," + record.expectedY + ") within one "
                    + SNAP_TARGET + "px cursor-quantization cell");
        Button snapButton = findButton(mc, SNAP_TARGET_LABEL::equals);
        if (snapButton == null) return Step.Result.fail("no button reads '" + SNAP_TARGET_LABEL + "'");

        // Mirror: mirrorBackToFront() copies the 10x16 face at UV (1,1) onto the face at UV (12,1).
        if (!adjustBoolean(s, "mirrorFrontBack")) return Step.Result.fail("mirrorFrontBack is false");
        Button mirrorButton = findButton(mc, MIRROR_ON_LABEL::equals);
        if (mirrorButton == null) return Step.Result.fail("no button reads '" + MIRROR_ON_LABEL + "'");
        BufferedImage mirrored = FullScenario.composeCapeNow(mc);
        if (mirrored == null) return Step.Result.fail("composeCapeImage unavailable");
        if (mirrored.getWidth() != 64 || mirrored.getHeight() != 32)
            return Step.Result.fail("composed atlas is " + mirrored.getWidth() + "x"
                    + mirrored.getHeight() + " expected 64x32");
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 10; x++) {
                int source = mirrored.getRGB(1 + x, 1 + y);
                int copy = mirrored.getRGB(12 + x, 1 + y);
                if (source != copy)
                    return Step.Result.fail("mirror mismatch at face pixel (" + x + "," + y + "): "
                            + Integer.toHexString(source) + " vs " + Integer.toHexString(copy));
            }
        }
        // The relation must not be vacuous: with the mirror off the same face must differ.
        VanillaShim.press(mirrorButton);
        if (adjustBoolean(s, "mirrorFrontBack")) return Step.Result.fail("Mirror button did not toggle off");
        BufferedImage plain = FullScenario.composeCapeNow(mc);
        if (plain == null) return Step.Result.fail("composeCapeImage unavailable");
        long mirroredPixels = 0;
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 10; x++) {
                if (plain.getRGB(12 + x, 1 + y) != mirrored.getRGB(12 + x, 1 + y)) mirroredPixels++;
            }
        }
        Button mirrorOff = findButton(mc, MIRROR_OFF_LABEL::equals);
        if (mirrorOff == null || !VanillaShim.press(mirrorOff) || !adjustBoolean(s, "mirrorFrontBack"))
            return Step.Result.fail("could not restore the Mirror button to ON");
        if (mirroredPixels == 0)
            return Step.Result.fail("mirroring changed no pixel of the copied face");
        BufferedImage restored = FullScenario.composeCapeNow(mc);
        if (restored == null || FullScenario.countDifferingPixels(restored, mirrored) != 0)
            return Step.Result.fail("toggling Mirror off and on again did not restore the atlas");

        // Reset Position: the real button returns the transform to the cover fit, snapped to 4px.
        String resetLabel = Component.translatable("quickskin.cape.adjust_reset").getString();
        if (!FullScenario.pressActiveButton(mc, resetLabel))
            return Step.Result.fail("could not press the '" + resetLabel + "' button");
        BufferedImage sourceImage = (BufferedImage) FullScenario.adjustScreenObject(s, "sourceImage");
        int srcFrameHeight = FullScenario.adjustScreenInt(s, "srcFrameHeight");
        double fitScale = Math.max(64.0 / sourceImage.getWidth(), 32.0 / srcFrameHeight);
        double fitX = Math.round(((64 - sourceImage.getWidth() * fitScale) / 2.0) / SNAP_TARGET) * SNAP_TARGET;
        double fitY = Math.round(((32 - srcFrameHeight * fitScale) / 2.0) / SNAP_TARGET) * SNAP_TARGET;
        double resetScale = FullScenario.adjustScreenDouble(s, "imgScale");
        double resetX = FullScenario.adjustScreenDouble(s, "imgOffsetX");
        double resetY = FullScenario.adjustScreenDouble(s, "imgOffsetY");
        if (resetScale != fitScale || resetScale != record.openScale)
            return Step.Result.fail("Reset scale=" + resetScale + " expected fit " + fitScale
                    + " (opened at " + record.openScale + ")");
        if (resetX != fitX || resetY != fitY)
            return Step.Result.fail("Reset offsets=(" + resetX + "," + resetY + ") expected fit ("
                    + fitX + "," + fitY + ")");
        if (resetX == offX && resetY == offY)
            return Step.Result.fail("Reset left the dragged offsets in place");

        // Cancel: the real button closes without applying.
        String cancelLabel = Component.translatable("gui.cancel").getString();
        if (!FullScenario.pressActiveButton(mc, cancelLabel))
            return Step.Result.fail("could not press the '" + cancelLabel + "' button");
        if (VanillaShim.currentScreen(mc) instanceof CapeAdjustScreen)
            return Step.Result.fail("Cancel did not close the editor");
        if (applyCalled.get()) return Step.Result.fail("Cancel invoked onApply");

        return Step.Result.pass("Snap " + SNAP_TARGET + "px: a (" + fmt(record.dragDx) + ","
                + fmt(record.dragDy) + ") GUI-px drag at displayScale " + fmt(record.displayScale)
                + " moved offsets (" + record.startX + "," + record.startY + ") -> (" + offX + ","
                + offY + "); Mirror ON copied face (1,1)-(11,17) onto (12,1)-(22,17) changing "
                + mirroredPixels + " pixels; Reset Position restored scale " + fmt(resetScale)
                + " offsets (" + resetX + "," + resetY + "); Cancel closed without applying");
    }

    /**
     * Modern mouse events read an integer GUI cursor pixel instead of the requested fractional
     * coordinate. That sub-pixel loss can move the editor's nearest-grid rounding by one cell.
     */
    private static boolean snappedOffsetAgrees(double actual, double expected) {
        return Math.abs(actual - expected) <= SNAP_TARGET;
    }

    // ----- cape_import_standard ------------------------------------------------------------------

    private Step buildImportStandard(Minecraft mc, String prefix, String suffix) {
        final AtomicInteger phase = new AtomicInteger();
        final AtomicReference<Path> source = new AtomicReference<>();
        final AtomicReference<Set<String>> before = new AtomicReference<>();
        final AtomicReference<String> failure = new AtomicReference<>();
        return Step.of("cape_import_standard")
                .action(() -> {
                    phase.set(0);
                    failure.set(null);
                    translucentCapeHash = null;
                    owner.enterWorldView(mc);
                    try {
                        source.set(TestAssets.makeTranslucentCape());
                    } catch (Exception e) {
                        E2ELog.error("cape_import_standard fixture failed", e);
                        failure.set("could not write the translucent cape fixture: " + e);
                    }
                    before.set(capeHashes());
                    VanillaShim.setScreen(mc, new PlayerCapeMenuScreen(null));
                })
                .minTicks(25)
                .ready(() -> importStandardReady(mc, phase, source, before, failure))
                .settleTicks(10)
                .timeoutTicks(600)
                .screenshot(prefix + "full_05g3_cape_import_standard" + suffix)
                .assertion(() -> assertImportStandard(mc, source, failure));
    }

    private boolean importStandardReady(
            Minecraft mc, AtomicInteger phase, AtomicReference<Path> source,
            AtomicReference<Set<String>> before, AtomicReference<String> failure) {
        if (failure.get() != null) return true;
        if (!(VanillaShim.currentScreen(mc) instanceof PlayerCapeMenuScreen menu)) return false;
        if (phase.get() == 0) {
            if (source.get() == null) {
                failure.set("no translucent cape fixture to drop");
                return true;
            }
            menu.onFilesDrop(List.of(source.get())); // the real CapeImportWorkflow from here on
            phase.set(1);
            return false;
        }
        String message = menuString(menu, "importMessage");
        int timer = menuInt(menu, "importMessageTimer");
        String imported = Component.translatable("quickskin.cape.imported").getString();
        if (!imported.equals(message) || timer <= 0) return false;
        Set<String> gained = new HashSet<>(capeHashes());
        gained.removeAll(before.get());
        if (gained.size() != 1) {
            E2ELog.warn("cape_import_standard: catalog gained " + gained.size() + " capes");
            return false;
        }
        translucentCapeHash = gained.iterator().next();
        return true;
    }

    private Step.Result assertImportStandard(
            Minecraft mc, AtomicReference<Path> source, AtomicReference<String> failure) throws Exception {
        if (failure.get() != null) return Step.Result.fail(failure.get());
        if (!(VanillaShim.currentScreen(mc) instanceof PlayerCapeMenuScreen menu))
            return Step.Result.fail("cape menu not open: " + FullScenario.screenName(mc));
        String hash = translucentCapeHash;
        if (hash == null) return Step.Result.fail("no cape was catalogued by the import");
        LocalAssetManager assets = LocalAssetManager.getInstance();
        AssetMetadata meta = assets.getMetadata(hash);
        if (meta == null || !meta.isCape()) return Step.Result.fail("no cape metadata for " + hash);
        if (meta.isAnimated() || meta.frameCount() != 1)
            return Step.Result.fail("a standard PNG import was catalogued as animated");

        Path saved = assets.getSourcePath(hash);
        if (saved == null || !Files.isRegularFile(saved))
            return Step.Result.fail("catalogued cape has no saved file: " + saved);
        if (saved.equals(source.get()))
            return Step.Result.fail("the import catalogued the fixture itself instead of a saved copy");
        BufferedImage savedImage = SafeImageReader.readPng(
                BoundedFileReader.readBytes(saved, MAX_CAPE_BYTES));
        if (savedImage.getWidth() != 64 || savedImage.getHeight() != 32)
            return Step.Result.fail("saved cape is " + savedImage.getWidth() + "x"
                    + savedImage.getHeight() + " expected 64x32");
        BufferedImage sourceImage = TestAssets.makeTranslucentCapeImage();
        long differing = FullScenario.countDifferingPixels(savedImage, sourceImage);
        if (differing <= 0)
            return Step.Result.fail("saved cape is byte-identical to the source; no elytra was composited");
        if (CapeImportProcessor.isElytraAreaTransparent(savedImage))
            return Step.Result.fail("saved cape still has a transparent elytra area");
        if (!CapeElytraSilhouette.hasRequiredCutout(savedImage, 1))
            return Step.Result.fail("saved cape lacks the vanilla elytra silhouette cutout");

        int probe = savedImage.getRGB(TestAssets.TRANSLUCENT_CAPE_PROBE_X, TestAssets.TRANSLUCENT_CAPE_PROBE_Y);
        int probeAlpha = (probe >>> 24) & 0xFF;
        if (probeAlpha != TestAssets.TRANSLUCENT_CAPE_ALPHA)
            return Step.Result.fail("visible-face probe alpha=" + probeAlpha + " expected "
                    + TestAssets.TRANSLUCENT_CAPE_ALPHA);
        if (!closeRgb(probe, TEAL_RGB))
            return Step.Result.fail("visible-face probe colour " + Integer.toHexString(probe)
                    + " drifted from teal " + Integer.toHexString(TEAL_RGB));
        int border = savedImage.getRGB(TestAssets.TRANSLUCENT_CAPE_BORDER_X, TestAssets.TRANSLUCENT_CAPE_BORDER_Y);
        if (border != BORDER_ARGB)
            return Step.Result.fail("border pixel " + Integer.toHexString(border)
                    + " expected opaque yellow " + Integer.toHexString(BORDER_ARGB));
        int elytra = savedImage.getRGB(TestAssets.TRANSLUCENT_CAPE_ELYTRA_X, TestAssets.TRANSLUCENT_CAPE_ELYTRA_Y);
        if (((elytra >>> 24) & 0xFF) != 0xFF || (elytra & 0xFFFFFF) == TEAL_RGB)
            return Step.Result.fail("elytra pixel " + Integer.toHexString(elytra)
                    + " is not an opaque vanilla elytra pixel");

        String message = menuString(menu, "importMessage");
        int colour = menuInt(menu, "importMessageColor");
        int timer = menuInt(menu, "importMessageTimer");
        String imported = Component.translatable("quickskin.cape.imported").getString();
        if (!imported.equals(message) || timer <= 0)
            return Step.Result.fail("import message '" + message + "' (timer " + timer
                    + ") expected '" + imported + "'");
        if (colour != IMPORTED_MESSAGE_ARGB)
            return Step.Result.fail("import message colour " + Integer.toHexString(colour)
                    + " expected " + Integer.toHexString(IMPORTED_MESSAGE_ARGB));
        if (!menuListsCape(menu, hash))
            return Step.Result.fail("the menu's localCapes list does not contain local_cape:" + hash);

        return Step.Result.pass("real workflow saved " + saved.getFileName() + " as local_cape:" + hash
                + " (" + differing + " pixels changed by the vanilla elytra composite); probe ("
                + TestAssets.TRANSLUCENT_CAPE_PROBE_X + "," + TestAssets.TRANSLUCENT_CAPE_PROBE_Y
                + ") alpha=" + probeAlpha + " teal, border " + Integer.toHexString(border)
                + ", elytra pixel " + Integer.toHexString(elytra) + " opaque; message '" + message
                + "' colour " + Integer.toHexString(colour) + "; listed under My Capes");
    }

    // ----- translucent_cape_worn -----------------------------------------------------------------

    private Step buildTranslucentWorn(
            Minecraft mc, UUID uuid, PlayerAppearanceService svc, String prefix, String suffix) {
        return Step.of("translucent_cape_worn")
                .action(() -> {
                    owner.enterWorldView(mc);
                    owner.setChestSlot(mc, ItemStack.EMPTY);
                    String id = translucentCapeId();
                    if (id != null) svc.applyCape(uuid, id);
                })
                .minTicks(30)
                .ready(() -> {
                    owner.setChestSlot(mc, ItemStack.EMPTY);
                    String id = translucentCapeId();
                    return id != null
                            && FullScenario.hasExpectedCape(svc, uuid, id)
                            && mc.player != null
                            && VanillaShim.cloakTexture(mc.player) != null
                            && FullScenario.hasEmptyChest(mc);
                })
                .settleTicks(10)
                .timeoutTicks(300)
                .screenshot(prefix + "full_05g4_translucent_cape_worn" + suffix)
                .assertion(() -> {
                    String id = translucentCapeId();
                    if (id == null) return Step.Result.fail("no translucent cape was imported");
                    Step.Result route = FullScenario.assertCapeRoute(mc, svc, uuid, id, false);
                    if (!route.pass()) return route;
                    BufferedImage presented = presentedAtlas(translucentCapeHash);
                    if (presented == null) return Step.Result.fail("loadTexture(FULL) returned nothing");
                    int probe = presented.getRGB(
                            TestAssets.TRANSLUCENT_CAPE_PROBE_X, TestAssets.TRANSLUCENT_CAPE_PROBE_Y);
                    int alpha = (probe >>> 24) & 0xFF;
                    if (alpha != TestAssets.TRANSLUCENT_CAPE_ALPHA)
                        return Step.Result.fail("presentation flattened the cape: probe alpha="
                                + alpha + " expected " + TestAssets.TRANSLUCENT_CAPE_ALPHA);
                    if (!TextureAlphaDetector.hasTransparentPixels(presented))
                        return Step.Result.fail("presented atlas reports no transparent pixels");
                    if (!CapeElytraSilhouette.hasRequiredCutout(presented, 1))
                        return Step.Result.fail("presented atlas lacks the vanilla elytra cutout");
                    return Step.Result.pass(route.message() + "; presented atlas keeps probe alpha "
                            + alpha + " (" + Integer.toHexString(probe) + ") with transparency intact");
                });
    }

    // ----- translucent_cape_elytra ---------------------------------------------------------------

    private Step buildTranslucentElytra(
            Minecraft mc, UUID uuid, PlayerAppearanceService svc, String prefix, String suffix) {
        return Step.of("translucent_cape_elytra")
                .action(() -> {
                    owner.enterWorldView(mc);
                    owner.poseElytraForEvidence(mc);
                })
                .minTicks(25)
                .ready(() -> {
                    owner.poseElytraForEvidence(mc);
                    String id = translucentCapeId();
                    return id != null
                            && FullScenario.hasExpectedCape(svc, uuid, id)
                            && mc.player != null
                            && mc.player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA)
                            && mc.player.isCrouching()
                            && VanillaShim.cloakTexture(mc.player) != null;
                })
                .settleTicks(12)
                .timeoutTicks(300)
                .screenshot(prefix + "full_05g5_translucent_cape_elytra" + suffix)
                .assertion(() -> {
                    String id = translucentCapeId();
                    if (id == null) return Step.Result.fail("no translucent cape was imported");
                    Step.Result route = FullScenario.assertCapeRoute(mc, svc, uuid, id, true);
                    if (!route.pass()) return route;
                    BufferedImage presented = presentedAtlas(translucentCapeHash);
                    if (presented == null) return Step.Result.fail("loadTexture(FULL) returned nothing");
                    int elytra = presented.getRGB(
                            TestAssets.TRANSLUCENT_CAPE_ELYTRA_X, TestAssets.TRANSLUCENT_CAPE_ELYTRA_Y);
                    int alpha = (elytra >>> 24) & 0xFF;
                    if (alpha != 0xFF)
                        return Step.Result.fail("presented elytra pixel alpha=" + alpha
                                + " expected 255 (vanilla elytra composite)");
                    if ((elytra & 0xFFFFFF) == TEAL_RGB)
                        return Step.Result.fail("presented elytra pixel is the cape's teal, not vanilla elytra");
                    if (!CapeElytraSilhouette.hasRequiredCutout(presented, 1))
                        return Step.Result.fail("presented atlas lacks the vanilla elytra cutout");
                    return Step.Result.pass(route.message() + "; presented elytra pixel ("
                            + TestAssets.TRANSLUCENT_CAPE_ELYTRA_X + ","
                            + TestAssets.TRANSLUCENT_CAPE_ELYTRA_Y + ")=" + Integer.toHexString(elytra)
                            + " is opaque vanilla elytra");
                });
    }

    // ----- cape_import_cancel (no capture) -------------------------------------------------------

    /** Mutable bookkeeping for the two-part cancel/batch-limit import exercise. */
    private static final class CancelRecord {
        volatile Path nonStandard;
        final List<Path> oversizedBatch = new ArrayList<>();
        volatile Set<String> before;
        volatile PlayerCapeMenuScreen menu;
        volatile String cancelMessage;
        volatile int cancelColour;
        volatile String batchMessage;
        volatile int batchColour;
    }

    private Step buildImportCancel(Minecraft mc) {
        final AtomicInteger phase = new AtomicInteger();
        final AtomicInteger hold = new AtomicInteger();
        final AtomicReference<String> failure = new AtomicReference<>();
        final CancelRecord record = new CancelRecord();
        return Step.of("cape_import_cancel")
                .action(() -> {
                    phase.set(0);
                    hold.set(0);
                    failure.set(null);
                    record.oversizedBatch.clear();
                    owner.enterWorldView(mc);
                    owner.setChestSlot(mc, ItemStack.EMPTY);
                    try {
                        record.nonStandard = writePngFixture(
                                "qs_e2e_cape_nonstandard.png", TestAssets.makeZoomSourceImage());
                        record.oversizedBatch.addAll(writeOversizedBatch());
                    } catch (Exception e) {
                        E2ELog.error("cape_import_cancel fixture failed", e);
                        failure.set("could not write the import fixtures: " + e);
                    }
                    record.before = capeHashes();
                    VanillaShim.setScreen(mc, new PlayerCapeMenuScreen(null));
                })
                .minTicks(25)
                .ready(() -> importCancelReady(mc, phase, hold, failure, record))
                .timeoutTicks(600)
                .assertion(() -> assertImportCancel(mc, failure, record));
    }

    private boolean importCancelReady(
            Minecraft mc, AtomicInteger phase, AtomicInteger hold,
            AtomicReference<String> failure, CancelRecord record) {
        if (failure.get() != null) return true;
        Screen current = VanillaShim.currentScreen(mc);
        try {
            switch (phase.get()) {
                case 0 -> {
                    if (!(current instanceof PlayerCapeMenuScreen menu)) return false;
                    if (record.nonStandard == null) {
                        failure.set("no non-standard cape source to drop");
                        return true;
                    }
                    record.menu = menu;
                    menu.onFilesDrop(List.of(record.nonStandard));
                    phase.set(1);
                    return false;
                }
                case 1 -> {
                    // The workflow's AdjustmentHandler must open the editor over the menu.
                    if (!(current instanceof CapeAdjustScreen editor)) return false;
                    if (FullScenario.adjustScreenObject(editor, "parent") != record.menu) {
                        failure.set("the workflow's editor does not have the cape menu as parent");
                        return true;
                    }
                    String cancelLabel = Component.translatable("gui.cancel").getString();
                    if (!FullScenario.pressActiveButton(mc, cancelLabel)) {
                        failure.set("could not press the editor's real '" + cancelLabel + "' button");
                        return true;
                    }
                    phase.set(2);
                    return false;
                }
                case 2 -> {
                    if (current != record.menu) return false;
                    String message = menuString(record.menu, "importMessage");
                    String expected = Component.translatable("quickskin.cape.no_valid").getString();
                    if (!expected.equals(message) || menuInt(record.menu, "importMessageTimer") <= 0) {
                        return false;
                    }
                    record.cancelMessage = message;
                    record.cancelColour = menuInt(record.menu, "importMessageColor");
                    if (record.oversizedBatch.size() != OVERSIZED_BATCH_FILES) {
                        failure.set("oversized batch has " + record.oversizedBatch.size() + " files");
                        return true;
                    }
                    record.menu.onFilesDrop(List.copyOf(record.oversizedBatch));
                    phase.set(3);
                    return false;
                }
                default -> {
                    if (current != record.menu) {
                        hold.set(0);
                        return false;
                    }
                    String message = menuString(record.menu, "importMessage");
                    String expected = Component.translatable(
                            "quickskin.cape.error", BATCH_LIMIT_ERROR).getString();
                    if (!expected.equals(message) || menuInt(record.menu, "importMessageTimer") <= 0) {
                        hold.set(0);
                        return false;
                    }
                    record.batchMessage = message;
                    record.batchColour = menuInt(record.menu, "importMessageColor");
                    return hold.incrementAndGet() >= MESSAGE_HOLD_TICKS;
                }
            }
        } catch (Exception e) {
            E2ELog.warn("cape_import_cancel ready: " + e);
            return false;
        }
    }

    private Step.Result assertImportCancel(
            Minecraft mc, AtomicReference<String> failure, CancelRecord record) {
        try {
            if (failure.get() != null) return Step.Result.fail(failure.get());
            Screen current = VanillaShim.currentScreen(mc);
            if (record.menu == null || current != record.menu)
                return Step.Result.fail("the cape menu is not open again: " + FullScenario.screenName(mc));
            Set<String> after = capeHashes();
            if (!after.equals(record.before)) {
                Set<String> gained = new HashSet<>(after);
                gained.removeAll(record.before);
                Set<String> lost = new HashSet<>(record.before);
                lost.removeAll(after);
                return Step.Result.fail("cancelled/failed imports changed the catalog: gained="
                        + gained + " lost=" + lost);
            }
            String noValid = Component.translatable("quickskin.cape.no_valid").getString();
            if (!noValid.equals(record.cancelMessage))
                return Step.Result.fail("cancel message '" + record.cancelMessage
                        + "' expected '" + noValid + "'");
            if (record.cancelColour != ERROR_MESSAGE_ARGB)
                return Step.Result.fail("cancel message colour " + Integer.toHexString(record.cancelColour)
                        + " expected " + Integer.toHexString(ERROR_MESSAGE_ARGB));
            String batchError = Component.translatable("quickskin.cape.error", BATCH_LIMIT_ERROR).getString();
            if (!batchError.equals(record.batchMessage))
                return Step.Result.fail("batch message '" + record.batchMessage
                        + "' expected '" + batchError + "'");
            if (record.batchColour != ERROR_MESSAGE_ARGB)
                return Step.Result.fail("batch message colour " + Integer.toHexString(record.batchColour)
                        + " expected " + Integer.toHexString(ERROR_MESSAGE_ARGB));
            String live = menuString(record.menu, "importMessage");
            if (!batchError.equals(live))
                return Step.Result.fail("live import message '" + live + "' expected '" + batchError + "'");
            return Step.Result.pass("editor opened by the real workflow for a "
                    + TestAssets.ZOOM_SOURCE_W + "x" + TestAssets.ZOOM_SOURCE_H
                    + " source and its Cancel produced '" + record.cancelMessage + "'; a "
                    + OVERSIZED_BATCH_FILES + "-file drop produced '" + record.batchMessage
                    + "'; both in " + Integer.toHexString(ERROR_MESSAGE_ARGB)
                    + "; catalog unchanged (" + after.size() + " capes); cape menu open again");
        } finally {
            owner.enterWorldView(mc);
        }
    }

    // ===== helpers ===============================================================================

    private String translucentCapeId() {
        String hash = translucentCapeHash;
        return hash == null ? null : "local_cape:" + hash;
    }

    /** The atlas Quick Skin presents locally for a catalogued cape (silhouette-masked, not flattened). */
    private static BufferedImage presentedAtlas(String hash) throws IOException {
        if (hash == null) return null;
        byte[] bytes = LocalAssetManager.getInstance().loadTexture(hash, TextureQuality.FULL);
        return bytes == null ? null : SafeImageReader.readPng(bytes);
    }

    private static Set<String> capeHashes() {
        Set<String> hashes = new HashSet<>();
        for (AssetMetadata meta : LocalAssetManager.getInstance().getAssetsByType("cape")) {
            hashes.add(meta.hash());
        }
        return hashes;
    }

    private static boolean menuListsCape(PlayerCapeMenuScreen menu, String hash) {
        Object list = FullScenario.screenField(menu, "localCapes");
        if (!(list instanceof List<?> entries)) return false;
        String id = "local_cape:" + hash;
        for (Object entry : entries) {
            if (entry instanceof CapeEntry cape && id.equals(cape.getCapeId())) return true;
        }
        return false;
    }

    private static String menuString(PlayerCapeMenuScreen menu, String field) {
        Object value = FullScenario.screenField(menu, field);
        return value == null ? null : String.valueOf(value);
    }

    private static int menuInt(PlayerCapeMenuScreen menu, String field) {
        Object value = FullScenario.screenField(menu, field);
        return value instanceof Integer i ? i : Integer.MIN_VALUE;
    }

    private static boolean adjustBoolean(CapeAdjustScreen screen, String name) throws Exception {
        return FullScenario.adjustScreenField(name).getBoolean(screen);
    }

    private static int adjustInt(CapeAdjustScreen screen, String name) throws Exception {
        return FullScenario.adjustScreenInt(screen, name);
    }

    /** The selected snap size in cape pixels: {@code SNAP_SIZES[snapIndex]}. */
    private static int snapSize(CapeAdjustScreen screen) throws Exception {
        int[] sizes = (int[]) FullScenario.adjustScreenField("SNAP_SIZES").get(null);
        int index = FullScenario.adjustScreenInt(screen, "snapIndex");
        return index >= 0 && index < sizes.length ? sizes[index] : Integer.MIN_VALUE;
    }

    private static Button findButton(Minecraft mc, Predicate<String> label) {
        Screen screen = VanillaShim.currentScreen(mc);
        if (screen == null) return null;
        for (GuiEventListener child : screen.children()) {
            if (child instanceof Button button && label.test(button.getMessage().getString())) {
                return button;
            }
        }
        return null;
    }

    private static boolean closeRgb(int argb, int rgb) {
        for (int shift = 0; shift <= 16; shift += 8) {
            int actual = (argb >> shift) & 0xFF;
            int expected = (rgb >> shift) & 0xFF;
            if (Math.abs(actual - expected) > CHANNEL_TOLERANCE) return false;
        }
        return true;
    }

    private static String fmt(double value) {
        return String.format(java.util.Locale.ROOT, "%.3f", value);
    }

    /** Same disposable per-run fixture directory {@code TestAssets} uses. */
    private static Path fixtureDirectory() throws IOException {
        Path runDirectory = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path fixtureDirectory = runDirectory.resolve("e2e-fixtures");
        if (Files.isSymbolicLink(fixtureDirectory)) {
            throw new IOException("E2E fixture directory must not be a symbolic link");
        }
        Files.createDirectories(fixtureDirectory);
        return fixtureDirectory;
    }

    private static Path fixturePath(String filename) throws IOException {
        if (filename == null || !filename.matches("[a-z0-9_.-]+")) {
            throw new IOException("unsafe E2E fixture filename: " + filename);
        }
        Path directory = fixtureDirectory();
        Path fixture = directory.resolve(filename).normalize();
        if (!directory.equals(fixture.getParent())) {
            throw new IOException("E2E fixture escapes its directory: " + filename);
        }
        Files.deleteIfExists(fixture);
        return fixture;
    }

    private static Path writePngFixture(String filename, BufferedImage image) throws IOException {
        Path fixture = fixturePath(filename);
        if (!ImageIO.write(image, "png", fixture.toFile())) {
            throw new IOException("no PNG writer for " + filename);
        }
        return fixture;
    }

    /**
     * Seventeen non-decodable {@code .png} files: one more than the workflow accepts, so its
     * constructor records the batch cap as the first error, and none of the sixteen it does keep
     * can be saved, so the batch reports that cap rather than a partial success.
     */
    private static List<Path> writeOversizedBatch() throws IOException {
        byte[] bogus = "quickskin-e2e-not-a-png".getBytes(StandardCharsets.US_ASCII);
        List<Path> files = new ArrayList<>();
        for (int i = 0; i < OVERSIZED_BATCH_FILES; i++) {
            Path fixture = fixturePath(String.format(java.util.Locale.ROOT, "qs_e2e_cape_bogus_%02d.png", i));
            Files.write(fixture, bogus);
            files.add(fixture);
        }
        return files;
    }
}
