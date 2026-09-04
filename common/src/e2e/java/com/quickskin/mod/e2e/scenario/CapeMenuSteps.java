package com.quickskin.mod.e2e.scenario;

import com.quickskin.mod.client.compat.CPMCompatIntegration;
import com.quickskin.mod.client.gui.screen.CapeEntry;
import com.quickskin.mod.client.gui.screen.DeletionConfirmScreen;
import com.quickskin.mod.client.gui.screen.PlayerCapeMenuScreen;
import com.quickskin.mod.client.gui.screen.SettingsScreen;
import com.quickskin.mod.client.services.AnimatedTextureManager;
import com.quickskin.mod.client.services.LocalAssetManager;
import com.quickskin.mod.client.services.PlayerAppearanceService;
import com.quickskin.mod.common.data.AssetMetadata;
import com.quickskin.mod.common.data.PlayerAppearance;
import com.quickskin.mod.config.ClientConfig;
import com.quickskin.mod.e2e.E2ELog;
import com.quickskin.mod.e2e.Step;
import com.quickskin.mod.e2e.VanillaShim;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The cape menu driven through its real input surface: tile clicks, the animation speed slider,
 * a hovered tooltip, wheel scrolling, the per-tile delete button with its confirmation dialog, the
 * None tile, and the Hide Built-in Capes setting flipped through the real settings checkbox.
 *
 * <p>Every action enters through a public Minecraft override on the mod's own screen
 * ({@code mouseClicked}, {@code mouseReleased}, {@code mouseScrolled}, {@code onClose}) so the
 * production handler runs end to end; private grid geometry and selection state are only
 * <em>read</em> by reflection on mod-owned names to locate tiles and to assert what the click did.
 * The block starts right after {@code cape_editor_ignores_elytra} with the HD cape worn and an
 * elytra possibly still equipped, and ends with no cape worn and built-in capes visible again.</p>
 */
final class CapeMenuSteps {

    /** {@code SpeedSlider} maps its 0..1 value quadratically onto 10%..300%. */
    private static final double SPEED_MIN = 0.1;
    private static final double SPEED_MAX = 3.0;
    private static final double TARGET_SPEED = 2.0;
    private static final double DEFAULT_SPEED = 1.0;
    private static final double SPEED_TOLERANCE = 1.0e-3;
    /** Mirrors the screen's private delete-button geometry (ACTION_BUTTON_SIZE, margin). */
    private static final int ACTION_BUTTON_SIZE = 11;
    private static final int ACTION_BUTTON_MARGIN = 2;
    /** A corner no tile, widget or tooltip trigger occupies. */
    private static final int NEUTRAL_MOUSE_X = 2;
    private static final int NEUTRAL_MOUSE_Y = 2;
    /** One wheel notch; the sign is what the scroll handler negates into an offset. */
    private static final double SCROLL_NOTCH = 1.0;
    /** {@code getCapePosition} truncates the offset, so anything below one pixel is "at the top". */
    private static final double SCROLL_SETTLED_BELOW = 1.0;


    private final FullScenario owner;

    // per-step hold counters / phase state, reset by each step's action
    private final AtomicInteger localCapesHold = new AtomicInteger();
    private final AtomicBoolean gifClicked = new AtomicBoolean();
    private final AtomicReference<String> gifClickFailure = new AtomicReference<>();
    private final AtomicReference<int[]> gifTile = new AtomicReference<>();

    private final AtomicInteger sliderHold = new AtomicInteger();
    private final AtomicInteger sliderPolls = new AtomicInteger();
    private final AtomicBoolean sliderDriven = new AtomicBoolean();
    private final AtomicReference<String> sliderFailure = new AtomicReference<>();

    private final AtomicInteger tooltipHold = new AtomicInteger();
    private final AtomicBoolean mouseMoved = new AtomicBoolean();
    private final AtomicReference<String> mouseMoveFailure = new AtomicReference<>();
    private final AtomicReference<int[]> hdTile = new AtomicReference<>();

    private final AtomicInteger scrollPhase = new AtomicInteger();
    private final AtomicReference<String> scrollFailure = new AtomicReference<>();
    private volatile double scrolledTarget;
    private volatile double scrolledOffset;
    private volatile int scrolledMaxScroll;

    private final AtomicInteger deletePhase = new AtomicInteger();
    private final AtomicReference<String> deleteFailure = new AtomicReference<>();
    private final AtomicReference<Path> deletedPath = new AtomicReference<>();
    private final AtomicReference<String> deletedPrimary = new AtomicReference<>();
    private final AtomicReference<int[]> deleteButton = new AtomicReference<>();

    private final AtomicInteger noneHold = new AtomicInteger();
    private final AtomicReference<String> noneFailure = new AtomicReference<>();
    private final AtomicReference<int[]> noneTile = new AtomicReference<>();

    private final AtomicInteger hiddenPhase = new AtomicInteger();
    private final AtomicInteger hiddenHold = new AtomicInteger();
    private final AtomicBoolean hiddenParked = new AtomicBoolean();
    private final AtomicReference<String> hiddenFailure = new AtomicReference<>();

    CapeMenuSteps(FullScenario owner) {
        this.owner = owner;
    }

