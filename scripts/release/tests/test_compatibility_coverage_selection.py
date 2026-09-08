from __future__ import annotations

import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "e2e"))
sys.path.insert(0, str(ROOT / "scripts" / "architecture"))

from module_graph import load_graph  # noqa: E402
from scenario_contract import COMPATIBILITY_EXECUTION_PROFILES, default_contract  # noqa: E402
from selection import compatibility_affected, compatibility_targets, select  # noqa: E402

HUD = "modules/hud-preview/src/main/java/com/quickskin/mod/client/gui/overlay/HudPreviewIntegration.java"
TEXTURES = "modules/client-textures/src/main/java/com/quickskin/mod/client/services/AnimatedTextureManager.java"


class CompatibilityCoverageSelectionTest(unittest.TestCase):
    def setUp(self) -> None:
        self.contract = default_contract()
        self.graph = load_graph()

    def test_every_compatibility_capture_and_reference_capture_is_a_target(self) -> None:
        targets = compatibility_targets(self.contract)
        scenarios = {scenario for profile in COMPATIBILITY_EXECUTION_PROFILES
                     for scenario in self.contract.scenarios_for_profile(profile)}
        self.assertTrue(scenarios)
        for scenario_id in scenarios:
            scenario = self.contract.scenario(scenario_id)
            for role in scenario.roles:
                for step in role.steps:
                    self.assertNotEqual((), step.modules)
                    self.assertEqual(step.capture is not None,
                                     (scenario_id, role.role, step.id) in targets)
        # Contract references plus the reference the reviewed lock substitutes for 3D Skin Layers.
        self.assertIn(("phase0-smoke", "client_a", "apply_local_skin"), targets)
        self.assertIn(("propagation-live", "client_b", "observe_before"), targets)
        self.assertIn(("full", "client_a", "skin_menu_screen"), targets)
        for scenario_id, role_id, step_id in targets:
            self.assertIn(step_id, self.contract.role(scenario_id, role_id).step_ids)

    def test_hud_change_stays_selective_and_carries_compatibility_forward(self) -> None:
        self.assertIs(False, compatibility_affected(self.contract, self.graph, [HUD]))
        result = select(self.contract, self.graph, [HUD])
        self.assertEqual(("affected", "affected-module-coverage"), (result.mode, result.reason))

    def test_texture_change_keeps_the_complete_profile_for_the_compatibility_wave(self) -> None:
        self.assertIs(True, compatibility_affected(self.contract, self.graph, [TEXTURES]))
        result = select(self.contract, self.graph, [TEXTURES])
        self.assertEqual(("full", "compatibility-coverage"), (result.mode, result.reason))
        self.assertEqual(sorted(self.contract.scenarios_for_profile("pr")),
                         sorted(run.scenario for run in result.runs))

    def test_unproven_ownership_is_neither_affected_nor_clear(self) -> None:
        for paths in ([], ["docs/ai/PROJECT.md"], ["common/src/main/java/com/quickskin/mod/QuickSkin.java"],
                      [HUD, "modules/hud-preview/src/main/resources/x.json"]):
            with self.subTest(paths=paths):
                self.assertIsNone(compatibility_affected(self.contract, self.graph, paths))


if __name__ == "__main__":
    unittest.main()
