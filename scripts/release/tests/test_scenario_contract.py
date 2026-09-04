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
        "await_frames_observed",
        "equip_elytra_live",
        "await_elytra_observed",
        "apply_hd_cape_live",
        "await_hd_observed",
        "remove_cape_live",
    ),
    ("propagation-live", "client_b"): (
        "baseline",
        "confirm_self",
        "observe_before",
        "await_live_change",
        "observe_animation_frame",
        "observe_remote_elytra",
        "observe_hd_cape",
        "observe_cape_removed",
    ),
    ("full", "client_a"): (
        "baseline",
        "local_skin_apply",
        "skin_menu_screen",
        "external_skin_drop",
        "catalog_rename",
        "catalog_sort",
        "catalog_own_skin",
        "catalog_delete_protected",
        "catalog_delete",
        "model_slim",
        "model_classic",
        "slim_skin_auto_detect",
        "legacy_skin_apply",
        "folder_asset_normalization",
        "base_layer_transparency",
        "base_layer_transparency_first_person",
        "transparency_disabled",
        "restore_classic_skin",
        "hd_skin_no_downscale",
        "transparent_skin_layers",
        "restore_reference_skin",
        "cape_menu_screen",
        "known_cape_apply",
        "cape_adjust_screen",
        "cape_preview_selected_a",
        "cape_preview_selected_b",
        "cape_adjust_opaque_off",
        "cape_adjust_opaque_on",
        "cape_fill_color_picker",
        "cape_adjust_zoom_out",
        "cape_adjust_zoom_in",
        "cape_editor_snap_mirror",
        "cape_import_standard",
        "translucent_cape_worn",
        "translucent_cape_elytra",
        "cape_import_cancel",
        "bundled_bmo_cape",
        "bundled_bmo_elytra",
        "bmo_padded_source_screen",
        "bmo_adjust_screen",
        "adjusted_bmo_cape",
        "adjusted_bmo_elytra",
        "remove_cape_with_elytra",
        "vanilla_elytra_after_cape_removal",
        "bmo_render_parity",
        "animated_cape_apply",
        "animated_cape_advance",
        "hd_cape_no_downscale",
        "elytra_hides_cape",
        "cape_editor_ignores_elytra",
        "cape_menu_local_capes",
        "cape_speed_slider",
        "cape_tile_tooltip",
        "cape_scroll",
        "cape_delete_local",
        "cape_none_selected",
        "no_cape_after_removal",
        "cape_menu_hidden_builtin",
        "settings_screen",
        "settings_keybind_capture",
        "settings_gui_edit_tab",
        "settings_modpack_tab",
        "settings_server_tab",
        "styled_buttons_vanilla_background",
        "rename_dialog",
        "delete_dialog",
        "stale_skin_fallback",
        "hud_preview_overlay",
        "title_screen_splash_order",
    ),
    ("server-policy", "client_a"): (
        "baseline",
        "transparent_skin_server_policy",
        "cooldown_skin_menu",
        "cape_change_during_cooldown",
    ),
    ("session", "client_a"): (
        "apply_look",
        "pause_menu_preview",
        "inventory_paper_doll",
        "tab_list_head",
        "quit_to_title",
    ),
    ("mod-compatibility", "client_a"): (
        "integration_active",
        "baseline_with_mod",
        "apply_local_skin_with_mod",
    ),
    ("mod-compatibility-remote", "client_a"): (
        "integration_active",
        "prepare_remote_baseline",
        "await_observer_baseline",
        "apply_remote_change",
    ),
    ("mod-compatibility-remote", "client_b"): (
        "integration_active",
        "confirm_self",
        "observe_remote_baseline",
        "observe_remote_applied",
    ),
    ("mod-compatibility-late-join", "client_a"): (
        "integration_active",
        "prepare_late_join_state",
    ),
    ("mod-compatibility-late-join", "client_b"): (
        "integration_active",
        "confirm_self",
        "observe_late_join_state",
    ),
    ("mod-compatibility-cpm-first-person", "client_a"): (
        "integration_active",
        "prepare_model",
        "first_person_hand_initial",
        "first_person_hand_after_10_seconds",
    ),
}