    /** Inserted right after {@code cape_editor_ignores_elytra}, in this exact order. */
    List<Step> build(Minecraft mc, UUID uuid, PlayerAppearanceService svc, String prefix, String suffix) {
        List<Step> steps = new ArrayList<>();

        // 9a. click the GIF tile through the real handler ------------------------------------------
        steps.add(Step.of("cape_menu_local_capes")
                .action(() -> {
                    localCapesHold.set(0);
                    gifClicked.set(false);
                    gifClickFailure.set(null);
                    gifTile.set(null);
                    owner.enterWorldView(mc);
                    owner.setChestSlot(mc, ItemStack.EMPTY);
                    VanillaShim.setScreen(mc, new PlayerCapeMenuScreen(null));
                })
                .minTicks(25)
                .ready(() -> {
                    PlayerCapeMenuScreen screen = openCapeMenu(mc);
                    if (screen == null) return false;
                    String gifId = gifCapeId();
                    if (gifId == null) {
                        gifClickFailure.compareAndSet(null, "GIF cape " + owner.gifCapeHash
                                + " does not resolve to a catalog primary");
                        return true;
                    }
                    if (!gifClicked.get()) {
                        // Opening a screen re-centres the mouse, which would hover-highlight (and
                        // tooltip) whichever tile sits under the window centre in this capture.
                        String parked = VanillaShim.moveMouseTo(mc, NEUTRAL_MOUSE_X, NEUTRAL_MOUSE_Y);
                        if (parked != null) {
                            gifClickFailure.compareAndSet(null, "parking the mouse: " + parked);
                            return true;
                        }
                        CapeEntry gif = localCapeByAlias(screen, owner.gifCapeHash);
                        if (gif == null) {
                            gifClickFailure.compareAndSet(null, "GIF cape "
                                    + describeCape(owner.gifCapeHash) + " is not listed in My Capes");
                            return true;
                        }
                        int[] centre = visibleTileCentre(screen, gif);
                        if (centre == null) {
                            gifClickFailure.compareAndSet(null,
                                    "GIF cape tile is not inside the visible grid");
                            return true;
                        }
                        gifTile.set(centre);
                        String clicked = VanillaShim.clickAt(mc, centre[0], centre[1], 0);
                        if (clicked != null) {
                            gifClickFailure.compareAndSet(null, "GIF cape tile click at "
                                    + centre[0] + "," + centre[1] + ": " + clicked);
                            return true;
                        }
                        gifClicked.set(true);
                        E2ELog.info("clicked GIF cape tile at " + centre[0] + "," + centre[1]);
                    }
                    CapeEntry selected = selectedCape(screen);
                    return selected != null
                            && sameCape(selected.getCapeId(), owner.gifCapeHash)
                            && localCapesHold.incrementAndGet() >= FullScenario.PREVIEW_HOLD_TICKS;
                })
                .timeoutTicks(400)
                .screenshot(prefix + "full_09a_cape_menu_local_capes" + suffix)
                .assertion(() -> {
                    String failure = gifClickFailure.get();
                    if (failure != null) return Step.Result.fail(failure);
                    PlayerCapeMenuScreen screen = openCapeMenu(mc);
                    if (screen == null)
                        return Step.Result.fail("cape menu not open: " + FullScenario.screenName(mc));
                    String gifId = gifCapeId();
                    if (gifId == null)
                        return Step.Result.fail("GIF cape " + owner.gifCapeHash + " has no primary");
                    CapeEntry selected = selectedCape(screen);
                    if (selected == null || !sameCape(selected.getCapeId(), owner.gifCapeHash))
                        return Step.Result.fail("selected cape="
                                + (selected == null ? null : selected.getCapeId())
                                + " expected " + gifId);
                    if (!sameCape(ClientConfig.getInstance().activeCapeHash, owner.gifCapeHash))
                        return Step.Result.fail("persisted cape="
                                + ClientConfig.getInstance().activeCapeHash + " expected " + gifId);
                    PlayerAppearance app = svc.getAppearance(uuid);
                    if (app == null || !sameCape(app.getCapeId(), owner.gifCapeHash))
                        return Step.Result.fail("appearance cape="
                                + (app == null ? null : app.getCapeId()) + " expected " + gifId);
                    AbstractSliderButton slider = speedSlider(screen);
                    if (slider == null) return Step.Result.fail("animation speed slider missing");
                    if (!slider.visible)
                        return Step.Result.fail("speed slider hidden with an animated cape selected");
                    int maxScroll = intField(screen, "maxScroll");
                    if (maxScroll <= 0)
                        return Step.Result.fail("no scrollbar: maxScroll=" + maxScroll
                                + " with " + knownCapes(screen).size() + " built-in capes");
                    if (!selected.isAnimated())
                        return Step.Result.fail("selected GIF entry does not report isAnimated()");
                    if (!selected.isLocal())
                        return Step.Result.fail("selected GIF entry does not report isLocal()");
                    String hdId = hdCapeId();
                    if (hdId == null || localCapeByAlias(screen, owner.hdCapeHash) == null)
                        return Step.Result.fail("HD cape " + describeCape(owner.hdCapeHash)
                                + " is not listed in My Capes");
                    int[] tile = gifTile.get();
                    String badge = Component.translatable("quickskin.cape.animated_badge").getString();
                    return Step.Result.pass("clicked GIF tile centre (" + tile[0] + "," + tile[1]
                            + ") -> selected/persisted/applied " + gifId
                            + " (alias " + owner.gifCapeHash + ")"
                            + "; isAnimated=true (" + badge + " badge), isLocal=true (custom marker)"
                            + "; speed slider visible; maxScroll=" + maxScroll
                            + " over " + knownCapes(screen).size() + " built-in capes"
                            + "; My Capes lists " + localCapes(screen).size() + " tiles incl. HD "
                            + hdId);
                }));

        // 9b. animation speed slider to 200% through a real click on its track -------------------
        steps.add(Step.of("cape_speed_slider")
                .action(() -> {
                    sliderHold.set(0);
                    sliderPolls.set(0);
                    sliderDriven.set(false);
                    sliderFailure.set(null);
                })
                .minTicks(10)
                .ready(() -> {
                    PlayerCapeMenuScreen screen = openCapeMenu(mc);
                    if (screen == null) return false;
                    String gifId = gifCapeId();
                    if (gifId == null) return false;
                    if (!sliderDriven.get()) {
                        String problem = clickSliderToSpeed(mc, screen, TARGET_SPEED);
                        if (problem != null) {
                            sliderFailure.compareAndSet(null, problem);
                            return true;
                        }
                        sliderDriven.set(true);
                    }
                    String disagreement = speedAgrees(screen, gifId, TARGET_SPEED);
                    if (disagreement != null) {
                        if (sliderPolls.incrementAndGet() % 40 == 0) {
                            E2ELog.info("cape_speed_slider: waiting; " + disagreement);
                        }
                        return false;
                    }
                    return sliderHold.incrementAndGet() >= FullScenario.PREVIEW_HOLD_TICKS;
                })
                .timeoutTicks(400)
                .screenshot(prefix + "full_09b_cape_speed_slider" + suffix)
                .assertion(() -> {
                    String failure = sliderFailure.get();
                    if (failure != null) return Step.Result.fail(failure);
                    PlayerCapeMenuScreen screen = openCapeMenu(mc);
                    if (screen == null)
                        return Step.Result.fail("cape menu not open: " + FullScenario.screenName(mc));
                    String gifId = gifCapeId();
                    if (gifId == null)
                        return Step.Result.fail("GIF cape " + owner.gifCapeHash + " has no primary");
                    String disagreement = speedAgrees(screen, gifId, TARGET_SPEED);
                    if (disagreement != null) return Step.Result.fail(disagreement);
                    String message = speedSlider(screen).getMessage().getString();
                    float configured = ClientConfig.getInstance().getCapeAnimationSpeed(gifId);
                    float live = animationSpeed();
                    // Put the real control back to 100% after the capture so later menu state is
                    // the default; going through the same click path is itself a second check.
                    String restore = clickSliderToSpeed(mc, screen, DEFAULT_SPEED);
                    if (restore != null) return Step.Result.fail("restoring 100%: " + restore);
                    String restored = speedAgrees(screen, gifId, DEFAULT_SPEED);
                    if (restored != null) return Step.Result.fail("restoring 100%: " + restored);
                    return Step.Result.pass("slider click set \"" + message + "\"; config speed("
                            + gifId + ")=" + configured + "; AnimationState.speedMultiplier("
                            + menuAnimationId() + ")=" + live
                            + "; restored to 100% afterwards");
                }));

        // 9c. hover the HD tile with the real mouse position so render() draws the tooltip -------
        steps.add(Step.of("cape_tile_tooltip")
                .action(() -> {
                    tooltipHold.set(0);
                    mouseMoved.set(false);
                    mouseMoveFailure.set(null);
                    hdTile.set(null);
                })
                .minTicks(10)
                .ready(() -> {
                    PlayerCapeMenuScreen screen = openCapeMenu(mc);
                    if (screen == null) return false;
                    String hdId = hdCapeId();
                    if (hdId == null) {
                        mouseMoveFailure.compareAndSet(null, "HD cape " + owner.hdCapeHash
                                + " does not resolve to a catalog primary");
                        return true;
                    }
                    CapeEntry hd = localCapeByAlias(screen, owner.hdCapeHash);
                    if (hd == null) {
                        mouseMoveFailure.compareAndSet(null,
                                "HD cape " + describeCape(owner.hdCapeHash) + " not listed");
                        return true;
                    }
                    if (!mouseMoved.get()) {
                        int[] centre = visibleTileCentre(screen, hd);
                        if (centre == null) {
                            mouseMoveFailure.compareAndSet(null,
                                    "HD cape tile is not inside the visible grid");
                            return true;
                        }
                        String problem = VanillaShim.moveMouseTo(mc, centre[0], centre[1]);
                        if (problem != null) {
                            mouseMoveFailure.compareAndSet(null, problem);
                            return true;
                        }
                        hdTile.set(centre);
                        mouseMoved.set(true);
                        E2ELog.info("mouse moved over HD cape tile at " + centre[0] + "," + centre[1]);
                    }
                    CapeEntry hovered = capeAt(screen, VanillaShim.guiMouseX(mc), VanillaShim.guiMouseY(mc));
                    return hovered != null
                            && sameCape(hovered.getCapeId(), owner.hdCapeHash)
                            && tooltipHold.incrementAndGet() >= FullScenario.PREVIEW_HOLD_TICKS;
                })
                .timeoutTicks(400)
                .screenshot(prefix + "full_09c_cape_tile_tooltip" + suffix)
                .assertion(() -> {
                    String failure = mouseMoveFailure.get();
                    if (failure != null) return Step.Result.fail(failure);
                    PlayerCapeMenuScreen screen = openCapeMenu(mc);
                    if (screen == null)
                        return Step.Result.fail("cape menu not open: " + FullScenario.screenName(mc));
                    String hdId = hdCapeId();
                    int mouseX = VanillaShim.guiMouseX(mc);
                    int mouseY = VanillaShim.guiMouseY(mc);
                    CapeEntry hovered = capeAt(screen, mouseX, mouseY);
                    if (hovered == null || !sameCape(hovered.getCapeId(), owner.hdCapeHash))
                        return Step.Result.fail("mouse (" + mouseX + "," + mouseY + ") hovers "
                                + (hovered == null ? null : hovered.getCapeId()) + " expected " + hdId);
                    AssetMetadata metadata = hovered.getLocalCape();
                    if (metadata == null || metadata.resolution() == null)
                        return Step.Result.fail("HD cape entry carries no resolution metadata");
                    String resolution = metadata.resolution().name();
                    if (!"CAPE_256".equals(resolution))
                        return Step.Result.fail("HD cape resolution=" + resolution + " expected CAPE_256");
                    List<String> lines = tooltipLines(screen, hovered);
                    if (lines == null) return Step.Result.fail("getCapeTooltip reflection failed");
                    String name = hovered.getFriendlyName();
                    String staticLine = Component.translatable("quickskin.tooltip.static_cape").getString();
                    String resolutionLine = Component.translatable(
                            "quickskin.tooltip.resolution", resolution).getString();
                    String previewLine = Component.translatable("quickskin.tooltip.click_preview").getString();
                    for (String expected : new String[] {name, staticLine, resolutionLine, previewLine}) {
                        if (!lines.contains(expected))
                            return Step.Result.fail("tooltip lacks \"" + expected + "\": " + lines);
                    }
                    int[] tile = hdTile.get();
                    return Step.Result.pass("mouse at (" + mouseX + "," + mouseY + ") over HD tile "
                            + hdId + " centre (" + tile[0] + "," + tile[1] + "); tooltip lines " + lines);
                }));

        // 9d. wheel scroll over the grid, then back to the top (no capture) ----------------------
        steps.add(Step.of("cape_scroll")
                .action(() -> {
                    scrollPhase.set(0);
                    scrollFailure.set(null);
                    scrolledTarget = 0;
                    scrolledOffset = 0;
                    scrolledMaxScroll = 0;
                })
                .minTicks(5)
                .ready(() -> {
                    PlayerCapeMenuScreen screen = openCapeMenu(mc);
                    if (screen == null) return false;
                    int[] centre = gridCentre(screen);
                    double target = doubleField(screen, "targetScrollOffset");
                    double offset = doubleField(screen, "scrollOffset");
                    switch (scrollPhase.get()) {
                        case 0 -> {
                            scrolledMaxScroll = intField(screen, "maxScroll");
                            if (scrolledMaxScroll <= 0) {
                                scrollFailure.compareAndSet(null,
                                        "nothing to scroll: maxScroll=" + scrolledMaxScroll);
                                return true;
                            }
                            String scrolled =
                                    VanillaShim.scrollAt(mc, centre[0], centre[1], -SCROLL_NOTCH);
                            if (scrolled != null) {
                                scrollFailure.compareAndSet(null, "wheel notch at " + centre[0]
                                        + "," + centre[1] + ": " + scrolled);
                                return true;
                            }
                            scrolledTarget = doubleField(screen, "targetScrollOffset");
                            if (!(scrolledTarget > 0)) {
                                scrollFailure.compareAndSet(null,
                                        "wheel notch left targetScrollOffset=" + scrolledTarget);
                                return true;
                            }
                            scrollPhase.set(1);
                            return false;
                        }
                        case 1 -> {
                            if (!(offset > 0)) return false;
                            scrolledOffset = offset;
                            String returned =
                                    VanillaShim.scrollAt(mc, centre[0], centre[1], SCROLL_NOTCH);
                            if (returned != null) {
                                scrollFailure.compareAndSet(null, "return notch: " + returned);
                                return true;
                            }
                            scrollPhase.set(2);
                            return false;
                        }
                        default -> {
                            return target == 0.0 && offset < SCROLL_SETTLED_BELOW;
                        }
                    }
                })
                .timeoutTicks(400)
                .assertion(() -> {
                    String failure = scrollFailure.get();
                    if (failure != null) return Step.Result.fail(failure);
                    PlayerCapeMenuScreen screen = openCapeMenu(mc);
                    if (screen == null)
                        return Step.Result.fail("cape menu not open: " + FullScenario.screenName(mc));
                    double target = doubleField(screen, "targetScrollOffset");
                    double offset = doubleField(screen, "scrollOffset");
                    if (!(scrolledTarget > 0) || !(scrolledOffset > 0))
                        return Step.Result.fail("scroll never moved: target=" + scrolledTarget
                                + " offset=" + scrolledOffset);
                    if (target != 0.0 || !(offset < SCROLL_SETTLED_BELOW))
                        return Step.Result.fail("scroll did not return to the top: target=" + target
                                + " offset=" + offset);
                    // Park the cursor so no tile tooltip or hover highlight leaks into later frames.
                    String parked = VanillaShim.moveMouseTo(mc, NEUTRAL_MOUSE_X, NEUTRAL_MOUSE_Y);
                    if (parked != null) return Step.Result.fail("parking the mouse: " + parked);
                    return Step.Result.pass("wheel notch scrolled target=" + scrolledTarget
                            + " (maxScroll=" + scrolledMaxScroll + "), rendered offset reached "
                            + scrolledOffset + ", return notch settled target=0 offset=" + offset
                            + "; mouse parked at (" + NEUTRAL_MOUSE_X + "," + NEUTRAL_MOUSE_Y + ")");
                }));

        // 9e. delete the contrast cape via its tile button and the real confirmation -------------
        steps.add(Step.of("cape_delete_local")
                .action(() -> {
                    deletePhase.set(0);
                    deleteFailure.set(null);
                    deletedPath.set(null);
                    deletedPrimary.set(null);
                    deleteButton.set(null);
                    PlayerCapeMenuScreen screen = openCapeMenu(mc);
                    if (screen == null) {
                        deleteFailure.set("cape menu not open: " + FullScenario.screenName(mc));
                        return;
                    }
                    String contrastId = contrastCapeId();
                    CapeEntry contrast = localCapeByAlias(screen, owner.previewCapeHashB);
                    if (contrastId == null || contrast == null) {
                        deleteFailure.set("contrast cape " + describeCape(owner.previewCapeHashB)
                                + " is not listed in My Capes");
                        return;
                    }
                    deletedPrimary.set(primaryHash(owner.previewCapeHashB));
                    if (doubleField(screen, "scrollOffset") >= SCROLL_SETTLED_BELOW) {
                        deleteFailure.set("grid is still scrolled: offset="
                                + doubleField(screen, "scrollOffset"));
                        return;
                    }
                    int[] pos = capePosition(screen, contrast);
                    if (pos == null) {
                        deleteFailure.set("no grid position for " + contrastId);
                        return;
                    }
                    int size = intField(screen, "capeDisplaySize");
                    int buttonX = pos[0] + size - ACTION_BUTTON_SIZE - ACTION_BUTTON_MARGIN;
                    int buttonY = pos[1] + ACTION_BUTTON_MARGIN;
                    int clickX = buttonX + ACTION_BUTTON_SIZE / 2;
                    int clickY = buttonY + ACTION_BUTTON_SIZE / 2;
                    CapeEntry under = capeAt(screen, clickX, clickY);
                    if (under == null || !sameCape(under.getCapeId(), owner.previewCapeHashB)) {
                        deleteFailure.set("delete button (" + clickX + "," + clickY + ") is not over "
                                + contrastId + " but " + (under == null ? null : under.getCapeId()));
                        return;
                    }
                    deletedPath.set(contrast.getPath());
                    deleteButton.set(new int[] {clickX, clickY});
                    String clickedDelete = VanillaShim.clickAt(mc, clickX, clickY, 0);
                    if (clickedDelete != null) {
                        deleteFailure.set("delete-button click: " + clickedDelete);
                        return;
                    }
                    if (!(VanillaShim.currentScreen(mc) instanceof DeletionConfirmScreen)) {
                        deleteFailure.set("delete button did not open DeletionConfirmScreen: "
                                + FullScenario.screenName(mc));
                    }
                })
                .minTicks(5)
                .ready(() -> {
                    if (deleteFailure.get() != null) return true;
                    Screen current = VanillaShim.currentScreen(mc);
                    if (deletePhase.get() == 0) {
                        if (!(current instanceof DeletionConfirmScreen) || current.children().isEmpty())
                            return false;
                        if (!owner.pressLastButton(mc)) {
                            deleteFailure.compareAndSet(null, "confirmation dialog has no button to press");
                            return true;
                        }
                        deletePhase.set(1);
                        return false;
                    }
                    PlayerCapeMenuScreen screen = openCapeMenu(mc);
                    if (screen == null) return false;
                    String expected = Component.translatable("quickskin.cape.deleted").getString();
                    return expected.equals(stringField(screen, "importMessage"))
                            && intField(screen, "importMessageTimer") > 0
                            && contrastGone();
                })
                .settleTicks(10)
                .timeoutTicks(300)
                .screenshot(prefix + "full_09e_cape_delete_local" + suffix)
                .assertion(() -> {
                    String failure = deleteFailure.get();
                    if (failure != null) return Step.Result.fail(failure);
                    PlayerCapeMenuScreen screen = openCapeMenu(mc);
                    if (screen == null)
                        return Step.Result.fail("cape menu not open: " + FullScenario.screenName(mc));
                    String contrastId = "local_cape:" + deletedPrimary.get();
                    String expected = Component.translatable("quickskin.cape.deleted").getString();
                    String message = stringField(screen, "importMessage");
                    int timer = intField(screen, "importMessageTimer");
                    if (!expected.equals(message) || timer <= 0)
                        return Step.Result.fail("deleted message not shown: \"" + message
                                + "\" timer=" + timer);
                    if (!contrastGone())
                        return Step.Result.fail("catalog still resolves " + contrastId
                                + " / alias " + owner.previewCapeHashB);
                    Path path = deletedPath.get();
                    Path capes = LocalAssetManager.getInstance().getCapesDirectory();
                    if (path == null || capes == null)
                        return Step.Result.fail("deleted path or capes directory unknown");
                    if (!path.toAbsolutePath().normalize().startsWith(capes.toAbsolutePath().normalize()))
                        return Step.Result.fail("deleted file " + path + " was not under " + capes);
                    if (Files.exists(path))
                        return Step.Result.fail("file still exists after delete: " + path);
                    if (localCapeById(screen, contrastId) != null
                            || localCapeByAlias(screen, owner.previewCapeHashB) != null)
                        return Step.Result.fail("My Capes still lists " + contrastId);
                    String gifId = gifCapeId();
                    PlayerAppearance app = svc.getAppearance(uuid);
                    if (gifId == null || app == null || !sameCape(app.getCapeId(), owner.gifCapeHash)
                            || !sameCape(ClientConfig.getInstance().activeCapeHash, owner.gifCapeHash))
                        return Step.Result.fail("active cape changed by deleting another tile: "
                                + (app == null ? null : app.getCapeId()) + " / "
                                + ClientConfig.getInstance().activeCapeHash);
                    int[] button = deleteButton.get();
                    return Step.Result.pass("delete button (" + button[0] + "," + button[1]
                            + ") -> DeletionConfirmScreen -> confirm removed " + contrastId
                            + " (" + path.getFileName() + " gone from uploads/capes); \"" + message
                            + "\" shown with timer=" + timer + "; active cape still " + gifId);
                }));

        // 9f. click the None tile through the real handler ----------------------------------------
        steps.add(Step.of("cape_none_selected")
                .action(() -> {
                    noneHold.set(0);
                    noneFailure.set(null);
                    noneTile.set(null);
                    PlayerCapeMenuScreen screen = openCapeMenu(mc);
                    if (screen == null) {
                        noneFailure.set("cape menu not open: " + FullScenario.screenName(mc));
                        return;
                    }
                    List<CapeEntry> local = localCapes(screen);
                    CapeEntry none = local.isEmpty() ? null : local.get(0);
                    if (none == null || none.getKnownCape() == null || !none.getKnownCape().isNoCape()) {
                        noneFailure.set("first My Capes tile is not None: "
                                + (none == null ? null : none.getCapeId()));
                        return;
                    }
                    int[] centre = visibleTileCentre(screen, none);
                    if (centre == null) {
                        noneFailure.set("None tile is not inside the visible grid");
                        return;
                    }
                    noneTile.set(centre);
                    String clickedNone = VanillaShim.clickAt(mc, centre[0], centre[1], 0);
                    if (clickedNone != null) {
                        noneFailure.set("None tile click: " + clickedNone);
                    }
                })
                .minTicks(10)
                .ready(() -> {
                    if (noneFailure.get() != null) return true;
                    PlayerCapeMenuScreen screen = openCapeMenu(mc);
                    if (screen == null) return false;
                    List<CapeEntry> local = localCapes(screen);
                    if (local.isEmpty()) return false;
                    AbstractSliderButton slider = speedSlider(screen);
                    // Let the "Deleted cape" message from the previous step expire first so this
                    // capture shows only the None selection.
                    return intField(screen, "importMessageTimer") == 0
                            && isSelected(screen, local.get(0))
                            && ClientConfig.getInstance().activeCapeHash.isEmpty()
                            && !svc.hasActiveCape(uuid)
                            && slider != null && !slider.visible
                            && noneHold.incrementAndGet() >= FullScenario.PREVIEW_HOLD_TICKS;
                })
                .timeoutTicks(400)
                .screenshot(prefix + "full_09f_cape_none_selected" + suffix)
                .assertion(() -> {
                    String failure = noneFailure.get();
                    if (failure != null) return Step.Result.fail(failure);
                    PlayerCapeMenuScreen screen = openCapeMenu(mc);
                    if (screen == null)
                        return Step.Result.fail("cape menu not open: " + FullScenario.screenName(mc));
                    List<CapeEntry> local = localCapes(screen);
                    if (local.isEmpty()) return Step.Result.fail("My Capes is empty");
                    CapeEntry none = local.get(0);
                    if (!isSelected(screen, none))
                        return Step.Result.fail("None tile is not the selected tile; selectedCape="
                                + (selectedCape(screen) == null ? null : selectedCape(screen).getCapeId()));
                    if (!ClientConfig.getInstance().activeCapeHash.isEmpty())
                        return Step.Result.fail("persisted cape survived None: "
                                + ClientConfig.getInstance().activeCapeHash);
                    if (svc.hasActiveCape(uuid) || svc.getCapeLocation(uuid) != null)
                        return Step.Result.fail("cape service still active after None");
                    AbstractSliderButton slider = speedSlider(screen);
                    if (slider == null || slider.visible)
                        return Step.Result.fail("speed slider still visible after None");
                    int[] tile = noneTile.get();
                    return Step.Result.pass("clicked None tile centre (" + tile[0] + "," + tile[1]
                            + ") -> selectedCape=null (None outlined), persisted cape empty, "
                            + "service cape cleared, speed slider hidden");
                }));

        // 9g. rear world view: plaid skin, no cape, no elytra ------------------------------------
        steps.add(Step.of("no_cape_after_removal")
                .action(() -> {
                    owner.enterWorldView(mc);
                    owner.setChestSlot(mc, ItemStack.EMPTY);
                })
                .minTicks(25)
                .ready(() -> {
                    owner.setChestSlot(mc, ItemStack.EMPTY);
                    owner.pinRearEvidenceView(mc);
                    return noCapeProblem(mc, svc, uuid) == null;
                })
                .settleTicks(12)
                .timeoutTicks(300)
                .screenshot(prefix + "full_09g_no_cape_after_removal" + suffix)
                .assertion(() -> {
                    String problem = noCapeProblem(mc, svc, uuid);
                    if (problem != null) return Step.Result.fail(problem);
                    Object skin = svc.getSkinLocation(uuid);
                    if (skin == null) return Step.Result.fail("Quick Skin skin location is null");
                    String expectedSkin = owner.skinHash == null ? null : "local_skin:" + owner.skinHash;
                    PlayerAppearance app = svc.getAppearance(uuid);
                    if (expectedSkin == null || app == null || !expectedSkin.equals(app.getSkinId()))
                        return Step.Result.fail("skin id=" + (app == null ? null : app.getSkinId())
                                + " expected " + expectedSkin);
                    String rendered = VanillaShim.skinTexture(mc.player);
                    boolean cpm = CPMCompatIntegration.isAvailable();
                    if (!cpm && !String.valueOf(skin).equals(rendered))
                        return Step.Result.fail("renderer skin=" + rendered + " expected " + skin);
                    return Step.Result.pass("rear view: skin " + expectedSkin + " -> " + skin
                            + (cpm ? " (renderer texture owned by CPM: " + rendered + ")"
                            : " == renderer skin")
                            + "; no active cape, cloak=null, profile elytra=null, chest empty");
                }));

        // 9h. Hide Built-in Capes through the real settings checkbox -----------------------------
        steps.add(Step.of("cape_menu_hidden_builtin")
                .action(() -> {
                    hiddenPhase.set(0);
                    hiddenHold.set(0);
                    hiddenParked.set(false);
                    hiddenFailure.set(null);
                    VanillaShim.setScreen(mc, new SettingsScreen(null));
                })
                .minTicks(10)
                .ready(() -> {
                    if (hiddenFailure.get() != null) return true;
                    switch (hiddenPhase.get()) {
                        case 0 -> {
                            if (!(VanillaShim.currentScreen(mc) instanceof SettingsScreen settings)
                                    || settings.children().isEmpty()) {
                                return false;
                            }
                            Object tab = FullScenario.screenField(settings, "guiEditTabButton");
                            if (tab == null || !VanillaShim.press(tab)) {
                                hiddenFailure.compareAndSet(null, "GUI Edit tab button not pressable");
                                return true;
                            }
                            hiddenPhase.set(1);
                            return false;
                        }
                        case 1 -> {
                            if (!(VanillaShim.currentScreen(mc) instanceof SettingsScreen settings))
                                return false;
                            Object box = FullScenario.screenField(settings, "hideBuiltInCapesCheckbox");
                            if (!(box instanceof Checkbox checkbox)) {
                                hiddenFailure.compareAndSet(null, "hideBuiltInCapesCheckbox missing");
                                return true;
                            }
                            if (!checkbox.visible || !settings.children().contains(checkbox)) {
                                hiddenFailure.compareAndSet(null,
                                        "Hide Built-in Capes checkbox is not on the GUI Edit tab");
                                return true;
                            }
                            if (!checkbox.selected() && !VanillaShim.press(checkbox)) {
                                hiddenFailure.compareAndSet(null, "checkbox press failed");
                                return true;
                            }
                            if (!checkbox.selected()) {
                                hiddenFailure.compareAndSet(null, "checkbox did not become selected");
                                return true;
                            }
                            settings.onClose();
                            if (!ClientConfig.getInstance().hideBuiltInCapes) {
                                hiddenFailure.compareAndSet(null,
                                        "onClose did not persist hideBuiltInCapes=true");
                                return true;
                            }
                            VanillaShim.setScreen(mc, new PlayerCapeMenuScreen(null));
                            hiddenPhase.set(2);
                            return false;
                        }
                        default -> {
                            PlayerCapeMenuScreen screen = openCapeMenu(mc);
                            if (screen == null) return false;
                            if (!hiddenParked.get()) {
                                String parked = VanillaShim.moveMouseTo(mc, NEUTRAL_MOUSE_X, NEUTRAL_MOUSE_Y);
                                if (parked != null) {
                                    hiddenFailure.compareAndSet(null, "parking the mouse: " + parked);
                                    return true;
                                }
                                hiddenParked.set(true);
                            }
                            return knownCapes(screen).isEmpty()
                                    && intField(screen, "maxScroll") == 0
                                    && hiddenHold.incrementAndGet() >= FullScenario.PREVIEW_HOLD_TICKS;
                        }
                    }
                })
                .timeoutTicks(400)
                .screenshot(prefix + "full_09h_cape_menu_hidden_builtin" + suffix)
                .assertion(() -> {
                    try {
                        String failure = hiddenFailure.get();
                        if (failure != null) return Step.Result.fail(failure);
                        PlayerCapeMenuScreen screen = openCapeMenu(mc);
                        if (screen == null)
                            return Step.Result.fail("cape menu not open: " + FullScenario.screenName(mc));
                        if (!ClientConfig.getInstance().hideBuiltInCapes)
                            return Step.Result.fail("hideBuiltInCapes is false");
                        List<CapeEntry> known = knownCapes(screen);
                        if (!known.isEmpty())
                            return Step.Result.fail("built-in section still lists " + known.size() + " capes");
                        int maxScroll = intField(screen, "maxScroll");
                        if (maxScroll != 0) return Step.Result.fail("scrollbar present: maxScroll=" + maxScroll);
                        List<CapeEntry> local = localCapes(screen);
                        if (local.isEmpty() || local.get(0).getKnownCape() == null
                                || !local.get(0).getKnownCape().isNoCape())
                            return Step.Result.fail("My Capes does not start with the None tile");
                        List<String> expectedLocal = new ArrayList<>();
                        for (String alias : new String[] {owner.hdCapeHash, owner.gifCapeHash,
                                owner.previewCapeHashA, owner.adjustedBmoCapeHash}) {
                            if (alias == null) continue;
                            if (localCapeByAlias(screen, alias) == null)
                                return Step.Result.fail("My Capes lost local cape " + describeCape(alias));
                            expectedLocal.add(primaryCapeId(alias));
                        }
                        return Step.Result.pass("real GUI Edit tab + Hide Built-in Capes checkbox + Done "
                                + "persisted hideBuiltInCapes=true; menu lists 0 built-in capes, maxScroll=0, "
                                + "My Capes = None + " + (local.size() - 1) + " local capes incl. "
                                + expectedLocal);
                    } finally {
                        ClientConfig config = ClientConfig.getInstance();
                        config.hideBuiltInCapes = false;
                        config.save();
                    }
                }));

        return steps;
    }

