from __future__ import annotations

import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path

from test_workflow_security import ROOT, job_block, step_script


WORKFLOW = "mod-compatibility-review.yml"
SHA = "a" * 40
REPOSITORY = "The-Plum-Team/Quick-Skin-Mod"


class ModCompatibilityReviewApiBudgetTest(unittest.TestCase):
    def select(self, owners: list[dict], *, lane_count: int = 8,
               bad_marker: bool = False,
               transport_failure: int | None = None) -> tuple[list[dict], list[str]]:
        script = step_script(WORKFLOW, "enumerate",
                             "Select every unique capsule produced by a successful lane")
        script = script[script.index("pending='{\"include\":[]}'"):
                        script.index('pending_count="$(jq')]
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            lanes = [{"id": f"lane-{index}", "artifact_id": 100 + index,
                      "artifact_name": f"mod-compatibility-review-input-10-lane-{index}-2"}
                     for index in range(lane_count)]
            markers = [{"name": f"mod-compatibility-lane-complete-10-lane-{index}--{100+index}",
                        "expired": bad_marker and index == 1, "size_in_bytes": 100,
                        "digest": "sha256:" + "b" * 64,
                        "workflow_run": {"id": 20, "head_sha": SHA}}
                       for index in range(lane_count)]
            (root / "markers.json").write_text(json.dumps(markers))
            (root / "owners.json").write_text(json.dumps(owners))
            fake_api = '''
            set -euo pipefail
            github_api_retry() {
              printf '%s\n' "$1" >> "$TEST_ROOT/requests"
              case "$1" in
                *'/actions/artifacts?name='*)
                  local name="${1#*name=}"
                  name="${name%&per_page=*}"
                  jq --arg name "$name" '[.[] | select(.name == $name)] |
                    {total_count:length,artifacts:.}' "$TEST_ROOT/markers.json" ;;
                */actions/runs/20)
                  if [[ -n "${TEST_FAILURE_STATUS:-}" ]]; then
                    case "$TEST_FAILURE_STATUS" in
                      403) printf 'gh: API rate limit exceeded (HTTP 403)\n' >&2 ;;
                      429) printf 'gh: secondary rate limit (HTTP 429)\n' >&2 ;;
                      404) printf 'gh: Not Found (HTTP 404)\n' >&2 ;;
                    esac
                    return 1
                  fi
                  printf '.\n' >> "$TEST_ROOT/owner-requests"
                  local count
                  count="$(wc -l < "$TEST_ROOT/owner-requests")"
                  jq --argjson count "$count" '.[[($count-1), (length-1)] | min]' \
                    "$TEST_ROOT/owners.json" ;;
                *) return 1 ;;
              esac
            }
            '''
            if transport_failure is not None:
                # Exercise the production retry wrapper. Only its CLI transport and sleeps
                # are isolated; no network request or real delay is made by this regression.
                fake_api = ('source "$TEST_RETRY_SCRIPT"\nsleep() { :; }\n' +
                            fake_api.replace("github_api_retry() {", "gh() {\nshift"))
            result = subprocess.run(
                ["bash", "-c", fake_api + script + '\nprintf "%s" "$pending"'],
                capture_output=True, text=True, timeout=30,
                env=os.environ | {"TEST_ROOT": str(root), "GITHUB_SHA": SHA,
                    "GITHUB_REPOSITORY": REPOSITORY, "SOURCE_RUN_ID": "10",
                    "TEST_FAILURE_STATUS": str(transport_failure or ""),
                    "TEST_RETRY_SCRIPT": str(ROOT / "scripts/ci/github_api_retry.sh"),
                    "GITHUB_API_RETRY_ATTEMPTS": "2", "GITHUB_API_RETRY_MAX_DELAY_SECONDS": "1",
                    "GITHUB_API_RETRY_MAX_WAIT_SECONDS": "1", "GITHUB_RUN_ID": "",
                    "matrix": json.dumps({"include": lanes})},
            )
            if transport_failure is not None:
                self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
                return [], (root / "requests").read_text().splitlines()
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            return json.loads(result.stdout)["include"], (root / "requests").read_text().splitlines()

    @staticmethod
    def owner(**changes) -> dict:
        return {"id": 20, "status": "completed", "conclusion": "success",
                "event": "repository_dispatch", "head_branch": "master", "head_sha": SHA,
                "path": ".github/workflows/mod-compatibility-review.yml",
                "head_repository": {"full_name": REPOSITORY}} | changes

    def test_eight_markers_from_one_validated_owner_need_nine_requests(self) -> None:
        pending, requests = self.select([self.owner()])
        self.assertEqual([], pending)
        self.assertEqual(9, len(requests))
        self.assertEqual(1, sum("/actions/runs/20" in path for path in requests))
        self.assertEqual(8, sum("/actions/artifacts?name=" in path for path in requests))

    def test_unsettled_owner_is_rechecked_then_cached_only_after_validation(self) -> None:
        pending, requests = self.select(
            [self.owner(status="in_progress", conclusion=None), self.owner()], lane_count=3)
        self.assertEqual(["lane-0"], [lane["id"] for lane in pending])
        self.assertEqual(2, sum("/actions/runs/20" in path for path in requests))

    def test_invalid_owner_never_enters_the_cache(self) -> None:
        for changes in ({"id": 21}, {"head_sha": "c" * 40}, {"head_branch": "untrusted"},
                        {"event": "pull_request"}, {"path": ".github/workflows/build-gate.yml"},
                        {"head_repository": {"full_name": "other/repository"}},
                        {"conclusion": "cancelled"}):
            with self.subTest(changes=changes):
                pending, requests = self.select([self.owner(**changes)], lane_count=3)
                self.assertEqual(3, len(pending))
                self.assertEqual(3, sum("/actions/runs/20" in path for path in requests))

    def test_cached_owner_does_not_bypass_marker_validation(self) -> None:
        pending, requests = self.select([self.owner()], lane_count=3, bad_marker=True)
        self.assertEqual(["lane-1"], [lane["id"] for lane in pending])
        self.assertEqual(1, sum("/actions/runs/20" in path for path in requests))

    def test_owner_transport_failure_stops_before_examining_other_lanes(self) -> None:
        for status, attempts in ((403, 2), (429, 2), (404, 1)):
            with self.subTest(status=status):
                _, requests = self.select([self.owner()], transport_failure=status)
                self.assertEqual(attempts, sum("/actions/runs/20" in path for path in requests))
                self.assertEqual(1, sum("/actions/artifacts?name=" in path for path in requests))

    def test_only_recovery_origin_restarts_the_historical_sweep(self) -> None:
        recover = job_block(WORKFLOW, "recover")
        continuation = job_block(WORKFLOW, "continue")
        self.assertIn("github.event.client_payload.recovery_chain == true", continuation)
        self.assertIn("needs.gate.result == 'success'", continuation)
        self.assertIn("needs.gate.outputs.settled == 'true'", continuation)
        self.assertIn("github.event.action == 'mod-compatibility-review-sweep-requested'", recover)
        self.assertIn("inputs.operation == 'recover'", recover)
        producer = step_script("mod-compatibility-e2e.yml", "request-review",
                               "Dispatch the exact settled compatibility source")
        self.assertNotIn("recovery_chain", producer)

        script = step_script(WORKFLOW, "recover", "Dispatch the oldest authenticated pending source")
        script = script[script.index('payload="$RUNNER_TEMP/mod-compatibility-review-recovery.json"'):]
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            fake_api = '''
            set -euo pipefail
            github_api_retry() {
              [[ "$1" == --method && "$2" == POST && "$4" == --input ]]
              cp "$5" "$RUNNER_TEMP/dispatched.json"
            }
            '''
            result = subprocess.run(["bash", "-c", fake_api + script],
                capture_output=True, text=True, timeout=15,
                env=os.environ | {"RUNNER_TEMP": temporary, "GITHUB_REPOSITORY": REPOSITORY,
                    "source_run_id": "10", "source_sha": SHA})
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            payload = json.loads((root / "dispatched.json").read_text())
            self.assertEqual("mod-compatibility-review-requested", payload["event_type"])
            self.assertIs(True, payload["client_payload"]["recovery_chain"])
            self.assertEqual("10", payload["client_payload"]["source_run_id"])
            self.assertEqual(SHA, payload["client_payload"]["source_sha"])


if __name__ == "__main__":
    unittest.main()
