package com.quickskin.mod.e2e.scenario;

import com.mojang.blaze3d.platform.InputConstants;
import com.quickskin.mod.client.gui.screen.PlayerSkinMenuScreen;
import com.quickskin.mod.client.gui.screen.SettingsScreen;
import com.quickskin.mod.client.gui.widget.PrimaryButton;
import com.quickskin.mod.client.gui.widget.StyledButton;
import com.quickskin.mod.client.gui.widget.TabButton;
import com.quickskin.mod.client.input.KeybindRegistry;
import com.quickskin.mod.client.services.PlayerAppearanceService;
import com.quickskin.mod.common.data.BackgroundStyle;
import com.quickskin.mod.config.ClientConfig;
import com.quickskin.mod.config.ServerConfig;
import com.quickskin.mod.e2e.E2ELog;
import com.quickskin.mod.e2e.Step;
import com.quickskin.mod.e2e.VanillaShim;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The {@code full} scenario's settings block: every tab of {@link SettingsScreen} driven through
 * its real widgets, the keybind capture round trip, and the GUI Edit tab's persisted effect on the
 * skin menu (styled buttons over the vanilla in-world background).
 *
 * <p>Step order and capture identity are owned by {@code e2e/scenario-contract.json}:
 * {@code settings_screen}, {@code settings_keybind_capture}, {@code settings_gui_edit_tab},
 * {@code settings_modpack_tab}, {@code settings_server_tab},
 * {@code styled_buttons_vanilla_background}. All six capture.</p>
 *
 * <p>Private {@code SettingsScreen} members are read by name through
 * {@link FullScenario#screenField}; they are Quick Skin's own names and survive remapping. Vanilla
 * calls stay on public API that the harness jar's remapper rewrites ({@code Checkbox.selected()},
 * {@code Screen.keyPressed(int,int,int)}, {@code KeyMapping.saveString()}).</p>
 */
final class SettingsSteps {

    /** Rendered ticks a settled tab must hold before its screenshot, so the frame really shows it. */
    private static final int TAB_HOLD_TICKS = 15;

    /** GLFW_KEY_ESCAPE, the value {@code SettingsScreen.keyPressed} compares against. */
    private static final int KEY_ESCAPE = 256;

    /** What the Client tab's keybind button reads while it waits for a key (yellow "???" inside). */
    private static final String CAPTURE_LABEL = "> ??? <";

    // --- vanilla-background pixel probe -------------------------------------------------------
    /** Rendered ticks the skin menu must hold before the background probe frame is grabbed. */
    private static final int BACKGROUND_HOLD_TICKS = 20;
    /**
     * Luma above which a pixel cannot come from the opaque-stars background.
     *
     * <p>Opaque stars is a solid black fill with a 15%-alpha white star tile and a darkening
     * vignette, so every pixel of it stays at luma 38 or below. The vanilla in-world path instead
     * draws a 0x90 black overlay over the live world: locked daytime sky comes through at roughly
     * luma 70 and lit plains grass at roughly 60.
     */
    private static final int BRIGHT_LUMA = 48;
    /** Share of the probed strip that must show the world through the overlay. */
    private static final double MIN_BRIGHT_FRACTION = 0.25;
    /** Share the opaque-stars control frame may reach; with a black fill it is exactly zero. */
    private static final double MAX_CONTROL_BRIGHT_FRACTION = 0.02;

    private final FullScenario owner;

    private final AtomicInteger clientHold = new AtomicInteger();
    private final AtomicInteger captureHold = new AtomicInteger();
    private final AtomicInteger guiEditHold = new AtomicInteger();
    private final AtomicInteger modpackHold = new AtomicInteger();
    private final AtomicInteger serverHold = new AtomicInteger();

    private final AtomicInteger backgroundPhase = new AtomicInteger();
    private final AtomicInteger backgroundHold = new AtomicInteger();
    /** {@code {brightPixels, sampledPixels}} of the probe strip, once measured. */
    private final AtomicReference<long[]> backgroundCounts = new AtomicReference<>();
    /** The same counts over the earlier opaque-stars skin-menu capture, when it is readable. */
    private final AtomicReference<long[]> controlCounts = new AtomicReference<>();
    private final AtomicReference<int[]> backgroundRegion = new AtomicReference<>();
    private final AtomicReference<String> backgroundFailure = new AtomicReference<>();

    SettingsSteps(FullScenario owner) {
        this.owner = owner;
    }