    // ===== identity: SHA-1 aliases -> catalog primaries =========================================
    // TestAssets hands the scenario bare SHA-1 aliases; the menu, ClientConfig.activeCapeHash and
    // CapeEntry.getCapeId() all speak the catalog primary ("local_cape:sha256-<64hex>"). Resolve
    // through the catalog at each use so a reload cannot leave a stale primary behind.

    /** The catalog primary for a SHA-1 alias (or an already-primary id), or {@code null}. */
    private static String primaryHash(String alias) {
        if (alias == null || alias.isEmpty()) return null;
        AssetMetadata metadata = LocalAssetManager.getInstance().getMetadata(alias);
        return metadata == null ? null : metadata.hash();
    }

    /** {@code "local_cape:" + primary} for an alias, or {@code null} when it is not catalogued. */
    private static String primaryCapeId(String alias) {
        String primary = primaryHash(alias);
        return primary == null ? null : "local_cape:" + primary;
    }

    /** True when {@code actual} is the primary or alias id form of the cape behind {@code alias}. */
    private static boolean sameCape(String actual, String alias) {
        if (actual == null || alias == null) return false;
        if (actual.equals("local_cape:" + alias)) return true;
        String primary = primaryCapeId(alias);
        return primary != null && primary.equals(actual);
    }

