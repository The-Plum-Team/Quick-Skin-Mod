from __future__ import annotations

import copy
import hashlib
import json
import sys
import tempfile
import unittest
from pathlib import Path
from typing import Any, Callable


ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "e2e"))

import scenario_contract  # noqa: E402


EXPECTED_STEPS = {
    ("phase0-smoke", "client_a"): ("baseline", "apply_local_skin"),
    ("propagation", "client_a"): ("baseline", "apply_local_look"),
    ("propagation", "client_b"): (
        "baseline",
        "confirm_self",
        "await_propagation",
        "observe_a",
    ),
    ("propagation-live", "client_a"): (
        "baseline",
        "await_observer_settled",
        "apply_live",
    ),
    ("propagation-live", "client_b"): (
        "baseline",
        "confirm_self",
        "observe_before",
        "await_live_change",
    ),
    ("full", "client_a"): (
        "baseline",
        "local_skin_apply",
        "skin_menu_screen",
        "external_skin_drop",
        "model_slim",
        "model_classic",
        "cape_menu_screen",
        "known_cape_apply",
        "cape_adjust_screen",
        "cape_preview_selected_a",
        "cape_preview_selected_b",
        "cape_adjust_opaque_off",
        "cape_adjust_opaque_on",
        "cape_adjust_zoom_out",
        "cape_adjust_zoom_in",
        "bundled_bmo_cape",
        "bundled_bmo_elytra",
        "bmo_adjust_screen",
        "adjusted_bmo_cape",
        "adjusted_bmo_elytra",
        "bmo_render_parity",
        "animated_cape_apply",
        "animated_cape_advance",
        "hd_cape_no_downscale",
        "elytra_hides_cape",
        "cape_editor_ignores_elytra",
        "settings_screen",
        "rename_dialog",
        "delete_dialog",
        "hud_preview_overlay",
        "title_screen_splash_order",
    ),
}

EXPECTED_CAPTURES = {
    ("phase0-smoke", "client_a"): ("baseline", "apply_local_skin"),
    ("propagation", "client_a"): ("baseline", "apply_local_look"),
    ("propagation", "client_b"): ("baseline", "observe_a"),
    ("propagation-live", "client_a"): ("baseline", "apply_live"),
    ("propagation-live", "client_b"): (
        "baseline",
        "observe_before",
        "await_live_change",
    ),
    ("full", "client_a"): tuple(
        step
        for step in EXPECTED_STEPS[("full", "client_a")]
        if step != "bmo_render_parity"
    ),
}


class ScenarioContractTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.contract_path = ROOT / "e2e" / "scenario-contract.json"
        self.payload: dict[str, Any] = json.loads(
            self.contract_path.read_text(encoding="utf-8")
        )
        self.contract = scenario_contract.load_contract(self.contract_path)

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def write_contract(
        self, payload: object, *, compact: bool = False
    ) -> Path:
        path = self.root / "scenario-contract.json"
        if compact:
            text = json.dumps(payload, separators=(",", ":")) + "\n"
        else:
            text = json.dumps(payload, indent=2) + "\n"
        path.write_text(text, encoding="utf-8")
        return path

    def assert_mutation_rejected(
        self, mutate: Callable[[dict[str, Any]], None]
    ) -> None:
        payload = copy.deepcopy(self.payload)
        mutate(payload)
        with self.assertRaises(scenario_contract.ScenarioContractError):
            scenario_contract.load_contract(self.write_contract(payload))

    def step(
        self,
        payload: dict[str, Any],
        scenario_index: int,
        role_index: int,
        step_id: str,
    ) -> dict[str, Any]:
        steps = payload["scenarios"][scenario_index]["roles"][role_index]["steps"]
        return next(item for item in steps if item["id"] == step_id)

    def test_steps_captures_and_orchestration_are_exact(self) -> None:
        self.assertEqual(
            ("phase0-smoke", "propagation", "propagation-live", "full"),
            self.contract.scenario_ids,
        )
        self.assertEqual(
            ("phase0-smoke",),
            self.contract.scenarios_for_profile("runtime-default"),
        )
        self.assertEqual(
            self.contract.scenario_ids,
            self.contract.scenarios_for_profile("pr"),
        )
        self.assertEqual(
            self.contract.scenario_ids,
            scenario_contract.scenarios_for_profile(
                "release", self.contract
            ),
        )
        self.assertEqual(
            {
                "phase0-smoke": ("client_a",),
                "propagation": ("client_a", "client_b"),
                "propagation-live": ("client_a", "client_b"),
                "full": ("client_a",),
            },
            {
                scenario: self.contract.expected_roles(scenario)
                for scenario in self.contract.scenario_ids
            },
        )
        for key, expected in EXPECTED_STEPS.items():
            with self.subTest(key=key):
                role = self.contract.role(*key)
                self.assertEqual(expected, role.step_ids)
                self.assertEqual(expected, self.contract.expected_steps(*key))
                self.assertEqual(
                    expected,
                    scenario_contract.expected_steps(*key, self.contract),
                )
                self.assertTrue(all(step.assertion_required for step in role.steps))
                self.assertEqual(
                    EXPECTED_CAPTURES[key],
                    self.contract.expected_capture_steps(*key),
                )

        smoke = self.contract.orchestration_for("phase0-smoke")
        sequential = self.contract.orchestration_for("propagation")
        live = scenario_contract.orchestration_for(
            "propagation-live", self.contract
        )
        self.assertEqual("single-client", smoke.mode)
        self.assertFalse(smoke.two_clients)
        self.assertEqual((), smoke.role_order)
        self.assertIsNone(smoke.start_after)
        self.assertEqual("sequential-two-client", sequential.mode)
        self.assertTrue(sequential.two_clients)
        self.assertEqual(("client_a", "client_b"), sequential.role_order)
        self.assertEqual("concurrent-two-client", live.mode)
        self.assertTrue(live.two_clients)
        self.assertIsNotNone(live.start_after)
        assert live.start_after is not None
        self.assertEqual("client_a", live.start_after.role)
        self.assertEqual("Alice joined the game", live.start_after.server_log_marker)
        self.assertEqual(300, live.start_after.timeout_seconds)
        self.assertEqual("Alice joined the game", live.server_log_marker)
        self.assertEqual(300, live.server_log_timeout_seconds)

    def test_steps_are_the_only_authored_source_of_capture_truth(self) -> None:
        self.assertNotIn("captures", self.payload)
        for scenario in self.payload["scenarios"]:
            self.assertIn("execution_profiles", scenario)
            for role in scenario["roles"]:
                self.assertNotIn("probes", role)
                self.assertGreater(len(role["steps"]), 0)
                for step in role["steps"]:
                    with self.subTest(
                        scenario=scenario["scenario"],
                        role=role["role"],
                        step=step["id"],
                    ):
                        self.assertEqual(
                            {"id", "assertion_required"}
                            | ({"capture"} if "capture" in step else set()),
                            set(step),
                        )
                        self.assertIs(step["assertion_required"], True)
                        if "capture" in step:
                            self.assertEqual(
                                {"title", "review_tier", "expectation", "probes"},
                                set(step["capture"]),
                            )
                            self.assertNotIn("capture_id", step["capture"])
                            for probe in step["capture"]["probes"]:
                                self.assertNotIn("step", probe)

        authored_capture_count = sum(
            "capture" in step
            for scenario in self.payload["scenarios"]
            for role in scenario["roles"]
            for step in role["steps"]
        )
        self.assertEqual(41, authored_capture_count)
        self.assertEqual(authored_capture_count, len(self.contract.captures))

    def test_capture_metadata_and_ids_are_derived_from_steps(self) -> None:
        expected_ids = {
            scenario_contract.capture_id(scenario, role, step)
            for (scenario, role), steps in EXPECTED_CAPTURES.items()
            for step in steps
        }
        self.assertEqual(expected_ids, set(self.contract.capture_ids))
        self.assertEqual(41, len(expected_ids))
        first = self.contract.capture_by_id("full.client_a.baseline")
        self.assertIs(
            first,
            self.contract.capture("full", "client_a", "baseline"),
        )
        self.assertEqual(
            "full.client_a.baseline",
            scenario_contract.capture_id("full", "client_a", "baseline"),
        )
        skin_menu = self.contract.capture_by_id(
            "full.client_a.skin_menu_screen"
        )
        self.assertEqual("Skin menu", skin_menu.title)
        self.assertEqual("key", skin_menu.review_tier)
        self.assertIn("dark, subtly starred background", skin_menu.expectation)

        for capture_id in (
            "phase0-smoke.client_a.baseline",
            "propagation.client_a.baseline",
            "propagation.client_b.baseline",
            "propagation-live.client_a.baseline",
            "propagation-live.client_b.baseline",
            "propagation-live.client_b.observe_before",
            "full.client_a.baseline",
        ):
            with self.subTest(capture_id=capture_id):
                expectation = self.contract.capture_by_id(capture_id).expectation
                self.assertIn("any valid default skin variant is acceptable", expectation)

    def test_comparisons_preserve_thresholds_regions_and_order(self) -> None:
        def values(scenario: str, role: str) -> list[tuple[object, ...]]:
            return [
                (
                    item.first_step,
                    item.second_step,
                    item.minimum_changed_fraction,
                    item.region,
                )
                for item in self.contract.comparisons_for(scenario, role)
            ]

        self.assertEqual(
            [("baseline", "apply_local_skin", 0.00001, None)],
            values("phase0-smoke", "client_a"),
        )
        self.assertEqual(
            [("baseline", "apply_local_look", 0.00001, None)],
            values("propagation", "client_a"),
        )
        self.assertEqual(
            [("baseline", "observe_a", 0.00001, None)],
            values("propagation", "client_b"),
        )
        self.assertEqual(
            [("baseline", "apply_live", 0.00001, None)],
            values("propagation-live", "client_a"),
        )
        self.assertEqual(
            [
                (
                    "observe_before",
                    "await_live_change",
                    0.03,
                    (0.30, 0.28, 0.60, 0.85),
                )
            ],
            values("propagation-live", "client_b"),
        )
        self.assertEqual(
            [
                ("baseline", "local_skin_apply", 0.00001, None),
                ("model_slim", "model_classic", 0.00001, None),
                (
                    "animated_cape_apply",
                    "animated_cape_advance",
                    0.01,
                    (0.44, 0.45, 0.56, 0.78),
                ),
                ("known_cape_apply", "hd_cape_no_downscale", 0.00001, None),
                (
                    "cape_preview_selected_a",
                    "cape_preview_selected_b",
                    0.00001,
                    None,
                ),
                (
                    "cape_adjust_opaque_off",
                    "cape_adjust_opaque_on",
                    0.00001,
                    None,
                ),
                (
                    "cape_adjust_zoom_out",
                    "cape_adjust_zoom_in",
                    0.00001,
                    None,
                ),
            ],
            values("full", "client_a"),
        )

    def test_visual_probes_remain_bound_to_their_capture_step(self) -> None:
        self.assertEqual((1600, 900), self.contract.gui_text_reference_size)
        probes = self.contract.probes_for("full", "client_a")
        self.assertEqual(8, len(probes))
        self.assertEqual(
            probes,
            self.contract.capture(
                "full", "client_a", "skin_menu_screen"
            ).probes
            + self.contract.capture(
                "full", "client_a", "cape_menu_screen"
            ).probes
            + self.contract.capture(
                "full", "client_a", "cape_adjust_screen"
            ).probes
            + self.contract.capture(
                "full", "client_a", "settings_screen"
            ).probes,
        )
        opaque = probes[0]
        self.assertIsInstance(opaque, scenario_contract.OpaqueStarsProbe)
        assert isinstance(opaque, scenario_contract.OpaqueStarsProbe)
        self.assertEqual("skin_menu_screen", opaque.step)
        self.assertEqual((0.03, 0.20, 0.20, 0.80), opaque.region)
        self.assertEqual((32.0, 64, 0.10), (
            opaque.maximum_mean_luma,
            opaque.bright_luma,
            opaque.maximum_bright_fraction,
        ))

        text_values = [
            (
                probe.step,
                probe.label,
                probe.box,
                probe.minimum_luma_exclusive,
                probe.minimum_pixels,
            )
            for probe in probes
            if isinstance(probe, scenario_contract.RequiredGuiTextProbe)
        ]
        self.assertEqual(
            [
                (
                    "skin_menu_screen",
                    "skin catalog labels",
                    (480, 195, 850, 300),
                    175,
                    500,
                ),
                (
                    "skin_menu_screen",
                    "skin drop-zone instructions",
                    (560, 440, 745, 523),
                    175,
                    300,
                ),
                (
                    "cape_menu_screen",
                    "cape menu title",
                    (590, 100, 735, 140),
                    159,
                    174,
                ),
                (
                    "cape_menu_screen",
                    "cape drop-zone instructions",
                    (590, 200, 885, 260),
                    159,
                    531,
                ),
                (
                    "cape_adjust_screen",
                    "cape editor title",
                    (675, 20, 925, 50),
                    75,
                    400,
                ),
                (
                    "cape_adjust_screen",
                    "cape editor instructions",
                    (335, 624, 725, 655),
                    75,
                    750,
                ),
                (
                    "settings_screen",
                    "Open Skin Menu setting label",
                    (445, 235, 655, 265),
                    175,
                    300,
                ),
            ],
            text_values,
        )
        self.assertEqual(
            probes[:3],
            self.contract.probes_for(
                "full", "client_a", "skin_menu_screen"
            ),
        )

    def test_sha_is_the_exact_validated_file_bytes(self) -> None:
        expected = hashlib.sha256(self.contract_path.read_bytes()).hexdigest()
        self.assertEqual(expected, self.contract.sha256)
        self.assertEqual(expected, scenario_contract.contract_sha256())
        compact_path = self.write_contract(self.payload, compact=True)
        compact = scenario_contract.load_contract(compact_path)
        self.assertEqual(
            hashlib.sha256(compact_path.read_bytes()).hexdigest(),
            compact.sha256,
        )
        self.assertNotEqual(self.contract.sha256, compact.sha256)

        reordered = dict(reversed(tuple(self.payload.items())))
        self.assertEqual(
            scenario_contract.canonical_sha256(self.payload),
            scenario_contract.canonical_sha256(reordered),
        )

    def test_structural_and_semantic_mutations_fail_closed(self) -> None:
        def unknown_root(value: dict[str, Any]) -> None:
            value["extra"] = True

        def duplicate_scenario(value: dict[str, Any]) -> None:
            value["scenarios"].append(copy.deepcopy(value["scenarios"][0]))

        def unsupported_execution_profile(value: dict[str, Any]) -> None:
            value["scenarios"][0]["execution_profiles"].append("nightly")

        def multiple_runtime_defaults(value: dict[str, Any]) -> None:
            value["scenarios"][1]["execution_profiles"].append(
                "runtime-default"
            )

        def duplicate_role(value: dict[str, Any]) -> None:
            value["scenarios"][1]["roles"].append(
                copy.deepcopy(value["scenarios"][1]["roles"][0])
            )

        def duplicate_step(value: dict[str, Any]) -> None:
            steps = value["scenarios"][0]["roles"][0]["steps"]
            steps.append(copy.deepcopy(steps[0]))

        def legacy_string_step(value: dict[str, Any]) -> None:
            value["scenarios"][0]["roles"][0]["steps"][0] = "baseline"

        def missing_assertion(value: dict[str, Any]) -> None:
            del value["scenarios"][0]["roles"][0]["steps"][0][
                "assertion_required"
            ]

        def false_assertion(value: dict[str, Any]) -> None:
            value["scenarios"][0]["roles"][0]["steps"][0][
                "assertion_required"
            ] = False

        def duplicate_launch_role(value: dict[str, Any]) -> None:
            value["scenarios"][1]["orchestration"]["role_order"] = [
                "client_a",
                "client_a",
            ]

        def unknown_start_role(value: dict[str, Any]) -> None:
            value["scenarios"][2]["orchestration"]["start_after"][
                "role"
            ] = "client_c"

        def incomplete_start_after(value: dict[str, Any]) -> None:
            del value["scenarios"][2]["orchestration"]["start_after"][
                "server_log_marker"
            ]

        def explicit_capture_id(value: dict[str, Any]) -> None:
            self.step(value, 0, 0, "baseline")["capture"][
                "capture_id"
            ] = "phase0-smoke.client_a.baseline"

        def invalid_review_tier(value: dict[str, Any]) -> None:
            self.step(value, 0, 0, "baseline")["capture"][
                "review_tier"
            ] = "required"

        def captureless_role(value: dict[str, Any]) -> None:
            for item in value["scenarios"][0]["roles"][0]["steps"]:
                item.pop("capture", None)

        def role_level_probes(value: dict[str, Any]) -> None:
            value["scenarios"][3]["roles"][0]["probes"] = []

        def probe_on_non_capture_step(value: dict[str, Any]) -> None:
            self.step(value, 1, 1, "confirm_self")["probes"] = []

        def unknown_comparison_step(value: dict[str, Any]) -> None:
            value["scenarios"][0]["roles"][0]["comparisons"][0][
                "second_step"
            ] = "not_a_step"

        def comparison_uses_non_capture(value: dict[str, Any]) -> None:
            value["scenarios"][1]["roles"][1]["comparisons"][0][
                "second_step"
            ] = "confirm_self"

        def zero_comparison_threshold(value: dict[str, Any]) -> None:
            value["scenarios"][0]["roles"][0]["comparisons"][0][
                "minimum_changed_fraction"
            ] = 0

        def inverted_comparison_region(value: dict[str, Any]) -> None:
            value["scenarios"][2]["roles"][1]["comparisons"][0][
                "region"
            ] = [0.8, 0.2, 0.1, 0.9]

        def duplicate_comparison(value: dict[str, Any]) -> None:
            comparisons = value["scenarios"][0]["roles"][0]["comparisons"]
            comparisons.append(copy.deepcopy(comparisons[0]))

        def unknown_probe_kind(value: dict[str, Any]) -> None:
            self.step(value, 3, 0, "skin_menu_screen")["capture"][
                "probes"
            ][0]["kind"] = "golden"

        def explicit_probe_step(value: dict[str, Any]) -> None:
            self.step(value, 3, 0, "skin_menu_screen")["capture"][
                "probes"
            ][0]["step"] = "skin_menu_screen"

        def probe_box_escapes_reference(value: dict[str, Any]) -> None:
            self.step(value, 3, 0, "skin_menu_screen")["capture"][
                "probes"
            ][1]["box"][2] = 1601

        def duplicate_probe(value: dict[str, Any]) -> None:
            probes = self.step(
                value, 3, 0, "skin_menu_screen"
            )["capture"]["probes"]
            probes.append(copy.deepcopy(probes[0]))

        cases = {
            "unknown root field": unknown_root,
            "boolean schema": lambda value: value.__setitem__(
                "schema_version", True
            ),
            "unsupported schema": lambda value: value.__setitem__(
                "schema_version", 2
            ),
            "bad reference size": lambda value: value.__setitem__(
                "gui_text_reference_size", [1600]
            ),
            "duplicate scenario": duplicate_scenario,
            "unsupported execution profile": unsupported_execution_profile,
            "multiple runtime defaults": multiple_runtime_defaults,
            "duplicate role": duplicate_role,
            "duplicate step": duplicate_step,
            "legacy string step": legacy_string_step,
            "missing assertion": missing_assertion,
            "non-boolean assertion": lambda value: self.step(
                value, 0, 0, "baseline"
            ).__setitem__("assertion_required", 1),
            "false assertion": false_assertion,
            "wrong single orchestration": lambda value: value["scenarios"][0][
                "orchestration"
            ].__setitem__("mode", "sequential-two-client"),
            "duplicate launch role": duplicate_launch_role,
            "unknown start role": unknown_start_role,
            "incomplete start-after": incomplete_start_after,
            "explicit capture id": explicit_capture_id,
            "invalid review tier": invalid_review_tier,
            "captureless role": captureless_role,
            "role-level probes": role_level_probes,
            "probe on non-capture step": probe_on_non_capture_step,
            "unknown comparison step": unknown_comparison_step,
            "comparison non-capture": comparison_uses_non_capture,
            "zero comparison threshold": zero_comparison_threshold,
            "inverted comparison region": inverted_comparison_region,
            "duplicate comparison": duplicate_comparison,
            "unknown probe kind": unknown_probe_kind,
            "explicit probe step": explicit_probe_step,
            "probe box escapes": probe_box_escapes_reference,
            "duplicate probe": duplicate_probe,
        }
        for label, mutate in cases.items():
            with self.subTest(label=label):
                self.assert_mutation_rejected(mutate)

    def test_malformed_json_encoding_and_symlinks_are_rejected(self) -> None:
        duplicate = self.contract_path.read_text(encoding="utf-8").replace(
            '"schema_version": 1,',
            '"schema_version": 1,\n  "schema_version": 1,',
            1,
        )
        duplicate_path = self.root / "duplicate.json"
        duplicate_path.write_text(duplicate, encoding="utf-8")
        with self.assertRaisesRegex(
            scenario_contract.ScenarioContractError,
            "duplicate JSON object key",
        ):
            scenario_contract.load_contract(duplicate_path)

        nonfinite = self.contract_path.read_text(encoding="utf-8").replace(
            '"schema_version": 1',
            '"schema_version": NaN',
            1,
        )
        nonfinite_path = self.root / "nonfinite.json"
        nonfinite_path.write_text(nonfinite, encoding="utf-8")
        with self.assertRaisesRegex(
            scenario_contract.ScenarioContractError,
            "non-finite JSON number",
        ):
            scenario_contract.load_contract(nonfinite_path)

        invalid_utf8 = self.root / "invalid-utf8.json"
        invalid_utf8.write_bytes(b'{"schema_version": "\xff"}')
        with self.assertRaises(scenario_contract.ScenarioContractError):
            scenario_contract.load_contract(invalid_utf8)

        symlink = self.root / "contract-link.json"
        try:
            symlink.symlink_to(self.contract_path)
        except OSError:
            self.skipTest("symlinks are unavailable on this platform")
        with self.assertRaisesRegex(
            scenario_contract.ScenarioContractError,
            "must not be a symlink",
        ):
            scenario_contract.load_contract(symlink)

    def test_helpers_fail_closed_for_unknown_identity(self) -> None:
        for action in (
            lambda: self.contract.scenario("missing"),
            lambda: self.contract.scenarios_for_profile("missing"),
            lambda: self.contract.expected_steps("full", "client_b"),
            lambda: self.contract.probes_for("full", "client_a", "missing"),
            lambda: self.contract.capture("full", "client_a", "missing"),
            lambda: self.contract.capture_by_id("full.client_a.missing"),
            lambda: scenario_contract.capture_id(
                "full.bad", "client_a", "baseline"
            ),
        ):
            with self.subTest(action=action), self.assertRaises(
                scenario_contract.ScenarioContractError
            ):
                action()


if __name__ == "__main__":
    unittest.main()
