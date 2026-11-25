package com.quickskin.mod.e2e.scenario;

import com.mojang.blaze3d.platform.InputConstants;
import com.quickskin.mod.client.gui.screen.PlayerCapeMenuScreen;
import com.quickskin.mod.client.gui.screen.PlayerSkinMenuScreen;
import com.quickskin.mod.client.gui.screen.SettingsScreen;
import com.quickskin.mod.client.input.KeybindRegistry;
import com.quickskin.mod.common.data.BackgroundStyle;
import com.quickskin.mod.config.ClientConfig;
import com.quickskin.mod.e2e.Scenario;
import com.quickskin.mod.e2e.Step;
import com.quickskin.mod.e2e.VanillaShim;
import com.quickskin.mod.e2e.generated.ScenarioContract.ScenarioId;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

/** Exercises the callbacks installed by the composition root through their real consumers. */
public final class FeatureNavigationScenario implements Scenario {
    @Override
    public ScenarioId id() { return ScenarioId.FEATURE_NAVIGATION; }

    @Override
    public List<Step> build(Minecraft mc) {
        String version = System.getProperty("quickskin.e2e.version");
        String role = System.getProperty("quickskin.e2e.role");
        AtomicReference<String> originalKey = new AtomicReference<>();
        Step open = Step.of("open_skin_menu_using_key")
                .action(() -> {
                    VanillaShim.setScreen(mc, null);
                    originalKey.set(KeybindRegistry.OPEN_SKIN_MENU.saveString());
                    InputConstants.Key key = InputConstants.getKey("key.keyboard.f8");
                    KeybindRegistry.OPEN_SKIN_MENU.setKey(key);
                    KeyMapping.resetMapping();
                    KeyMapping.click(key);
                })
                .ready(() -> VanillaShim.currentScreen(mc) instanceof PlayerSkinMenuScreen screen
                        && !screen.children().isEmpty() && VanillaShim.moveMouseTo(mc, 5, 5) == null)
                .minTicks(30).settleTicks(20).timeoutTicks(400)
                .screenshot(version + "_navigation_01_key_" + role + ".png")
                .assertion(() -> {
                    KeybindRegistry.OPEN_SKIN_MENU.setKey(InputConstants.getKey(originalKey.get()));
                    KeyMapping.resetMapping();
                    return VanillaShim.currentScreen(mc) instanceof PlayerSkinMenuScreen
                            ? Step.Result.pass("registered key callback opened the skin menu; key restored")
                            : Step.Result.fail("registered key did not open the skin menu");
                });
        return List.of(open, vanillaMenuButton(mc, version, role),
                settingsReturn(mc, true, version, role),
                settingsReturn(mc, false, version, role),
                hiddenButtonWithoutKey(mc),
                skinMenuKeyOver(mc, false),
                skinMenuKeyWhileTyping(mc),
                skinMenuKeyOver(mc, true),
                favouritesKeyOverPauseMenu(mc),
                modListOpensSkinMenu(mc));
    }

    /** The key both skin-menu-key steps bind: a letter, so that it is one a player types too. */
    private static final String TYPED_KEY = "key.keyboard.y";

    /** The Change Skin buttons Quick Skin put on a vanilla menu: its label, or Essential's icon. */
    private static List<Button> changeSkinButtons(Screen screen) {
        String label = Component.translatable("quickskin.button.change_skin").getString();
        boolean essential = com.quickskin.mod.client.compat.EssentialCompatIntegration.isAvailable();
        return screen.children().stream()
                .filter(Button.class::isInstance).map(Button.class::cast)
                .filter(button -> button.visible
                        && (label.equals(button.getMessage().getString())
                        || essential && button instanceof com.quickskin.mod.client.gui.widget.IconActionButton))
                .toList();
    }

