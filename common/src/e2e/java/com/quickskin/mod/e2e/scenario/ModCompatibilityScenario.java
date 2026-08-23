package com.quickskin.mod.e2e.scenario;

import com.quickskin.mod.e2e.CompatibilityProbe;
import com.quickskin.mod.e2e.Scenario;
import com.quickskin.mod.e2e.Step;
import com.quickskin.mod.e2e.generated.ScenarioContract.ScenarioId;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;

/** Proves that one lock-selected optional integration activates before the full base suite runs. */
public final class ModCompatibilityScenario implements Scenario {
    private volatile CompatibilityProbe.Result probe =
            new CompatibilityProbe.Result(false, "probe not executed");

    @Override
    public ScenarioId id() {
        return ScenarioId.MOD_COMPATIBILITY;
    }

    @Override
    public List<Step> build(Minecraft mc) {
        final String version = System.getProperty("quickskin.e2e.version", "v1_20_1");
        final String role = System.getProperty("quickskin.e2e.role", "client_a");
        final String modId = System.getProperty("quickskin.e2e.compatibility", "").trim();
        final ModCompatibilityFeature feature = ModCompatibilityFeature.create(modId, mc);
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
                .action(feature::prepareBaseline)
                .minTicks(feature.baselineMinTicks())
                .ready(() -> probe.active() && feature.baselineReady())
                .settleTicks(feature.baselineSettleTicks())
                .timeoutTicks(feature.baselineTimeoutTicks())
                .screenshot(version + "_compat_01_baseline_" + role + ".png")
                .assertion(() -> probe.active()
                        ? feature.assertBaseline()
                        : Step.Result.fail(probe.detail())));

        steps.add(Step.of("apply_local_skin_with_mod")
                .action(feature::applyQuickSkinFeature)
                .minTicks(feature.appliedMinTicks())
                .ready(() -> probe.active() && feature.quickSkinFeatureReady())
                .settleTicks(feature.appliedSettleTicks())
                .timeoutTicks(feature.appliedTimeoutTicks())
                .screenshot(version + "_compat_02_local_skin_" + role + ".png")
                .assertion(() -> {
                    CompatibilityProbe.Result after =
                            CompatibilityProbe.verifyConfiguredIntegration();
                    if (!after.active()) return Step.Result.fail(after.detail());
                    return feature.assertQuickSkinFeature();
                }));

        return steps;
    }
}
