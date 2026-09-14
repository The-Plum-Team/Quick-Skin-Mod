from __future__ import annotations

from contextlib import contextmanager, redirect_stderr, redirect_stdout
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import MagicMock, patch

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts/ci"))

import feature_coverage_github as publisher
import feature_review as review
from test_workflow_security import job_block, step_script


class GitHubGetFailureTest(unittest.TestCase):
    @contextmanager
    def transport(self, *, raw=b"private-response", returncode=1, timeout=False, watchdog=False):
        process = MagicMock()
        process.stdout = io.BytesIO(raw)
        process.poll.return_value = None if timeout else returncode
        process.wait.side_effect = ([subprocess.TimeoutExpired("private-command", 5), 0]
                                    if timeout else None)
        process.wait.return_value = returncode
        timer = MagicMock()
        if watchdog:
            timer.start.side_effect = process.kill
        with patch.object(publisher.subprocess, "Popen", return_value=process) as spawn, \
             patch.object(publisher.threading, "Timer", return_value=timer) as timer_factory:
            try:
                yield process, spawn
            finally:
                timer_factory.assert_called_once_with(60, process.kill)
                timer.start.assert_called_once_with()
                timer.cancel.assert_called_once_with()
                self.assertTrue(process.stdout.closed)
                spawn.assert_called_once_with(["gh", "api", "--method", "GET", "repos/example/project/test"],
                    stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)

    def test_nonzero_and_watchdog_are_typed_without_retry_or_response_text(self):
        for returncode, watchdog in ((1, False), (-9, True)):
            with self.subTest(returncode=returncode), self.transport(returncode=returncode, watchdog=watchdog) as (process, _):
                stdout, stderr = io.StringIO(), io.StringIO()
                with redirect_stdout(stdout), redirect_stderr(stderr), \
                     self.assertRaises(publisher.GitHubGetUnavailable) as caught:
                    publisher._get("repos/example/project/test", maximum=32)
                self.assertEqual("bounded GitHub GET failed or timed out", str(caught.exception))
                self.assertEqual(("", ""), (stdout.getvalue(), stderr.getvalue()))
                self.assertEqual(2, process.wait.call_count)  # Completion plus final reap, not another GET.
                self.assertEqual(int(watchdog), process.kill.call_count)

    def test_wait_timeout_is_typed_and_process_is_killed_and_reaped(self):
        with self.transport(timeout=True) as (process, _):
            with self.assertRaises(publisher.GitHubGetUnavailable) as caught:
                publisher._get("repos/example/project/test", maximum=32)
            self.assertNotIn("private-command", str(caught.exception))
            process.kill.assert_called_once_with()
            self.assertEqual(2, process.wait.call_count)

    def test_oversize_is_not_transport_unavailability_even_when_process_fails(self):
        with self.transport(raw=b"x" * 33) as (process, _):
            with self.assertRaises(publisher.coverage.CoverageError) as caught:
                publisher._get("repos/example/project/test", maximum=32)
            self.assertNotIsInstance(caught.exception, publisher.GitHubGetUnavailable)
            process.kill.assert_called_once_with()
            process.wait.assert_called_once_with(timeout=5)

    def test_success_returns_exact_bounded_bytes(self):
        with self.transport(raw=b"x" * 32, returncode=0) as (process, _):
            self.assertEqual(b"x" * 32, publisher._get("repos/example/project/test", maximum=32))
            process.kill.assert_not_called()

    def test_malformed_json_and_archive_digest_remain_integrity_errors(self):
        api = publisher.Api("example/project")
        for raw in (b'{"id":1,"id":2}', b'{"id":NaN}', b'not-json'):
            with self.subTest(raw=raw), patch.object(publisher, "_get", return_value=raw), \
                 self.assertRaises(publisher.coverage.CoverageError) as caught:
                api.json("actions/runs/55")
            self.assertNotIsInstance(caught.exception, publisher.GitHubGetUnavailable)
        with tempfile.TemporaryDirectory() as temporary, patch.object(publisher, "_get", return_value=b"bad"), \
             self.assertRaises(publisher.coverage.CoverageError) as caught:
            api.download({"id": 55, "size_in_bytes": 3, "digest": "sha256:" + "a" * 64}, Path(temporary) / "archive.zip")
        self.assertNotIsInstance(caught.exception, publisher.GitHubGetUnavailable)


class ProofFailureBridgeTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.folder = Path(temporary.name)
        self.output = self.folder / "verifier-output"

    def invoke(self, error, *, plan=False, output=True):
        arguments = ["feature_review.py", "--github-repository", "example/project", "--repository", str(ROOT),
                     "--source-sha", "a" * 40, "--source-run-id", "55"]
        arguments += (["--plan"] if plan else ["--bundle-key", "mc1.20.1", "--verify-proof",
                      str(self.folder / "proof.json"), "--manifest", str(self.folder / "manifest.json")])
        if output:
            arguments += ["--github-output", str(self.output)]
        stdout, stderr = io.StringIO(), io.StringIO()
        with patch.object(sys, "argv", arguments), patch.object(review, "verify", side_effect=error, return_value=None), \
             patch.object(review, "authenticate_full", side_effect=error), \
             patch.object(publisher, "_get", side_effect=AssertionError("no live transport")), \
             redirect_stdout(stdout), redirect_stderr(stderr):
            if error is None:
                status = review.main()
            else:
                with self.assertRaises(SystemExit) as stopped:
                    review.main()
                status = stopped.exception.code
        return status, stdout.getvalue(), stderr.getvalue()

    def test_only_typed_verification_failure_emits_the_fixed_retention_output(self):
        status, stdout, stderr = self.invoke(publisher.GitHubGetUnavailable("private-response and private-command"))
        self.assertEqual((2, ""), (status, stdout))
        self.assertNotIn("private-", stderr)
        self.assertEqual("proof_transport_unavailable=true\n", self.output.read_text())
        self.assertEqual([self.output], list(self.folder.iterdir()))

    def test_same_text_and_other_integrity_failures_never_emit_the_signal(self):
        for error in (ValueError("bounded GitHub GET failed or timed out"),
                      publisher.coverage.CoverageError("bounded GitHub GET failed or timed out"),
                      review.ci_reuse.ReuseError("original PR execution is stale, foreign or unsuccessful"),
                      publisher.coverage.CoverageError("selected curation proof has a foreign source, scope or manifest")):
            with self.subTest(error=type(error).__name__):
                self.assertEqual(2, self.invoke(error)[0])
                self.assertFalse(self.output.exists())

    def test_planning_or_missing_output_cannot_emit_a_verification_signal(self):
        for plan, output in ((True, True), (False, False)):
            with self.subTest(plan=plan, output=output):
                self.assertEqual(2, self.invoke(publisher.GitHubGetUnavailable("unavailable"), plan=plan, output=output)[0])
                self.assertFalse(self.output.exists())

    def test_success_keeps_existing_outputs_without_a_retention_signal(self):
        status, stdout, stderr = self.invoke(None)
        self.assertEqual((0, ""), (status, stderr))
        self.assertEqual({"selected": False, "selection_sha256": ""}, json.loads(stdout))
        self.assertEqual("selected=false\nselection_sha256=\n", self.output.read_text())

    def test_output_write_failure_never_returns_success(self):
        self.output.mkdir()
        with self.assertRaises(OSError):
            self.invoke(publisher.GitHubGetUnavailable("unavailable"))

    def classify(self, *, signal="", outcome="failure", existing=None):
        capsule = self.folder / "visual-review-capsule"
        capsule.mkdir(exist_ok=True)
        failure = capsule / "visual-review-failure.json"
        if existing is not None:
            failure.write_text(json.dumps(existing))
        output = self.folder / "classifier-output"
        script = step_script("visual-review-drain.yml", "review", "Classify a failed attempt without provider text")
        for expression, value in (("steps.check.outputs.review_complete", ""), ("steps.capsule.outcome", outcome),
                                  ("steps.capsule.outputs.proof_transport_unavailable", signal)):
            script = script.replace("${{ " + expression + " }}", value)
        self.assertNotIn("${{", script)
        jq = shutil.which("jq")
        self.assertIsNotNone(jq)
        result = subprocess.run(["bash", "-c", "gh() { return 97; }\n" + script], cwd=self.folder,
            env={"PATH": os.pathsep.join((str(Path(jq).parent), os.defpath)),
                 "RUNNER_TEMP": str(self.folder), "GITHUB_OUTPUT": str(output)}, capture_output=True, text=True, timeout=10)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("", result.stdout)
        return json.loads(failure.read_text()), dict(line.split("=", 1) for line in output.read_text().splitlines())

    def cleanup_admitted(self, classification):
        block = job_block("visual-review-drain.yml", "cleanup")
        condition = block.split("    if: >-\n", 1)[1].split("    runs-on:", 1)[0].strip()
        self.assertTrue(condition.startswith("always() &&"))
        condition = condition.removeprefix("always() &&")
        values = {"needs.select.outputs.eligible": "true", "needs.review.outputs.review_complete": "false",
                  "needs.review.outputs.already_reviewed": "false", "needs.review.outputs.capsule_missing": "false",
                  "needs.review.outputs.failure_category": classification["category"],
                  "needs.review.outputs.failure_transient": str(classification["transient"]).lower()}
        for expression, value in values.items():
            condition = condition.replace(expression, "'" + value + "'")
        self.assertNotIn("needs.", condition)
        result = subprocess.run(["bash", "-c", "if [[ " + condition + " ]]; then exit 0; else exit 1; fi"],
                                capture_output=True, text=True, timeout=10)
        self.assertIn(result.returncode, (0, 1), result.stderr)
        return result.returncode == 0

    def test_actual_classifier_consumes_failed_verifier_output_and_preserves_capsule(self):
        self.assertEqual(2, self.invoke(publisher.GitHubGetUnavailable("unavailable"))[0])
        signal = dict(line.split("=", 1) for line in self.output.read_text().splitlines())["proof_transport_unavailable"]
        classification, outputs = self.classify(signal=signal)
        self.assertEqual({"schema_version": 1, "category": "github_transport_unavailable",
                          "stage": "proof_reauthentication", "transient": True}, classification)
        self.assertEqual({"failure_category": "github_transport_unavailable", "failure_transient": "true"}, outputs)
        self.assertFalse(self.cleanup_admitted(classification))
        restore = step_script("visual-review-drain.yml", "review", "Restore the exact authenticated preparation")
        self.assertIn('--verify-proof "$proof" --manifest "$manifest" --github-output "$GITHUB_OUTPUT"',
                      " ".join(restore.replace("\\\n", "").split()))

    def test_actual_classifier_keeps_terminal_invalid_fallback_without_typed_signal(self):
        self.assertEqual(2, self.invoke(ValueError("bounded GitHub GET failed or timed out"))[0])
        classification, outputs = self.classify()
        self.assertEqual({"schema_version": 1, "category": "protected_validation",
                          "stage": "drain", "transient": False}, classification)
        self.assertEqual("false", outputs["failure_transient"])
        self.assertTrue(self.cleanup_admitted(classification))

    def test_signal_requires_failed_step_and_exact_true_without_overwriting_existing_marker(self):
        for signal, outcome in (("true", "success"), ("false", "failure"), ("TRUE", "failure"), ("", "failure")):
            with self.subTest(signal=signal, outcome=outcome):
                with tempfile.TemporaryDirectory() as temporary:
                    previous, self.folder = self.folder, Path(temporary)
                    try:
                        classification, _ = self.classify(signal=signal, outcome=outcome)
                        self.assertEqual("protected_validation", classification["category"])
                        self.assertFalse(classification["transient"])
                    finally:
                        self.folder = previous
        marker = {"schema_version": 1, "category": "invalid_model_image", "stage": "model_images", "transient": False}
        classification, _ = self.classify(signal="true", existing=marker)
        self.assertEqual(marker, classification)


if __name__ == "__main__":
    unittest.main()