    /** Binds a key mapping for one step and remembers what it was, for {@link #restoreKey}. */
    private static void bindKey(KeyMapping mapping, String key, AtomicReference<String> original) {
        original.set(mapping.saveString());
        mapping.setKey(InputConstants.getKey(key));
        KeyMapping.resetMapping();
    }

    private static void restoreKey(KeyMapping mapping, AtomicReference<String> original) {
        if (original.get() == null) return;
        mapping.setKey(InputConstants.getKey(original.get()));
        KeyMapping.resetMapping();
    }

    /**
     * With no key bound to the skin menu the setting still hides the button: the settings box is
     * not locked, and the title screen is built without the button.
     */
    private Step hiddenButtonWithoutKey(Minecraft mc) {
        AtomicReference<String> originalKey = new AtomicReference<>();
        AtomicReference<Screen> title = new AtomicReference<>();
        AtomicReference<String> failure = new AtomicReference<>();
        AtomicReference<String> evidence = new AtomicReference<>();
        AtomicInteger phase = new AtomicInteger();
        return Step.of("hidden_change_skin_button_without_a_key")
                .action(() -> {
                    originalKey.set(KeybindRegistry.OPEN_SKIN_MENU.saveString());
                    KeybindRegistry.OPEN_SKIN_MENU.setKey(InputConstants.UNKNOWN);
                    KeyMapping.resetMapping();
                    ClientConfig config = ClientConfig.getInstance();
                    config.hideChangeSkinButton = false;
                    config.save();
                    // Settings draw their parent behind them, so it is built first
                    title.set(new TitleScreen());
                    VanillaShim.setScreen(mc, title.get());
                })
                .ready(() -> {
                    Screen current = VanillaShim.currentScreen(mc);
                    if (phase.get() == 0) {
                        if (current != title.get() || current.children().isEmpty()) return false;
                        phase.set(1);
                        VanillaShim.setScreen(mc, new SettingsScreen(current));
                        return false;
                    }
                    if (phase.get() == 1) {
                        if (!(current instanceof SettingsScreen settings) || settings.children().isEmpty()) return false;
                        Object field = FullScenario.screenField(settings, "hideChangeSkinButtonCheckbox");
                        if (!(field instanceof Checkbox checkbox) || !settings.children().contains(checkbox)) {
                            failure.set("the Client tab settings open on shows no Hide the Change Skin button box");
                            return true;
                        }
                        if (!checkbox.active) {
                            failure.set("the Hide the Change Skin button box is locked while no key is bound");
                            return true;
                        }
                        // The box sits on the Client tab, which settings open on; pressing it
                        // applies the change at once, as the player's click does
                        if (!checkbox.selected() && !VanillaShim.press(checkbox)) {
                            failure.set("could not press the Hide the Change Skin button box");
                            return true;
                        }
                        if (!ClientConfig.getInstance().hideChangeSkinButton) {
                            failure.set("pressing the box did not hide the button in the configuration");
                            return true;
                        }
                        phase.set(2);
                        // Opened again, the title screen is built with the new setting
                        VanillaShim.setScreen(mc, title.get());
                        return false;
                    }
                    if (failure.get() != null) return true;
                    if (current != title.get() || current.children().isEmpty()) return false;
                    List<Button> buttons = changeSkinButtons(current);
                    evidence.set("title screen widgets=" + current.children().size()
                            + ", Change Skin buttons=" + buttons.size());
                    if (!buttons.isEmpty()) failure.set("the hidden Change Skin button is on the title screen: " + evidence.get());
                    return true;
                })
                .minTicks(20).timeoutTicks(400)
                .assertion(() -> {
                    restoreKey(KeybindRegistry.OPEN_SKIN_MENU, originalKey);
                    ClientConfig config = ClientConfig.getInstance();
                    config.hideChangeSkinButton = false;
                    config.save();
                    if (failure.get() != null) return Step.Result.fail(failure.get());
                    return Step.Result.pass("with no key bound the settings box hid the Change Skin button: "
                            + evidence.get() + "; key and setting restored");
                });
    }