    /**
     * Replaces the old {@code settings_screen} step; steps in this exact order:
     * settings_screen, settings_keybind_capture, settings_gui_edit_tab, settings_modpack_tab,
     * settings_server_tab, styled_buttons_vanilla_background.
     */
    List<Step> build(Minecraft mc, UUID uuid, PlayerAppearanceService svc, String prefix, String suffix) {
        List<Step> steps = new ArrayList<>();
        final String backgroundProbeShot = prefix + "full_10a6_probe_vanilla_background" + suffix;
        final String starsControlShot = prefix + "full_02b_skin_menu" + suffix;

        // 10a. settings screen opens on its Client tab ------------------------------------------
        // Known starting config: overlay off, vanilla buttons, starred background. The capture is
        // the untouched Client tab; nothing is toggled here (the GUI Edit step owns the toggles).
        steps.add(Step.of("settings_screen")
                .action(() -> {
                    clientHold.set(0);
                    ClientConfig c = ClientConfig.getInstance();
                    c.showSkinPreviewOverlay = false;
                    c.enableStyledButtons = false;
                    c.menuBackgroundStyle = BackgroundStyle.OPAQUE_STARS.getId();
                    c.save();
                    VanillaShim.setScreen(mc, new SettingsScreen(null));
                })
                .minTicks(25)
                .ready(() -> clientTabReady(mc) == null
                        && clientHold.incrementAndGet() >= TAB_HOLD_TICKS)
                .timeoutTicks(300)
                .screenshot(prefix + "full_10a_settings" + suffix)
                .assertion(() -> {
                    String problem = clientTabReady(mc);
                    if (problem != null) return Step.Result.fail(problem);
                    SettingsScreen s = (SettingsScreen) VanillaShim.currentScreen(mc);
                    Checkbox transparency = widget(s, "disableSkinTransparencyCheckbox", Checkbox.class);
                    Button keybind = widget(s, "keybindButton", Button.class);
                    ClientConfig c = ClientConfig.getInstance();
                    return Step.Result.pass("SettingsScreen open on tab CLIENT (clientTabButton "
                            + "selected); disableSkinTransparencyCheckbox.selected="
                            + transparency.selected() + " == config.disableSkinTransparency="
                            + c.disableSkinTransparency + "; keybindButton reads '"
                            + keybind.getMessage().getString() + "' == OPEN_SKIN_MENU translated key '"
                            + KeybindRegistry.OPEN_SKIN_MENU.getTranslatedKeyMessage().getString()
                            + "' (saveString=" + KeybindRegistry.OPEN_SKIN_MENU.saveString()
                            + "); config showSkinPreviewOverlay=false enableStyledButtons=false "
                            + "menuBackgroundStyle=" + c.menuBackgroundStyle);
                }));

        // 10a2. keybind capture through the real keybind button ----------------------------------
        // The button's label is rewritten by SettingsScreen.render every frame, so readiness waits
        // for the rendered "> ??? <" rather than trusting the field alone.
        final AtomicReference<String> keyBefore = new AtomicReference<>();
        final AtomicReference<String> keyMessageBefore = new AtomicReference<>();
        steps.add(Step.of("settings_keybind_capture")
                .action(() -> {
                    captureHold.set(0);
                    keyBefore.set(KeybindRegistry.OPEN_SKIN_MENU.saveString());
                    keyMessageBefore.set(
                            KeybindRegistry.OPEN_SKIN_MENU.getTranslatedKeyMessage().getString());
                    if (!(VanillaShim.currentScreen(mc) instanceof SettingsScreen s)) {
                        E2ELog.warn("settings_keybind_capture: settings screen is not open: "
                                + FullScenario.screenName(mc));
                        return;
                    }
                    Button keybind = widget(s, "keybindButton", Button.class);
                    if (keybind == null) {
                        E2ELog.warn("settings_keybind_capture: SettingsScreen.keybindButton not built");
                        return;
                    }
                    if (!VanillaShim.press(keybind)) {
                        E2ELog.warn("settings_keybind_capture: could not press keybindButton");
                    }
                })
                .minTicks(10)
                .ready(() -> keybindCaptureReady(mc) == null
                        && captureHold.incrementAndGet() >= TAB_HOLD_TICKS)
                .timeoutTicks(300)
                .screenshot(prefix + "full_10a2_settings_keybind_capture" + suffix)
                .assertion(() -> {
                    String problem = keybindCaptureReady(mc);
                    if (problem != null) return Step.Result.fail(problem);
                    SettingsScreen s = (SettingsScreen) VanillaShim.currentScreen(mc);
                    String before = keyBefore.get();
                    String messageBefore = keyMessageBefore.get();
                    if (before == null || messageBefore == null)
                        return Step.Result.fail("original OPEN_SKIN_MENU key was not recorded");

                    // Finish the capture with Escape through the public key handler. In
                    // SettingsScreen.keyPressed, Escape during a capture binds InputConstants.UNKNOWN
                    // (an unbind) rather than cancelling; the packaged profile's key is unbound to
                    // begin with, so the mapping must come back equal. Anything else is restored so
                    // the rest of the run never sees a changed binding.
                    String consumed = VanillaShim.pressKey(mc, KEY_ESCAPE, 0, 0);
                    if (consumed != null) return Step.Result.fail("Escape during capture: " + consumed);
                    if (FullScenario.screenField(s, "selectedKey") != null)
                        return Step.Result.fail("selectedKey is still set after Escape");
                    if (!(VanillaShim.currentScreen(mc) instanceof SettingsScreen))
                        return Step.Result.fail("Escape closed the settings screen instead of ending "
                                + "the keybind capture: " + FullScenario.screenName(mc));

                    String after = KeybindRegistry.OPEN_SKIN_MENU.saveString();
                    String restored = "";
                    if (!before.equals(after)) {
                        KeybindRegistry.OPEN_SKIN_MENU.setKey(InputConstants.getKey(before));
                        KeyMapping.resetMapping();
                        String verify = KeybindRegistry.OPEN_SKIN_MENU.saveString();
                        if (!before.equals(verify))
                            return Step.Result.fail("Escape rebound OPEN_SKIN_MENU from " + before
                                    + " to " + after + " and restoring it failed (now " + verify + ")");
                        restored = "; Escape unbound it (" + after + ") and the harness restored " + before;
                    }
                    String messageAfter =
                            KeybindRegistry.OPEN_SKIN_MENU.getTranslatedKeyMessage().getString();
                    if (!messageBefore.equals(messageAfter))
                        return Step.Result.fail("translated key message changed across the capture: '"
                                + messageBefore + "' -> '" + messageAfter + "'");
                    return Step.Result.pass("keybindButton showed '" + CAPTURE_LABEL
                            + "' with selectedKey == OPEN_SKIN_MENU; Escape ended the capture "
                            + "(selectedKey=null, screen still open) leaving key " + after
                            + " == original " + before + " ('" + messageAfter + "')" + restored
                            + "; the button label re-reads the translated key on its next render");
                }));

        // 10a3. GUI Edit tab: the five real checkboxes, then persisted through onClose -----------
        steps.add(Step.of("settings_gui_edit_tab")
                .action(() -> {
                    guiEditHold.set(0);
                    if (!(VanillaShim.currentScreen(mc) instanceof SettingsScreen s)) {
                        E2ELog.warn("settings_gui_edit_tab: settings screen is not open: "
                                + FullScenario.screenName(mc));
                        return;
                    }
                    TabButton tab = widget(s, "guiEditTabButton", TabButton.class);
                    if (tab == null) {
                        E2ELog.warn("settings_gui_edit_tab: SettingsScreen.guiEditTabButton not built");
                        return;
                    }
                    if (!VanillaShim.press(tab)) {
                        E2ELog.warn("settings_gui_edit_tab: could not press guiEditTabButton");
                    }
                })
                .minTicks(10)
                .ready(() -> guiEditTabReady(mc) == null
                        && guiEditHold.incrementAndGet() >= TAB_HOLD_TICKS)
                .timeoutTicks(300)
                .screenshot(prefix + "full_10a3_settings_gui_edit_tab" + suffix)
                .assertion(() -> {
                    String problem = guiEditTabReady(mc);
                    if (problem != null) return Step.Result.fail(problem);
                    SettingsScreen s = (SettingsScreen) VanillaShim.currentScreen(mc);

                    // Parent is null, so the styled-button change cannot recreate a parent screen;
                    // onClose must persist the checkboxes and close to the world.
                    s.onClose();
                    if (VanillaShim.currentScreen(mc) instanceof SettingsScreen)
                        return Step.Result.fail("SettingsScreen.onClose did not close the screen");
                    ClientConfig c = ClientConfig.getInstance();
                    List<String> wrong = new ArrayList<>();
                    if (!c.showSkinPreviewOverlay) wrong.add("showSkinPreviewOverlay=false");
                    if (!c.enableStyledButtons) wrong.add("enableStyledButtons=false");
                    if (c.getMenuBackgroundStyle() != BackgroundStyle.VANILLA_BLUR)
                        wrong.add("menuBackgroundStyle=" + c.menuBackgroundStyle);
                    if (c.enablePlayerPreviewCustomization)
                        wrong.add("enablePlayerPreviewCustomization=true");
                    if (c.hideBuiltInCapes) wrong.add("hideBuiltInCapes=true");
                    if (!wrong.isEmpty())
                        return Step.Result.fail("ClientConfig after onClose: " + wrong);
                    return Step.Result.pass("GUI Edit tab (guiEditTabButton selected) held "
                            + "showOverlayCheckbox=on enableStyledButtonsCheckbox=on "
                            + "menuBackgroundCheckbox=on enablePlayerPreviewCustomizationCheckbox=off "
                            + "hideBuiltInCapesCheckbox=off; onClose persisted showSkinPreviewOverlay="
                            + c.showSkinPreviewOverlay + " enableStyledButtons=" + c.enableStyledButtons
                            + " menuBackgroundStyle=" + c.menuBackgroundStyle + " ("
                            + c.getMenuBackgroundStyle() + ") enablePlayerPreviewCustomization="
                            + c.enablePlayerPreviewCustomization + " hideBuiltInCapes="
                            + c.hideBuiltInCapes + " and closed to " + FullScenario.screenName(mc));
                }));

        // 10a4. Modpack tab on a fresh screen ------------------------------------------------------
        steps.add(Step.of("settings_modpack_tab")
                .action(() -> {
                    modpackHold.set(0);
                    VanillaShim.setScreen(mc, new SettingsScreen(null));
                })
                .minTicks(20)
                .ready(() -> modpackTabReady(mc) == null
                        && modpackHold.incrementAndGet() >= TAB_HOLD_TICKS)
                .timeoutTicks(300)
                .screenshot(prefix + "full_10a4_settings_modpack_tab" + suffix)
                .assertion(() -> {
                    String problem = modpackTabReady(mc);
                    if (problem != null) return Step.Result.fail(problem);
                    SettingsScreen s = (SettingsScreen) VanillaShim.currentScreen(mc);
                    Checkbox ownSkin = widget(s, "enablePlayerOwnSkinSystemCheckbox", Checkbox.class);
                    return Step.Result.pass("Modpack tab (modpackTabButton selected) shows "
                            + "enablePlayerOwnSkinSystemCheckbox.selected=" + ownSkin.selected()
                            + " == config.enablePlayerOwnSkinSystem="
                            + ClientConfig.getInstance().enablePlayerOwnSkinSystem
                            + " as the only tab widget in children");
                }));

        // 10a5. Server tab as a non-operator on the same screen ----------------------------------
        steps.add(Step.of("settings_server_tab")
                .action(() -> serverHold.set(0))
                .minTicks(5)
                .ready(() -> serverTabReady(mc) == null
                        && serverHold.incrementAndGet() >= TAB_HOLD_TICKS)
                .timeoutTicks(300)
                .screenshot(prefix + "full_10a5_settings_server_tab" + suffix)
                .assertion(() -> {
                    String problem = serverTabReady(mc);
                    if (problem != null) return Step.Result.fail(problem);
                    SettingsScreen s = (SettingsScreen) VanillaShim.currentScreen(mc);
                    if (mc.player == null) return Step.Result.fail("player is null");
                    Boolean operator = VanillaShim.hasPermissionLevel(mc.player, 2);
                    if (operator == null)
                        return Step.Result.fail("cannot read the player's permission level in this "
                                + "runtime; the non-admin Server tab cannot be certified");
                    if (operator)
                        return Step.Result.fail("packaged player unexpectedly has operator level 2; "
                                + "the non-admin Server tab cannot be certified");
                    Checkbox serverTransparency =
                            widget(s, "serverDisableSkinTransparencyCheckbox", Checkbox.class);
                    EditBox cooldown = widget(s, "skinChangeCooldownEditBox", EditBox.class);
                    if (serverTransparency.active)
                        return Step.Result.fail("serverDisableSkinTransparencyCheckbox is active "
                                + "for a non-admin");
                    if (cooldown.active)
                        return Step.Result.fail("skinChangeCooldownEditBox is active for a non-admin");
                    String expectedCooldown = String.valueOf(
                            ServerConfig.getInstance().skinChangeCooldownSeconds);
                    if (!expectedCooldown.equals(cooldown.getValue()))
                        return Step.Result.fail("skinChangeCooldownEditBox='" + cooldown.getValue()
                                + "' expected ServerConfig.skinChangeCooldownSeconds="
                                + expectedCooldown);
                    String notice = Component.translatable("quickskin.settings.server_notice").getString();
                    if (notice.isEmpty() || notice.equals("quickskin.settings.server_notice"))
                        return Step.Result.fail("server notice has no translation: '" + notice + "'");
                    ServerConfig override = ClientConfig.getInstance().getServerOverride();
                    String message = "Server tab (serverTabButton selected) as non-admin "
                            + "(hasPermissions(2)=false): serverDisableSkinTransparencyCheckbox "
                            + "active=false selected=" + serverTransparency.selected()
                            + " (server override disableSkinTransparency="
                            + (override == null ? "<none>" : String.valueOf(override.disableSkinTransparency))
                            + "); skinChangeCooldownEditBox active=false value='"
                            + cooldown.getValue() + "' == ServerConfig.skinChangeCooldownSeconds="
                            + expectedCooldown + "; render draws the yellow notice '" + notice + "'";
                    s.onClose();
                    if (VanillaShim.currentScreen(mc) instanceof SettingsScreen)
                        return Step.Result.fail("SettingsScreen.onClose did not close the screen");
                    return Step.Result.pass(message + "; onClose closed to "
                            + FullScenario.screenName(mc));
                }));

        // 10a6. styled buttons over the vanilla in-world background --------------------------------
        // BackgroundRenderer keeps no static state for its in-world vanilla path (it only lazily
        // builds a panorama renderer on the title screen), so the render route is proven from
        // pixels: a probe frame is grabbed while the menu holds, and the strip left of the panel
        // must show the world through the 0x90 overlay. Under opaque stars that strip is a black
        // fill (luma <= 38 everywhere), so the same metric over the earlier skin-menu capture is
        // the control that proves the probe discriminates.
        steps.add(Step.of("styled_buttons_vanilla_background")
                .action(() -> {
                    backgroundPhase.set(0);
                    backgroundHold.set(0);
                    backgroundCounts.set(null);
                    controlCounts.set(null);
                    backgroundRegion.set(null);
                    backgroundFailure.set(null);
                    owner.enterWorldView(mc);
                    ClientConfig c = ClientConfig.getInstance();
                    if (!c.enableStyledButtons || c.getMenuBackgroundStyle() != BackgroundStyle.VANILLA_BLUR) {
                        backgroundFailure.set("GUI Edit persisted state was lost before the skin menu "
                                + "opened: enableStyledButtons=" + c.enableStyledButtons
                                + " menuBackgroundStyle=" + c.menuBackgroundStyle);
                        return;
                    }
                    VanillaShim.setScreen(mc, new PlayerSkinMenuScreen(null));
                })
                .minTicks(30)
                .ready(() -> backgroundProbeReady(mc, backgroundProbeShot, starsControlShot))
                .settleTicks(20)
                .timeoutTicks(600)
                .screenshot(prefix + "full_10a6_styled_buttons_vanilla_background" + suffix)
                .assertion(() -> {
                    try {
                        return backgroundVerdict(mc);
                    } finally {
                        // Every later dialog step runs with the defaults again.
                        ClientConfig c = ClientConfig.getInstance();
                        c.enableStyledButtons = false;
                        c.setMenuBackgroundStyle(BackgroundStyle.OPAQUE_STARS); // saves
                        c.save();
                        owner.enterWorldView(mc);
                    }
                }));

        return steps;
    }