    private static String describeCape(String alias) {
        return "local_cape:" + alias + " (primary " + primaryCapeId(alias) + ")";
    }

    private String gifCapeId() { return primaryCapeId(owner.gifCapeHash); }
    private String hdCapeId() { return primaryCapeId(owner.hdCapeHash); }
    private String contrastCapeId() { return primaryCapeId(owner.previewCapeHashB); }

    // ===== cape menu reads (mod-owned names) ====================================================

    private static PlayerCapeMenuScreen openCapeMenu(Minecraft mc) {
        return VanillaShim.currentScreen(mc) instanceof PlayerCapeMenuScreen screen
                && !screen.children().isEmpty() ? screen : null;
    }

    @SuppressWarnings("unchecked")
    private static List<CapeEntry> localCapes(PlayerCapeMenuScreen screen) {
        Object list = FullScenario.screenField(screen, "localCapes");
        return list instanceof List<?> l ? new ArrayList<>((List<CapeEntry>) l) : List.of();
    }

    @SuppressWarnings("unchecked")
    private static List<CapeEntry> knownCapes(PlayerCapeMenuScreen screen) {
        Object list = FullScenario.screenField(screen, "knownCapes");
        return list instanceof List<?> l ? new ArrayList<>((List<CapeEntry>) l) : List.of();
    }

