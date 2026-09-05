package com.quickskin.mod.e2e;

import com.quickskin.mod.e2e.generated.ScenarioContract;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Applies a runner-recomputed selection only after validation of the complete authored scenario. */
final class E2ESelection {
    private E2ESelection() {}

    static List<Step> apply(Scenario scenario, String role, List<Step> authored) {
        String identity = System.getProperty("quickskin.e2e.selection");
        String stepInput = System.getProperty("quickskin.e2e.steps");
        String captureInput = System.getProperty("quickskin.e2e.captures");
        if (identity == null && stepInput == null && captureInput == null) return authored;
        if (identity == null || !identity.matches("[a-f0-9]{64}")
                || stepInput == null || captureInput == null)
            throw new IllegalStateException("incomplete E2E selection identity");

        ScenarioContract.RoleSpec spec = ScenarioContract.role(scenario.id(), role);
        List<String> steps = names(stepInput);
        List<String> captures = names(captureInput);
        if (steps.isEmpty() || !steps.containsAll(captures))
            throw new IllegalStateException("selected captures require their executable steps");
        List<String> orderedSteps = spec.steps().stream().map(ScenarioContract.StepSpec::id)
                .filter(steps::contains).toList();
        List<String> orderedCaptures = spec.steps().stream().filter(ScenarioContract.StepSpec::captureRequired)
                .map(ScenarioContract.StepSpec::id).filter(captures::contains).toList();
        if (!steps.equals(orderedSteps) || !captures.equals(orderedCaptures))
            throw new IllegalStateException("unknown, duplicate or out-of-order E2E selection");
        if (spec.atomic() && steps.size() != spec.steps().size())
            throw new IllegalStateException("coordinated scenarios require all executable steps");
        for (ScenarioContract.StepSpec step : spec.steps()) {
            if (steps.contains(step.id()) && (!steps.containsAll(step.requires())
                    || !captures.containsAll(step.requiresCaptures())))
                throw new IllegalStateException("E2E selection omitted prerequisites for " + step.id());
        }
        for (List<String> pair : spec.comparisons()) {
            if (pair.stream().anyMatch(captures::contains) && !captures.containsAll(pair))
                throw new IllegalStateException("E2E selection omitted a screenshot comparison partner");
        }
        List<Step> result = new ArrayList<>();
        for (Step step : authored) {
            if (!steps.contains(step.name)) continue;
            if (!captures.contains(step.name)) step.screenshot = null;
            result.add(step);
        }
        return List.copyOf(result);
    }

    private static List<String> names(String input) {
        if (input.length() > 32768) throw new IllegalStateException("E2E selection exceeds its limit");
        List<String> values = input.isEmpty() ? List.of() : Arrays.asList(input.split(",", -1));
        Set<String> seen = new HashSet<>();
        for (String value : values) {
            if (!value.matches("[a-z][a-z0-9_-]*") || !seen.add(value))
                throw new IllegalStateException("invalid E2E selection identifier");
        }
        return values;
    }
}
