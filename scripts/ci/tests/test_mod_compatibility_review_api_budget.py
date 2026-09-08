from __future__ import annotations

import copy
import hashlib
import json
import os
import pickle
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from test_workflow_security import ROOT, job_block, step_script
from test_ci_reuse import FixtureApi
import ci_reuse


WORKFLOW = "mod-compatibility-review.yml"
SHA = "a" * 40
REPOSITORY = "The-Plum-Team/Quick-Skin-Mod"


class ModCompatibilityPreparationApiBudgetTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        # These production guards use Ubuntu Bash semantics. macOS Bash 3.2 ignores
        # errexit for a failed [[ comparison ]] inside a loop, unlike the CI runner.
        for directory in os.get_exec_path():
            candidate = Path(directory) / "bash"
            if candidate.is_file() and os.access(candidate, os.X_OK):
                probe = subprocess.run([str(candidate), "-c", 'exit "$((BASH_VERSINFO[0] < 4))"'],
                                       env=os.environ | {"BASH_ENV": ""}, timeout=5)
                if probe.returncode == 0:
                    cls.bash = str(candidate)
                    return
        raise unittest.SkipTest("The workflow regression requires Bash 4 or later, as used on Ubuntu")

    def prepare(self, *, distinct_reference: bool = False, invalid_reference: bool = False,
                transport_failure: bool = False, bad_manifest: bool = False,
                master_changed: bool = False, final_transport_failure: bool = False) -> dict:
        script = step_script(WORKFLOW, "prepare-review", "Fetch and authenticate every curated capsule")
        initialization = script[script.index('mkdir -p "$lanes_root" "$api_cache"'):
                                script.index('while IFS= read -r lane; do')]
        authentication = script[script.index('original_source_sha="$source_sha"'):
                                script.index('for kind in base candidate; do')]
        after_loop = script[script.index("done < <(jq -c '.include[]' \"$pending\")") +
                            len("done < <(jq -c '.include[]' \"$pending\")"):
                            script.index('batch="$RUNNER_TEMP/mod-compatibility-review-batch"')]
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            fixture_root = root / "fixture"
            fixture_root.mkdir()
            api = FixtureApi(fixture_root, REPOSITORY)
            reference, _ = ci_reuse.find_reference(api, api.covered, "e2e")
            second_reference = copy.deepcopy(reference)
            if distinct_reference:
                # A second legitimate PR execution can seal the same tested Git tree.
                api.runs[21] = {**api.runs[20], "id": 21}
                api.inventories[21] = []
                for original in api.inventories[20]:
                    if original["name"] != "tested-source-e2e":
                        api.add_artifact(21, original["id"] + 2000, original["name"])
                second_seal = {**api.seals["e2e"], "run_id": 21}
                second_artifact = api.add_descriptor(21, 121, "tested-source-e2e",
                                                     "tested-source.json", second_seal)
                api.job_lists[21] = copy.deepcopy(api.job_lists[20])
                for page in api.job_lists[21]:
                    for job in page["jobs"]:
                        job["run_id"] = 21
                second_reference.update(source=second_seal,
                                        seal_artifact=ci_reuse.artifact_record(second_artifact))
            if invalid_reference:
                second_reference["seal_artifact"]["digest"] = "sha256:" + "c" * 64
            (root / "api.pickle").write_bytes(pickle.dumps(api))
            (root / "transport.py").write_text('''
import json, os, pickle, sys
from pathlib import Path
from unittest.mock import patch
sys.path.insert(0, os.environ["TEST_CI_TESTS"])
from test_ci_reuse import FixtureApi
import ci_reuse
import feature_coverage_github
root = Path(os.environ["TEST_ROOT"])
api = pickle.loads((root / "api.pickle").read_bytes())
with (root / "verifications").open("a") as output:
    output.write("verify\\n")
for name in ("run", "artifact", "download", "json", "artifacts", "jobs", "current_sha"):
    original = getattr(api, name)
    def counted(*args, _name=name, _original=original, **kwargs):
        with (root / "transport-requests").open("a") as output:
            output.write(_name + "\\n")
        if os.environ["TEST_TRANSPORT_FAILURE"] == "true":
            raise ValueError("isolated API transport failure")
        return _original(*args, **kwargs)
    setattr(api, name, counted)
with patch.object(feature_coverage_github, "Api", return_value=api):
    raise SystemExit(ci_reuse.main())
''')
            contract = root / "contract.json"
            contract.write_text("{}\n")
            contract_digest = hashlib.sha256(contract.read_bytes()).hexdigest()
            for index in range(8):
                capsule = root / f"capsule-{index}"
                capsule.mkdir()
                manifest = capsule / "manifest.json"
                manifest.write_text('[{"fixture":"paired frame"}]\n')
                value = second_reference if index % 2 else reference
                # Equivalent JSON member ordering must not cause another authentication.
                value = dict(reversed(list(value.items()))) if index % 2 else value
                proof = {"source_run_id": value["source"]["run_id"], "runtime_source": value,
                         "manifest_sha256": hashlib.sha256(manifest.read_bytes()).hexdigest(),
                         "frame_count": 1, "scenario_contract_sha256": contract_digest,
                         "compatibility_contract_sha256": contract_digest}
                (capsule / "proof.json").write_text(json.dumps(proof))
                if bad_manifest and index == 1:
                    manifest.write_text('[{"fixture":"tampered frame"}]\n')
            harness = '''
set -euo pipefail
trap 'printf "%s" "${authenticated_runtime_sources-}" > "$TEST_ROOT/cache.json"' EXIT
python3() {
  if [[ "$1" == scripts/ci/ci_reuse.py ]]; then
    shift
    command "$TEST_PYTHON" "$TEST_ROOT/transport.py" "$@"
  else
    command "$TEST_PYTHON" "$@"
  fi
}
github_api_retry() {
  printf '%s\\n' "$1" >> "$TEST_ROOT/final-requests"
  [[ "$1" == "repos/$GITHUB_REPOSITORY/branches/master" && "$2" == --jq && "$3" == .commit.sha ]]
  printf '%s\\n' "$TEST_FINAL_MASTER"
  [[ "$TEST_FINAL_TRANSPORT_FAILURE" == false ]]
}
lanes_root="$TEST_ROOT/lanes"
api_cache="$TEST_ROOT/api-cache"
source_contract="$TEST_ROOT/contract.json"
source_compatibility_contract="$source_contract"
''' + initialization + '''
for index in {0..7}; do
  proof="$TEST_ROOT/capsule-$index/proof.json"
  manifest="$TEST_ROOT/capsule-$index/manifest.json"
''' + authentication + '''
  printf '%s\\n' "$index" >> "$TEST_ROOT/completed-lanes"
done
''' + after_loop
            result = subprocess.run([self.bash, "-c", harness], capture_output=True, text=True, timeout=30,
                env=os.environ | {"TEST_ROOT": str(root), "RUNNER_TEMP": str(root),
                    "BASH_ENV": "",
                    "TEST_PYTHON": sys.executable, "TEST_CI_TESTS": str(ROOT / "scripts/ci/tests"),
                    "TEST_TRANSPORT_FAILURE": str(transport_failure).lower(),
                    "TEST_FINAL_TRANSPORT_FAILURE": str(final_transport_failure).lower(),
                    "TEST_FINAL_MASTER": "f" * 40 if master_changed else api.covered,
                    "GITHUB_SHA": api.covered, "source_sha": api.covered,
                    "GITHUB_REPOSITORY": REPOSITORY})
            def lines(name):
                path = root / name
                return path.read_text().splitlines() if path.exists() else []
            return {"returncode": result.returncode, "output": result.stdout + result.stderr,
                    "verifications": lines("verifications"), "requests": lines("transport-requests"),
                    "completed": lines("completed-lanes"), "final_requests": lines("final-requests"),
                    "cache": json.loads((root / "cache.json").read_text() or "{}")}

    def test_eight_equal_references_authenticate_once_and_recheck_master_at_end(self) -> None:
        result = self.prepare()
        self.assertEqual(0, result["returncode"], result["output"])
        self.assertEqual(1, len(result["verifications"]))
        self.assertEqual(9, len(result["requests"]))
        self.assertEqual(8, len(result["completed"]))
        self.assertEqual(1, len(result["final_requests"]))

    def test_distinct_valid_reference_gets_its_own_authentication(self) -> None:
        result = self.prepare(distinct_reference=True)
        self.assertEqual(0, result["returncode"], result["output"])
        self.assertEqual(2, len(result["verifications"]))
        self.assertEqual(18, len(result["requests"]))
        self.assertEqual(8, len(result["completed"]))

    def test_different_forged_reference_cannot_use_an_authenticated_entry(self) -> None:
        result = self.prepare(invalid_reference=True)
        self.assertNotEqual(0, result["returncode"])
        self.assertEqual(2, len(result["verifications"]))
        self.assertEqual(["0"], result["completed"])
        self.assertEqual(1, len(result["cache"]))

    def test_failed_transport_aborts_before_caching_or_examining_another_capsule(self) -> None:
        result = self.prepare(transport_failure=True)
        self.assertNotEqual(0, result["returncode"])
        self.assertEqual(1, len(result["verifications"]))
        self.assertEqual(["run"], result["requests"])
        self.assertEqual([], result["completed"])
        self.assertEqual({}, result["cache"])
        self.assertEqual([], result["final_requests"])

    def test_cached_reference_does_not_bypass_each_capsule_manifest(self) -> None:
        result = self.prepare(bad_manifest=True)
        self.assertNotEqual(0, result["returncode"])
        self.assertEqual(1, len(result["verifications"]))
        self.assertEqual(["0"], result["completed"])
        self.assertEqual([], result["final_requests"])

    def test_master_advance_after_first_verification_blocks_batch_publication(self) -> None:
        result = self.prepare(master_changed=True)
        self.assertNotEqual(0, result["returncode"])
        self.assertEqual(1, len(result["verifications"]))
        self.assertEqual(8, len(result["completed"]))
        self.assertEqual(1, len(result["final_requests"]))

    def test_final_transport_failure_is_not_masked_by_matching_partial_output(self) -> None:
        result = self.prepare(final_transport_failure=True)
        self.assertNotEqual(0, result["returncode"])
        self.assertEqual(1, len(result["verifications"]))
        self.assertEqual(8, len(result["completed"]))
        self.assertEqual(1, len(result["final_requests"]))


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