    private static CapeEntry localCapeById(PlayerCapeMenuScreen screen, String capeId) {
        if (capeId == null) return null;
        for (CapeEntry entry : localCapes(screen)) {
            if (capeId.equals(entry.getCapeId())) return entry;
        }
        return null;
    }

    /** The My Capes tile for the cape behind a SHA-1 alias, matched in either id form. */
    private static CapeEntry localCapeByAlias(PlayerCapeMenuScreen screen, String alias) {
        if (alias == null) return null;
        for (CapeEntry entry : localCapes(screen)) {
            if (sameCape(entry.getCapeId(), alias)) return entry;
        }
        return null;
    }

    private static CapeEntry selectedCape(PlayerCapeMenuScreen screen) {
        Object selected = FullScenario.screenField(screen, "selectedCape");
        return selected instanceof CapeEntry entry ? entry : null;
    }

    private static AbstractSliderButton speedSlider(PlayerCapeMenuScreen screen) {
        Object slider = FullScenario.screenField(screen, "animationSpeedSlider");
        return slider instanceof AbstractSliderButton s ? s : null;
    }

    private static int intField(PlayerCapeMenuScreen screen, String name) {
        Object value = FullScenario.screenField(screen, name);
        return value instanceof Integer i ? i : Integer.MIN_VALUE;
    }