    // ===== tab readiness (null when ready, otherwise the fail-closed reason) ===================

    private String clientTabReady(Minecraft mc) {
        if (!(VanillaShim.currentScreen(mc) instanceof SettingsScreen s))
            return "settings not open: " + FullScenario.screenName(mc);
        String tab = activeTab(s);
        if (!"CLIENT".equals(tab)) return "activeTab=" + tab + " expected CLIENT";
        TabButton tabButton = widget(s, "clientTabButton", TabButton.class);
        if (tabButton == null) return "SettingsScreen.clientTabButton not built";
        if (!tabButton.isSelected()) return "clientTabButton is not selected";
        Checkbox transparency = widget(s, "disableSkinTransparencyCheckbox", Checkbox.class);
        if (transparency == null) return "SettingsScreen.disableSkinTransparencyCheckbox not built";
        if (!s.children().contains(transparency))
            return "disableSkinTransparencyCheckbox is not among the Client tab's children";
        boolean expected = ClientConfig.getInstance().disableSkinTransparency;
        if (transparency.selected() != expected)
            return "disableSkinTransparencyCheckbox.selected=" + transparency.selected()
                    + " expected config.disableSkinTransparency=" + expected;
        Button keybind = widget(s, "keybindButton", Button.class);
        if (keybind == null) return "SettingsScreen.keybindButton not built";
        if (!s.children().contains(keybind))
            return "keybindButton is not among the Client tab's children";
        String shown = keybind.getMessage().getString();
        String translated = KeybindRegistry.OPEN_SKIN_MENU.getTranslatedKeyMessage().getString();
        if (!shown.equals(translated))
            return "keybindButton reads '" + shown + "' expected translated key '" + translated + "'";
        return null;
    }