    /**
     * A real key press, sent as the window reports one, opens the skin menu over the title screen
     * or the pause menu, which the menu returns to.
     */
    private Step skinMenuKeyOver(Minecraft mc, boolean pauseMenu) {
        AtomicReference<String> originalKey = new AtomicReference<>();
        AtomicReference<Screen> parent = new AtomicReference<>();
        AtomicReference<String> failure = new AtomicReference<>();
        AtomicInteger phase = new AtomicInteger();
        String where = pauseMenu ? "pause menu" : "title screen";
        return Step.of(pauseMenu ? "open_skin_menu_using_key_on_pause_menu" : "open_skin_menu_using_key_on_title_screen")
                .action(() -> {
                    bindKey(KeybindRegistry.OPEN_SKIN_MENU, TYPED_KEY, originalKey);
                    Screen screen = pauseMenu ? new PauseScreen(true) : new TitleScreen();
                    parent.set(screen);
                    VanillaShim.setScreen(mc, screen);
                })
                .ready(() -> {
                    Screen current = VanillaShim.currentScreen(mc);
                    if (phase.get() == 0) {
                        if (current != parent.get() || current.children().isEmpty()) return false;
                        phase.set(1);
                        String pressed = VanillaShim.pressKeyThroughKeyboard(mc, InputConstants.KEY_Y, 0, 0);
                        if (pressed != null) {
                            failure.set(pressed);
                            return true;
                        }
                        return false;
                    }
                    return failure.get() != null || current instanceof PlayerSkinMenuScreen
                            && !current.children().isEmpty();
                })
                .minTicks(20).timeoutTicks(400)
                .assertion(() -> {
                    restoreKey(KeybindRegistry.OPEN_SKIN_MENU, originalKey);
                    if (failure.get() != null) return Step.Result.fail(failure.get());
                    Screen current = VanillaShim.currentScreen(mc);
                    if (!(current instanceof PlayerSkinMenuScreen))
                        return Step.Result.fail("the skin menu's key did nothing over the " + where);
                    if (FullScenario.screenField(current, "parent") != parent.get())
                        return Step.Result.fail("the skin menu opened by its key over the " + where
                                + " does not return to it");
                    return Step.Result.pass("a real press of the skin menu's key (Y) over the " + where
                            + " opened the skin menu, which returns to it; key restored");
                });
    }

    /**
     * The same key typed into a text field of an allowed screen stays the field's: here a title
     * screen another mod gave a search box, which has the focus.
     */
    private Step skinMenuKeyWhileTyping(Minecraft mc) {
        AtomicReference<String> originalKey = new AtomicReference<>();
        AtomicReference<Screen> title = new AtomicReference<>();
        AtomicReference<EditBox> box = new AtomicReference<>();
        AtomicReference<String> failure = new AtomicReference<>();
        AtomicInteger phase = new AtomicInteger();
        AtomicInteger heldTicks = new AtomicInteger();
        return Step.of("skin_menu_key_while_typing")
                .action(() -> {
                    bindKey(KeybindRegistry.OPEN_SKIN_MENU, TYPED_KEY, originalKey);
                    Screen screen = new TitleScreen() {
                        @Override
                        protected void init() {
                            super.init();
                            EditBox field = new EditBox(mc.font, 4, 4, 120, 20, Component.literal("Search"));
                            box.set(addRenderableWidget(field));
                            setFocused(field);
                        }
                    };
                    title.set(screen);
                    VanillaShim.setScreen(mc, screen);
                })
                .ready(() -> {
                    Screen current = VanillaShim.currentScreen(mc);
                    if (phase.get() == 0) {
                        if (current != title.get() || box.get() == null || current.getFocused() != box.get()) return false;
                        phase.set(1);
                        String pressed = VanillaShim.pressKeyThroughKeyboard(mc, InputConstants.KEY_Y, 0, 0);
                        if (pressed != null) {
                            failure.set(pressed);
                            return true;
                        }
                        return false;
                    }
                    // A menu the key opened would be on screen by the next tick; give it twenty
                    return failure.get() != null || current != title.get() || heldTicks.incrementAndGet() >= 20;
                })
                .minTicks(20).timeoutTicks(400)
                .assertion(() -> {
                    restoreKey(KeybindRegistry.OPEN_SKIN_MENU, originalKey);
                    if (failure.get() != null) return Step.Result.fail(failure.get());
                    Screen current = VanillaShim.currentScreen(mc);
                    if (current instanceof PlayerSkinMenuScreen)
                        return Step.Result.fail("typing the skin menu's key into a focused text field opened the skin menu");
                    if (current != title.get())
                        return Step.Result.fail("typing into the title screen's text field left it for "
                                + (current == null ? "no screen" : current.getClass().getName()));
                    return Step.Result.pass("the skin menu's key (Y) typed into a focused text field of the title screen"
                            + " left the title screen and its field in front for 20 ticks; key restored");
                });
    }