    private static double doubleField(PlayerCapeMenuScreen screen, String name) {
        Object value = FullScenario.screenField(screen, name);
        return value instanceof Double d ? d : Double.NaN;
    }

    private static String stringField(PlayerCapeMenuScreen screen, String name) {
        Object value = FullScenario.screenField(screen, name);
        return value instanceof String s ? s : null;
    }

    private static Object invokePrivate(PlayerCapeMenuScreen screen, String name,
                                        Class<?>[] types, Object... args) {
        try {
            Method method = PlayerCapeMenuScreen.class.getDeclaredMethod(name, types);
            method.setAccessible(true);
            return method.invoke(screen, args);
        } catch (Throwable t) {
            E2ELog.warn("PlayerCapeMenuScreen." + name + ": " + t);
            return null;
        }
    }

    /** The screen's own on-screen top-left corner for a tile, or {@code null}. */
    private static int[] capePosition(PlayerCapeMenuScreen screen, CapeEntry entry) {
        Object pos = invokePrivate(screen, "getCapePosition", new Class<?>[] {CapeEntry.class}, entry);
        return pos instanceof int[] p && p.length == 2 ? p : null;
    }

    /** The screen's own hit test, so a click lands exactly where the handler will look. */
    private static CapeEntry capeAt(PlayerCapeMenuScreen screen, int mouseX, int mouseY) {
        Object entry = invokePrivate(screen, "getCapeAt",
                new Class<?>[] {int.class, int.class}, mouseX, mouseY);
        return entry instanceof CapeEntry e ? e : null;
    }