    private String keybindCaptureReady(Minecraft mc) {
        if (!(VanillaShim.currentScreen(mc) instanceof SettingsScreen s))
            return "settings not open: " + FullScenario.screenName(mc);
        Object selected = FullScenario.screenField(s, "selectedKey");
        if (selected != KeybindRegistry.OPEN_SKIN_MENU)
            return "selectedKey=" + selected + " expected KeybindRegistry.OPEN_SKIN_MENU";
        Button keybind = widget(s, "keybindButton", Button.class);
        if (keybind == null) return "SettingsScreen.keybindButton not built";
        String shown = keybind.getMessage().getString();
        if (!CAPTURE_LABEL.equals(shown))
            return "keybindButton reads '" + shown + "' expected '" + CAPTURE_LABEL + "'";
        return null;
    }

    private String guiEditTabReady(Minecraft mc) {
        if (!(VanillaShim.currentScreen(mc) instanceof SettingsScreen s))
            return "settings not open: " + FullScenario.screenName(mc);
        String tab = activeTab(s);
        if (!"GUI_EDIT".equals(tab)) return "activeTab=" + tab + " expected GUI_EDIT";
        TabButton tabButton = widget(s, "guiEditTabButton", TabButton.class);
        if (tabButton == null) return "SettingsScreen.guiEditTabButton not built";
        if (!tabButton.isSelected()) return "guiEditTabButton is not selected";
        String[] names = {
                "showOverlayCheckbox", "enableStyledButtonsCheckbox", "menuBackgroundCheckbox",
                "enablePlayerPreviewCustomizationCheckbox", "hideBuiltInCapesCheckbox"};
        boolean[] wanted = {true, true, true, false, false};
        for (int i = 0; i < names.length; i++) {
            Checkbox box = widget(s, names[i], Checkbox.class);
            if (box == null) return "SettingsScreen." + names[i] + " not built";
            if (!s.children().contains(box))
                return names[i] + " is not among the GUI Edit tab's children";
            // Drive the real widget only when its state differs, so repeated polls are no-ops.
            if (box.selected() != wanted[i] && !VanillaShim.press(box))
                return "could not press " + names[i];
            if (box.selected() != wanted[i])
                return names[i] + ".selected=" + box.selected() + " expected " + wanted[i];
        }
        return null;
    }

