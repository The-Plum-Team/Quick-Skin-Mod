package com.quickskin.mod.e2e.scenario;

import com.mojang.blaze3d.platform.InputConstants;
import com.quickskin.mod.client.gui.screen.PlayerCapeMenuScreen;
import com.quickskin.mod.client.gui.screen.PlayerSkinMenuScreen;
import com.quickskin.mod.client.gui.screen.SettingsScreen;
import com.quickskin.mod.client.gui.widget.StyledButton;
import com.quickskin.mod.client.input.KeybindRegistry;
import com.quickskin.mod.config.ClientConfig;
import com.quickskin.mod.e2e.Scenario;
import com.quickskin.mod.e2e.Step;
import com.quickskin.mod.e2e.VanillaShim;
import com.quickskin.mod.e2e.generated.ScenarioContract.ScenarioId;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
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
                settingsReturn(mc, false, version, role));
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

    private Step settingsReturn(Minecraft mc, boolean skin, String version, String role) {
        AtomicReference<Screen> parent = new AtomicReference<>();
        AtomicReference<String> failure = new AtomicReference<>();
        AtomicInteger phase = new AtomicInteger();
        boolean targetStyled = skin;
        String name = skin ? "skin_menu_settings_return" : "cape_menu_settings_return";
        return Step.of(name)
                .action(() -> {
                    ClientConfig.getInstance().enableStyledButtons = !targetStyled;
                    ClientConfig.getInstance().save();
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
                        Object field = FullScenario.screenField(settings, "enableStyledButtonsCheckbox");
                        if (!(field instanceof Checkbox checkbox) || !settings.children().contains(checkbox)) {
                            if (!VanillaShim.press(button)) failure.set("could not open GUI Edit tab");
                            return failure.get() != null;
                        }
                        if (checkbox.selected() != targetStyled && !VanillaShim.press(checkbox)) {
                            failure.set("could not change styled buttons");
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
                    boolean hasStyled = current.children().stream().anyMatch(StyledButton.class::isInstance);
                    if (hasStyled != targetStyled
                            || ClientConfig.getInstance().enableStyledButtons != targetStyled)
                        return Step.Result.fail("reconstructed parent did not apply the selected button style");
                    return Step.Result.pass("settings reconstructed " + current.getClass().getSimpleName()
                            + " through its API; styled buttons=" + hasStyled);
                });
    }
}
