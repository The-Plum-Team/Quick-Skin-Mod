from __future__ import annotations

import io
import sys
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts" / "ci"))

import runtime_store_cache_policy as policy


class RuntimeStoreCachePolicyTest(unittest.TestCase):
    def decide(self, **changes: object) -> bool:
        values: dict[str, object] = {
            "event_name": "workflow_dispatch",
            "ref_name": "master",
            "ref_type": "branch",
            "ref_protected": True,
        }
        values.update(changes)
        return policy.is_read_only(**values)  # type: ignore[arg-type]

    def test_only_a_protected_master_dispatch_may_write(self) -> None:
        self.assertFalse(self.decide())
        self.assertFalse(self.decide(event_name="repository_dispatch"))

    def test_every_other_context_restores_read_only(self) -> None:
        for changes in (
            {"event_name": "pull_request"},
            {"event_name": "push"},
            {"event_name": "schedule"},
            {"ref_protected": False},
            {"ref_type": "tag"},
            {"ref_name": "feature/topic"},
            {"ref_name": "automation/sync/1.20.1"},
            {"ref_name": "codex/experiment"},
            {"ref_name": "dependabot/pip/x"},
            {"ref_name": "refs/pull/42/merge"},
            {"ref_name": "refs/tags/v1.2.3"},
        ):
            with self.subTest(**changes):
                self.assertTrue(self.decide(**changes))

    def test_malformed_input_is_rejected_instead_of_guessed(self) -> None:
        for changes in (
            {"event_name": ""},
            {"event_name": "   "},
            {"ref_name": ""},
            {"ref_type": ""},
            {"ref_protected": "true"},
            {"ref_protected": 1},
        ):
            with self.subTest(**changes), self.assertRaises(policy.PolicyError):
                self.decide(**changes)

    def test_cli_prints_one_lowercase_boolean_and_fails_closed(self) -> None:
        stdout = io.StringIO()
        with redirect_stdout(stdout):
            code = policy.main(
                [
                    "--event-name",
                    "workflow_dispatch",
                    "--ref-name",
                    "master",
                    "--ref-type",
                    "branch",
                    "--ref-protected",
                    "true",
                ]
            )
        self.assertEqual(0, code)
        self.assertEqual("false\n", stdout.getvalue())

        stdout = io.StringIO()
        with redirect_stdout(stdout):
            code = policy.main(
                [
                    "--event-name",
                    "pull_request",
                    "--ref-name",
                    "topic",
                    "--ref-type",
                    "branch",
                    "--ref-protected",
                    "false",
                ]
            )
        self.assertEqual(0, code)
        self.assertEqual("true\n", stdout.getvalue())

        stderr = io.StringIO()
        with redirect_stdout(io.StringIO()), redirect_stderr(stderr):
            code = policy.main(
                [
                    "--event-name",
                    "   ",
                    "--ref-name",
                    "master",
                    "--ref-type",
                    "branch",
                    "--ref-protected",
                    "true",
                ]
            )
        self.assertEqual(2, code)
        self.assertIn("Runtime store cache policy error", stderr.getvalue())


if __name__ == "__main__":
    unittest.main()
