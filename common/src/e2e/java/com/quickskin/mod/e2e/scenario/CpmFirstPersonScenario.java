package com.quickskin.mod.e2e.scenario;

import com.quickskin.mod.e2e.CompatibilityProbe;
import com.quickskin.mod.e2e.E2ELog;
import com.quickskin.mod.e2e.Scenario;
import com.quickskin.mod.e2e.Step;
import com.quickskin.mod.e2e.generated.ScenarioContract.ScenarioId;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;

/** Proves that CPM keeps its custom first-person hand intact after ten seconds of play. */
public final class CpmFirstPersonScenario implements Scenario {
    static final int FIRST_PERSON_RECHECK_TICKS = 20 * 10;
    private static final int FIRST_PERSON_SETTLE_TICKS = 20;
    private static final int FIRST_PERSON_TIMEOUT_TICKS = 400;

    private volatile CompatibilityProbe.Result probe =
            new CompatibilityProbe.Result(false, "probe not executed");

    @Override
    public ScenarioId id() {
        return ScenarioId.MOD_COMPATIBILITY_CPM_FIRST_PERSON;
    }

    @Override
    public List<Step> build(Minecraft mc) {
        final String version = System.getProperty("quickskin.e2e.version", "v1_20_1");
        final String role = System.getProperty("quickskin.e2e.role", "client_a");
        final String modId = System.getProperty("quickskin.e2e.compatibility", "").trim();
        final ModCompatibilityFeature.CpmFeature feature =
                new ModCompatibilityFeature.CpmFeature(mc);
        final List<Step> steps = new ArrayList<>();

        steps.add(Step.of("integration_active")
                .action(() -> {
                    probe = CompatibilityProbe.verifyConfiguredIntegration();
                    E2ELog.info("CPM first-person compatibility probe "
                            + (probe.active() ? "PASS" : "FAIL") + ": " + probe.detail());
                })
                .minTicks(1)
                .ready(() -> true)
                .assertion(() -> {
                    if (!"cpm".equals(modId)) {
                        return Step.Result.fail("CPM first-person scenario selected for " + modId);
                    }
                    return probe.active()
                            ? Step.Result.pass(probe.detail())
                            : Step.Result.fail(probe.detail());
                }));

        steps.add(Step.of("prepare_model")
                .action(feature::prepareBaseline)
                .minTicks(feature.baselineMinTicks())
                .ready(() -> probe.active() && feature.baselineReady())
                .settleTicks(feature.baselineSettleTicks())
                .timeoutTicks(feature.baselineTimeoutTicks())
                .assertion(() -> probe.active()
                        ? feature.assertBaseline()
                        : Step.Result.fail(probe.detail())));

        steps.add(Step.of("first_person_hand_initial")
                .action(feature::enterFirstPerson)
                .minTicks(FIRST_PERSON_SETTLE_TICKS)
                .ready(() -> probe.active() && feature.firstPersonReady())
                .settleTicks(FIRST_PERSON_SETTLE_TICKS)
                .timeoutTicks(FIRST_PERSON_TIMEOUT_TICKS)
                .screenshot(version + "_compat_cpm_01_first_person_hand_" + role + ".png")
                .assertion(() -> feature.assertFirstPersonHand("initial")));

        steps.add(Step.of("first_person_hand_after_10_seconds")
                .action(feature::beginFirstPersonRecheck)
                .minTicks(FIRST_PERSON_RECHECK_TICKS)
                .ready(() -> probe.active() && feature.firstPersonReady())
                .settleTicks(FIRST_PERSON_SETTLE_TICKS)
                .timeoutTicks(FIRST_PERSON_TIMEOUT_TICKS)
                .screenshot(version + "_compat_cpm_02_first_person_hand_after_10_seconds_"
                        + role + ".png")
                .assertion(() -> feature.assertFirstPersonHand("10-second")));

        return steps;
    }
}
