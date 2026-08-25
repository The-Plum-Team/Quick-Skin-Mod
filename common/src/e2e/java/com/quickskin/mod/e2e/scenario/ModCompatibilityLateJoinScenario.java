package com.quickskin.mod.e2e.scenario;

import com.quickskin.mod.e2e.CompatibilityProbe;
import com.quickskin.mod.e2e.E2ELog;
import com.quickskin.mod.e2e.Scenario;
import com.quickskin.mod.e2e.Step;
import com.quickskin.mod.e2e.generated.ScenarioContract.ScenarioId;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Proves that Bob recovers Alice's already-active CPM or Ears state when he joins later. */
public final class ModCompatibilityLateJoinScenario implements Scenario {
    private final ModCompatibilityRemoteEvidence evidence =
            new ModCompatibilityRemoteEvidence();

    @Override
    public ScenarioId id() {
        return ScenarioId.MOD_COMPATIBILITY_LATE_JOIN;
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
        boolean appliedState = ModCompatibilityRemoteEvidence
                .lateJoinUsesAppliedState(modId);
        List<Step> steps = new ArrayList<>();
        steps.add(evidence.integrationStep(modId));
        steps.add(Step.of("prepare_late_join_state")
                .action(() -> prepareLateJoinState(feature, appliedState))
                .minTicks(appliedState
                        ? feature.appliedMinTicks() : feature.baselineMinTicks())
                .ready(() -> evidence.integrationActive()
                        && lateJoinStateReady(feature, appliedState))
                .settleTicks(appliedState
                        ? feature.appliedSettleTicks() : feature.baselineSettleTicks())
                .timeoutTicks(appliedState
                        ? feature.appliedTimeoutTicks() : feature.baselineTimeoutTicks())
                .assertion(() -> assertLateJoinState(feature, appliedState)));
        return steps;
    }

    private void prepareLateJoinState(
            ModCompatibilityFeature feature, boolean appliedState) {
        ModCompatibilityRemoteEvidence.disableAutomaticOwnSkin();
        if (appliedState) {
            feature.applyQuickSkinFeature();
        } else {
            feature.prepareBaseline();
        }
        E2ELog.info("Alice selected the optional-mod state before Bob was launched");
    }

    private boolean lateJoinStateReady(
            ModCompatibilityFeature feature, boolean appliedState) {
        return appliedState
                ? feature.quickSkinFeatureReady()
                : feature.baselineReady();
    }

    private Step.Result assertLateJoinState(
            ModCompatibilityFeature feature, boolean appliedState) {
        if (!evidence.integrationActive()) {
            return Step.Result.fail(evidence.integrationDetail());
        }
        CompatibilityProbe.Result after = CompatibilityProbe.verifyConfiguredIntegration();
        if (!after.active()) return Step.Result.fail(after.detail());
        Step.Result state = appliedState
                ? feature.assertQuickSkinFeature()
                : feature.assertBaseline();
        if (!state.pass()) return state;
        return Step.Result.pass("Alice completed her pre-existing late-join state: "
                + state.message());
    }

    private List<Step> buildObserver(Minecraft minecraft) {
        String version = System.getProperty("quickskin.e2e.version", "v1_20_1");
        String role = System.getProperty("quickskin.e2e.role", "client_b");
        String modId = ModCompatibilityRemoteEvidence.selectedMod();
        boolean appliedState = ModCompatibilityRemoteEvidence
                .lateJoinUsesAppliedState(modId);
        UUID observerId = minecraft.player.getUUID();
        List<Step> steps = new ArrayList<>();
        steps.add(evidence.integrationStep(modId));
        steps.add(evidence.confirmObserver(observerId));
        steps.add(Step.of("observe_late_join_state")
                .action(() -> evidence.stepTowardVantage(minecraft))
                .minTicks(5)
                .ready(() -> {
                    evidence.stepTowardVantage(minecraft);
                    return evidence.atVantage(minecraft)
                            && evidence.checkRemoteState(
                                    minecraft, modId, appliedState).pass();
                })
                .settleTicks(20)
                .timeoutTicks(20 * 90)
                .screenshot(version + "_compat_late_join_01_observed_" + role + ".png")
                .assertion(() -> assertLateJoinObservation(
                        minecraft, modId, appliedState)));
        return steps;
    }

    private Step.Result assertLateJoinObservation(
            Minecraft minecraft, String modId, boolean appliedState) {
        Step.Result state = evidence.checkRemoteState(minecraft, modId, appliedState);
        if (!state.pass()) return state;
        Step.Result rear = evidence.checkRearComposition(minecraft);
        if (!rear.pass()) return rear;
        return Step.Result.pass("Bob joined after Alice completed the state and recovered it "
                + "without another Alice-side change: " + state.message() + "; "
                + rear.message());
    }
}
