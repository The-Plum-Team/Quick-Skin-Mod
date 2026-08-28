#!/usr/bin/env python3
"""Select a compatibility source or admit its source-wide Claude batch."""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Protocol

from visual_review_queue import (
    Artifact,
    GitHubApi,
    GitHubRateLimitError,
    QueueError,
    valid_owner,
)


PLAN_NAME = "mod-compatibility-plan"
SOURCE_WORKFLOW = ".github/workflows/mod-compatibility-e2e.yml"
SOURCE_EVENTS = frozenset({"repository_dispatch"})
REVIEW_WORKFLOW = ".github/workflows/mod-compatibility-review.yml"
REVIEW_EVENTS = frozenset({"repository_dispatch"})
COMPLETE_NAME = re.compile(
    r"^mod-compatibility-review-complete-(?P<source>[1-9][0-9]*)$"
)
BLOCK_NAME = re.compile(
    r"^mod-compatibility-wave-block-(?P<source>[1-9][0-9]*)$"
)
MAX_PLANS = 1_000
MAX_MARKERS_PER_SOURCE = 100
MAX_PLAN_BYTES = 16_777_216
MAX_MARKER_BYTES = 1_048_576
REPOSITORY = re.compile(r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")
SHA = re.compile(r"^[0-9a-f]{40}$")
DEFAULT_CALL_BUDGET = 12
DEFAULT_CALL_SPACING_SECONDS = 2
MAX_PARALLEL_CALLS = 32
MAX_CALL_SPACING_SECONDS = 30
MAX_BATCH_LANES = 64


class AdmissionError(QueueError):
    """Raised when an untrusted matrix cannot be admitted safely."""


def admit(
    matrix: Any,
    *,
    call_budget: int = DEFAULT_CALL_BUDGET,
    call_spacing_seconds: int = DEFAULT_CALL_SPACING_SECONDS,
) -> dict[str, Any]:
    if (
        isinstance(call_budget, bool)
        or not isinstance(call_budget, int)
        or not 1 <= call_budget <= MAX_PARALLEL_CALLS
    ):
        raise AdmissionError("call budget must be between 1 and 32")
    if (
        isinstance(call_spacing_seconds, bool)
        or not isinstance(call_spacing_seconds, int)
        or not 0 <= call_spacing_seconds <= MAX_CALL_SPACING_SECONDS
    ):
        raise AdmissionError("call spacing must be between 0 and 30 seconds")
    if not isinstance(matrix, dict) or set(matrix) != {"include"}:
        raise AdmissionError("matrix must contain only include")
    lanes = matrix["include"]
    if not isinstance(lanes, list) or not 1 <= len(lanes) <= MAX_BATCH_LANES:
        raise AdmissionError("batch must contain between 1 and 64 lanes")
    if not all(isinstance(lane, dict) for lane in lanes):
        raise AdmissionError("every matrix lane must be an object")
    if any(
        "model_parallelism" in lane or "model_call_spacing_seconds" in lane
        for lane in lanes
    ):
        raise AdmissionError("matrix already contains protected admission fields")

    # Curated lanes remain independently recoverable, but one source-wide runner packs their
    # exact semantic representatives before model admission. The budget therefore bounds actual
    # concurrent calls directly instead of being divided into under-filled per-lane executors.
    return {
        "include": lanes,
        "model_call_spacing_seconds": call_spacing_seconds,
        "model_parallelism": call_budget,
    }


@dataclass(frozen=True)
class PendingCompatibilityReview:
    source_run_id: int
    source_sha: str
    artifact: Artifact


class CompatibilityQueueApi(Protocol):
    def list_artifacts_named(self, name: str) -> list[Artifact]: ...

    def get_run(self, run_id: int) -> dict[str, Any]: ...

    def get_branch_sha(self, branch: str) -> str: ...


def _has_authenticated_marker(
    api: CompatibilityQueueApi,
    *,
    repository: str,
    source_run_id: int,
    name: str,
    workflow: str,
    conclusions: frozenset[str],
    allow_in_progress: bool = False,
) -> bool:
    artifacts = api.list_artifacts_named(name)
    if len(artifacts) > MAX_MARKERS_PER_SOURCE:
        raise QueueError(
            f"mod compatibility marker exceeds {MAX_MARKERS_PER_SOURCE} artifacts"
        )
    pattern = (
        COMPLETE_NAME
        if name.startswith("mod-compatibility-review-complete-")
        else BLOCK_NAME
    )
    run_cache: dict[int, dict[str, Any]] = {}
    for artifact in artifacts:
        match = pattern.fullmatch(artifact.name)
        if (
            match is None
            or int(match.group("source")) != source_run_id
            or artifact.expired
            or artifact.size_in_bytes > MAX_MARKER_BYTES
        ):
            continue
        owner = run_cache.get(artifact.run_id)
        if owner is None:
            owner = api.get_run(artifact.run_id)
            run_cache[artifact.run_id] = owner
        if valid_owner(
            owner,
            repository=repository,
            artifact=artifact,
            workflow=workflow,
            events=REVIEW_EVENTS,
            conclusions=conclusions,
            allow_in_progress=allow_in_progress,
        ):
            return True
    return False


def list_pending(
    api: CompatibilityQueueApi,
    *,
    repository: str,
) -> list[PendingCompatibilityReview]:
    current_master = api.get_branch_sha("master")
    if not SHA.fullmatch(current_master):
        raise QueueError("master branch returned an invalid SHA")
    plans = api.list_artifacts_named(PLAN_NAME)
    if len(plans) > MAX_PLANS:
        raise QueueError(f"mod compatibility plan inventory exceeds {MAX_PLANS}")

    candidates: list[PendingCompatibilityReview] = []
    source_ids: set[int] = set()
    run_cache: dict[int, dict[str, Any]] = {}
    for artifact in sorted(plans, key=lambda item: item.order):
        source_run_id = artifact.run_id
        if (
            artifact.name != PLAN_NAME
            or artifact.expired
            or artifact.size_in_bytes > MAX_PLAN_BYTES
            or artifact.head_sha != current_master
        ):
            continue
        if source_run_id in source_ids:
            raise QueueError(
                f"source run {source_run_id} owns duplicate compatibility plans"
            )
        owner = run_cache.get(source_run_id)
        if owner is None:
            owner = api.get_run(source_run_id)
            run_cache[source_run_id] = owner
        if not valid_owner(
            owner,
            repository=repository,
            artifact=artifact,
            workflow=SOURCE_WORKFLOW,
            events=SOURCE_EVENTS,
            conclusions=frozenset({"success", "failure"}),
        ):
            continue
        source_ids.add(source_run_id)
        complete_name = f"mod-compatibility-review-complete-{source_run_id}"
        block_name = f"mod-compatibility-wave-block-{source_run_id}"
        if _has_authenticated_marker(
            api,
            repository=repository,
            source_run_id=source_run_id,
            name=complete_name,
            workflow=REVIEW_WORKFLOW,
            conclusions=frozenset({"success", "failure"}),
            allow_in_progress=True,
        ) or _has_authenticated_marker(
            api,
            repository=repository,
            source_run_id=source_run_id,
            name=block_name,
            workflow=REVIEW_WORKFLOW,
            conclusions=frozenset({"failure", "cancelled"}),
            allow_in_progress=True,
        ):
            continue
        candidates.append(
            PendingCompatibilityReview(
                source_run_id=source_run_id,
                source_sha=artifact.head_sha,
                artifact=artifact,
            )
        )
    return candidates


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository")
    parser.add_argument("--github-output", type=Path)
    parser.add_argument("--admit-matrix", type=Path)
    parser.add_argument("--call-budget", type=int, default=DEFAULT_CALL_BUDGET)
    parser.add_argument(
        "--call-spacing-seconds",
        type=int,
        default=DEFAULT_CALL_SPACING_SECONDS,
    )
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        if args.admit_matrix is not None:
            if args.repository is not None or args.github_output is not None:
                raise AdmissionError(
                    "matrix admission cannot select a repository queue"
                )
            with args.admit_matrix.open("r", encoding="utf-8") as source:
                matrix = json.load(source)
            admitted = admit(
                matrix,
                call_budget=args.call_budget,
                call_spacing_seconds=args.call_spacing_seconds,
            )
            json.dump(admitted, sys.stdout, separators=(",", ":"), sort_keys=True)
            sys.stdout.write("\n")
            return 0
        if args.repository is None or args.github_output is None:
            raise QueueError(
                "--repository and --github-output are required for queue selection"
            )
        repository = args.repository.strip()
        if not REPOSITORY.fullmatch(repository):
            raise QueueError("repository must use owner/name form")
        token = os.environ.get("GH_TOKEN", "")
        if not token:
            raise QueueError("GH_TOKEN is required")
        api = GitHubApi(
            repository=repository,
            token=token,
            api_url=os.environ.get("GITHUB_API_URL", "https://api.github.com"),
        )
        pending = list_pending(api, repository=repository)
        with args.github_output.open("a", encoding="utf-8") as output:
            if not pending:
                output.write("eligible=false\n")
            else:
                selected = pending[0]
                output.write("eligible=true\n")
                output.write(f"source_run_id={selected.source_run_id}\n")
                output.write(f"source_sha={selected.source_sha}\n")
        return 0
    except GitHubRateLimitError as exc:
        print(f"Mod compatibility review queue deferred: {exc}", file=sys.stderr)
        return 75
    except (OSError, QueueError, json.JSONDecodeError) as exc:
        print(f"Mod compatibility review queue error: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