    private String modpackTabReady(Minecraft mc) {
        if (!(VanillaShim.currentScreen(mc) instanceof SettingsScreen s))
            return "settings not open: " + FullScenario.screenName(mc);
        TabButton tabButton = widget(s, "modpackTabButton", TabButton.class);
        if (tabButton == null) return "SettingsScreen.modpackTabButton not built";
        String tab = activeTab(s);
        if (!"MODPACK".equals(tab)) {
            if (!VanillaShim.press(tabButton)) return "could not press modpackTabButton";
            return "activeTab=" + tab + " expected MODPACK (modpackTabButton pressed)";
        }
        if (!tabButton.isSelected()) return "modpackTabButton is not selected";
        Checkbox ownSkin = widget(s, "enablePlayerOwnSkinSystemCheckbox", Checkbox.class);
        if (ownSkin == null) return "SettingsScreen.enablePlayerOwnSkinSystemCheckbox not built";
        if (!s.children().contains(ownSkin))
            return "enablePlayerOwnSkinSystemCheckbox is not among the Modpack tab's children";
        boolean expected = ClientConfig.getInstance().enablePlayerOwnSkinSystem;
        if (ownSkin.selected() != expected)
            return "enablePlayerOwnSkinSystemCheckbox.selected=" + ownSkin.selected()
                    + " expected config.enablePlayerOwnSkinSystem=" + expected;
        return null;
    }

