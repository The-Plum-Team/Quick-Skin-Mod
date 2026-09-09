from __future__ import annotations

import json
import os
import shlex
import shutil
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

from scripts.ci.tests.test_workflow_security import ROOT, job_block, step_script


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
            "value = responses[route]\n"
            "if isinstance(value, dict) and '__archive' in value:\n"
            "    sys.stdout.buffer.write((root / value['__archive']).read_bytes())\n"
            "else: print(json.dumps(value))\n"
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

    def guard(self, bundle="mc1.20.1", *, legacy=False, overrides=None, marker=None, marker_key=None,
              wave_block=None, archive_path="visual-review-wave-block.json", fresh_workspace=False):
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
        if wave_block is not None:
            archive = self.folder / "block.zip"
            with zipfile.ZipFile(archive, "w") as stream:
                stream.writestr(archive_path, json.dumps(wave_block))
            marker_name = "visual-review-wave-block-" + "a" * 40
            responses[prefix + f"artifacts?name={marker_name}&per_page=100"] = [{
                "artifacts": [{"id": 99, "name": marker_name, "expired": False,
                    "size_in_bytes": archive.stat().st_size,
                    "workflow_run": {"id": 99, "head_sha": "a" * 40}}]
            }]
            responses[prefix + "runs/99"] = {
                **owner, "id": 99, "conclusion": "failure",
                "path": ".github/workflows/visual-review-drain.yml",
            }
            responses[prefix + "artifacts/99/zip"] = {"__archive": archive.name}
        (self.folder / "responses.json").write_text(json.dumps(responses))
        output = Path(env["GITHUB_OUTPUT"])
        output.write_text("")
        requests = self.folder / "requests.jsonl"
        requests.write_text("")
        workspace = ROOT
        if fresh_workspace:
            workspace = self.folder / "workspace"
            workspace.mkdir()
            # Materialize only files requested by actual unconditional pre-guard checkouts.
            # The old ordering leaves this fresh runner empty and reproduces the live Errno2.
            prefix_steps = job_block("visual-review-drain.yml", "review").split(
                "      - name: Revalidate the artifact-scoped queue entry", 1)[0]
            for step in prefix_steps.split("      - name: ")[1:]:
                if "uses: actions/checkout@" not in step:
                    continue
                self.assertNotIn("        if:", step)
                self.assertIn("ref: ${{ github.sha }}", step)
                self.assertIn("persist-credentials: false", step)
                self.assertIn("sparse-checkout-cone-mode: false", step)
                sparse = step.split("          sparse-checkout: |\n", 1)[1]
                files = [line.strip() for line in sparse.splitlines()
                         if line.startswith("            ")]
                self.assertEqual(["scripts/ci/bounded_zip.py"], files)
                for name in files:
                    destination = workspace / name
                    destination.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copyfile(ROOT / name, destination)
        result = subprocess.run(["bash", "-c", self.script], env=env, cwd=workspace,
                                capture_output=True, text=True)
        return result, output.read_text(), requests.read_text()

    def block(self, **changes):
        return {"schema_version": 1, "kind": "quick-skin-visual-review-wave-block",
                "generation_sha": "a" * 40, "implementation_sha": "a" * 40,
                "source_run_id": 55, "source_sha": "a" * 40,
                "report_sha256": "c" * 64, **changes}

    def test_fresh_runner_authenticates_a_real_block_before_any_capsule_or_model_work(self):
        result, output, requests = self.guard(wave_block=self.block(), fresh_workspace=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("wave_blocked=true", output)
        self.assertIn("reviewable=false", output)
        self.assertIn("/artifacts/99/zip", requests)
        self.assertNotIn("/artifacts/77", requests)
        self.assertNotIn("visual-review-55--", requests)

    def test_fresh_runner_rejects_a_traversal_block_without_extracting_outside_its_root(self):
        result, output, requests = self.guard(wave_block=self.block(), fresh_workspace=True,
                                              archive_path="../escaped.json")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("unsafe archive path", result.stderr)
        self.assertNotIn("wave_blocked=true", output)
        self.assertNotIn("/artifacts/77", requests)
        self.assertFalse((self.folder / "escaped.json").exists())

    def test_fresh_runner_rejects_a_block_payload_for_another_generation(self):
        result, output, requests = self.guard(
            wave_block=self.block(generation_sha="d" * 40), fresh_workspace=True)
        self.assertNotEqual(0, result.returncode)
        self.assertNotIn("can't open file", result.stderr)
        self.assertNotIn("wave_blocked=true", output)
        self.assertNotIn("/artifacts/77", requests)

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
