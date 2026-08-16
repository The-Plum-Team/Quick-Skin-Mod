#!/usr/bin/env python3
"""Make one tool-free Claude call and emit only a sanitized capacity marker."""

from __future__ import annotations

import argparse
import json
import math
import os
import signal
import subprocess
import sys
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "e2e"))

from check_visual_review import model_error_category  # noqa: E402


MAX_OUTPUT_BYTES = 4_194_304
MAX_EVENT_BYTES = 1_048_576
MAX_PROBE_SECONDS = 5 * 60
PAUSE_UTILIZATION = 0.95
KNOWN_RATE_LIMIT_TYPES = frozenset(
    {
        "five_hour",
        "seven_day",
        "seven_day_cowork",
        "seven_day_cowork_opus",
        "seven_day_fable",
        "seven_day_opus",
        "seven_day_sonnet",
    }
)
TRANSIENT_CATEGORIES = frozenset(
    {
        "cli_or_api",
        "overloaded",
        "quota_or_rate_limit",
        "quota_near_limit",
        "structured_output_retries_exhausted",
    }
)
PROBE_SCHEMA = {
    "type": "object",
    "additionalProperties": False,
    "required": ["available"],
    "properties": {"available": {"type": "boolean", "enum": [True]}},
}


class ProbeError(RuntimeError):
    pass


def _reject_nonfinite(value: str) -> Any:
    raise ValueError(f"non-finite JSON number {value}")


def _reject_duplicate_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    value: dict[str, Any] = {}
    for key, item in pairs:
        if key in value:
            raise ValueError(f"duplicate JSON key {key!r}")
        value[key] = item
    return value


def classify_probe(
    returncode: int,
    envelope: Any,
    rate_limits: list[dict[str, Any]] | None = None,
) -> tuple[str, str]:
    """Return ``(state, category)`` without propagating provider-authored text."""

    for info in rate_limits or []:
        status = info.get("status")
        utilization = info.get("utilization")
        if status not in {"allowed", "allowed_warning", "rejected"}:
            return ("error", "cli_or_api")
        if utilization is not None:
            if (
                isinstance(utilization, bool)
                or not isinstance(utilization, (int, float))
                or not math.isfinite(utilization)
                or not 0 <= utilization <= 1
            ):
                return ("error", "cli_or_api")
        if status == "rejected":
            return ("paused", "quota_or_rate_limit")
        if utilization is not None and utilization >= PAUSE_UTILIZATION:
            return ("paused", "quota_near_limit")
    if (
        returncode == 0
        and isinstance(envelope, dict)
        and envelope.get("type") == "result"
        and envelope.get("subtype") == "success"
        and envelope.get("is_error") is False
        and envelope.get("structured_output") == {"available": True}
    ):
        return ("ready", "")
    category = (
        model_error_category(envelope)
        if isinstance(envelope, dict)
        else "cli_or_api"
    )
    if category in TRANSIENT_CATEGORIES:
        return ("paused", category)
    return ("error", category)


def sanitized_rate_limit_summary(
    rate_limits: list[dict[str, Any]] | None,
) -> dict[str, Any]:
    """Reduce private provider events to bounded, non-sensitive decision evidence."""

    statuses: set[str] = set()
    max_utilization: float | None = None
    rate_limit_types: set[str] = set()
    for info in rate_limits or []:
        status = info.get("status")
        if status in {"allowed", "allowed_warning", "rejected"}:
            statuses.add(status)
        utilization = info.get("utilization")
        if (
            isinstance(utilization, (int, float))
            and not isinstance(utilization, bool)
            and math.isfinite(utilization)
            and 0 <= utilization <= 1
        ):
            normalized_utilization = float(utilization)
            max_utilization = (
                normalized_utilization
                if max_utilization is None
                else max(max_utilization, normalized_utilization)
            )
        rate_limit_type = info.get("rateLimitType")
        if rate_limit_type is not None:
            rate_limit_types.add(
                rate_limit_type
                if isinstance(rate_limit_type, str)
                and rate_limit_type in KNOWN_RATE_LIMIT_TYPES
                else "other"
            )

    if "rejected" in statuses:
        provider_status = "rejected"
    elif "allowed_warning" in statuses:
        provider_status = "allowed_warning"
    elif "allowed" in statuses:
        provider_status = "allowed"
    else:
        provider_status = "not_reported"
    if max_utilization is None:
        utilization_band = "not_reported"
    elif max_utilization >= PAUSE_UTILIZATION:
        utilization_band = "at_or_above_95_percent"
    else:
        utilization_band = "below_95_percent"
    return {
        "provider_status": provider_status,
        "rate_limit_types": sorted(rate_limit_types),
        "utilization_band": utilization_band,
    }


