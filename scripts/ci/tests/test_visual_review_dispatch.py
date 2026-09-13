from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from test_workflow_security import ROOT, job_block, step_script

sys.path.insert(0, str(ROOT / "scripts/pages"))
from evidence_target import inventory


@unittest.skipUnless(shutil.which("jq") and shutil.which("bash"), "workflow shell requires bash and jq")
class VisualReviewDispatchTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="qsm-dispatch-test-")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.sha = "a" * 40
        self.targets = [{"bundle_key": row["bundle_key"], "minecraft_target": row["minecraft_target"]}
                        for row in inventory()["include"]]
        self.artifacts = [{"id": 1000 + index,
            "name": f"visual-review-input-55-{self.sha}--{target['bundle_key']}",
            "expired": False, "digest": "sha256:" + "b" * 64, "size_in_bytes": 1024,
            "workflow_run": {"id": 99, "head_sha": self.sha, "head_branch": "master"}}
            for index, target in enumerate(self.targets)]
        self.config = {"pages": [{"artifacts": self.artifacts}], "errors": {}, "inventory_error": ""}
        self.log = self.root / "calls.jsonl"
        gh = self.root / "gh"
        gh.write_text(f"#!{sys.executable}\n" + '''
import json, os, sys
from pathlib import Path
root = Path(os.environ["DISPATCH_FIXTURE"])
config = json.loads((root / "config.json").read_text())
log = root / "calls.jsonl"
args = sys.argv[1:]
if args == ["api", "--paginate", "--slurp", "repos/The-Plum-Team/Quick-Skin-Mod/actions/runs/99/artifacts?per_page=100"]:
    with log.open("a") as stream:
        stream.write(json.dumps({"method": "GET"}) + "\\n")
    if config["inventory_error"]:
        print(config["inventory_error"], file=sys.stderr)
        sys.exit(1)
    print(json.dumps(config["pages"]))
elif args[:4] == ["api", "--method", "POST", "repos/The-Plum-Team/Quick-Skin-Mod/dispatches"]:
    payload = json.loads(Path(args[args.index("--input") + 1]).read_text())
    key = payload["client_payload"]["artifact_id"]
    history = [json.loads(line) for line in log.read_text().splitlines()]
    count = sum(item.get("artifact_id") == key for item in history)
    with log.open("a") as stream:
        stream.write(json.dumps({"method": "POST", "artifact_id": key, "payload": payload}) + "\\n")
    errors = config["errors"].get(key, [])
    if count < len(errors) and errors[count]:
        print(errors[count], file=sys.stderr)
        sys.exit(1)
else:
    raise AssertionError(args)
''')
        gh.chmod(0o755)
        sleep = self.root / "sleep"
        sleep.write_text("#!/bin/sh\nexit 0\n")
        sleep.chmod(0o755)
        self.env = {**os.environ, "PATH": str(self.root) + os.pathsep + os.environ["PATH"],
            "DISPATCH_FIXTURE": str(self.root), "RUNNER_TEMP": str(self.root),
            "GITHUB_REF": "refs/heads/master", "GITHUB_SHA": self.sha, "GENERATION_SHA": self.sha,
            "SOURCE_RUN_ID": "55", "GITHUB_RUN_ID": "99", "GITHUB_REPOSITORY": "The-Plum-Team/Quick-Skin-Mod"}
        self.env.pop("BASH_ENV", None)
        self.env.pop("ENV", None)
        for key in list(self.env):
            if key.startswith("BASH_FUNC_gh"):
                self.env.pop(key)

    def execute(self, script=None):
        (self.root / "config.json").write_text(json.dumps(self.config))
        self.env["TARGET_MATRIX"] = json.dumps({"include": self.targets})
        script = script or step_script("visual-review.yml", "request-drain", "Request one protected queue drain")
        result = subprocess.run(["bash", "-c", script], env=self.env, text=True, capture_output=True, timeout=30)
        calls = [json.loads(line) for line in self.log.read_text().splitlines()] if self.log.exists() else []
        return result, calls

    def test_complete_matrix_reads_one_inventory_and_dispatches_every_exact_capsule(self):
        result, calls = self.execute()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(1, sum(call["method"] == "GET" for call in calls))
        self.assertEqual(len(self.targets), len(calls) - 1)
        for artifact, call in zip(self.artifacts, calls[1:]):
            self.assertEqual({"event_type": "visual-review-drain-requested", "client_payload": {
                "artifact_id": str(artifact["id"]), "artifact_name": artifact["name"],
                "generation_sha": self.sha}}, call["payload"])
        block = job_block("visual-review.yml", "request-drain")
        self.assertNotIn("strategy:", block)
        self.assertIn("      - curate\n", block)
        self.assertIn("needs.curate.result == 'failure'", block)
        self.assertIn("max-parallel: 3", job_block("visual-review.yml", "curate"))

    def test_missing_failed_sibling_and_one_failed_dispatch_do_not_suppress_other_targets(self):
        self.artifacts.pop(1)
        self.config["errors"]["1000"] = ["request failed (HTTP 500)"]
        result, calls = self.execute()
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(len(self.targets) - 1, sum(call["method"] == "POST" for call in calls))
        self.assertIn("missing=1 failed=1 deferred=0", result.stdout)
        self.assertEqual(str(self.artifacts[-1]["id"]), calls[-1]["artifact_id"])

    def test_duplicate_or_foreign_capsule_never_dispatches_but_siblings_progress(self):
        mutations = [{"expired": True}, {"digest": "invalid"}, {"size_in_bytes": 536870913},
            {"id": True}, {"workflow_run": {"id": 98, "head_sha": self.sha, "head_branch": "master"}},
            {"workflow_run": {"id": 99, "head_sha": "c" * 40, "head_branch": "master"}},
            {"workflow_run": {"id": 99, "head_sha": self.sha, "head_branch": "topic"}}]
        original = self.artifacts[0]
        for changes in mutations:
            with self.subTest(changes=changes):
                self.artifacts[0] = {**original, **changes}
                self.log.unlink(missing_ok=True)
                result, calls = self.execute()
                self.assertNotEqual(0, result.returncode)
                self.assertEqual(len(self.targets) - 1, len(calls) - 1)
                self.assertNotIn("1000", [call.get("artifact_id") for call in calls])
        self.artifacts[0] = original
        self.artifacts.append({**original, "id": 9999})
        self.log.unlink(missing_ok=True)
        result, calls = self.execute()
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(len(self.targets) - 1, len(calls) - 1)

    def test_secondary_limit_retries_exact_payload_and_primary_limit_leaves_pending_capsules(self):
        self.config["errors"]["1000"] = ["secondary rate limit (HTTP 429)", ""]
        result, calls = self.execute()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(calls[1], calls[2])
        self.assertEqual(len(self.targets) + 1, len(calls) - 1)
        self.log.unlink()
        self.config["errors"] = {"1000": ["API rate limit exceeded"]}
        result, calls = self.execute()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(2, len(calls))
        self.assertIn(f"deferred={len(self.targets)}", result.stdout)
        self.log.unlink()
        self.config["errors"] = {}
        result, calls = self.execute()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(len(self.targets), len(calls) - 1)

    def test_inventory_failure_or_bound_never_dispatches(self):
        self.config["inventory_error"] = "API unavailable"
        result, calls = self.execute()
        self.assertNotEqual(0, result.returncode)
        self.assertEqual([{"method": "GET"}], calls)
        self.log.unlink()
        self.config["inventory_error"] = ""
        self.config["pages"] *= 11
        result, calls = self.execute()
        self.assertNotEqual(0, result.returncode)
        self.assertEqual([{"method": "GET"}], calls)

    def test_historical_single_capsule_keeps_its_existing_name(self):
        self.targets = [{"bundle_key": "forge-and-fabric-1.20.1", "minecraft_target": ""}]
        self.artifacts[:] = [{**self.artifacts[0], "name": f"visual-review-input-55-{self.sha}"}]
        result, calls = self.execute()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(2, len(calls))

    def test_foreign_generation_or_duplicate_target_plan_stops_before_api_access(self):
        self.env["GITHUB_SHA"] = "c" * 40
        result, calls = self.execute()
        self.assertNotEqual(0, result.returncode)
        self.assertEqual([], calls)
        self.env["GITHUB_SHA"] = self.sha
        self.targets.append(self.targets[0])
        result, calls = self.execute()
        self.assertNotEqual(0, result.returncode)
        self.assertEqual([], calls)

    def test_target_row_generation_failure_cannot_report_success_without_dispatching(self):
        real_jq = shutil.which("jq")
        jq = self.root / "jq"
        jq.write_text(f"#!{sys.executable}\n" + f'''
import os, sys
args = sys.argv[1:]
if args[:2] == ["-r", ".[] | [.bundle_key, .minecraft_target] | @tsv"]:
    print("target row generation failed", file=sys.stderr)
    sys.exit(7)
os.execv({real_jq!r}, [{real_jq!r}, *args])
''')
        jq.chmod(0o755)
        result, calls = self.execute()
        self.assertEqual(7, result.returncode)
        self.assertIn("target row generation failed", result.stderr)
        self.assertNotIn("dispatch summary", result.stdout)
        self.assertEqual([], calls)


if __name__ == "__main__":
    unittest.main()
