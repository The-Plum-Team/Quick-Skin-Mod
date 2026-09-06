"""Run the shipped harness selector with the generated contract, without Minecraft mocks."""
from __future__ import annotations

import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "e2e"))
from generate_contract_java import generate_java


class E2ESelectionJavaTest(unittest.TestCase):
    def test_executable_harness_enforces_selection_before_any_action(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            suffix = ".exe" if os.name == "nt" else ""
            jdk = os.environ.get("JAVA_HOME")
            javac = str(Path(jdk) / "bin" / ("javac" + suffix)) if jdk else "javac"
            java = str(Path(jdk) / "bin" / ("java" + suffix)) if jdk else "java"
            package = root / "com/quickskin/mod/e2e"
            package.mkdir(parents=True)
            generate_java(ROOT / "e2e/scenario-contract.json", package / "generated/ScenarioContract.java")
            for name in ("Step.java", "E2ESelection.java", "E2EContractValidator.java"):
                shutil.copy2(ROOT / "common/src/e2e/java/com/quickskin/mod/e2e" / name, package / name)
            # Only the game-bearing Scenario interface is replaced. The complete production
            # selector, full-contract validator and Step implementation execute unchanged.
            (package / "Scenario.java").write_text('''package com.quickskin.mod.e2e;
import com.quickskin.mod.e2e.generated.ScenarioContract.ScenarioId;
interface Scenario { ScenarioId id(); }
''')
            (package / "SelectionCanary.java").write_text('''package com.quickskin.mod.e2e;
import com.quickskin.mod.e2e.generated.ScenarioContract;
import com.quickskin.mod.e2e.generated.ScenarioContract.ScenarioId;
import java.util.ArrayList;
import java.util.List;

public final class SelectionCanary {
    static void input(String steps, String captures) {
        System.setProperty("quickskin.e2e.selection", "a".repeat(64));
        System.setProperty("quickskin.e2e.steps", steps);
        System.setProperty("quickskin.e2e.captures", captures);
    }
    static List<Step> authored(ScenarioId id, String role) {
        List<Step> result = new ArrayList<>();
        for (var spec : ScenarioContract.role(id, role).steps()) {
            Step step = Step.of(spec.id()).assertion(() -> Step.Result.pass("checked"));
            if (spec.captureRequired()) step.screenshot(spec.id() + ".png");
            result.add(step);
        }
        E2EContractValidator.validate(() -> id, role, result);
        return result;
    }
    static void reject(ScenarioId id, String role, String steps, String captures) {
        input(steps, captures);
        try { E2ESelection.apply(() -> id, role, authored(id, role)); }
        catch (IllegalStateException expected) { return; }
        throw new AssertionError("accepted invalid selection: " + steps + " / " + captures);
    }
    static void check(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] args) {
        var original = authored(ScenarioId.PHASE0_SMOKE, "client_a");
        check(E2ESelection.apply(() -> ScenarioId.PHASE0_SMOKE, "client_a", original) == original);
        reject(ScenarioId.PHASE0_SMOKE, "client_a", "apply_local_skin", "");
        reject(ScenarioId.PHASE0_SMOKE, "client_a", "baseline,apply_local_skin", "baseline");
        reject(ScenarioId.PHASE0_SMOKE, "client_a", "baseline,baseline", "");
        reject(ScenarioId.PHASE0_SMOKE, "client_a", "apply_local_skin,baseline", "");
        reject(ScenarioId.PHASE0_SMOKE, "client_a", "missing", "");
        reject(ScenarioId.PHASE0_SMOKE, "client_a", "", "");
        reject(ScenarioId.PROPAGATION_LIVE, "client_a", "baseline", "");
        String prefix = String.join(",", ScenarioContract.role(ScenarioId.FULL, "client_a").steps()
                .stream().map(ScenarioContract.StepSpec::id).takeWhile(id -> !id.equals("animated_cape_apply")).toList());
        reject(ScenarioId.FULL, "client_a", prefix, ""); // parity action reads earlier captures
        input("cape_menu_settings_return", "cape_menu_settings_return");
        var selected = E2ESelection.apply(() -> ScenarioId.FEATURE_NAVIGATION, "client_a",
                authored(ScenarioId.FEATURE_NAVIGATION, "client_a"));
        check(selected.size() == 1 && selected.get(0).screenshot != null);
        input("baseline,apply_local_skin", "");
        selected = E2ESelection.apply(() -> ScenarioId.PHASE0_SMOKE, "client_a",
                authored(ScenarioId.PHASE0_SMOKE, "client_a"));
        check(selected.size() == 2 && selected.stream().allMatch(step -> step.screenshot == null && step.assertion != null));
        System.setProperty("quickskin.e2e.selection", "invalid");
        try {
            E2ESelection.apply(() -> ScenarioId.PHASE0_SMOKE, "client_a", authored(ScenarioId.PHASE0_SMOKE, "client_a"));
            throw new AssertionError("invalid identity accepted");
        } catch (IllegalStateException expected) {}
        System.out.println("selection canaries passed");
    }
}
''')
            compiled = subprocess.run([javac, "--release", "17", "-d", str(root / "classes"),
                                       *map(str, root.rglob("*.java"))], capture_output=True, text=True, timeout=60)
            self.assertEqual(0, compiled.returncode, compiled.stdout + compiled.stderr)
            result = subprocess.run([java, "-cp", str(root / "classes"),
                                     "com.quickskin.mod.e2e.SelectionCanary"],
                                    capture_output=True, text=True, timeout=30)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertIn("selection canaries passed", result.stdout)


if __name__ == "__main__":
    unittest.main()
