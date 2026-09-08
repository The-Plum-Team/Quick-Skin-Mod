from __future__ import annotations

import contextlib
import copy
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "e2e"))
sys.path.insert(0, str(ROOT / "scripts" / "architecture"))

import selection as selection_module
from module_graph import Module, ModuleGraph, load_graph
from scenario_contract import ScenarioContractError, default_contract, load_contract
from selection import SelectionError, load_selection, project_contract, select
from dataclasses import replace
import packaged_runtime


class E2ESelectionTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.contract = default_contract()
        self.graph = load_graph(ROOT)
        self.payload = json.loads((ROOT / "e2e/scenario-contract.json").read_bytes())
        self.editor_path = "modules/cape-editor/src/main/java/com/quickskin/mod/client/gui/screen/CapeAdjustScreen.java"

    def tearDown(self):
        self.temporary.cleanup()

    def parse(self, payload):
        path = self.root / "contract.json"
        path.write_text(json.dumps(payload))
        return load_contract(path)

    @contextlib.contextmanager
    def without_optional_mod_admission(self):
        """Isolate the module/binding selection algorithm from the separate optional-mod
        admission gate (ADR 0007), which keeps every module whose reverse dependency closure
        reaches a compatibility capture or reference on the complete profile. The gate itself is
        asserted by test_optional_mod_coverage_keeps_the_complete_profile and by
        scripts/release/tests/test_compatibility_coverage_selection.py."""
        with patch.object(selection_module, "_compatibility_touched", return_value=False):
            yield

    def isolated(self, scenario, role, step):
        for s in self.payload["scenarios"]:
            for r in s["roles"]:
                for t in r["steps"]:
                    target = (s["scenario"], r["role"], t["id"]) == (scenario, role, step)
                    t["covers"] = {"modules": ["feature" if target else "common"], "bindings": []}
        graph = ModuleGraph((
            Module("feature", "modules/feature", "java-library", "common", (), (), (), ()),
            Module("common", "common", "minecraft-assembly", "mixed", (), ("feature",), (), ()),
        ), {}, "a" * 64)
        return select(self.parse(self.payload), graph, ["modules/feature/src/main/java/Feature.java"])

    def test_editor_selects_consumers_and_real_navigation_bindings(self):
        with self.without_optional_mod_admission():
            result = select(self.contract, self.graph, [self.editor_path])
        self.assertEqual("affected", result.mode)
        self.assertEqual(("cape-editor",), result.direct_modules)
        self.assertEqual({"cape-editor", "cape-menu", "skin-menu", "common"}, set(result.affected_modules))
        self.assertEqual({"open-skin-menu", "settings-parent-reconstruction", "vanilla-menu-navigation"}, set(result.affected_bindings))
        self.assertEqual(["full", "feature-navigation"], [run.scenario for run in result.runs])
        role = result.role("full", "client_a")
        self.assertIn("cape_adjust_screen", role.captures)
        self.assertIn("cape_menu_local_capes", role.captures)
        self.assertIn("skin_menu_screen", role.captures)
        self.assertNotIn("settings_screen", role.captures)
        self.assertNotIn("base_layer_transparency_first_person", role.captures)
        self.assertLess(len(role.captures), len(self.contract.expected_capture_steps("full", "client_a")))
        self.assertEqual(4, len(result.role("feature-navigation", "client_a").captures))

    def test_projected_contract_keeps_assertions_and_hash_but_only_selected_images(self):
        with self.without_optional_mod_admission():
            plan = select(self.contract, self.graph, [self.editor_path])
        projected = project_contract(self.contract, plan)
        self.assertEqual(self.contract.sha256, projected.sha256)
        self.assertEqual(45, len(projected.captures))
        self.assertEqual(67, len(projected.expected_steps("full", "client_a")))
        self.assertEqual(plan.role("full", "client_a").captures,
                         projected.expected_capture_steps("full", "client_a"))
        self.assertEqual(set(projected.capture_ids), set(projected.review_regions))
        for run in plan.runs:
            for role in run.roles:
                self.assertTrue(all(step.assertion_required for step in projected.role(run.scenario, role.role).steps))

    def test_menu_integration_keeps_its_preview_controls_without_running_cape_editor(self):
        with self.without_optional_mod_admission():
            plan = select(self.contract, self.graph, [
                "modules/menu-integration/src/main/java/com/quickskin/mod/client/gui/integration/MenuIntegration.java"
            ])
        self.assertEqual("affected", plan.mode)
        self.assertEqual({"menu-integration", "common"}, set(plan.affected_modules))
        title = plan.role("full", "client_a")
        self.assertEqual(("baseline", "local_skin_apply", "title_screen_splash_order"), title.steps)
        self.assertEqual(("title_screen_splash_order",), title.captures)
        session = plan.role("session", "client_a")
        self.assertEqual(self.contract.expected_steps("session", "client_a"), session.steps)
        self.assertEqual(("pause_menu_preview", "inventory_paper_doll", "quit_to_title"), session.captures)
        navigation = plan.role("feature-navigation", "client_a")
        self.assertEqual(("open_skin_menu_using_vanilla_button",), navigation.steps)
        self.assertEqual(navigation.steps, navigation.captures)
        self.assertEqual(5, len(project_contract(self.contract, plan).captures))

    def test_hud_preview_uses_its_own_control_without_selecting_menus_or_capes(self):
        plan = select(self.contract, self.graph, [
            "modules/hud-preview/src/main/java/com/quickskin/mod/client/gui/overlay/SkinPreviewOverlay.java"
        ])
        self.assertEqual("affected", plan.mode)
        self.assertEqual({"hud-preview", "common"}, set(plan.affected_modules))
        self.assertEqual(["full"], [run.scenario for run in plan.runs])
        role = plan.role("full", "client_a")
        self.assertEqual(("baseline", "local_skin_apply", "hud_preview_disabled", "hud_preview_overlay"), role.steps)
        self.assertEqual(("hud_preview_disabled", "hud_preview_overlay"), role.captures)
        comparisons = project_contract(self.contract, plan).role("full", "client_a").comparisons
        self.assertEqual(1, len(comparisons))
        self.assertEqual("hud_preview_disabled", comparisons[0].first_step)
        self.assertEqual("hud_preview_overlay", comparisons[0].second_step)

    def test_projection_rejects_missing_prerequisites_and_malformed_roles(self):
        plan = select(self.contract, self.graph, [self.editor_path])
        run = plan.runs[0]
        role = run.roles[0]
        variants = [replace(plan, contract_sha256="0" * 64), replace(plan, runs=()),
                    replace(plan, runs=(run, run)),
                    replace(plan, runs=(replace(run, roles=()),)),
                    replace(plan, runs=(replace(run, roles=(replace(role, steps=role.steps[1:]),)),)),
                    replace(plan, runs=(replace(run, roles=(replace(role, captures=role.captures[1:]),)),))]
        for candidate in variants:
            with self.subTest(candidate=candidate.runs[:1]), self.assertRaises(SelectionError):
                project_contract(self.contract, candidate)

    def test_unknown_build_resource_policy_and_assembly_paths_force_full(self):
        for path in ("unowned/A.java", "architecture/modules.json", "e2e/scenario-contract.json",
                     "modules/cape-editor/build.gradle.kts", "modules/cape-editor/src/main/resources/a.png",
                     "common/src/main/java/com/quickskin/mod/runtime/ClientFeatureBindings.java"):
            with self.subTest(path=path):
                result = select(self.contract, self.graph, [self.editor_path, path])
                self.assertEqual("full", result.mode)
                self.assertEqual(self.contract.scenarios_for_profile("pr"), tuple(run.scenario for run in result.runs))
                self.assertEqual(self.contract.expected_capture_steps("full", "client_a"), result.role("full", "client_a").captures)

    def test_missing_input_or_binding_coverage_cannot_become_a_small_success(self):
        self.assertEqual("full", select(self.contract, self.graph, []).mode)
        for scenario in self.payload["scenarios"]:
            for role in scenario["roles"]:
                for step in role["steps"]:
                    step["covers"]["bindings"] = []
        with self.without_optional_mod_admission():
            result = select(self.parse(self.payload), self.graph, [self.editor_path])
        self.assertEqual("incomplete-module-or-binding-coverage", result.reason)
        self.assertEqual("full", result.mode)

    def test_optional_mod_coverage_keeps_the_complete_profile(self):
        """A change whose closure reaches a compatibility capture or one of its clean references
        keeps the complete profile, so the optional-mod wave has the runtime it pairs against."""
        result = select(self.contract, self.graph, [self.editor_path])
        self.assertEqual("full", result.mode)
        self.assertEqual("compatibility-coverage", result.reason)
        self.assertEqual({"cape-editor", "cape-menu", "skin-menu", "common"}, set(result.affected_modules))
        self.assertEqual(self.contract.scenarios_for_profile("pr"),
                         tuple(run.scenario for run in result.runs))
        self.assertEqual(self.contract.expected_capture_steps("full", "client_a"),
                         result.role("full", "client_a").captures)

    def test_capture_comparison_partners_are_required_in_both_directions(self):
        for step in ("baseline", "apply_local_skin"):
            result = self.isolated("phase0-smoke", "client_a", step)
            role = result.role("phase0-smoke", "client_a")
            self.assertEqual(("baseline", "apply_local_skin"), role.steps)
            self.assertEqual(role.steps, role.captures)

    def test_multiplayer_keeps_both_client_state_machines_without_extra_owner_captures(self):
        result = self.isolated("propagation-live", "client_b", "observe_remote_elytra")
        self.assertEqual(["propagation-live"], [run.scenario for run in result.runs])
        for role in ("client_a", "client_b"):
            self.assertEqual(self.contract.expected_steps("propagation-live", role),
                             result.role("propagation-live", role).steps)
        self.assertEqual((), result.role("propagation-live", "client_a").captures)
        self.assertIn("observe_remote_elytra", result.role("propagation-live", "client_b").captures)
        self.assertIn("observe_hd_cape", result.role("propagation-live", "client_b").captures)

    def test_actions_that_read_previous_images_keep_those_images_even_without_a_target_capture(self):
        result = self.isolated("full", "client_a", "bmo_render_parity")
        role = result.role("full", "client_a")
        self.assertIn("bmo_render_parity", role.steps)
        self.assertNotIn("bmo_render_parity", role.captures)
        self.assertTrue({"bundled_bmo_cape", "bundled_bmo_elytra", "adjusted_bmo_cape", "adjusted_bmo_elytra"} <= set(role.captures))
        self.assertNotIn("local_skin_apply", role.captures)

    def test_independent_navigation_does_not_execute_other_steps(self):
        result = self.isolated("feature-navigation", "client_a", "cape_menu_settings_return")
        role = result.role("feature-navigation", "client_a")
        self.assertEqual(("cape_menu_settings_return",), role.steps)
        self.assertEqual(role.steps, role.captures)

    def test_compatibility_reference_is_an_explicit_separate_obligation(self):
        result = select(self.contract, self.graph,
                        ["modules/cpm-integration/src/main/java/CPMCompatIntegration.java"], "compatibility-cpm")
        self.assertTrue(result.reference_captures)
        for reference in result.reference_captures:
            self.contract.capture_by_id(reference)

    def test_paths_must_be_canonical_and_bounded(self):
        for paths in (["../A.java"], ["/A.java"], ["a//b"], ["a\\b"], [None], ["a"] * 10001):
            with self.subTest(paths=paths[:2]):
                with self.assertRaises(ValueError):
                    select(self.contract, self.graph, paths)

    def test_step_dependencies_and_coverage_fail_closed(self):
        cases = [
            ("requires", ["baseline"]),  # self dependency
            ("requires", ["apply_local_skin"]),  # forward dependency
            ("requires_captures", ["baseline"]),
            ("covers", {"modules": [], "bindings": []}),
            ("covers", {"modules": ["common", "common"], "bindings": []}),
        ]
        for key, value in cases:
            payload = copy.deepcopy(self.payload)
            payload["scenarios"][0]["roles"][0]["steps"][0][key] = value
            with self.subTest(key=key, value=value), self.assertRaises(ScenarioContractError):
                self.parse(payload)
        self.payload["scenarios"][1]["execution_scope"] = "steps"
        with self.assertRaises(ScenarioContractError):
            self.parse(self.payload)

    def test_unknown_module_or_binding_coverage_is_rejected(self):
        for key in ("modules", "bindings"):
            payload = copy.deepcopy(self.payload)
            payload["scenarios"][0]["roles"][0]["steps"][0]["covers"][key] = ["unknown"]
            with self.subTest(key=key), self.assertRaises(SelectionError):
                select(self.parse(payload), self.graph, [self.editor_path])

    def test_serialized_plan_is_recomputed_not_trusted(self):
        with self.without_optional_mod_admission():
            result = select(self.contract, self.graph, [self.editor_path])
            path = self.root / "selection.json"
            path.write_bytes(result.to_bytes())
            self.assertEqual(result, load_selection(path, self.contract, self.graph))
            mutations = [
                lambda data: data["runs"][0]["roles"][0]["captures"].pop(),
                lambda data: data["runs"][0]["roles"][0]["steps"].pop(0),
                lambda data: data.__setitem__("contract_sha256", "0" * 64),
                lambda data: data.__setitem__("module_graph_sha256", "0" * 64),
                lambda data: data.__setitem__("input_kind", "authenticated-git-diff"),
                lambda data: data.__setitem__("schema_version", True),
            ]
            for mutate in mutations:
                data = json.loads(result.to_bytes())
                mutate(data)
                path.write_text(json.dumps(data))
                with self.assertRaises(SelectionError):
                    load_selection(path, self.contract, self.graph)
        # The recorded plan is bound to the executing policy, so a selection produced under a
        # different optional-mod admission is refused rather than silently narrowed.
        path.write_bytes(result.to_bytes())
        with self.assertRaises(SelectionError):
            load_selection(path, self.contract, self.graph)

    def test_partial_report_requires_exact_selection_and_keeps_assertions_without_captures(self):
        with self.without_optional_mod_admission():
            result = select(self.contract, self.graph, [self.editor_path])
        selected = result.role("full", "client_a")
        report = {"version": "1.20.1", "role": "client_a", "scenario": "full",
                  "contract_sha256": self.contract.sha256, "selection_sha256": result.sha256,
                  "status": "pass", "steps": [
                      {"name": step, "status": "pass", "message": "checked",
                       "screenshot": f"{step}.png" if step in selected.captures else None}
                      for step in selected.steps]}
        (self.root / "e2e-report").mkdir()
        report_path = self.root / "e2e-report/report.json"
        report_path.write_text(json.dumps(report))
        row = {"runtime_version": "1.20.1"}
        with self.assertRaisesRegex(packaged_runtime.RuntimeFailure, "independently expected selection"):
            packaged_runtime.validate_report(self.root, row, "full", "client_a")
        with patch.object(packaged_runtime, "inspect_screenshot_for_step", return_value={}) as inspect, \
                patch.object(packaged_runtime, "compare_screenshots", return_value={}):
            validated = packaged_runtime.validate_report(self.root, row, "full", "client_a", result)
            self.assertEqual(set(selected.captures), set(validated["pixel_validation"]["screenshots"]))
            self.assertEqual(len(selected.captures), inspect.call_count)
            for mutation in ("assertion", "capture", "identity"):
                bad = copy.deepcopy(report)
                if mutation == "assertion": bad["steps"][0]["status"] = "fail"
                elif mutation == "identity": bad["selection_sha256"] = "0" * 64
                else: next(step for step in bad["steps"] if step["screenshot"] is None)["screenshot"] = "extra.png"
                report_path.write_text(json.dumps(bad))
                with self.subTest(mutation=mutation), self.assertRaises(packaged_runtime.RuntimeFailure):
                    packaged_runtime.validate_report(self.root, row, "full", "client_a", result)


if __name__ == "__main__":
    unittest.main()