    /**
     * The favourites key over the pause menu goes back to the world, where the fan opens: the fan
     * is a layer of the world, never of a screen.
     */
    private Step favouritesKeyOverPauseMenu(Minecraft mc) {
        AtomicReference<String> originalKey = new AtomicReference<>();
        AtomicReference<Screen> pause = new AtomicReference<>();
        AtomicReference<String> failure = new AtomicReference<>();
        AtomicReference<Boolean> fanOpen = new AtomicReference<>();
        AtomicInteger phase = new AtomicInteger();
        return Step.of("favourites_key_on_pause_menu")
                .action(() -> {
                    bindKey(KeybindRegistry.FAVOURITES_FAN, "key.keyboard.u", originalKey);
                    Screen screen = new PauseScreen(true);
                    pause.set(screen);
                    VanillaShim.setScreen(mc, screen);
                })
                .ready(() -> {
                    Screen current = VanillaShim.currentScreen(mc);
                    if (phase.get() == 0) {
                        if (current != pause.get() || current.children().isEmpty()) return false;
                        phase.set(1);
                        String pressed = VanillaShim.pressKeyThroughKeyboard(mc, InputConstants.KEY_U, 0, 0);
                        if (pressed != null) {
                            failure.set(pressed);
                            return true;
                        }
                        fanOpen.set(com.quickskin.mod.client.gui.fan.FavouritesFan.capturesMouse());
                        return false;
                    }
                    return failure.get() != null || current == null;
                })
                .minTicks(20).timeoutTicks(400)
                .assertion(() -> {
                    restoreKey(KeybindRegistry.FAVOURITES_FAN, originalKey);
                    boolean open = com.quickskin.mod.client.gui.fan.FavouritesFan.capturesMouse();
                    if (open) com.quickskin.mod.client.gui.fan.FavouritesFan.close();
                    if (failure.get() != null) return Step.Result.fail(failure.get());
                    if (VanillaShim.currentScreen(mc) != null)
                        return Step.Result.fail("the favourites key left the pause menu open");
                    return Step.Result.pass("a real press of the favourites key (U) over the pause menu went back to"
                            + " the world for the fan (open=" + fanOpen.get() + "; with no favourites it only"
                            + " shows its notice); key restored");
                });
    }

