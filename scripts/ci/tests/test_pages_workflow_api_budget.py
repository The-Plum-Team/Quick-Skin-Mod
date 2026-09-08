from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from test_workflow_security import ROOT, step_script


SHA = "a" * 40
KEYS = sorted({f"mc{row['artifact_version']}"
               for row in json.loads((ROOT / "release/release-matrix.json").read_text())["artifacts"]})

FAKE_COMMAND = r'''
import json, os, pathlib, sys, urllib.parse
state = pathlib.Path(os.environ["PAGES_TEST_CALLS"])
calls = [json.loads(line) for line in state.read_text().splitlines()] if state.exists() else []
command = pathlib.Path(sys.argv[0]).name
arguments = sys.argv[1:]
calls.append([command, *arguments])
with state.open("a") as stream:
    stream.write(json.dumps(calls[-1]) + "\n")
sha = os.environ["GITHUB_SHA"]
keys = json.loads(os.environ["PAGES_TEST_KEYS"])
if command == "git":
    assert arguments == ["rev-parse", "HEAD"], arguments
    print(sha)
elif command == "python3":
    if arguments[0].endswith("evidence_target.py"):
        if "--validate-handoffs" in arguments:
            print("fixture handoffs")
        elif arguments[-1] == "source-branch":
            print("master")
        elif arguments[-1] == "keys":
            print(json.dumps(keys))
        else:
            raise AssertionError(arguments)
    elif arguments[0].endswith("evidence.py"):
        assert "validate" in arguments, arguments
    elif arguments[0].endswith("feature_pages.py"):
        assert "--verify-runtime-tree" in arguments, arguments
        if os.environ.get("PAGES_TEST_RUNTIME_FAILURE"):
            sys.exit(2)
    else:
        raise AssertionError(arguments)
elif command == "gh":
    endpoint = next(arg for arg in arguments if arg.startswith("repos/"))
    if "/branches/master" in endpoint:
        count = sum(row[0] == "gh" and any("/branches/master" in arg for arg in row) for row in calls)
        moved = int(os.environ.get("PAGES_TEST_MOVE_AT", "0"))
        print("b" * 40 if moved and count >= moved else sha)
    elif "/actions/workflows/on-demand-e2e.yml" in endpoint:
        print(123)
    elif "/actions/workflows/pages.yml" in endpoint:
        print(456)
    elif "/actions/workflows/123/runs" in endpoint:
        print(json.dumps({"total_count": 0, "workflow_runs": []}))
    elif "/actions/artifacts?" in endpoint:
        name = urllib.parse.parse_qs(urllib.parse.urlparse(endpoint).query)["name"][0]
        count = sum(row[0] == "gh" and any("/actions/artifacts?" in arg for arg in row) for row in calls)
        artifact_sha = "c" * 40 if count == 2 and os.environ.get("PAGES_TEST_FOREIGN_CACHE") else sha
        owner = 901 if count == 2 and os.environ.get("PAGES_TEST_SECOND_OWNER_FAILURE") else 900
        print(json.dumps([{"artifacts": [{"name": name, "expired": False,
            "workflow_run": {"id": owner, "head_branch": "master", "head_sha": artifact_sha}}]}]))
    elif endpoint.endswith(("/actions/runs/900", "/actions/runs/901")):
        owner = int(endpoint.rsplit("/", 1)[1])
        status = os.environ.get("PAGES_TEST_OWNER_FAILURE" if owner == 900 else "PAGES_TEST_SECOND_OWNER_FAILURE")
        if status:
            print("gh: API rate limit exceeded for installation (HTTP " + status + ")", file=sys.stderr)
            sys.exit(1)
        print(json.dumps({"id": owner, "workflow_id": 456, "status": "completed", "conclusion": "success",
            "event": "workflow_dispatch", "head_branch": "master", "head_sha": sha,
            "path": ".github/workflows/pages.yml", "head_repository": {"full_name": "owner/repo"}}))
    elif endpoint.endswith("/actions/runs/42"):
        print(json.dumps({"id": 42, "workflow_id": 123, "status": "completed", "conclusion": "success",
            "event": "workflow_dispatch", "head_branch": "master", "head_sha": sha,
            "path": ".github/workflows/on-demand-e2e.yml", "head_repository": {"full_name": "owner/repo"}}))
    elif "/actions/runs/42/artifacts" in endpoint:
        print(json.dumps([{"artifacts": []}]))
    else:
        raise AssertionError(endpoint)
else:
    raise AssertionError(command)
'''