    private String serverTabReady(Minecraft mc) {
        if (!(VanillaShim.currentScreen(mc) instanceof SettingsScreen s))
            return "settings not open: " + FullScenario.screenName(mc);
        TabButton tabButton = widget(s, "serverTabButton", TabButton.class);
        if (tabButton == null) return "SettingsScreen.serverTabButton not built";
        String tab = activeTab(s);
        if (!"SERVER".equals(tab)) {
            if (!VanillaShim.press(tabButton)) return "could not press serverTabButton";
            return "activeTab=" + tab + " expected SERVER (serverTabButton pressed)";
        }
        if (!tabButton.isSelected()) return "serverTabButton is not selected";
        Checkbox transparency = widget(s, "serverDisableSkinTransparencyCheckbox", Checkbox.class);
        if (transparency == null) return "SettingsScreen.serverDisableSkinTransparencyCheckbox not built";
        if (!s.children().contains(transparency))
            return "serverDisableSkinTransparencyCheckbox is not among the Server tab's children";
        EditBox cooldown = widget(s, "skinChangeCooldownEditBox", EditBox.class);
        if (cooldown == null) return "SettingsScreen.skinChangeCooldownEditBox not built";
        if (!s.children().contains(cooldown))
            return "skinChangeCooldownEditBox is not among the Server tab's children";
        return null;
    }

