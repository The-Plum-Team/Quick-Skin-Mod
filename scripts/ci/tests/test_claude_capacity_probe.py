from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts" / "ci"))

from claude_capacity_probe import (  # noqa: E402
    ProbeError,
    classify_probe,
    load_stream,
    probe_command,
    resolve_claude_executable,
    sanitized_rate_limit_summary,
    write_marker,
)


class ClaudeCapacityProbeTest(unittest.TestCase):
    def test_success_requires_the_exact_structured_acknowledgement(self) -> None:
        envelope = {
            "type": "result",
            "subtype": "success",
            "is_error": False,
            "structured_output": {"available": True},
        }

        self.assertEqual(("ready", ""), classify_probe(0, envelope))
        self.assertEqual(
            ("paused", "cli_or_api"),
            classify_probe(0, {**envelope, "structured_output": {"available": False}}),
        )

    def test_subscription_limit_is_a_sanitized_pause(self) -> None:
        state = classify_probe(
            1,
            {
                "type": "result",
                "subtype": "success",
                "is_error": True,
                "api_error_status": 429,
                "result": "provider-authored text must not escape",
            },
        )

        self.assertEqual(("paused", "quota_or_rate_limit"), state)

    def test_authentication_failure_is_not_hidden_as_capacity(self) -> None:
        state = classify_probe(
            1,
            {
                "type": "result",
                "subtype": "success",
                "is_error": True,
                "result": "Not logged in",
            },
        )

        self.assertEqual(("error", "authentication"), state)

    def test_warning_does_not_impersonate_an_exhausted_subscription(self) -> None:
        success = {
            "type": "result",
            "subtype": "success",
            "is_error": False,
            "structured_output": {"available": True},
        }

        self.assertEqual(
            ("ready", ""),
            classify_probe(
                0,
                success,
                [{"status": "allowed_warning", "rateLimitType": "seven_day"}],
            ),
        )
        self.assertEqual(
            ("ready", ""),
            classify_probe(
                0,
                success,
                [
                    {
                        "status": "allowed_warning",
                        "rateLimitType": "seven_day",
                        "utilization": 0.53,
                    }
                ],
            ),
        )

    def test_rejection_or_explicit_95_percent_stops_expensive_fan_out(self) -> None:
        success = {
            "type": "result",
            "subtype": "success",
            "is_error": False,
            "structured_output": {"available": True},
        }

        self.assertEqual(
            ("paused", "quota_or_rate_limit"),
            classify_probe(
                1,
                success,
                [{"status": "rejected", "rateLimitType": "five_hour"}],
            ),
        )
        self.assertEqual(
            ("paused", "quota_near_limit"),
            classify_probe(
                0,
                success,
                [{"status": "allowed_warning", "utilization": 0.95}],
            ),
        )
        self.assertEqual(
            ("paused", "quota_near_limit"),
            classify_probe(
                0,
                success,
                [{"status": "allowed", "utilization": 0.97}],
            ),
        )

    def test_unknown_or_malformed_rate_state_fails_closed(self) -> None:
        success = {
            "type": "result",
            "subtype": "success",
            "is_error": False,
            "structured_output": {"available": True},
        }

        self.assertEqual(
            ("error", "cli_or_api"),
            classify_probe(0, success, [{"status": "future_status"}]),
        )
        self.assertEqual(
            ("error", "cli_or_api"),
            classify_probe(0, success, [{"status": "allowed", "utilization": "1"}]),
        )
        self.assertEqual(
            ("error", "cli_or_api"),
            classify_probe(0, success, [{"status": "allowed", "utilization": 95}]),
        )

    def test_rate_limit_summary_is_bounded_and_sanitized(self) -> None:
        self.assertEqual(
            {
                "provider_status": "allowed_warning",
                "rate_limit_types": ["other", "seven_day"],
                "utilization_band": "below_95_percent",
            },
            sanitized_rate_limit_summary(
                [
                    {
                        "status": "allowed_warning",
                        "rateLimitType": "seven_day",
                        "utilization": 0.53,
                    },
                    {
                        "status": "allowed",
                        "rateLimitType": ["provider-authored text must not escape"],
                    },
                ]
            ),
        )

    def test_stream_parser_extracts_only_result_and_rate_limit_state(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            output = Path(raw) / "stream.jsonl"
            events = [
                {"type": "system", "subtype": "init"},
                {
                    "type": "rate_limit_event",
                    "rate_limit_info": {
                        "status": "allowed",
                        "rateLimitType": "seven_day",
                        "utilization": 0.4,
                    },
                },
                {
                    "type": "result",
                    "subtype": "success",
                    "is_error": False,
                    "structured_output": {"available": True},
                },
            ]
            output.write_text(
                "".join(json.dumps(event) + "\n" for event in events),
                encoding="utf-8",
            )

            result, rate_limits = load_stream(output)

        self.assertEqual(events[-1], result)
        self.assertEqual([events[1]["rate_limit_info"]], rate_limits)

    def test_stream_parser_rejects_a_malformed_rate_event(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            output = Path(raw) / "stream.jsonl"
            output.write_text(
                json.dumps(
                    {"type": "rate_limit_event", "rate_limit_info": "unknown"}
                )
                + "\n",
                encoding="utf-8",
            )

            self.assertEqual((None, []), load_stream(output))

    def test_probe_has_no_tools_sessions_or_repository_customization(self) -> None:
        command = probe_command(Path("/opt/claude"), "claude-sonnet-5")

        self.assertIn("--safe-mode", command)
        self.assertIn("--no-session-persistence", command)
        self.assertIn("--disable-slash-commands", command)
        self.assertIn("--system-prompt", command)
        self.assertIn("--effort", command)
        self.assertIn("stream-json", command)
        self.assertIn("--verbose", command)
        tools = command.index("--tools")
        self.assertEqual("", command[tools + 1])
        self.assertNotIn("Read", command)
        self.assertNotIn("Bash", command)

    def test_locked_npm_bin_symlink_resolves_inside_node_modules(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            node_modules = Path(raw) / "node_modules"
            package = node_modules / "@anthropic-ai" / "claude-code"
            package.mkdir(parents=True)
            executable = package / "cli.js"
            executable.write_text("#!/bin/sh\nexit 0\n", encoding="utf-8")
            executable.chmod(0o755)
            bin_directory = node_modules / ".bin"
            bin_directory.mkdir()
            link = bin_directory / "claude"
            link.symlink_to(Path("..") / "@anthropic-ai" / "claude-code" / "cli.js")

            self.assertEqual(executable.resolve(), resolve_claude_executable(link))

    def test_locked_npm_bin_symlink_cannot_escape_node_modules(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            node_modules = root / "node_modules"
            bin_directory = node_modules / ".bin"
            bin_directory.mkdir(parents=True)
            executable = root / "outside"
            executable.write_text("#!/bin/sh\nexit 0\n", encoding="utf-8")
            executable.chmod(0o755)
            link = bin_directory / "claude"
            link.symlink_to(executable)

            with self.assertRaises(ProbeError):
                resolve_claude_executable(link)

    def test_marker_contains_only_sanitized_machine_state(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            marker = Path(raw) / "marker.json"
            write_marker(
                marker,
                state="paused",
                category="quota_or_rate_limit",
            )

            self.assertEqual(
                {
                    "schema_version": 2,
                    "state": "paused",
                    "category": "quota_or_rate_limit",
                    "rate_limit_summary": {
                        "provider_status": "not_reported",
                        "rate_limit_types": [],
                        "utilization_band": "not_reported",
                    },
                },
                json.loads(marker.read_text(encoding="utf-8")),
            )

    def test_ready_warning_marker_keeps_only_bounded_decision_evidence(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            marker = Path(raw) / "marker.json"
            write_marker(
                marker,
                state="ready",
                category="",
                rate_limits=[
                    {
                        "status": "allowed_warning",
                        "rateLimitType": "seven_day",
                        "utilization": 0.53,
                        "private": "provider-authored text must not escape",
                    }
                ],
            )

            self.assertEqual(
                {
                    "schema_version": 2,
                    "state": "ready",
                    "rate_limit_summary": {
                        "provider_status": "allowed_warning",
                        "rate_limit_types": ["seven_day"],
                        "utilization_band": "below_95_percent",
                    },
                },
                json.loads(marker.read_text(encoding="utf-8")),
            )


if __name__ == "__main__":
    unittest.main()
