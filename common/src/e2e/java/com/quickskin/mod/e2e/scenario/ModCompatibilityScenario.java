package com.quickskin.mod.e2e.scenario;

import com.quickskin.mod.client.gui.util.SkinImporter;
import com.quickskin.mod.client.services.PlayerAppearanceService;
import com.quickskin.mod.common.data.AssetMetadata;
import com.quickskin.mod.common.data.PlayerAppearance;
import com.quickskin.mod.e2e.CompatibilityProbe;
import com.quickskin.mod.e2e.DefaultSkinEvidenceView;
import com.quickskin.mod.e2e.E2ELog;
import com.quickskin.mod.e2e.Scenario;
import com.quickskin.mod.e2e.Step;
import com.quickskin.mod.e2e.TestAssets;
import com.quickskin.mod.e2e.VanillaShim;
import com.quickskin.mod.e2e.generated.ScenarioContract.ScenarioId;
import net.minecraft.client.Minecraft;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Proves that one lock-selected optional integration activates before the full base suite runs. */
public final class ModCompatibilityScenario implements Scenario {
    private volatile CompatibilityProbe.Result probe =
            new CompatibilityProbe.Result(false, "probe not executed");
    private volatile String skinHash;

    @Override
    public ScenarioId id() {
        return ScenarioId.MOD_COMPATIBILITY;
    }

    @Override
    public List<Step> build(Minecraft mc) {
        final String version = System.getProperty("quickskin.e2e.version", "v1_20_1");
        final String role = System.getProperty("quickskin.e2e.role", "client_a");
        final UUID uuid = mc.player.getUUID();
        final PlayerAppearanceService appearance = PlayerAppearanceService.getInstance();
        final List<Step> steps = new ArrayList<>();

        steps.add(Step.of("integration_active")
                .action(() -> {
                    probe = CompatibilityProbe.verifyConfiguredIntegration();
                    E2ELog.info("compatibility probe "
                            + (probe.active() ? "PASS" : "FAIL") + ": " + probe.detail());
                })
                .minTicks(1)
                .ready(() -> true)
                .assertion(() -> probe.active()
                        ? Step.Result.pass(probe.detail())
                        : Step.Result.fail(probe.detail())));

        steps.add(Step.of("baseline_with_mod")
                .action(() -> DefaultSkinEvidenceView.hold(mc, false))
                .minTicks(40)
                .ready(() -> VanillaShim.isExpectedDefaultSkinResolved(mc.player)
                        && DefaultSkinEvidenceView.hold(mc, false))
                .settleTicks(20)
                .timeoutTicks(400)
                .screenshot(version + "_compat_01_baseline_" + role + ".png")
                .assertion(() -> {
                    if (!probe.active()) return Step.Result.fail(probe.detail());
                    String expected = VanillaShim.expectedDefaultSkinTexture(mc.player);
                    String actual = VanillaShim.skinTexture(mc.player);
                    if (expected == null || !expected.equals(actual)) {
                        return Step.Result.fail("default skin did not stabilize: expected="
                                + expected + " actual=" + actual);
                    }
                    return Step.Result.pass(probe.detail() + "; defaultSkin=" + actual);
                }));

        steps.add(Step.of("apply_local_skin_with_mod")
                .action(() -> {
                    DefaultSkinEvidenceView.enterFirstPerson(mc);
                    try {
                        Path file = TestAssets.makeClassicSkin();
                        AssetMetadata metadata = SkinImporter.importSkin(file);
                        if (metadata == null) {
                            E2ELog.warn("compatibility SkinImporter returned null");
                            return;
                        }
                        skinHash = metadata.hash();
                        appearance.applySkin(uuid, "local_skin:" + skinHash, "auto");
                    } catch (Exception e) {
                        E2ELog.error("compatibility local skin action failed", e);
                    }
                })
                .minTicks(40)
                .ready(() -> skinHash != null
                        && appearance.getAppearance(uuid) != null
                        && appearance.getSkinLocation(uuid) != null)
                .timeoutTicks(400)
                .screenshot(version + "_compat_02_local_skin_" + role + ".png")
                .assertion(() -> {
                    CompatibilityProbe.Result after =
                            CompatibilityProbe.verifyConfiguredIntegration();
                    if (!after.active()) return Step.Result.fail(after.detail());
                    if (skinHash == null) return Step.Result.fail("skin import failed");
                    PlayerAppearance state = appearance.getAppearance(uuid);
                    String expected = "local_skin:" + skinHash;
                    if (state == null || !expected.equals(state.getSkinId())) {
                        return Step.Result.fail("local skin state was not retained with "
                                + after.detail());
                    }
                    return Step.Result.pass(after.detail()
                            + "; skinId=" + state.getSkinId() + " location resolved");
                }));

        return steps;
    }
}