    private static boolean isSelected(PlayerCapeMenuScreen screen, CapeEntry entry) {
        Object selected = invokePrivate(screen, "isSelected", new Class<?>[] {CapeEntry.class}, entry);
        return Boolean.TRUE.equals(selected);
    }

    private static List<String> tooltipLines(PlayerCapeMenuScreen screen, CapeEntry entry) {
        Object lines = invokePrivate(screen, "getCapeTooltip", new Class<?>[] {CapeEntry.class}, entry);
        if (!(lines instanceof List<?> list)) return null;
        List<String> out = new ArrayList<>();
        for (Object line : list) {
            out.add(line instanceof Component c ? c.getString() : String.valueOf(line));
        }
        return out;
    }

    /**
     * Centre of a tile, but only when the screen's own hit test agrees that point is that tile
     * (i.e. it is inside the scissored grid and not culled), so a click there is a real selection.
     */
    private static int[] visibleTileCentre(PlayerCapeMenuScreen screen, CapeEntry entry) {
        int[] pos = capePosition(screen, entry);
        int size = intField(screen, "capeDisplaySize");
        if (pos == null || size <= 0) return null;
        int cx = pos[0] + size / 2;
        int cy = pos[1] + size / 2;
        CapeEntry under = capeAt(screen, cx, cy);
        if (under == null || !under.getCapeId().equals(entry.getCapeId())) return null;
        return new int[] {cx, cy};
    }