    // ===== vanilla-background probe ============================================================

    /**
     * Drives the probe one phase per poll: hold the settled menu, grab the probe frame, measure the
     * strip left of the panel in it and in the opaque-stars control capture, then stay ready while
     * the contract capture settles. A phase that cannot proceed records a reason and reports ready
     * so the assertion fails with it instead of the step timing out silently.
     */
    private boolean backgroundProbeReady(Minecraft mc, String probeShot, String controlShot) {
        if (backgroundFailure.get() != null) return true;
        if (!owner.skinMenuLayoutSettled(mc)) {
            if (backgroundPhase.get() == 0) backgroundHold.set(0);
            return false;
        }
        switch (backgroundPhase.get()) {
            case 0:
                if (backgroundHold.incrementAndGet() < BACKGROUND_HOLD_TICKS) return false;
                if (!VanillaShim.screenshot(mc, probeShot)) {
                    backgroundFailure.set("could not grab the background probe frame " + probeShot);
                    return true;
                }
                backgroundPhase.set(1);
                return false;
            case 1: {
                BufferedImage shot = FullScenario.readShot(probeShot);
                if (shot == null) return false; // async write still in flight
                int[] region = probeRegion(mc);
                if (region == null) {
                    backgroundFailure.set("could not derive the strip left of the skin menu panel");
                    return true;
                }
                backgroundRegion.set(region);
                backgroundCounts.set(countBright(mc, shot, region));
                BufferedImage control = FullScenario.readShot(controlShot);
                if (control != null && control.getWidth() == shot.getWidth()
                        && control.getHeight() == shot.getHeight()) {
                    controlCounts.set(countBright(mc, control, region));
                } else {
                    E2ELog.warn("styled_buttons_vanilla_background: opaque-stars control frame "
                            + controlShot + " unavailable or a different size; probe runs uncontrolled");
                }
                backgroundPhase.set(2);
                return true;
            }
            default:
                return true;
        }
    }

    /** The GUI-coordinate strip {@code {x0,y0,x1,y1}} left of the skin menu panel, upper half. */
    private static int[] probeRegion(Minecraft mc) {
        Screen screen = VanillaShim.currentScreen(mc);
        if (!(screen instanceof PlayerSkinMenuScreen)) return null;
        int guiWidth = mc.getWindow().getGuiScaledWidth();
        int guiHeight = mc.getWindow().getGuiScaledHeight();
        Object panelX = FullScenario.screenField(screen, "panelX");
        int right = panelX instanceof Integer x ? x : guiWidth / 8;
        // Keep a margin inside the panel edge so its outline and frosted fill never enter the count.
        right = Math.min(right - 2, guiWidth - 1);
        if (right < 8 || guiHeight < 16) return null;
        // The upper half faces the locked daytime sky, the strongest light through the overlay.
        return new int[] {0, 0, right, guiHeight / 2};
    }

    /** {@code {brightPixels, sampledPixels}} of a GUI-coordinate region in a captured frame. */
    private static long[] countBright(Minecraft mc, BufferedImage shot, int[] guiRegion) {
        double scaleX = (double) shot.getWidth() / Math.max(1, mc.getWindow().getGuiScaledWidth());
        double scaleY = (double) shot.getHeight() / Math.max(1, mc.getWindow().getGuiScaledHeight());
        int px0 = Math.max(0, (int) Math.floor(guiRegion[0] * scaleX));
        int py0 = Math.max(0, (int) Math.floor(guiRegion[1] * scaleY));
        int px1 = Math.min(shot.getWidth() - 1, (int) Math.ceil(guiRegion[2] * scaleX));
        int py1 = Math.min(shot.getHeight() - 1, (int) Math.ceil(guiRegion[3] * scaleY));
        long bright = 0;
        long sampled = 0;
        for (int y = py0; y <= py1; y++) {
            for (int x = px0; x <= px1; x++) {
                if (luma(shot.getRGB(x, y)) > BRIGHT_LUMA) bright++;
                sampled++;
            }
        }
        return new long[] {bright, sampled};
    }

