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

import generate_contract_java  # noqa: E402
import scenario_contract  # noqa: E402


class GenerateContractJavaTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.contract_path = ROOT / "e2e" / "scenario-contract.json"
        self.payload: dict[str, Any] = json.loads(
            self.contract_path.read_text(encoding="utf-8")
        )

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def write_contract(self, payload: object) -> Path:
        path = self.root / "scenario-contract.json"
        path.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
        return path

    @staticmethod
    def step(
        payload: dict[str, Any],
        scenario_index: int,
        role_index: int,
        step_id: str,
    ) -> dict[str, Any]:
        steps = payload["scenarios"][scenario_index]["roles"][role_index]["steps"]
        return next(item for item in steps if item["id"] == step_id)

    def test_generated_source_preserves_the_typed_api_and_exact_raw_hash(self) -> None:
        output = self.root / "generated" / "ScenarioContract.java"
        self.assertTrue(
            generate_contract_java.generate_java(self.contract_path, output)
        )
        source = output.read_text(encoding="utf-8")
        expected_hash = hashlib.sha256(self.contract_path.read_bytes()).hexdigest()
        self.assertIn(f'public static final String SHA256 = "{expected_hash}";', source)
        self.assertIn("public static final int SCREENSHOT_WIDTH = 1920;", source)
        self.assertIn("public static final int SCREENSHOT_HEIGHT = 1080;", source)
        self.assertIn("public enum ScenarioId {", source)
        self.assertIn('PHASE0_SMOKE("phase0-smoke")', source)
        self.assertIn('PROPAGATION_LIVE("propagation-live")', source)
        self.assertIn(
            "public record StepSpec(String id, boolean assertionRequired, "
            "boolean captureRequired) {}",
            source,
        )
        self.assertIn('new StepSpec("confirm_self", true, false)', source)
        self.assertIn('new StepSpec("skin_menu_screen", true, true)', source)
        self.assertIn(
            'new StepSpec("remove_cape_with_elytra", true, false)',
            source,
        )
        self.assertIn(
            'new StepSpec("vanilla_elytra_after_cape_removal", true, true)',
            source,
        )
        self.assertIn(
            "public static RoleSpec role(ScenarioId scenario, String role)",
            source,
        )
        self.assertIn("public static Set<String> roles(ScenarioId scenario)", source)

        original_bytes = output.read_bytes()
        self.assertFalse(
            generate_contract_java.generate_java(self.contract_path, output)
        )
        self.assertEqual(original_bytes, output.read_bytes())

    def test_generator_rejects_every_contract_the_canonical_parser_rejects(self) -> None:
        def duplicate_role(value: dict[str, Any]) -> None:
            roles = value["scenarios"][1]["roles"]
            roles.append(copy.deepcopy(roles[0]))

        def duplicate_step(value: dict[str, Any]) -> None:
            steps = value["scenarios"][0]["roles"][0]["steps"]
            steps.append(copy.deepcopy(steps[0]))

        def malformed_capture(value: dict[str, Any]) -> None:
            del self.step(value, 0, 0, "baseline")["capture"]["expectation"]

        def malformed_probe(value: dict[str, Any]) -> None:
            self.step(value, 3, 0, "skin_menu_screen")["capture"]["probes"][0][
                "region"
            ] = [0.9, 0.2, 0.1, 0.8]

        def malformed_comparison(value: dict[str, Any]) -> None:
            value["scenarios"][0]["roles"][0]["comparisons"][0][
                "minimum_changed_fraction"
            ] = True

        def enum_collision(value: dict[str, Any]) -> None:
            collision = copy.deepcopy(value["scenarios"][0])
            collision["scenario"] = "phase0_smoke"
            collision["execution_profiles"] = ["pr"]
            value["scenarios"].append(collision)

        mutations: dict[str, Callable[[dict[str, Any]], None]] = {
            "fractional schema": lambda value: value.__setitem__(
                "schema_version", 1.0
            ),
            "boolean schema": lambda value: value.__setitem__(
                "schema_version", True
            ),
            "false assertion": lambda value: self.step(
                value, 0, 0, "baseline"
            ).__setitem__("assertion_required", False),
            "extra key": lambda value: value.__setitem__("extra", None),
            "duplicate role": duplicate_role,
            "duplicate step": duplicate_step,
            "malformed capture": malformed_capture,
            "malformed probe": malformed_probe,
            "malformed comparison": malformed_comparison,
            "Java enum collision": enum_collision,
        }
        output = self.root / "ScenarioContract.java"
        for label, mutate in mutations.items():
            with self.subTest(label=label):
                payload = copy.deepcopy(self.payload)
                mutate(payload)
                invalid_contract = self.write_contract(payload)
                output.write_text("sentinel\n", encoding="utf-8")
                with self.assertRaises(scenario_contract.ScenarioContractError):
                    generate_contract_java.generate_java(invalid_contract, output)
                self.assertEqual("sentinel\n", output.read_text(encoding="utf-8"))

    def test_gradle_task_executes_this_generator_and_tracks_the_validator(self) -> None:
        conventions = (
            ROOT / "gradle" / "e2e-harness-conventions.gradle.kts"
        ).read_text(encoding="utf-8")
        self.assertNotIn("JsonSlurper", conventions)
        self.assertIn(
            'tasks.register<Exec>("generateE2EContractJava")', conventions
        )
        self.assertIn('rootProject.file("e2e/scenario_contract.py")', conventions)
        self.assertIn(
            'rootProject.file("e2e/generate_contract_java.py")', conventions
        )
        self.assertIn("scenarioContractValidator,", conventions)
        self.assertIn("scenarioContractGenerator,", conventions)
        self.assertIn('environmentVariable("QUICKSKIN_PYTHON")', conventions)
        self.assertIn('"--contract"', conventions)
        self.assertIn('"--output"', conventions)


if __name__ == "__main__":
    unittest.main()