    private static int[] gridCentre(PlayerCapeMenuScreen screen) {
        return new int[] {
                intField(screen, "gridX") + intField(screen, "gridWidth") / 2,
                intField(screen, "gridY") + intField(screen, "gridHeight") / 2};
    }

    // ===== speed slider ==========================================================================

    /** The slider position {@code SpeedSlider.applyValue} turns into exactly {@code speed}. */
    private static double sliderValueForSpeed(double speed) {
        return Math.sqrt((speed - SPEED_MIN) / (SPEED_MAX - SPEED_MIN));
    }

    /**
     * Press and release the slider track at the point that maps to {@code speed}, through the
     * screen's public mouse handlers so {@code AbstractSliderButton.onClick} sets the value,
     * {@code applyValue} writes config + live animation, and {@code onRelease} saves the config.
     *
     * @return null on success, otherwise the failure description
     */
    private static String clickSliderToSpeed(
            Minecraft mc, PlayerCapeMenuScreen screen, double speed) {
        AbstractSliderButton slider = speedSlider(screen);
        if (slider == null) return "animation speed slider missing";
        if (!slider.visible || !slider.active) return "animation speed slider is hidden/inactive";
        double value = sliderValueForSpeed(speed);
        // AbstractSliderButton.setValueFromMouse: (mouseX - (x + 4)) / (width - 8)
        double mouseX = slider.getX() + 4 + value * (slider.getWidth() - 8);
        double mouseY = slider.getY() + slider.getHeight() / 2.0;
        return VanillaShim.clickAt(mc, mouseX, mouseY, 0);
    }

    /**
     * The animation id the menu itself registers and drives: {@code "cape_" + <hash inside the
     * CapeEntry id>}, i.e. the catalog primary. FullScenario's earlier animated steps registered a
     * sibling state under the SHA-1 alias; that one is not what the slider writes to.
     */
    private String menuAnimationId() {
        String primary = primaryHash(owner.gifCapeHash);
        return primary == null ? null : "cape_" + primary;
    }

    private static Object animationState(String animationId) {
        if (animationId == null) return null;
        try {
            Field field = AnimatedTextureManager.class.getDeclaredField("animations");
            field.setAccessible(true);
            Object map = field.get(AnimatedTextureManager.getInstance());
            return map instanceof java.util.Map<?, ?> m ? m.get(animationId) : null;
        } catch (Throwable t) {
            E2ELog.warn("AnimatedTextureManager.animations: " + t);
            return null;
        }
    }

    /**
     * Live {@code AnimationState.speedMultiplier} for the GIF cape under the menu's primary-keyed
     * animation id, falling back to the SHA-1-alias key only if the primary one is absent;
     * NaN when neither is registered.
     */
    private float animationSpeed() {
        Object state = animationState(menuAnimationId());
        if (state == null && owner.gifCapeHash != null) {
            state = animationState("cape_" + owner.gifCapeHash);
        }
        if (state == null) return Float.NaN;
        Object speed = FullScenario.stateField(state, "speedMultiplier");
        return speed instanceof Float f ? f : Float.NaN;
    }

    /**
     * Widget label, persisted config, and the live animation must all agree on {@code speed}.
     *
     * @return null when they agree, otherwise the disagreement
     */
    private String speedAgrees(PlayerCapeMenuScreen screen, String capeId, double speed) {
        AbstractSliderButton slider = speedSlider(screen);
        if (slider == null) return "animation speed slider missing";
        String message = slider.getMessage().getString();
        String percent = Math.round(speed * 100) + "%";
        if (!message.contains(percent)) return "slider reads \"" + message + "\" expected " + percent;
        float configured = ClientConfig.getInstance().getCapeAnimationSpeed(capeId);
        if (Math.abs(configured - speed) > SPEED_TOLERANCE)
            return "config speed(" + capeId + ")=" + configured + " expected " + speed;
        float live = animationSpeed();
        if (Float.isNaN(live))
            return "animation " + menuAnimationId() + " is not registered";
        if (Math.abs(live - speed) > SPEED_TOLERANCE)
            return "AnimationState.speedMultiplier=" + live + " expected " + speed;
        return null;
    }

    /** True once neither the alias nor the recorded primary of the contrast cape resolves. */
    private boolean contrastGone() {
        LocalAssetManager assets = LocalAssetManager.getInstance();
        String primary = deletedPrimary.get();
        return assets.getMetadata(owner.previewCapeHashB) == null
                && (primary == null || assets.getMetadata(primary) == null);
    }

    // ===== world state ============================================================================

    private static String noCapeProblem(Minecraft mc, PlayerAppearanceService svc, UUID uuid) {
        if (mc.player == null) return "player is null";
        if (!mc.player.getItemBySlot(EquipmentSlot.CHEST).isEmpty()) return "chest slot is not empty";
        if (svc.hasActiveCape(uuid)) return "cape service remains active";
        if (svc.getCapeLocation(uuid) != null) return "cape location=" + svc.getCapeLocation(uuid);
        if (!ClientConfig.getInstance().activeCapeHash.isEmpty())
            return "persisted cape=" + ClientConfig.getInstance().activeCapeHash;
        PlayerAppearance app = svc.getAppearance(uuid);
        if (app != null && app.getCapeId() != null && !app.getCapeId().isEmpty())
            return "appearance cape=" + app.getCapeId();
        String cloak = VanillaShim.cloakTexture(mc.player);
        if (cloak != null) return "renderer cloak=" + cloak;
        String elytra = VanillaShim.elytraTexture(mc.player);
        if (elytra != null) return "profile elytra=" + elytra;
        return null;
    }

    // ===== real mouse position ====================================================================

    /** GUI-space mouse X exactly as {@code Minecraft.runTick} derives it for {@code Screen.render}. */
}