    /**
     * The loader's mod list opens the skin menu as Quick Skin's configuration screen, through the
     * very lookup that list makes: Mod Menu's on Fabric, the extension point on NeoForge and Forge.
     * Without Mod Menu, Fabric can only show that the entry is declared and packaged, and that Quick
     * Skin loaded without Mod Menu, which this run itself is.
     */
    private Step modListOpensSkinMenu(Minecraft mc) {
        AtomicReference<Screen> parent = new AtomicReference<>();
        AtomicReference<String> failure = new AtomicReference<>();
        AtomicReference<String> route = new AtomicReference<>();
        AtomicReference<String> declaredOnly = new AtomicReference<>();
        return Step.of("open_skin_menu_from_mod_list")
                .action(() -> {
                    Screen title = new TitleScreen();
                    parent.set(title);
                    VanillaShim.setScreen(mc, title);
                    try {
                        Screen configured = ModListLookup.configScreen(mc, title, route, declaredOnly);
                        if (configured != null) VanillaShim.setScreen(mc, configured);
                        else if (declaredOnly.get() == null) failure.set(route.get() + " gave no configuration screen");
                    } catch (Throwable t) {
                        failure.set("the mod list's lookup failed: " + t);
                    }
                })
                .ready(() -> {
                    if (failure.get() != null || declaredOnly.get() != null) return true;
                    Screen current = VanillaShim.currentScreen(mc);
                    return current instanceof PlayerSkinMenuScreen && !current.children().isEmpty();
                })
                .minTicks(20).timeoutTicks(400)
                .assertion(() -> {
                    if (failure.get() != null) return Step.Result.fail(failure.get());
                    if (declaredOnly.get() != null) return Step.Result.pass(declaredOnly.get());
                    Screen current = VanillaShim.currentScreen(mc);
                    if (!(current instanceof PlayerSkinMenuScreen))
                        return Step.Result.fail(route.get() + " did not open the skin menu");
                    if (FullScenario.screenField(current, "parent") != parent.get())
                        return Step.Result.fail(route.get() + " opened a skin menu that does not return to the mod list");
                    return Step.Result.pass(route.get() + " opened the skin menu, which returns to the screen that asked");
                });
    }

    private Step vanillaMenuButton(Minecraft mc, String version, String role) {
        AtomicReference<Screen> parent = new AtomicReference<>();
        AtomicReference<String> failure = new AtomicReference<>();
        AtomicInteger phase = new AtomicInteger();
        return Step.of("open_skin_menu_using_vanilla_button")
                .action(() -> {
                    Screen title = new TitleScreen();
                    parent.set(title);
                    VanillaShim.setScreen(mc, title);
                })
                .ready(() -> {
                    Screen current = VanillaShim.currentScreen(mc);
                    if (phase.get() == 0) {
                        if (current != parent.get() || current.children().isEmpty()) return false;
                        String label = Component.translatable("quickskin.button.change_skin").getString();
                        boolean essential = com.quickskin.mod.client.compat.EssentialCompatIntegration.isAvailable();
                        List<Button> buttons = current.children().stream()
                                .filter(Button.class::isInstance).map(Button.class::cast)
                                .filter(button -> button.visible && button.active
                                        && (label.equals(button.getMessage().getString())
                                        || essential && button instanceof
                                                com.quickskin.mod.client.gui.widget.IconActionButton)).toList();
                        if (buttons.isEmpty()) return false;
                        if (buttons.size() != 1) {
                            failure.set("vanilla menu has duplicate Quick Skin buttons");
                            return true;
                        }
                        phase.set(1);
                        if (!VanillaShim.press(buttons.get(0))) {
                            failure.set("could not press the injected vanilla-menu button");
                            return true;
                        }
                        return false;
                    }
                    return failure.get() != null || current instanceof PlayerSkinMenuScreen
                            && !current.children().isEmpty() && VanillaShim.moveMouseTo(mc, 5, 5) == null;
                })
                .minTicks(30).settleTicks(20).timeoutTicks(400)
                .screenshot(version + "_navigation_vanilla_button_" + role + ".png")
                .assertion(() -> {
                    if (failure.get() != null) return Step.Result.fail(failure.get());
                    Screen current = VanillaShim.currentScreen(mc);
                    if (!(current instanceof PlayerSkinMenuScreen)
                            || FullScenario.screenField(current, "parent") != parent.get())
                        return Step.Result.fail("vanilla-menu callback did not retain its parent");
                    return Step.Result.pass("injected vanilla-menu button opened its declared skin menu");
                });
    }