# Non-capture steps of the `full` role, listed explicitly so a step that silently gains or loses
# its capture cannot hide behind the derived tuple below.
FULL_NON_CAPTURE_STEPS = frozenset(
    {
        "folder_asset_normalization",
        "restore_classic_skin",
        "restore_reference_skin",
        "cape_import_cancel",
        "remove_cape_with_elytra",
        "bmo_render_parity",
        "cape_scroll",
    }
)

EXPECTED_CAPTURES = {
    ("phase0-smoke", "client_a"): ("baseline", "apply_local_skin"),
    ("propagation", "client_a"): ("baseline", "apply_local_look"),
    ("propagation", "client_b"): ("baseline", "observe_a"),
    ("propagation-live", "client_a"): ("baseline", "apply_live"),
    ("propagation-live", "client_b"): (
        "baseline",
        "observe_before",
        "await_live_change",
        "observe_animation_frame",
        "observe_remote_elytra",
        "observe_hd_cape",
        "observe_cape_removed",
    ),
    ("full", "client_a"): tuple(
        step
        for step in EXPECTED_STEPS[("full", "client_a")]
        if step not in FULL_NON_CAPTURE_STEPS
    ),
    ("server-policy", "client_a"): (
        "baseline",
        "transparent_skin_server_policy",
        "cooldown_skin_menu",
        "cape_change_during_cooldown",
    ),
    ("session", "client_a"): (
        "pause_menu_preview",
        "inventory_paper_doll",
        "tab_list_head",
        "quit_to_title",
    ),
    ("mod-compatibility", "client_a"): (
        "baseline_with_mod",
        "apply_local_skin_with_mod",
    ),
    ("mod-compatibility-remote", "client_a"): (),
    ("mod-compatibility-remote", "client_b"): (
        "observe_remote_baseline",
        "observe_remote_applied",
    ),
    ("mod-compatibility-late-join", "client_a"): (),
    ("mod-compatibility-late-join", "client_b"): (
        "observe_late_join_state",
    ),
    ("mod-compatibility-cpm-first-person", "client_a"): (
        "first_person_hand_initial",
        "first_person_hand_after_10_seconds",
    ),
}

EXPECTED_CAPTURE_COUNT = 92

# The scenario index the mutation cases below address. Mutations must target the intended
# scenario even after the contract grows, so these are named rather than inlined.
SMOKE, PROPAGATION, LIVE, FULL, SERVER_POLICY, SESSION, COMPATIBILITY = range(7)

