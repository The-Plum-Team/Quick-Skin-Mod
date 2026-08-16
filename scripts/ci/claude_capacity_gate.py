#!/usr/bin/env python3
"""Resolve a short-lived, authenticated Claude capacity circuit for CI fan-out."""

from __future__ import annotations

import argparse
import os
import re
import sys
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Protocol


ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts" / "ci"))

from visual_review_queue import (  # noqa: E402
    DRAIN_EVENTS,
    DRAIN_WORKFLOW,
    Artifact,
    GitHubApi,
    QueueError,
    valid_owner,
)


READY_NAME = "claude-capacity-ready"
PAUSE_NAME = "claude-capacity-pause"
MARKER_NAMES = frozenset({READY_NAME, PAUSE_NAME})
COMPATIBILITY_REVIEW_WORKFLOW = ".github/workflows/mod-compatibility-review.yml"
COMPATIBILITY_REVIEW_EVENTS = frozenset({"repository_dispatch"})
DEFAULT_READY_MINUTES = 10
DEFAULT_PAUSE_MINUTES = 30
MAX_MARKER_BYTES = 1_048_576
MAX_MARKERS_PER_NAME = 1_000
SHA = re.compile(r"^[0-9a-f]{40}$")


@dataclass(frozen=True)
class CapacityState:
    state: str
    probe_required: bool

    @property
    def ready(self) -> bool:
        return self.state == "ready"


class CapacityApi(Protocol):
    def list_artifacts_named(self, name: str) -> list[Artifact]: ...

    def get_run(self, run_id: int) -> dict[str, Any]: ...


def resolve_capacity(
    api: CapacityApi,
    *,
    repository: str,
    implementation_sha: str,
    now: datetime | None = None,
    ready_ttl: timedelta = timedelta(minutes=DEFAULT_READY_MINUTES),
    pause_ttl: timedelta = timedelta(minutes=DEFAULT_PAUSE_MINUTES),
) -> CapacityState:
    """Return the newest live marker from the exact protected implementation.

    Marker contents are deliberately irrelevant. GitHub's immutable artifact metadata and the
    authenticated owner workflow decide whether a marker may open or close the circuit.
    """

    if not SHA.fullmatch(implementation_sha):
        raise QueueError("implementation SHA must contain 40 lowercase hex characters")
    if ready_ttl <= timedelta(0) or pause_ttl <= timedelta(0):
        raise QueueError("capacity marker TTLs must be positive")
    current_time = now or datetime.now(timezone.utc)
    candidates: list[Artifact] = []
    run_cache: dict[int, dict[str, Any]] = {}
    for name in sorted(MARKER_NAMES):
        named = api.list_artifacts_named(name)
        if len(named) > MAX_MARKERS_PER_NAME:
            raise QueueError(
                f"Claude capacity marker name exceeds {MAX_MARKERS_PER_NAME}"
            )
        ttl = ready_ttl if name == READY_NAME else pause_ttl
        for artifact in named:
            if (
                artifact.name != name
                or artifact.expired
                or artifact.size_in_bytes > MAX_MARKER_BYTES
                or artifact.head_sha != implementation_sha
                or artifact.created_at > current_time + timedelta(minutes=1)
                or artifact.created_at + ttl <= current_time
            ):
                continue
            owner = run_cache.get(artifact.run_id)
            if owner is None:
                owner = api.get_run(artifact.run_id)
                run_cache[artifact.run_id] = owner
            drain_owner = valid_owner(
                owner,
                repository=repository,
                artifact=artifact,
                workflow=DRAIN_WORKFLOW,
                events=DRAIN_EVENTS,
                conclusions=frozenset({"success", "failure"}),
                allow_in_progress=True,
            )
            compatibility_owner = valid_owner(
                owner,
                repository=repository,
                artifact=artifact,
                workflow=COMPATIBILITY_REVIEW_WORKFLOW,
                events=COMPATIBILITY_REVIEW_EVENTS,
                conclusions=frozenset({"success", "failure"}),
                allow_in_progress=True,
            )
            if drain_owner or compatibility_owner:
                candidates.append(artifact)
    if not candidates:
        return CapacityState(state="unknown", probe_required=True)
    newest = max(candidates, key=lambda artifact: artifact.order)
    return CapacityState(
        state="ready" if newest.name == READY_NAME else "paused",
        probe_required=False,
    )


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--implementation-sha", required=True)
    parser.add_argument("--github-output", type=Path, required=True)
    parser.add_argument(
        "--ready-minutes", type=int, default=DEFAULT_READY_MINUTES
    )
    parser.add_argument(
        "--pause-minutes", type=int, default=DEFAULT_PAUSE_MINUTES
    )
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        repository = args.repository.strip()
        if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
            raise QueueError("repository must use owner/name form")
        if not 1 <= args.ready_minutes <= 60:
            raise QueueError("ready marker TTL must be between 1 and 60 minutes")
        if not 1 <= args.pause_minutes <= 24 * 60:
            raise QueueError("pause marker TTL must be between 1 and 1440 minutes")
        token = os.environ.get("GH_TOKEN", "")
        if not token:
            raise QueueError("GH_TOKEN is required")
        api = GitHubApi(
            repository=repository,
            token=token,
            api_url=os.environ.get("GITHUB_API_URL", "https://api.github.com"),
        )
        state = resolve_capacity(
            api,
            repository=repository,
            implementation_sha=args.implementation_sha,
            ready_ttl=timedelta(minutes=args.ready_minutes),
            pause_ttl=timedelta(minutes=args.pause_minutes),
        )
        with args.github_output.open("a", encoding="utf-8") as output:
            output.write(f"state={state.state}\n")
            output.write(f"ready={'true' if state.ready else 'false'}\n")
            output.write(
                f"probe_required={'true' if state.probe_required else 'false'}\n"
            )
        return 0
    except (OSError, QueueError) as exc:
        print(f"Claude capacity gate error: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
