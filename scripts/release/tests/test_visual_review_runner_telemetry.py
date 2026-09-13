"""Offline checks for observable local waits, never provider queue accounting."""

from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor
from contextlib import redirect_stderr, redirect_stdout
import io
import json
from pathlib import Path
import sys
import tempfile
import threading
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch


ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "e2e"))
import visual_review_runner as runner  # noqa: E402


def snapshots(output: str, prefix: str) -> list[dict[str, int]]:
    return [
        {key: int(value) for key, value in
         (item.split("=") for item in line.split(": ", 1)[1].split(", "))}
        for line in output.splitlines() if line.startswith(prefix)
    ]


class RunnerTelemetryTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.capsule = Path(self.temporary.name)
        self.provider = runner.ClaudeProvider(
            capsule=self.capsule, work_root=self.capsule / "review-work",
            claude=Path(sys.executable), triage_prompt="private-prompt",
            verify_prompt="private-prompt", triage_model="haiku",
            verify_model="opus", paired=False, attempts=3, call_spacing_seconds=0,
        )

    def test_progress_is_cumulative_and_original_schema_is_unchanged(self) -> None:
        output = io.StringIO()
        with redirect_stdout(output):
            self.provider._record_attempt("triage", 0)
            self.provider._record_attempt("triage", 0)
            self.provider._record_attempt("verify", 0)
            self.provider.emit_terminal_telemetry(failed=False)
        progress = snapshots(output.getvalue(), "Sanitized model progress:")
        self.assertEqual([1, 2, 3, 3], [row["total"] for row in progress])
        self.assertEqual([0, 0, 0, 1], [row["final"] for row in progress])
        self.assertEqual(list(range(1, 5)), [row["sequence"] for row in progress])
        expected = {"total": 3, "triage": 2, "verify": 1, "retries": 1,
                    "triage_chunks": 1, "verify_chunks": 1}
        self.assertEqual(expected, self.provider.telemetry())
        telemetry = self.capsule / "telemetry.json"
        runner._write_telemetry(telemetry, self.provider, None, state="failed")
        self.assertEqual({"schema_version": 1, "state": "failed",
                          "model_attempts": expected, "review_plan": None},
                         json.loads(telemetry.read_text()))
        self.assertEqual(0, snapshots(output.getvalue(), "Sanitized local retry wait:")[-1]["started"])

    def test_each_existing_category_records_actual_wait_not_requested_delay(self) -> None:
        output = io.StringIO()
        categories = sorted(runner.TRANSIENT_MODEL_CATEGORIES)
        with redirect_stdout(output), patch.object(self.provider._cancelled, "wait", return_value=False) as wait:
            for category in categories:
                with patch.object(runner.time, "monotonic_ns", side_effect=[10, 1_500_010]):
                    self.provider._retry_wait(category, 30.0)
            self.provider.emit_terminal_telemetry(failed=False)
        latest = snapshots(output.getvalue(), "Sanitized local retry wait:")[-1]
        self.assertEqual(6, latest["started"])
        self.assertEqual(6, latest["completed"])
        self.assertEqual(0, latest["cancelled"])
        self.assertEqual(9, latest["waited_ms"])
        self.assertTrue(all(latest[category] == 1 for category in categories))
        self.assertEqual([((30.0,), {})] * 6, wait.call_args_list)

    def test_cancelled_wait_measures_elapsed_without_claiming_terminal_snapshot(self) -> None:
        output = io.StringIO()
        with redirect_stdout(output), patch.object(self.provider._cancelled, "wait", return_value=True), \
                patch.object(runner.time, "monotonic_ns", side_effect=[100, 2_999_999]):
            with self.assertRaises(runner.ReviewCancelled):
                self.provider._retry_wait("quota_or_rate_limit", 60)
        rows = snapshots(output.getvalue(), "Sanitized local retry wait:")
        self.assertEqual((1, 0, 0), tuple(rows[0][key] for key in ("started", "completed", "cancelled")))
        self.assertEqual((1, 0, 1, 2, 0), tuple(rows[-1][key] for key in
                         ("started", "completed", "cancelled", "waited_ms", "final")))

    def test_interrupted_wait_keeps_exception_and_partial_counters(self) -> None:
        output = io.StringIO()
        with redirect_stdout(output), patch.object(self.provider._cancelled, "wait", side_effect=KeyboardInterrupt), \
                patch.object(runner.time, "monotonic_ns", side_effect=[0, 4_000_000]):
            with self.assertRaises(KeyboardInterrupt):
                self.provider._retry_wait("timeout", 30)
        latest = snapshots(output.getvalue(), "Sanitized local retry wait:")[-1]
        self.assertEqual((1, 4, 0), (latest["cancelled"], latest["waited_ms"], latest["final"]))

    def test_concurrent_snapshots_are_atomic_and_worker_waits_are_summed(self) -> None:
        output = io.StringIO()
        barrier = threading.Barrier(2)
        clock = threading.local()

        def now() -> int:
            value = getattr(clock, "value", 0)
            clock.value = value + 1_600_000
            return value

        def wait(_seconds: float) -> bool:
            barrier.wait(timeout=5)
            return False

        def work(index: int) -> None:
            self.provider._record_attempt("triage", index)
            self.provider._retry_wait("overloaded", 30)

        with redirect_stdout(output), patch.object(self.provider._cancelled, "wait", side_effect=wait), \
                patch.object(runner.time, "monotonic_ns", side_effect=now):
            with ThreadPoolExecutor(max_workers=2) as executor:
                list(executor.map(work, range(2)))
            self.provider.emit_terminal_telemetry(failed=False)
        attempts = snapshots(output.getvalue(), "Sanitized model progress:")
        waits = snapshots(output.getvalue(), "Sanitized local retry wait:")
        self.assertEqual(list(range(1, 8)), [row["sequence"] for row in attempts])
        self.assertEqual([row["sequence"] for row in attempts], [row["sequence"] for row in waits])
        self.assertEqual((2, 2, 3), (attempts[-1]["total"], waits[-1]["completed"], waits[-1]["waited_ms"]))
        lines = output.getvalue().splitlines()
        self.assertTrue(all(lines[index].startswith("Sanitized model progress:") and
                            lines[index + 1].startswith("Sanitized local retry wait:")
                            for index in range(0, len(lines), 2)))

    def test_logging_failure_does_not_change_counters_or_wait_behavior(self) -> None:
        sink = Mock()
        sink.write.side_effect = BrokenPipeError("private-output-error")
        with patch.object(runner.sys, "stdout", sink), \
                patch.object(self.provider._cancelled, "wait", return_value=False) as wait, \
                patch.object(runner.time, "monotonic_ns", side_effect=[0, 1_000_000]):
            self.provider._record_attempt("triage", 0)
            self.provider._retry_wait("timeout", 30)
            self.provider.emit_terminal_telemetry(failed=True)
        self.assertEqual(1, self.provider.telemetry()["total"])
        wait.assert_called_once_with(30)

    def test_failed_cli_preserves_three_attempts_and_original_30_60_delays(self) -> None:
        output = io.StringIO()
        process = Mock()
        process.wait.return_value = 1
        with redirect_stdout(output), patch.object(self.provider, "_prepare_model_manifest", return_value=[]), \
                patch.object(runner.subprocess, "Popen", return_value=process) as popen, \
                patch.object(runner, "load", return_value={"api_error_status": 429, "result": "private-provider-text"}), \
                patch.object(self.provider._cancelled, "wait", return_value=False) as wait, \
                patch.object(runner.time, "monotonic_ns", side_effect=[0, 3_000_000, 10_000_000, 15_000_000]):
            with self.assertRaises(runner.RunnerError) as error:
                self.provider("triage", 0, [], {})
            self.provider.emit_terminal_telemetry(failed=True)
        self.assertEqual("quota_or_rate_limit", error.exception.category)
        self.assertEqual(3, popen.call_count)
        self.assertEqual([((30.0,), {}), ((60.0,), {})], wait.call_args_list)
        self.assertEqual([runner.MAX_MODEL_SECONDS] * 3, [call.kwargs["timeout"] for call in process.wait.call_args_list])
        self.assertEqual(2, self.provider.telemetry()["retries"])
        last = snapshots(output.getvalue(), "Sanitized local retry wait:")[-1]
        self.assertEqual((2, 8, 1, 1), (last["started"], last["waited_ms"], last["final"], last["failed"]))
        self.assertNotIn("private-", output.getvalue())
        for line in output.getvalue().splitlines():
            self.assertRegex(line, r"^Sanitized (model progress|local retry wait): [a-z_]+=[0-9]+(?:, [a-z_]+=[0-9]+)*$")

    def test_popen_failure_does_not_record_an_attempt_or_wait(self) -> None:
        output = io.StringIO()
        with redirect_stdout(output), patch.object(self.provider, "_prepare_model_manifest", return_value=[]), \
                patch.object(runner.subprocess, "Popen", side_effect=OSError("private-token")), \
                patch.object(self.provider._cancelled, "wait") as wait:
            with self.assertRaises(runner.RunnerError) as error:
                self.provider("triage", 0, [], {})
            self.provider.emit_terminal_telemetry(failed=True)
        self.assertEqual("cli_unavailable", error.exception.category)
        wait.assert_not_called()
        self.assertEqual(0, self.provider.telemetry()["total"])
        self.assertNotIn("private-token", output.getvalue())

    def test_main_emits_terminal_snapshot_on_success_and_handled_failures(self) -> None:
        for failure in (None, runner.RunnerError("timeout", "triage", transient=True), ValueError("private-validation")):
            with self.subTest(failure=type(failure).__name__), tempfile.TemporaryDirectory() as temporary:
                capsule = Path(temporary)
                inputs = capsule / "review-input"
                inputs.mkdir()
                manifest = inputs / "manifest.json"
                manifest.write_text("[]")
                args = SimpleNamespace(
                    capsule=capsule, manifest=manifest, input_root=inputs,
                    model_attempts=3, max_parallel_calls=32, call_spacing_seconds=0,
                    cache=None, cache_policy_sha256=None, review_mode="anchor-semantic",
                    claude=Path(sys.executable), triage_prompt=Path("unused"), verify_prompt=Path("unused"),
                    triage_model="haiku", verify_model="opus", triage_chunk_size=8, verify_chunk_size=4,
                    review_identical=False, output=capsule / "report.json",
                    completion_state=capsule / "complete.json", telemetry_report=capsule / "telemetry.json",
                    failure_report=capsule / "failure.json",
                )

                def execute(_manifest: object, provider: runner.ClaudeProvider, **_kwargs: object) -> tuple:
                    provider._record_attempt("triage", 0)
                    if failure is not None:
                        raise failure
                    return [], {"frames": 1, "stopped_early": 0}

                output = io.StringIO()
                with redirect_stdout(output), redirect_stderr(io.StringIO()), \
                        patch.object(runner, "parse_args", return_value=args), \
                        patch.object(runner, "load", return_value=[{}]), \
                        patch.object(runner, "validate_manifest", return_value=([{}], [])), \
                        patch.object(runner, "validate_input"), patch.object(runner, "_bounded_prompt", return_value="private"), \
                        patch.object(runner, "execute_review", side_effect=execute), \
                        patch.object(runner, "write_normalized_report"):
                    status = runner.main([])
                self.assertEqual(0 if failure is None else 2, status)
                progress = snapshots(output.getvalue(), "Sanitized model progress:")
                self.assertEqual((1, int(failure is not None), 1),
                                 tuple(progress[-1][key] for key in ("final", "failed", "total")))
                telemetry = json.loads(args.telemetry_report.read_text())
                self.assertEqual({"schema_version", "state", "model_attempts", "review_plan"}, set(telemetry))
                self.assertEqual("complete" if failure is None else "failed", telemetry["state"])


if __name__ == "__main__":
    unittest.main()
