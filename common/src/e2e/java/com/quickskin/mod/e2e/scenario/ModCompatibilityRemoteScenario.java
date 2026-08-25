package com.quickskin.mod.e2e.scenario;

import com.quickskin.mod.e2e.CompatibilityProbe;
import com.quickskin.mod.e2e.E2ELog;
import com.quickskin.mod.e2e.Scenario;
import com.quickskin.mod.e2e.Step;
import com.quickskin.mod.e2e.generated.ScenarioContract.ScenarioId;
import com.quickskin.mod.networking.NetworkSyncService;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Proves that the selected optional integration changes what a second client renders for Alice.
 * Alice coordinates the two states without screenshots; Bob captures both from one fixed rear
 * camera after checking the real remote Ears or CPM state on his own client.
 */
public final class ModCompatibilityRemoteScenario implements Scenario {
    private final ModCompatibilityRemoteEvidence evidence =
            new ModCompatibilityRemoteEvidence();
    private volatile boolean remoteBaselinePrepared;

    @Override
    public ScenarioId id() {
        return ScenarioId.MOD_COMPATIBILITY_REMOTE;
    }

    @Override
    public List<Step> build(Minecraft minecraft) {
        String role = System.getProperty("quickskin.e2e.role", "client_a");
        return "client_b".equals(role)
                ? buildObserver(minecraft)
                : buildSubject(minecraft);
    }

    private List<Step> buildSubject(Minecraft minecraft) {
        String modId = ModCompatibilityRemoteEvidence.selectedMod();
        ModCompatibilityFeature feature = ModCompatibilityFeature.create(modId, minecraft);
        List<Step> steps = new ArrayList<>();
        steps.add(evidence.integrationStep(modId));

        steps.add(Step.of("prepare_remote_baseline")
                .action(() -> {
                    ModCompatibilityRemoteEvidence.disableAutomaticOwnSkin();
                    E2ELog.info(
                            "Alice is waiting for Bob before selecting the optional-mod baseline");
                })
                .minTicks(feature.baselineMinTicks())
                .ready(() -> prepareRemoteBaselineAfterObserver(minecraft, feature)
                        && evidence.integrationActive()
                        && feature.baselineReady())
                .settleTicks(feature.baselineSettleTicks())
                .timeoutTicks(feature.baselineTimeoutTicks() + 20 * 120)
                .assertion(() -> evidence.integrationActive()
                        ? feature.assertBaseline()
                        : Step.Result.fail(evidence.integrationDetail())));

        steps.add(Step.of("await_observer_baseline")
                .action(() -> E2ELog.info(
                        "Alice is holding the optional-mod baseline until Bob acknowledges it"))
                .minTicks(10)
                .ready(() -> evidence.observerAcknowledged(minecraft))
                .timeoutTicks(20 * 150)
                .assertion(() -> evidence.observerAcknowledged(minecraft)
                        ? Step.Result.pass("Bob acknowledged the remotely rendered baseline")
                        : Step.Result.fail("Bob never acknowledged the remote baseline")));

        steps.add(Step.of("apply_remote_change")
                .action(feature::applyQuickSkinFeature)
                .minTicks(feature.appliedMinTicks())
                .ready(() -> evidence.integrationActive() && feature.quickSkinFeatureReady())
                .settleTicks(feature.appliedSettleTicks())
                .timeoutTicks(feature.appliedTimeoutTicks())
                .assertion(() -> {
                    CompatibilityProbe.Result after =
                            CompatibilityProbe.verifyConfiguredIntegration();
                    if (!after.active()) return Step.Result.fail(after.detail());
                    return feature.assertQuickSkinFeature();
                }));
        return steps;
    }

    private boolean prepareRemoteBaselineAfterObserver(
            Minecraft minecraft, ModCompatibilityFeature feature) {
        if (remoteBaselinePrepared) return true;
        if (!evidence.observerConfirmed(minecraft)) return false;

        feature.prepareBaseline();
        remoteBaselinePrepared = true;
        E2ELog.info("Alice selected the optional-mod baseline after Bob confirmed readiness");
        return true;
    }

    private List<Step> buildObserver(Minecraft minecraft) {
        String version = System.getProperty("quickskin.e2e.version", "v1_20_1");
        String role = System.getProperty("quickskin.e2e.role", "client_b");
        String modId = ModCompatibilityRemoteEvidence.selectedMod();
        UUID observerId = minecraft.player.getUUID();
        List<Step> steps = new ArrayList<>();
        steps.add(evidence.integrationStep(modId));
        steps.add(evidence.confirmObserver(observerId));

        steps.add(Step.of("observe_remote_baseline")
                .action(() -> evidence.stepTowardVantage(minecraft))
                .minTicks(5)
                .ready(() -> {
                    evidence.stepTowardVantage(minecraft);
                    return evidence.atVantage(minecraft)
                            && evidence.checkRemoteState(minecraft, modId, false).pass();
                })
                .settleTicks(20)
                .timeoutTicks(20 * 90)
                .screenshot(version + "_compat_remote_01_baseline_" + role + ".png")
                .assertion(() -> acknowledgeRemoteBaseline(
                        minecraft, observerId, modId)));

        steps.add(Step.of("observe_remote_applied")
                .action(() -> evidence.stepTowardVantage(minecraft))
                .minTicks(5)
                .ready(() -> {
                    evidence.stepTowardVantage(minecraft);
                    return evidence.atVantage(minecraft)
                            && evidence.checkRemoteState(minecraft, modId, true).pass();
                })
                .settleTicks(20)
                .timeoutTicks(20 * 90)
                .screenshot(version + "_compat_remote_02_applied_" + role + ".png")
                .assertion(() -> {
                    if (!evidence.remoteBaselineObserved()) {
                        return Step.Result.fail(
                                "no asserted remote baseline was captured before the change");
                    }
                    Step.Result state = evidence.checkRemoteState(
                            minecraft, modId, true);
                    if (!state.pass()) return state;
                    Step.Result rear = evidence.checkRearComposition(minecraft);
                    if (!rear.pass()) return rear;
                    return Step.Result.pass("remote optional-mod transition witnessed: "
                            + state.message() + "; " + rear.message());
                }));
        return steps;
    }

    private Step.Result acknowledgeRemoteBaseline(
            Minecraft minecraft, UUID observerId, String modId) {
        Step.Result state = evidence.checkRemoteState(minecraft, modId, false);
        if (!state.pass()) return state;
        Step.Result rear = evidence.checkRearComposition(minecraft);
        if (!rear.pass()) return rear;
        try {
            NetworkSyncService.getInstance()
                    .syncAppearance(observerId, "", "", "slim");
            evidence.markRemoteBaselineObserved();
            E2ELog.info("Bob acknowledged the captured optional-mod baseline");
            return Step.Result.pass("remote optional-mod baseline captured: "
                    + state.message() + "; acknowledgement sent; " + rear.message());
        } catch (Throwable failure) {
            E2ELog.error("failed to acknowledge the remote compatibility baseline", failure);
            return Step.Result.fail("baseline captured but acknowledgement failed: " + failure);
        }
    }
}
