from __future__ import annotations

import copy
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts/release"))

import build_matrix  # noqa: E402
import matrix as release_matrix  # noqa: E402


class MatrixBuildTest(unittest.TestCase):
    def setUp(self) -> None:
        self.data = release_matrix.load_matrix(ROOT / "release/release-matrix.json")

    def test_complete_plan_covers_each_lane_once_in_separate_serial_processes(self) -> None:
        plan = build_matrix.build_plan(self.data, clean=True, rerun_tasks=True)
        self.assertEqual(len({row["artifact_version"] for row in self.data["artifacts"]}), len(plan))
        nodes = [node for target in plan for node in target["artifact_nodes"]]
        self.assertCountEqual([row["artifact_node"] for row in self.data["artifacts"]], nodes)
        for target in plan:
            arguments = target["arguments"]
            for argument in ("--no-daemon", "--no-parallel", "--rerun-tasks", "clean",
                             f"-PquickskinTarget={target['target']}"):
                self.assertIn(argument, arguments)
            self.assertEqual(["buildTargetLanes", "buildTargetE2EHarnesses"], arguments[-2:])
            self.assertNotIn("buildAllLanes", arguments)

    def test_target_selection_cannot_hide_a_broken_unselected_lane(self) -> None:
        data = copy.deepcopy(self.data)
        data["artifacts"][-1]["gradle_task"] = ":wrong:task"
        with self.assertRaises(release_matrix.MatrixError):
            build_matrix.build_plan(data, target=data["artifacts"][0]["artifact_version"])

    def test_unknown_and_command_like_targets_fail_before_execution(self) -> None:
        for target in ("0.0.0", "--parallel", "1.20.1 buildAllLanes"):
            with self.subTest(target=target), self.assertRaises(release_matrix.MatrixError):
                build_matrix.build_plan(self.data, target=target)

    def run_build(self, *, target=None, failure=None, remove_output=False,
                  mutate_matrix=False, alter_earlier_output=False):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            (root / "release").mkdir()
            matrix_path = root / "release/release-matrix.json"
            matrix_path.write_text(json.dumps(self.data))
            (root / "gradle.properties").write_text("mod_version=3.0.0\n")
            report_path = root / "build/results.json"
            build_matrix.write_report(report_path, {"status": "success", "stale": True})
            calls = []
            produced = []

            def runner(command, *, cwd, check):
                self.assertEqual(root, cwd)
                self.assertFalse(check)
                self.assertEqual(str(root / ("gradlew.bat" if build_matrix.os.name == "nt" else "gradlew")), command[0])
                self.assertEqual("running", json.loads(report_path.read_text())["status"])
                calls.append(command)
                version = next(arg.split("=", 1)[1] for arg in command if arg.startswith("-PquickskinTarget="))
                if failure == len(calls):
                    return subprocess.CompletedProcess(command, 7)
                for row in self.data["artifacts"]:
                    if row["artifact_version"] != version:
                        continue
                    for kind in ("jar", "harness_jar"):
                        output = root / row[kind].replace("{mod_version}", "3.0.0")
                        output.parent.mkdir(parents=True, exist_ok=True)
                        output.write_bytes(f"{version}:{kind}".encode())
                        produced.append(output)
                if remove_output:
                    produced[-1].unlink()
                if mutate_matrix:
                    matrix_path.write_text(matrix_path.read_text() + "\n")
                if alter_earlier_output and len(calls) > 1:
                    produced[0].write_bytes(b"unexpected later output")
                return subprocess.CompletedProcess(command, 0)

            with patch.object(release_matrix, "load_matrix", return_value=self.data):
                result = build_matrix.execute_build(root, target=target, clean=False,
                                                     rerun_tasks=False, report_path=report_path,
                                                     runner=runner)
            return result, json.loads(report_path.read_text()), calls

    def test_success_requires_every_target_and_every_output(self) -> None:
        result, report, calls = self.run_build()
        self.assertEqual(0, result)
        self.assertEqual("success", report["status"])
        self.assertEqual("full", report["scope"])
        self.assertNotIn("stale", report)
        self.assertEqual(len(report["plan"]), len(calls))
        self.assertEqual(2 * self.data["lane_count"], sum(len(row["outputs"]) for row in report["results"]))

    def test_partial_build_is_explicit_and_builds_only_its_loaders(self) -> None:
        target = self.data["artifacts"][0]["artifact_version"]
        result, report, calls = self.run_build(target=target)
        self.assertEqual(0, result)
        self.assertEqual("target", report["scope"])
        self.assertEqual(target, report["target"])
        self.assertEqual(1, len(calls))

    def test_mid_matrix_failure_stops_and_cannot_retain_previous_success(self) -> None:
        result, report, calls = self.run_build(failure=2)
        self.assertEqual(1, result)
        self.assertEqual("failed", report["status"])
        self.assertEqual(2, len(calls))
        self.assertEqual(7, report["results"][-1]["returncode"])
        self.assertNotIn("stale", report)

    def test_successful_process_with_missing_jar_is_failed_build(self) -> None:
        result, report, calls = self.run_build(remove_output=True)
        self.assertEqual((1, "failed", 1), (result, report["status"], len(calls)))
        self.assertIn("missing or linked", report["error"])

    def test_matrix_must_remain_identical_for_whole_run(self) -> None:
        result, report, calls = self.run_build(mutate_matrix=True)
        self.assertEqual((1, "failed", 1), (result, report["status"], len(calls)))
        self.assertIn("matrix changed", report["error"])

    def test_later_target_cannot_overwrite_an_earlier_target_output(self) -> None:
        result, report, _ = self.run_build(alter_earlier_output=True)
        self.assertEqual((1, "failed"), (result, report["status"]))
        self.assertIn("outputs changed", report["error"])


if __name__ == "__main__":
    unittest.main()