class PagesWorkflowApiBudgetTest(unittest.TestCase):
    def run_step(self, job: str, *, overrides=None):
        step = ("Discover matrix targets and validate the wake-up event" if job == "discover"
                else "Recheck shared source immediately before rendering")
        script = step_script("pages.yml", job, step)
        jq = shutil.which("jq")
        if jq is None:
            self.skipTest("workflow shell fixtures require jq")
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            commands = directory / "bin"
            commands.mkdir()
            for name in ("gh", "git", "python3"):
                command = commands / name
                command.write_text(f"#!{sys.executable}\n" + FAKE_COMMAND)
                command.chmod(0o755)
            (commands / "jq").symlink_to(jq)
            helper = directory / "scripts/ci/github_api_retry.sh"
            helper.parent.mkdir(parents=True)
            shutil.copyfile(ROOT / "scripts/ci/github_api_retry.sh", helper)
            for key in KEYS:
                target = directory / "public-evidence" / key
                target.mkdir(parents=True)
                (target / "manifest.json").write_text(json.dumps({"provenance": {
                    "target": {"sha": SHA}, "coverage_sha": SHA}}))
            output, calls = directory / "output", directory / "calls"
            # Explicit environment excludes real credentials, inherited shell hooks and gh functions.
            environment = {"PATH": f"{commands}{os.pathsep}{os.defpath}",
                "GH_CONFIG_DIR": str(directory / "gh-config"), "GITHUB_API_RETRY_ATTEMPTS": "1",
                "GITHUB_REF": "refs/heads/master", "GITHUB_SHA": SHA, "GITHUB_REPOSITORY": "owner/repo",
                "GITHUB_OUTPUT": str(output), "RUNNER_TEMP": str(directory),
                "DISPATCH_OPERATION": "deploy", "DISPATCH_RUN_ID": "42",
                "DISPATCH_BRANCH": "master", "DISPATCH_SHA": SHA,
                "SOURCE_BRANCH": "master", "SOURCE_SHA": SHA,
                "PAGES_TEST_CALLS": str(calls), "PAGES_TEST_KEYS": json.dumps(KEYS), **(overrides or {})}
            result = subprocess.run(["bash", "-c", script], cwd=directory, env=environment,
                text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30, check=False)
            return result, [json.loads(line) for line in calls.read_text().splitlines()], \
                output.read_text() if output.exists() else ""

    @staticmethod
    def requests(calls, fragment):
        return [row for row in calls if row[0] == "gh" and any(fragment in arg for arg in row[1:])]

    def test_duplicate_deploy_queries_each_name_but_reads_one_validated_owner(self):
        result, calls, output = self.run_step("discover")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(len(KEYS), len(self.requests(calls, "/actions/artifacts?")))
        self.assertEqual(1, len(self.requests(calls, "/actions/runs/900")))
        self.assertEqual(3, len(self.requests(calls, "/branches/master")))
        self.assertIn("Every Minecraft target already belongs", result.stdout)
        self.assertNotIn("eligible=true", output)

    def test_quota_failure_aborts_discovery_without_additional_candidates_or_fanout(self):
        for status in ("403", "429"):
            with self.subTest(status=status):
                result, calls, output = self.run_step("discover", overrides={"PAGES_TEST_OWNER_FAILURE": status})
                self.assertNotEqual(0, result.returncode)
                self.assertIn("API rate limit exceeded", result.stderr)
                self.assertEqual(1, len(self.requests(calls, "/actions/runs/900")))
                self.assertEqual(1, len(self.requests(calls, "/actions/artifacts?")))
                self.assertEqual([], self.requests(calls, "/actions/workflows/123/runs"))
                self.assertNotIn("eligible=true", output)

    def test_memoized_owner_does_not_authenticate_another_artifact_sha(self):
        result, calls, output = self.run_step("discover", overrides={"PAGES_TEST_FOREIGN_CACHE": "1"})
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(1, len(self.requests(calls, "/actions/runs/900")))
        self.assertEqual(2, len(self.requests(calls, "/actions/artifacts?")))
        self.assertNotIn("Every Minecraft target already belongs", result.stdout)
        self.assertIn("eligible=true", output)

    def test_later_owner_api_failure_does_not_become_a_cache_miss(self):
        result, calls, output = self.run_step("discover", overrides={"PAGES_TEST_SECOND_OWNER_FAILURE": "403"})
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(1, len(self.requests(calls, "/actions/runs/900")))
        self.assertEqual(1, len(self.requests(calls, "/actions/runs/901")))
        self.assertEqual(2, len(self.requests(calls, "/actions/artifacts?")))
        self.assertEqual([], self.requests(calls, "/actions/workflows/123/runs"))
        self.assertNotIn("eligible=true", output)

    def test_source_change_after_cache_inventory_never_accepts_its_snapshot(self):
        result, calls, output = self.run_step("discover", overrides={"PAGES_TEST_MOVE_AT": "3"})
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(len(KEYS), len(self.requests(calls, "/actions/artifacts?")))
        self.assertIn("advanced during cache discovery", result.stdout)
        self.assertNotIn("eligible=true", output)

    def test_build_brackets_all_manifest_checks_with_two_live_head_requests(self):
        result, calls, _output = self.run_step("build")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(2, len(self.requests(calls, "/branches/master")))
        validations = [index for index, row in enumerate(calls)
                       if row[0] == "python3" and row[1].endswith("/evidence.py")]
        self.assertEqual(len(KEYS), len(validations))
        heads = [index for index, row in enumerate(calls) if row in self.requests(calls, "/branches/master")]
        self.assertLess(heads[0], min(validations))
        self.assertGreater(heads[-1], max(validations))
        self.assertIn("--verify-runtime-tree", calls[-1])

    def test_build_stops_before_runtime_admission_when_either_head_check_changes(self):
        for boundary in ("1", "2"):
            with self.subTest(boundary=boundary):
                result, calls, _output = self.run_step("build", overrides={"PAGES_TEST_MOVE_AT": boundary})
                self.assertNotEqual(0, result.returncode)
                self.assertFalse(any("--verify-runtime-tree" in row for row in calls))

    def test_runtime_admission_failure_remains_a_failed_build_step(self):
        result, _calls, _output = self.run_step("build", overrides={"PAGES_TEST_RUNTIME_FAILURE": "1"})
        self.assertEqual(2, result.returncode)


if __name__ == "__main__":
    unittest.main()