def probe_command(claude: Path, model: str) -> list[str]:
    return [
        str(claude),
        "--print",
        "Return the required capacity acknowledgement.",
        "--model",
        model,
        "--effort",
        "low",
        "--output-format",
        "stream-json",
        "--verbose",
        "--json-schema",
        json.dumps(PROBE_SCHEMA, separators=(",", ":"), sort_keys=True),
        "--system-prompt",
        "Return only the requested structured acknowledgement.",
        "--safe-mode",
        "--no-session-persistence",
        "--disable-slash-commands",
        "--max-turns",
        "1",
        "--tools",
        "",
        "--disallowedTools",
        "mcp__*",
        "--permission-mode",
        "dontAsk",
    ]


def resolve_claude_executable(claude: Path) -> Path:
    """Resolve npm's expected ``.bin`` symlink without admitting an external target."""

    if (
        claude.name != "claude"
        or claude.parent.name != ".bin"
        or claude.parent.parent.name != "node_modules"
    ):
        raise ProbeError("Claude executable path is outside the locked npm layout")
    try:
        node_modules = claude.parent.parent.resolve(strict=True)
        executable = claude.resolve(strict=True)
        executable.relative_to(node_modules)
    except (OSError, RuntimeError, ValueError) as exc:
        raise ProbeError("Claude executable escapes the locked npm layout") from exc
    if not executable.is_file() or not os.access(executable, os.X_OK):
        raise ProbeError("Claude executable is unavailable")
    return executable


def load_stream(path: Path) -> tuple[Any, list[dict[str, Any]]]:
    try:
        payload = path.read_bytes()
    except OSError:
        return (None, [])
    if not payload or len(payload) > MAX_OUTPUT_BYTES:
        return (None, [])
    result: Any = None
    rate_limits: list[dict[str, Any]] = []
    for raw_line in payload.splitlines():
        if not raw_line or len(raw_line) > MAX_EVENT_BYTES:
            return (None, [])
        try:
            event = json.loads(
                raw_line,
                object_pairs_hook=_reject_duplicate_keys,
                parse_constant=_reject_nonfinite,
            )
        except (UnicodeDecodeError, json.JSONDecodeError, ValueError):
            return (None, [])
        if not isinstance(event, dict):
            return (None, [])
        if event.get("type") == "result":
            if result is not None:
                return (None, [])
            result = event
        elif event.get("type") == "rate_limit_event":
            info = event.get("rate_limit_info")
            if not isinstance(info, dict):
                return (None, [])
            rate_limits.append(info)
    return (result, rate_limits)


def run_probe(
    claude: Path, model: str, work_root: Path
) -> tuple[str, str, list[dict[str, Any]]]:
    executable = resolve_claude_executable(claude)
    work_root.mkdir(parents=True, exist_ok=False)
    stdout_path = work_root / "private-result.json"
    stderr_path = work_root / "private-stderr.log"
    with stdout_path.open("xb") as stdout, stderr_path.open("xb") as stderr:
        process = subprocess.Popen(
            probe_command(executable, model),
            cwd=work_root,
            stdin=subprocess.DEVNULL,
            stdout=stdout,
            stderr=stderr,
            start_new_session=True,
        )
        try:
            returncode = process.wait(timeout=MAX_PROBE_SECONDS)
        except subprocess.TimeoutExpired:
            try:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait(timeout=30)
            except (OSError, ProcessLookupError, subprocess.TimeoutExpired):
                pass
            return ("paused", "timeout", [])
    envelope, rate_limits = load_stream(stdout_path)
    state, category = classify_probe(returncode, envelope, rate_limits)
    return (state, category, rate_limits)


def write_marker(
    path: Path,
    *,
    state: str,
    category: str,
    rate_limits: list[dict[str, Any]] | None = None,
) -> None:
    marker = {
        "schema_version": 2,
        "state": state,
        "rate_limit_summary": sanitized_rate_limit_summary(rate_limits),
    }
    if category:
        marker["category"] = category
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("x", encoding="utf-8") as output:
        json.dump(
            marker,
            output,
            ensure_ascii=True,
            separators=(",", ":"),
            sort_keys=True,
        )
        output.write("\n")


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--claude", type=Path, required=True)
    parser.add_argument("--model", default="claude-sonnet-5")
    parser.add_argument("--work-root", type=Path, required=True)
    parser.add_argument("--marker", type=Path, required=True)
    parser.add_argument("--github-output", type=Path, required=True)
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        if not args.model or len(args.model) > 128:
            raise ProbeError("model identifier is invalid")
        state, category, rate_limits = run_probe(
            args.claude, args.model, args.work_root
        )
        if state == "error":
            raise ProbeError(f"non-transient Claude probe failure ({category})")
        write_marker(
            args.marker,
            state=state,
            category=category,
            rate_limits=rate_limits,
        )
        marker_name = (
            "claude-capacity-ready" if state == "ready" else "claude-capacity-pause"
        )
        with args.github_output.open("a", encoding="utf-8") as output:
            output.write(f"ready={'true' if state == 'ready' else 'false'}\n")
            output.write(f"marker_name={marker_name}\n")
            output.write(f"category={category}\n")
        return 0
    except (OSError, ProbeError) as exc:
        print(f"Claude capacity probe error: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