SERVER_POLICY_SEED = {
    "disableSkinTransparency": True,
    "skinChangeCooldownSeconds": 600,
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
            (
                "phase0-smoke",
                "propagation",
                "propagation-live",
                "full",
                "server-policy",
                "session",
                "mod-compatibility",
                "mod-compatibility-remote",
                "mod-compatibility-late-join",
                "mod-compatibility-cpm-first-person",
            ),
            self.contract.scenario_ids,
        )
        self.assertEqual(
            ("phase0-smoke",),
            self.contract.scenarios_for_profile("runtime-default"),
        )
        self.assertEqual(
            (
                "phase0-smoke",
                "propagation",
                "propagation-live",
                "full",
                "server-policy",
                "session",
            ),
            self.contract.scenarios_for_profile("pr"),
        )
        self.assertEqual(
            (
                "phase0-smoke",
                "propagation",
                "propagation-live",
                "full",
                "server-policy",
                "session",
            ),
            scenario_contract.scenarios_for_profile(
                "release", self.contract
            ),
        )
        self.assertEqual(
            ("mod-compatibility",),
            self.contract.scenarios_for_profile("compatibility"),
        )
        self.assertEqual(
            (
                "mod-compatibility-remote",
                "mod-compatibility-late-join",
            ),
            self.contract.scenarios_for_profile("compatibility-remote"),
        )
        self.assertEqual(
            ("mod-compatibility-cpm-first-person",),
            self.contract.scenarios_for_profile("compatibility-cpm"),
        )
        self.assertEqual(
            {
                "phase0-smoke": ("client_a",),
                "propagation": ("client_a", "client_b"),
                "propagation-live": ("client_a", "client_b"),
                "full": ("client_a",),
                "server-policy": ("client_a",),
                "session": ("client_a",),
                "mod-compatibility": ("client_a",),
                "mod-compatibility-remote": ("client_a", "client_b"),
                "mod-compatibility-late-join": ("client_a", "client_b"),
                "mod-compatibility-cpm-first-person": ("client_a",),
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
        compatibility_remote = self.contract.orchestration_for(
            "mod-compatibility-remote"
        )
        self.assertEqual("concurrent-two-client", compatibility_remote.mode)
        self.assertIsNotNone(compatibility_remote.start_after)
        assert compatibility_remote.start_after is not None
        self.assertEqual("client_a", compatibility_remote.start_after.role)
        self.assertEqual(
            "Alice joined the game",
            compatibility_remote.start_after.server_log_marker,
        )
        compatibility_late_join = self.contract.orchestration_for(
            "mod-compatibility-late-join"
        )
        self.assertEqual("sequential-two-client", compatibility_late_join.mode)
        self.assertEqual(
            ("client_a", "client_b"), compatibility_late_join.role_order
        )
        self.assertIsNone(compatibility_late_join.start_after)
        cpm_first_person = self.contract.orchestration_for(
            "mod-compatibility-cpm-first-person"
        )
        self.assertEqual("single-client", cpm_first_person.mode)

        for scenario in ("phase0-smoke", "propagation", "propagation-live", "full", "session", "mod-compatibility"):
            with self.subTest(default_server_config=scenario):
                self.assertIsNone(
                    self.contract.orchestration_for(scenario).server_config
                )
        policy = self.contract.orchestration_for("server-policy")
        self.assertEqual("single-client", policy.mode)
        self.assertFalse(policy.two_clients)
        self.assertIsNotNone(policy.server_config)
        assert policy.server_config is not None
        self.assertIsInstance(policy.server_config, scenario_contract.ServerConfigSeed)
        self.assertIs(True, policy.server_config.disable_skin_transparency)
        self.assertEqual(600, policy.server_config.skin_change_cooldown_seconds)
        self.assertEqual(SERVER_POLICY_SEED, policy.server_config.to_json_object())
        self.assertEqual(
            SERVER_POLICY_SEED,
            self.payload["scenarios"][SERVER_POLICY]["orchestration"]["server_config"],
        )

    def test_server_config_seed_is_exact_and_fails_closed(self) -> None:
        def seed(value: dict[str, Any]) -> dict[str, Any]:
            return value["scenarios"][SERVER_POLICY]["orchestration"]["server_config"]

        def unknown_key(value: dict[str, Any]) -> None:
            seed(value)["maxSkinBytes"] = 1

        def missing_transparency_key(value: dict[str, Any]) -> None:
            del seed(value)["disableSkinTransparency"]

        def missing_cooldown_key(value: dict[str, Any]) -> None:
            del seed(value)["skinChangeCooldownSeconds"]

        def non_boolean_transparency(value: dict[str, Any]) -> None:
            seed(value)["disableSkinTransparency"] = 1

        def string_transparency(value: dict[str, Any]) -> None:
            seed(value)["disableSkinTransparency"] = "true"

        def boolean_cooldown(value: dict[str, Any]) -> None:
            seed(value)["skinChangeCooldownSeconds"] = True

        def fractional_cooldown(value: dict[str, Any]) -> None:
            seed(value)["skinChangeCooldownSeconds"] = 600.0

        def string_cooldown(value: dict[str, Any]) -> None:
            seed(value)["skinChangeCooldownSeconds"] = "600"

        def negative_cooldown(value: dict[str, Any]) -> None:
            seed(value)["skinChangeCooldownSeconds"] = -1

        def oversized_cooldown(value: dict[str, Any]) -> None:
            seed(value)["skinChangeCooldownSeconds"] = 86401

        def all_default_seed(value: dict[str, Any]) -> None:
            seed(value)["disableSkinTransparency"] = False
            seed(value)["skinChangeCooldownSeconds"] = 0

        def non_object_seed(value: dict[str, Any]) -> None:
            value["scenarios"][SERVER_POLICY]["orchestration"]["server_config"] = [
                True,
                600,
            ]

        def empty_seed(value: dict[str, Any]) -> None:
            value["scenarios"][SERVER_POLICY]["orchestration"]["server_config"] = {}

        cases = {
            "unknown key": unknown_key,
            "missing transparency key": missing_transparency_key,
            "missing cooldown key": missing_cooldown_key,
            "non-boolean transparency": non_boolean_transparency,
            "string transparency": string_transparency,
            "boolean cooldown": boolean_cooldown,
            "fractional cooldown": fractional_cooldown,
            "string cooldown": string_cooldown,
            "negative cooldown": negative_cooldown,
            "oversized cooldown": oversized_cooldown,
            "all-default seed": all_default_seed,
            "non-object seed": non_object_seed,
            "empty seed": empty_seed,
        }
        for label, mutate in cases.items():
            with self.subTest(label=label):
                self.assert_mutation_rejected(mutate)

        # Boundary values remain accepted, and the seed is allowed on every orchestration mode.
        for label, mutate in {
            "maximum cooldown": lambda value: seed(value).__setitem__(
                "skinChangeCooldownSeconds", 86400
            ),
            "transparency only": lambda value: seed(value).__setitem__(
                "skinChangeCooldownSeconds", 0
            ),
            "cooldown only": lambda value: seed(value).__setitem__(
                "disableSkinTransparency", False
            ),
            "sequential two-client": lambda value: value["scenarios"][PROPAGATION][
                "orchestration"
            ].__setitem__("server_config", dict(SERVER_POLICY_SEED)),
            "concurrent two-client": lambda value: value["scenarios"][LIVE][
                "orchestration"
            ].__setitem__("server_config", dict(SERVER_POLICY_SEED)),
        }.items():
            with self.subTest(label=label):
                payload = copy.deepcopy(self.payload)
                mutate(payload)
                accepted = scenario_contract.load_contract(self.write_contract(payload))
                for scenario in accepted.scenario_ids:
                    raw = next(
                        item
                        for item in payload["scenarios"]
                        if item["scenario"] == scenario
                    )["orchestration"]
                    parsed = accepted.orchestration_for(scenario).server_config
                    if "server_config" in raw:
                        assert parsed is not None
                        self.assertEqual(raw["server_config"], parsed.to_json_object())
                    else:
                        self.assertIsNone(parsed)

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
                            capture_fields = {
                                "title",
                                "review_tier",
                                "expectation",
                                "probes",
                            }
                            if scenario["scenario"].startswith("mod-compatibility"):
                                capture_fields.add("compatibility_reference_capture_id")
                            self.assertEqual(capture_fields, set(step["capture"]))
                            self.assertNotIn("capture_id", step["capture"])
                            for probe in step["capture"]["probes"]:
                                self.assertNotIn("step", probe)

        authored_capture_count = sum(
            "capture" in step
            for scenario in self.payload["scenarios"]
            for role in scenario["roles"]
            for step in role["steps"]
        )
        self.assertEqual(EXPECTED_CAPTURE_COUNT, authored_capture_count)
        self.assertEqual(authored_capture_count, len(self.contract.captures))

    def test_capture_metadata_and_ids_are_derived_from_steps(self) -> None:
        expected_ids = {
            scenario_contract.capture_id(scenario, role, step)
            for (scenario, role), steps in EXPECTED_CAPTURES.items()
            for step in steps
        }
        self.assertEqual(expected_ids, set(self.contract.capture_ids))
        self.assertEqual((1920, 1080), self.contract.screenshot_size)
        self.assertEqual(expected_ids, set(self.contract.review_regions))
        self.assertEqual(EXPECTED_CAPTURE_COUNT, len(expected_ids))
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
        self.assertEqual(
            ((0.34, 0.09, 0.66, 0.91),),
            self.contract.review_regions_for("full.client_a.skin_menu_screen"),
        )

        padded_bmo = self.contract.capture_by_id(
            "full.client_a.bmo_padded_source_screen"
        )
        self.assertIn("opaque-black padding on all four sides", padded_bmo.expectation)
        aligned_bmo = self.contract.capture_by_id(
            "full.client_a.bmo_adjust_screen"
        )
        self.assertIn("auxiliary side, top and bottom UV faces", aligned_bmo.expectation)
        self.assertIn("dark, subtly starred background", skin_menu.expectation)
        observer_baseline = self.contract.capture_by_id(
            "propagation.client_b.baseline"
        )
        self.assertIn("remote subject is deliberately held behind", observer_baseline.expectation)
        self.assertIn("must not be visible", observer_baseline.expectation)

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
                self.assertIn("UUID-selected vanilla default skin", expectation)

        for capture_id in (
            "phase0-smoke.client_a.baseline",
            "propagation.client_a.baseline",
            "propagation.client_b.baseline",
            "propagation-live.client_a.baseline",
            "propagation-live.client_b.baseline",
            "full.client_a.baseline",
        ):
            with self.subTest(full_body_capture_id=capture_id):
                expectation = self.contract.capture_by_id(capture_id).expectation
                self.assertIn("third-person", expectation)
                self.assertIn("complete", expectation)

        live_before = self.contract.capture_by_id(
            "propagation-live.client_b.observe_before"
        ).expectation
        self.assertIn("Noor", live_before)
        self.assertIn("red top", live_before)

        cpm_initial = self.contract.capture_by_id(
            "mod-compatibility-cpm-first-person.client_a.first_person_hand_initial"
        )
        cpm_delayed = self.contract.capture_by_id(
            "mod-compatibility-cpm-first-person.client_a."
            "first_person_hand_after_10_seconds"
        )
        self.assertIn("white glove", cpm_initial.expectation)
        self.assertIn("10 uninterrupted seconds", cpm_delayed.expectation)
        self.assertEqual(
            "phase0-smoke.client_a.apply_local_skin",
            cpm_initial.compatibility_reference_capture_id,
        )
        self.assertEqual(
            ((0.58, 0.3, 1.0, 1.0),),
            self.contract.review_regions_for(cpm_delayed.capture_id),
        )

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
        live_region = (0.30, 0.28, 0.60, 0.85)
        self.assertEqual(
            [
                ("observe_before", "await_live_change", 0.03, live_region),
                (
                    "await_live_change",
                    "observe_animation_frame",
                    0.002,
                    live_region,
                ),
                (
                    "observe_animation_frame",
                    "observe_remote_elytra",
                    0.005,
                    live_region,
                ),
                ("observe_remote_elytra", "observe_hd_cape", 0.005, live_region),
                ("observe_hd_cape", "observe_cape_removed", 0.005, live_region),
            ],
            values("propagation-live", "client_b"),
        )
        self.assertEqual(
            [
                (
                    "observe_remote_baseline",
                    "observe_remote_applied",
                    0.005,
                    (0.3, 0.05, 0.7, 0.95),
                )
            ],
            values("mod-compatibility-remote", "client_b"),
        )
        self.assertEqual(
            [],
            values("mod-compatibility-remote", "client_a"),
        )
        self.assertEqual(
            [],
            values("mod-compatibility-late-join", "client_a"),
        )
        self.assertEqual(
            [],
            values("mod-compatibility-late-join", "client_b"),
        )
        self.assertEqual(
            [],
            values("mod-compatibility-cpm-first-person", "client_a"),
        )
        self.assertEqual(
            [
                ("baseline", "local_skin_apply", 0.00001, None),
                (
                    "model_slim",
                    "model_classic",
                    0.001,
                    (0.38, 0.18, 0.62, 0.96),
                ),
                (
                    "adjusted_bmo_elytra",
                    "vanilla_elytra_after_cape_removal",
                    0.005,
                    (0.4, 0.35, 0.6, 0.9),
                ),
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
                (
                    "baseline",
                    "hud_preview_overlay",
                    0.05,
                    (0.82, 0.72, 0.96, 0.99),
                ),
                (
                    "external_skin_drop",
                    "catalog_delete",
                    0.002,
                    (0.34, 0.09, 0.66, 0.91),
                ),
                (
                    "model_classic",
                    "slim_skin_auto_detect",
                    0.001,
                    (0.38, 0.18, 0.62, 0.96),
                ),
                (
                    "model_classic",
                    "legacy_skin_apply",
                    0.002,
                    (0.38, 0.18, 0.62, 0.96),
                ),
                (
                    "base_layer_transparency",
                    "transparency_disabled",
                    0.001,
                    (0.44, 0.4, 0.56, 0.7),
                ),
                (
                    "cape_adjust_zoom_in",
                    "cape_editor_snap_mirror",
                    0.00001,
                    None,
                ),
                (
                    "known_cape_apply",
                    "translucent_cape_worn",
                    0.001,
                    (0.44, 0.45, 0.56, 0.78),
                ),
                (
                    "translucent_cape_worn",
                    "translucent_cape_elytra",
                    0.005,
                    (0.4, 0.35, 0.6, 0.9),
                ),
                (
                    "cape_menu_screen",
                    "cape_menu_local_capes",
                    0.001,
                    (0.12, 0.07, 0.7, 0.91),
                ),
                (
                    "cape_menu_local_capes",
                    "cape_speed_slider",
                    0.00001,
                    None,
                ),
                (
                    "hd_cape_no_downscale",
                    "no_cape_after_removal",
                    0.005,
                    (0.44, 0.45, 0.56, 0.78),
                ),
                (
                    "cape_menu_local_capes",
                    "cape_menu_hidden_builtin",
                    0.001,
                    (0.12, 0.07, 0.7, 0.91),
                ),
                (
                    "settings_screen",
                    "settings_keybind_capture",
                    0.00001,
                    (0.16, 0.25, 0.28, 0.31),
                ),
                (
                    "settings_screen",
                    "settings_gui_edit_tab",
                    0.001,
                    (0.12, 0.1, 0.78, 0.88),
                ),
                (
                    "settings_gui_edit_tab",
                    "settings_server_tab",
                    0.001,
                    (0.12, 0.1, 0.78, 0.88),
                ),
                (
                    "skin_menu_screen",
                    "styled_buttons_vanilla_background",
                    0.02,
                    (0.03, 0.2, 0.2, 0.8),
                ),
                (
                    "local_skin_apply",
                    "stale_skin_fallback",
                    0.001,
                    (0.42, 0.34, 0.58, 0.86),
                ),
            ],
            values("full", "client_a"),
        )
        self.assertEqual(
            [
                (
                    "baseline",
                    "transparent_skin_server_policy",
                    0.00001,
                    None,
                ),
                (
                    "transparent_skin_server_policy",
                    "cape_change_during_cooldown",
                    0.001,
                    (0.4, 0.32, 0.6, 0.88),
                ),
            ],
            values("server-policy", "client_a"),
        )
        self.assertEqual(
            [
                ("pause_menu_preview", "inventory_paper_doll", 0.00001, None),
                ("pause_menu_preview", "quit_to_title", 0.00001, None),
            ],
            values("session", "client_a"),
        )
        self.assertEqual(
            [
                (
                    "baseline_with_mod",
                    "apply_local_skin_with_mod",
                    0.00001,
                    None,
                ),
            ],
            values("mod-compatibility", "client_a"),
        )

    def test_visual_probes_remain_bound_to_their_capture_step(self) -> None:
        self.assertEqual((1600, 900), self.contract.gui_text_reference_size)
        probes = self.contract.probes_for("full", "client_a")
        self.assertEqual(14, len(probes))
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
                "full", "client_a", "cape_fill_color_picker"
            ).probes
            + self.contract.capture(
                "full", "client_a", "bmo_padded_source_screen"
            ).probes
            + self.contract.capture(
                "full", "client_a", "cape_tile_tooltip"
            ).probes
            + self.contract.capture(
                "full", "client_a", "settings_screen"
            ).probes
            + self.contract.capture(
                "full", "client_a", "settings_server_tab"
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
                    (300, 624, 780, 672),
                    75,
                    900,
                ),
                (
                    "cape_fill_color_picker",
                    "fill colour hex value",
                    (79, 543, 220, 576),
                    175,
                    380,
                ),
                (
                    "bmo_padded_source_screen",
                    "BMO source dimensions",
                    (45, 100, 250, 140),
                    75,
                    120,
                ),
                (
                    "bmo_padded_source_screen",
                    "BMO output dimensions",
                    (1035, 100, 1255, 140),
                    75,
                    120,
                ),
                (
                    "cape_tile_tooltip",
                    "cape tile tooltip copy",
                    (765, 190, 1040, 345),
                    159,
                    2500,
                ),
                (
                    "settings_screen",
                    "Open Skin Menu setting label",
                    (445, 235, 655, 265),
                    175,
                    300,
                ),
                (
                    "settings_server_tab",
                    "server transparency setting label",
                    (305, 234, 780, 268),
                    175,
                    1300,
                ),
                (
                    "settings_server_tab",
                    "non-admin server notice",
                    (495, 655, 1105, 688),
                    175,
                    1700,
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
        self.assertEqual(
            self.contract.capture(
                "server-policy", "client_a", "cooldown_skin_menu"
            ).probes,
            self.contract.probes_for("server-policy", "client_a"),
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

        def missing_compatibility_reference(value: dict[str, Any]) -> None:
            del self.step(value, COMPATIBILITY, 0, "baseline_with_mod")["capture"][
                "compatibility_reference_capture_id"
            ]

        def unknown_compatibility_reference(value: dict[str, Any]) -> None:
            self.step(value, COMPATIBILITY, 0, "baseline_with_mod")["capture"][
                "compatibility_reference_capture_id"
            ] = "full.client_a.missing"

        def compatibility_reference_is_not_release(value: dict[str, Any]) -> None:
            self.step(value, COMPATIBILITY, 0, "baseline_with_mod")["capture"][
                "compatibility_reference_capture_id"
            ] = "mod-compatibility.client_a.apply_local_skin_with_mod"

        def missing_review_regions(value: dict[str, Any]) -> None:
            del value["review_regions"]["full.client_a.baseline"]

        def unknown_review_region(value: dict[str, Any]) -> None:
            value["review_regions"]["full.client_a.unknown"] = [[0.0, 0.0, 1.0, 1.0]]

        def inverted_review_region(value: dict[str, Any]) -> None:
            value["review_regions"]["full.client_a.baseline"] = [[0.8, 0.2, 0.1, 0.9]]

        def duplicate_review_region(value: dict[str, Any]) -> None:
            regions = value["review_regions"]["full.client_a.baseline"]
            regions.append(copy.deepcopy(regions[0]))

        cases = {
            "unknown root field": unknown_root,
            "boolean schema": lambda value: value.__setitem__(
                "schema_version", True
            ),
            "unsupported schema": lambda value: value.__setitem__(
                "schema_version", 3
            ),
            "wrong screenshot size": lambda value: value.__setitem__(
                "screenshot_size", [1280, 720]
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
            "missing compatibility reference": missing_compatibility_reference,
            "unknown compatibility reference": unknown_compatibility_reference,
            "compatibility reference outside release": compatibility_reference_is_not_release,
            "missing review region": missing_review_regions,
            "unknown review region": unknown_review_region,
            "inverted review region": inverted_review_region,
            "duplicate review region": duplicate_review_region,
        }
        for label, mutate in cases.items():
            with self.subTest(label=label):
                self.assert_mutation_rejected(mutate)

    def test_malformed_json_encoding_and_symlinks_are_rejected(self) -> None:
        duplicate = self.contract_path.read_text(encoding="utf-8").replace(
            '"schema_version": 2,',
            '"schema_version": 2,\n  "schema_version": 2,',
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
            '"schema_version": 2',
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