    static int luma(int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        return (299 * r + 587 * g + 114 * b) / 1000;
    }

    private Step.Result backgroundVerdict(Minecraft mc) {
        String failure = backgroundFailure.get();
        if (failure != null) return Step.Result.fail(failure);
        if (!(VanillaShim.currentScreen(mc) instanceof PlayerSkinMenuScreen screen))
            return Step.Result.fail("skin menu not open: " + FullScenario.screenName(mc));
        ClientConfig c = ClientConfig.getInstance();
        if (!c.enableStyledButtons)
            return Step.Result.fail("enableStyledButtons is false while the styled menu is open");
        if (c.getMenuBackgroundStyle() != BackgroundStyle.VANILLA_BLUR)
            return Step.Result.fail("menuBackgroundStyle=" + c.menuBackgroundStyle
                    + " expected " + BackgroundStyle.VANILLA_BLUR.getId());
        if (mc.player == null || mc.level == null)
            return Step.Result.fail("no player/level: BackgroundRenderer would take the panorama "
                    + "path instead of the in-world overlay");

        int styled = 0;
        int primary = 0;
        int plain = 0;
        for (GuiEventListener child : screen.children()) {
            if (child instanceof StyledButton) styled++;
            else if (child instanceof PrimaryButton) primary++;
            else if (child != null && child.getClass() == Button.class) plain++;
        }
        if (styled == 0)
            return Step.Result.fail("no StyledButton among the skin menu's children (styled="
                    + styled + " primary=" + primary + " plainVanillaButton=" + plain + ")");

        long[] counts = backgroundCounts.get();
        int[] region = backgroundRegion.get();
        if (counts == null || region == null)
            return Step.Result.fail("background probe never measured the strip left of the panel");
        if (counts[1] <= 0) return Step.Result.fail("background probe sampled no pixels");
        double fraction = (double) counts[0] / counts[1];
        String where = java.util.Arrays.toString(region);
        if (fraction < MIN_BRIGHT_FRACTION)
            return Step.Result.fail("the world does not show through the menu background: only "
                    + counts[0] + " of " + counts[1] + " pixels in GUI strip " + where
                    + " exceed luma " + BRIGHT_LUMA + " (fraction " + fraction
                    + "); an opaque-stars fill would look exactly like this");
        String control;
        long[] ctl = controlCounts.get();
        if (ctl != null && ctl[1] > 0) {
            double controlFraction = (double) ctl[0] / ctl[1];
            if (controlFraction > MAX_CONTROL_BRIGHT_FRACTION)
                return Step.Result.fail("the opaque-stars control capture already shows " + ctl[0]
                        + " of " + ctl[1] + " bright pixels in " + where + " (fraction "
                        + controlFraction + "), so the probe cannot tell the two backgrounds apart");
            control = "; opaque-stars control frame over the same strip: " + ctl[0] + " of "
                    + ctl[1] + " bright (fraction " + controlFraction + ")";
        } else {
            control = "; opaque-stars control frame unavailable";
        }
        return Step.Result.pass("skin menu open with " + styled + " StyledButton and " + primary
                + " PrimaryButton children (" + plain + " plain vanilla Button); config "
                + "enableStyledButtons=true menuBackgroundStyle=" + c.menuBackgroundStyle
                + " with player+level present selects BackgroundRenderer's in-world 0x90 overlay; "
                + "probe frame shows the world through it: " + counts[0] + " of " + counts[1]
                + " pixels in GUI strip " + where + " exceed luma " + BRIGHT_LUMA
                + " (fraction " + fraction + ", minimum " + MIN_BRIGHT_FRACTION + ")" + control);
    }

    // ===== reflective helpers ==================================================================

    /** The enum constant name of {@code SettingsScreen.activeTab}, or {@code null}. */
    private static String activeTab(SettingsScreen s) {
        Object tab = FullScenario.screenField(s, "activeTab");
        return tab == null ? null : tab.toString();
    }

    /** A private {@code SettingsScreen} widget by field name, or {@code null} when absent/mistyped. */
    private static <T> T widget(Screen s, String name, Class<T> type) {
        Object value = FullScenario.screenField(s, name);
        return type.isInstance(value) ? type.cast(value) : null;
    }
}
