from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path

from test_workflow_security import job_block, step_script


ROOT = Path(__file__).resolve().parents[3]
WORKFLOW = "build-gate.yml"
PARTITIONS = {"release": "policy-release", "ci": "policy-ci"}

# Exercise existing file-producing and Git-mutating fixtures in simultaneous worker
# processes. Each copied checkout and temporary root models a separate hosted job.
FIXTURE_WORKER = r"""
import json
import os
import sys
import tempfile
from pathlib import Path

kind = sys.argv[1]
sys.path.insert(0, str(Path('scripts', kind, 'tests').resolve()))
if kind == 'release':
    from test_verify_reproducibility import ReproducibilityTest
    fixture = ReproducibilityTest()
else:
    from test_version_port_merge import VersionPortMergeTest
    fixture = VersionPortMergeTest()
fixture.setUp()
try:
    repository = fixture.repository.resolve()
    assert repository.is_relative_to(Path(tempfile.gettempdir()).resolve())
    Path('same-checkout-file.txt').write_text(kind)
    collision = Path(tempfile.gettempdir(), 'same-temporary-file.txt')
    collision.write_text(kind)
    Path('ready.pending').write_text(json.dumps({'repository': str(repository)}))
    Path('ready.pending').replace('ready.json')
    assert sys.stdin.readline().strip() == 'continue'
    if kind == 'release':
        fixture.test_accepts_exact_second_build_bytes_for_production_and_harness()
        fixture.test_rejects_a_changed_second_build()
    else:
        fixture.test_probe_is_deterministic_and_always_restores_clean_target()
    assert Path('same-checkout-file.txt').read_text() == kind
    assert collision.read_text() == kind
    collision.unlink()
finally:
    fixture.tearDown()
assert not repository.exists()
"""


class PolicyIsolationTest(unittest.TestCase):
    def test_full_inventories_run_once_on_independent_pinned_jobs(self):
        workflow = (ROOT / ".github/workflows" / WORKFLOW).read_text()
        for suite, job in PARTITIONS.items():
            with self.subTest(suite=suite):
                block = job_block(WORKFLOW, job)
                command = (f"python scripts/ci/parallel_unittest.py -s scripts/{suite}/tests "
                           "-p 'test_*.py' -v")
                self.assertEqual(1, workflow.count(command))
                self.assertIn(command, block)
                # The fail-closed runner is the only executor of the suite, and it runs exactly
                # the start directory and pattern whose inventory the job records.
                self.assertNotIn("-m unittest", block)
                self.assertIn(f"loader.discover('scripts/{suite}/tests', pattern='test_*.py')", block)
                name = "release" if suite == "release" else "CI"
                script = step_script(WORKFLOW, job, f"Run the complete {name} policy suite")
                self.assertTrue(script.startswith("set -euo pipefail\n"), script)
                self.assertIn(f"{command} 2>&1 | tee \"$RUNNER_TEMP/{suite}-policy-tests.log\"", script)
                self.assertIn("needs: source\n", block)
                self.assertIn("needs.source.outputs.reused == 'false'", block)
                self.assertIn("runs-on: ubuntu-24.04", block)
                self.assertIn("ref: ${{ github.sha }}", block)
                self.assertIn("persist-credentials: false", block)
                self.assertIn("countTestCases()", block)
                self.assertIn("set -euo pipefail", block)
                self.assertNotIn("continue-on-error", block)
                self.assertNotIn("needs: policy", block)

    def test_final_gate_rejects_incomplete_partitions_and_only_accepts_authorized_reuse(self):
        gate = job_block(WORKFLOW, "build")
        self.assertIn("always() &&", gate)
        self.assertNotIn("!cancelled()", gate)
        script = step_script(WORKFLOW, "build", "Require the complete compilation and policy jobs")
        for reused in ("false", "true"):
            environment = {**os.environ, "SOURCE_RESULT": "success", "REUSED": reused,
                           **{name: "skipped" if reused == "true" else "success" for name in (
                               "COMPILE_RESULT", "POLICY_RESULT", "RELEASE_POLICY_RESULT", "CI_POLICY_RESULT")}}
            self.assertEqual(0, subprocess.run(["bash", "-c", script], env=environment,
                                               capture_output=True).returncode)
            for name in ("RELEASE_POLICY_RESULT", "CI_POLICY_RESULT"):
                invalid = ("failure", "cancelled", "", "success" if reused == "true" else "skipped")
                for result in invalid:
                    with self.subTest(reused=reused, partition=name, result=result):
                        completed = subprocess.run(["bash", "-c", script],
                            env={**environment, name: result}, capture_output=True)
                        self.assertNotEqual(0, completed.returncode)
                missing = {key: value for key, value in environment.items() if key != name}
                self.assertNotEqual(0, subprocess.run(["bash", "-c", script], env=missing,
                                                     capture_output=True).returncode)

    def test_real_release_files_and_ci_git_fixtures_remain_isolated_during_cleanup(self):
        with tempfile.TemporaryDirectory(prefix="qsm-policy-isolation-") as temporary:
            root = Path(temporary).resolve()
            workers = {}
            try:
                for suite in PARTITIONS:
                    checkout = root / suite / "checkout"
                    checkout.mkdir(parents=True)
                    scratch = root / suite / "temporary"
                    scratch.mkdir()
                    for directory in ("scripts", "e2e"):
                        shutil.copytree(ROOT / directory, checkout / directory,
                            ignore=shutil.ignore_patterns("__pycache__", "*.pyc"))
                    process = subprocess.Popen([sys.executable, "-c", FIXTURE_WORKER, suite],
                        cwd=checkout, env={**os.environ, "TMPDIR": str(scratch),
                                          "TMP": str(scratch), "TEMP": str(scratch)},
                        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
                    workers[suite] = (checkout, scratch, process)
                deadline = time.monotonic() + 30
                for checkout, _scratch, process in workers.values():
                    while not (checkout / "ready.json").exists():
                        if process.poll() is not None:
                            self.fail(process.communicate()[1])
                        self.assertLess(time.monotonic(), deadline, "real fixture setup timed out")
                        time.sleep(0.01)
                repositories = {suite: Path(json.loads((checkout / "ready.json").read_text())["repository"])
                                for suite, (checkout, _scratch, _process) in workers.items()}
                self.assertNotEqual(repositories["release"], repositories["ci"])
                self.assertTrue((repositories["release"] / "out/harness.jar").is_file())
                self.assertTrue((repositories["ci"] / ".git").is_dir())
                for suite in PARTITIONS:
                    checkout, scratch, process = workers[suite]
                    _stdout, stderr = process.communicate("continue\n", timeout=30)
                    self.assertEqual(0, process.returncode, stderr)
                    self.assertFalse(repositories[suite].exists())
                    self.assertFalse((scratch / "same-temporary-file.txt").exists())
                    self.assertEqual(suite, (checkout / "same-checkout-file.txt").read_text())
                    if suite == "release":
                        self.assertTrue(repositories["ci"].is_dir())
                        self.assertEqual("ci", (workers["ci"][1] / "same-temporary-file.txt").read_text())
            finally:
                for _checkout, _scratch, process in workers.values():
                    if process.poll() is None:
                        process.kill()
                    process.communicate()


if __name__ == "__main__":
    unittest.main()
