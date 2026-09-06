from __future__ import annotations

import json
import os
import shlex
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from scripts.ci.tests.test_workflow_security import ROOT, step_script


class VisualReviewGuardTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.folder = Path(self.temporary.name)
        self.tools = self.folder / "tools"
        self.tools.mkdir()
        gh = self.tools / "gh"
        gh.write_text(
            "#!/bin/sh\nexec " + shlex.quote(sys.executable) + " "
            + shlex.quote(str(self.folder / "api.py")) + ' "$@"\n'
        )
        gh.chmod(0o755)
        (self.folder / "api.py").write_text(
            "import json, os, pathlib, sys\n"
            "root = pathlib.Path(os.environ['RUNNER_TEMP'])\n"
            "route = sys.argv[-1]\n"
            "with (root / 'requests.jsonl').open('a') as log:\n"
            "    log.write(json.dumps(route) + '\\n')\n"
            "responses = json.loads((root / 'responses.json').read_text())\n"
            "if route not in responses: raise SystemExit('Unexpected API route: ' + route)\n"
            "print(json.dumps(responses[route]))\n"
        )
        self.script = step_script(
            "visual-review-drain.yml", "review", "Revalidate the artifact-scoped queue entry"
        )
        jq = shutil.which("jq")
        self.assertIsNotNone(jq)
        self.environment = {
            "PATH": os.pathsep.join((str(self.tools), str(Path(jq).parent), os.defpath)),
            "RUNNER_TEMP": str(self.folder),
            "GITHUB_OUTPUT": str(self.folder / "output"),
            "GITHUB_REPOSITORY": "example/quick-skin",
            "GITHUB_RUN_ID": "123",
            "SOURCE_RUN_ID": "55",
            "GENERATION_SHA": "a" * 40,
            "IMPLEMENTATION_SHA": "a" * 40,
            "ARTIFACT_ID": "77",
            "ARTIFACT_RUN_ID": "88",
            "ARTIFACT_DIGEST": "b" * 64,
            "ARTIFACT_SIZE": "1024",
        }

    def guard(self, bundle="mc1.20.1", *, legacy=False, overrides=None, marker=None, marker_key=None):
        review_key = "55" + (f"--{bundle}" if bundle else "")
        name = "visual-review-input-55"
        if not legacy:
            name += "-" + "a" * 40 + (f"--{bundle}" if bundle else "")
        env = {**self.environment, "BUNDLE_KEY": bundle, "REVIEW_KEY": review_key,
               "ARTIFACT_NAME": name, **(overrides or {})}
        prefix = "repos/example/quick-skin/actions/"
        owner = {"id": 88, "status": "completed", "conclusion": "success",
                 "event": "repository_dispatch", "head_branch": "master", "head_sha": "a" * 40,
                 "path": ".github/workflows/visual-review.yml",
                 "head_repository": {"full_name": "example/quick-skin"}}
        responses = {
            prefix + "artifacts/77": {
                "id": 77, "name": name, "digest": "sha256:" + "b" * 64,
                "size_in_bytes": 1024, "expired": False,
                "workflow_run": {"id": 88, "head_branch": "master", "head_sha": "a" * 40},
            },
            prefix + "runs/88": owner,
        }
        names = ["visual-review-wave-block-" + "a" * 40]
        for key in {"55", review_key, "55--mc1.21.1"}:
            names.extend((f"visual-review-{key}", f"visual-review-attempt-{key}"))
        for artifact_name in names:
            responses[prefix + f"artifacts?name={artifact_name}&per_page=100"] = [{"artifacts": []}]
        if marker is not None:
            marker_name = f"{marker}-{marker_key or review_key}"
            responses[prefix + f"artifacts?name={marker_name}&per_page=100"] = [{
                "artifacts": [{"name": marker_name, "expired": False,
                               "workflow_run": {"id": 99}, "created_at": "2099-01-01T00:00:00Z"}]
            }]
            responses[prefix + "runs/99"] = {
                **owner, "id": 99, "conclusion": "failure",
                "path": ".github/workflows/visual-review-drain.yml",
            }
        (self.folder / "responses.json").write_text(json.dumps(responses))
        output = Path(env["GITHUB_OUTPUT"])
        output.write_text("")
        requests = self.folder / "requests.jsonl"
        requests.write_text("")
        result = subprocess.run(["bash", "-c", self.script], env=env, cwd=ROOT,
                                capture_output=True, text=True)
        return result, output.read_text(), requests.read_text()

    def test_admits_every_shared_target_through_the_actual_guard(self) -> None:
        matrix = json.loads((ROOT / "release/release-matrix.json").read_text())
        for version in sorted({row["artifact_version"] for row in matrix["artifacts"]}):
            with self.subTest(version=version):
                result, output, requests = self.guard(f"mc{version}")
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertNotIn("reviewable=false", output)
                self.assertIn("/artifacts/77", requests)
                self.assertIn("/runs/88", requests)

    def test_keeps_legacy_generation_and_ungrouped_capsules(self) -> None:
        for legacy in (True, False):
            with self.subTest(legacy=legacy):
                result, output, requests = self.guard("", legacy=legacy)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertNotIn("reviewable=false", output)
                self.assertIn("/artifacts/77", requests)

    def test_rejects_mismatched_queue_identity_before_api_access(self) -> None:
        mutations = {
            "ARTIFACT_NAME": "visual-review-input-55-" + "a" * 40 + "--mc1.21.1",
            "GENERATION_SHA": "c" * 40,
            "SOURCE_RUN_ID": "56",
            "BUNDLE_KEY": "mc1.20.1/../mc1.21.1",
            "REVIEW_KEY": "55--mc1.21.1",
        }
        for field, value in mutations.items():
            with self.subTest(field=field):
                result, _, requests = self.guard(overrides={field: value})
                self.assertNotEqual(0, result.returncode)
                self.assertEqual("", requests)

    def test_only_the_selected_targets_report_or_attempt_suppresses_review(self) -> None:
        for marker, flag in (("visual-review", "already_reviewed=true"),
                             ("visual-review-attempt", "cooling=true")):
            for marker_key in ("55--mc1.20.1", "55--mc1.21.1", "55"):
                with self.subTest(marker=marker, marker_key=marker_key):
                    result, output, requests = self.guard(marker=marker, marker_key=marker_key)
                    self.assertEqual(0, result.returncode, result.stderr)
                    if marker_key == "55--mc1.20.1":
                        self.assertIn(flag, output)
                        self.assertIn("reviewable=false", output)
                        self.assertNotIn("/artifacts/77", requests)
                    else:
                        self.assertNotIn(flag, output)
                        self.assertIn("/artifacts/77", requests)


if __name__ == "__main__":
    unittest.main()
