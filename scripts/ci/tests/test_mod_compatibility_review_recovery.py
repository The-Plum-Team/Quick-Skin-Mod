from __future__ import annotations

import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path

from test_workflow_security import job_block, step_script


WORKFLOW = "mod-compatibility-review.yml"
SHA = "a" * 40
REPOSITORY = "The-Plum-Team/Quick-Skin-Mod"


class ModCompatibilityReviewRecoveryTest(unittest.TestCase):
    def test_capsule_inventory_reads_later_pages_and_rejects_incomplete_inventory(self) -> None:
        script = step_script(WORKFLOW, "enumerate",
                             "Select every unique capsule produced by a successful lane")
        start = script.index("artifacts='{\"artifacts\":[]}'")
        end = script.index('matrix="$(jq', start)
        inventory = script[start:end]
        for mode in ("complete", "partial", "overlap"):
            with self.subTest(mode=mode), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                (root / "page-1.json").write_text(json.dumps({
                    "total_count": 101, "artifacts": [{"id": index} for index in range(1, 101)]}))
                (root / "page-2.json").write_text(json.dumps({
                    "total_count": 101, "artifacts": [] if mode == "partial" else
                    [{"id": 100 if mode == "overlap" else 101}]}))
                fake_api = '''
                set -euo pipefail
                github_api_retry() { cat "$TEST_ROOT/page-${1##*&page=}.json"; }
                '''
                result = subprocess.run(
                    ["bash", "-c", fake_api + inventory + '\nprintf "%s" "$artifacts"'],
                    capture_output=True, text=True, timeout=15,
                    env=os.environ | {"TEST_ROOT": str(root), "GITHUB_REPOSITORY": REPOSITORY,
                                      "SOURCE_RUN_ID": "10"},
                )
                self.assertEqual(mode != "complete", result.returncode != 0,
                                 result.stdout + result.stderr)
                if mode == "complete":
                    self.assertEqual(101, len(json.loads(result.stdout)["artifacts"]))

    def test_gate_settles_available_lanes_but_publishes_only_the_complete_plan(self) -> None:
        script = step_script(WORKFLOW, "gate", "Resolve the durable compatibility review state")
        cases = (
            ("partial reviewed", 8, 8, 0, 10, False, True, False),
            ("partial new review", 8, 6, 2, 10, True, True, False),
            ("rerun completes plan", 10, 8, 2, 10, True, True, True),
            ("complete preserved", 10, 10, 0, 10, False, True, True),
            ("provider paused", 10, 8, 2, 10, False, False, False),
            ("no runtime capsules", 0, 0, 0, 10, False, True, False),
        )
        for label, count, completed, pending, runnable, ready, settled, complete in cases:
            with self.subTest(label=label), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                output = root / "outputs"
                result = subprocess.run(
                    ["bash", "-c", script], capture_output=True, text=True, timeout=15,
                    env=os.environ | {
                        "GITHUB_OUTPUT": str(output), "GITHUB_STEP_SUMMARY": str(root / "summary"),
                        "COUNT": str(count), "COMPLETED_COUNT": str(completed),
                        "PENDING_COUNT": str(pending), "RUNNABLE_COUNT": str(runnable),
                        "CACHED_READY": "false", "CAPACITY_CHECK_RESULT": "success",
                        "CAPACITY_STATE": "unknown" if ready else "paused",
                        "PROBE_REQUIRED": str(ready).lower(),
                        "PROBE_RESULT": "success" if ready else "skipped",
                        "PROBE_READY": str(ready).lower(), "PROBE_FRESH_READY": str(ready).lower(),
                        **{key: "success" if ready else "skipped"
                           for key in ("PREPARE_RESULT", "REVIEW_RESULT", "PUBLISH_RESULT")},
                    },
                )
                self.assertEqual(0, result.returncode, result.stdout + result.stderr)
                values = dict(line.split("=", 1) for line in output.read_text().splitlines())
                self.assertEqual(str(settled).lower(), values["settled"])
                self.assertEqual(str(complete).lower(), values["complete"])
        publication = job_block(WORKFLOW, "request-publication")
        gate = job_block(WORKFLOW, "gate")
        self.assertIn("needs.gate.outputs.complete == 'true'", publication)
        self.assertLess(gate.index("Upload the durable source completion marker"),
                        gate.index("Upload the durable source attempt settlement marker"))
        self.assertIn("if: success() && steps.result.outputs.settled == 'true'", gate)

    def test_lane_recovery_binds_new_capsules_and_preserves_good_first_attempt_lanes(self) -> None:
        script = step_script(WORKFLOW, "enumerate",
                             "Select every unique capsule produced by a successful lane")
        start = script.index("pending='{\"include\":[]}'")
        end = script.index('pending_count="$(jq', start)
        selection = script[start:end]
        for attempt, exact_marker, expected_pending in ((1, False, 0), (2, False, 1), (2, True, 0)):
            with self.subTest(attempt=attempt, exact=exact_marker), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                legacy = "mod-compatibility-lane-complete-10-fabric-ears"
                marker = {"name": legacy + ("--100" if exact_marker else ""),
                          "expired": False, "size_in_bytes": 100,
                          "digest": "sha256:" + "b" * 64,
                          "workflow_run": {"id": 20, "head_sha": SHA}}
                (root / "markers.json").write_text(json.dumps([marker]))
                (root / "owner.json").write_text(json.dumps({
                    "id": 20, "status": "completed", "conclusion": "success",
                    "event": "repository_dispatch", "head_branch": "master", "head_sha": SHA,
                    "path": ".github/workflows/mod-compatibility-review.yml",
                    "head_repository": {"full_name": REPOSITORY},
                }))
                fake_api = '''
                set -euo pipefail
                github_api_retry() {
                  case "$1" in
                    *'/actions/artifacts?name='*)
                      local marker_name="${1#*name=}"
                      marker_name="${marker_name%&per_page=*}"
                      jq --arg name "$marker_name" '[.[] | select(.name == $name)] |
                        {total_count:length,artifacts:.}' "$TEST_ROOT/markers.json"
                      ;;
                    */actions/runs/20) cat "$TEST_ROOT/owner.json" ;;
                    *) return 1 ;;
                  esac
                }
                '''
                result = subprocess.run(
                    ["bash", "-c", fake_api + selection + '\nprintf "%s" "$pending"'],
                    capture_output=True, text=True, timeout=15,
                    env=os.environ | {"TEST_ROOT": str(root), "GITHUB_SHA": SHA,
                        "GITHUB_REPOSITORY": REPOSITORY, "SOURCE_RUN_ID": "10",
                        "matrix": json.dumps({"include": [{"id": "fabric-ears",
                            "artifact_id": 100,
                            "artifact_name": f"mod-compatibility-review-input-10-fabric-ears-{attempt}"}]}),
                    },
                )
                self.assertEqual(0, result.returncode, result.stdout + result.stderr)
                self.assertEqual(expected_pending, len(json.loads(result.stdout)["include"]))

    def test_settlement_marker_records_the_partial_source_attempt(self) -> None:
        script = step_script(WORKFLOW, "gate",
                             "Create the authenticated source attempt settlement marker")
        with tempfile.TemporaryDirectory() as temporary:
            result = subprocess.run(["bash", "-c", script], capture_output=True, text=True,
                timeout=15, env=os.environ | {"RUNNER_TEMP": temporary, "COUNT": "8",
                    "RUNNABLE_COUNT": "10", "IMPLEMENTATION_SHA": SHA,
                    "SOURCE_RUN_ID": "10", "SOURCE_RUN_ATTEMPT": "2"})
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            marker = json.loads((Path(temporary) / "mod-compatibility-review-settled.json").read_text())
            self.assertEqual(2, marker["source_run_attempt"])
            self.assertEqual(8, marker["lane_count"])
            self.assertEqual(10, marker["runnable_count"])
            self.assertFalse((Path(temporary) / "mod-compatibility-review-complete.json").exists())


if __name__ == "__main__":
    unittest.main()