    /**
     * Opens settings over a skin or cape menu, changes a setting that menu reads only when it is
     * built (the skin menu's background, the cape menu's built-in capes) through the real GUI Edit
     * checkbox, and closes: settings must rebuild its parent through RestylableScreen.
     */
    private Step settingsReturn(Minecraft mc, boolean skin, String version, String role) {
        AtomicReference<Screen> parent = new AtomicReference<>();
        AtomicReference<String> failure = new AtomicReference<>();
        AtomicInteger phase = new AtomicInteger();
        String name = skin ? "skin_menu_settings_return" : "cape_menu_settings_return";
        String checkboxField = skin ? "menuBackgroundCheckbox" : "hideBuiltInCapesCheckbox";
        return Step.of(name)
                .action(() -> {
                    ClientConfig config = ClientConfig.getInstance();
                    // Start from the opposite of the state the step selects
                    config.setMenuBackgroundStyle(BackgroundStyle.OPAQUE_STARS);
                    config.hideBuiltInCapes = false;
                    config.save();
                    Screen original = skin ? new PlayerSkinMenuScreen(null) : new PlayerCapeMenuScreen(null);
                    parent.set(original);
                    // Settings renders its parent behind the dialog, so initialize the real
                    // parent through Minecraft before navigating away from it.
                    VanillaShim.setScreen(mc, original);
                })
                .ready(() -> {
                    Screen current = VanillaShim.currentScreen(mc);
                    if (phase.get() == 0) {
                        if (current != parent.get() || current.children().isEmpty()) return false;
                        phase.set(1);
                        VanillaShim.setScreen(mc, new SettingsScreen(current));
                        return false;
                    }
                    if (current instanceof SettingsScreen settings) {
                        Object tab = FullScenario.screenField(settings, "guiEditTabButton");
                        if (!(tab instanceof Button button)) return false;
                        Object field = FullScenario.screenField(settings, checkboxField);
                        if (!(field instanceof Checkbox checkbox) || !settings.children().contains(checkbox)) {
                            if (!VanillaShim.press(button)) failure.set("could not open GUI Edit tab");
                            return failure.get() != null;
                        }
                        if (!checkbox.selected() && !VanillaShim.press(checkbox)) {
                            failure.set("could not press " + checkboxField);
                            return true;
                        }
                        settings.onClose();
                        return false;
                    }
                    if (current == parent.get()) {
                        failure.set("settings returned the old parent instead of invoking its reconstruction API");
                        return true;
                    }
                    return current != null && current.getClass() == parent.get().getClass()
                            && !current.children().isEmpty() && VanillaShim.moveMouseTo(mc, 5, 5) == null;
                })
                .minTicks(20).settleTicks(20).timeoutTicks(400)
                .screenshot(version + "_navigation_" + name + "_" + role + ".png")
                .assertion(() -> {
                    if (failure.get() != null) return Step.Result.fail(failure.get());
                    Screen current = VanillaShim.currentScreen(mc);
                    if (current == null || current == parent.get()
                            || current.getClass() != parent.get().getClass())
                        return Step.Result.fail("settings did not reconstruct its declared parent");
                    ClientConfig config = ClientConfig.getInstance();
                    String applied = skin
                            ? "menuBackgroundStyle=" + config.menuBackgroundStyle
                            : "hideBuiltInCapes=" + config.hideBuiltInCapes;
                    boolean selected = skin
                            ? config.getMenuBackgroundStyle() == BackgroundStyle.VANILLA_BLUR
                            : config.hideBuiltInCapes;
                    if (!selected)
                        return Step.Result.fail("the GUI Edit change was not applied: " + applied);
                    return Step.Result.pass("settings reconstructed " + current.getClass().getSimpleName()
                            + " through its API after " + checkboxField + " was switched on; " + applied);
                });
    }
}
