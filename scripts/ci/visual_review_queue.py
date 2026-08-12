#!/usr/bin/env python3
"""Select one authenticated pending AI visual review from the durable artifact queue."""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Protocol


REPOSITORY = re.compile(r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")
SHA = re.compile(r"^[0-9a-f]{40}$")
DIGEST = re.compile(r"^sha256:[0-9a-f]{64}$")
INPUT_NAME = re.compile(r"^visual-review-input-(?P<source>[1-9][0-9]*)$")
REPORT_NAME = re.compile(r"^visual-review-(?P<source>[1-9][0-9]*)$")
ATTEMPT_NAME = re.compile(r"^visual-review-attempt-(?P<source>[1-9][0-9]*)$")
ANCHOR_SOURCE_BRANCH = re.compile(
    r"^automation/sync/forge-and-fabric-1\.20\.1/[A-Za-z0-9._/-]+$"
)
PREPARE_WORKFLOW = ".github/workflows/visual-review.yml"
DRAIN_WORKFLOW = ".github/workflows/visual-review-drain.yml"
PREPARE_EVENTS = frozenset({"repository_dispatch", "workflow_run"})
DRAIN_EVENTS = frozenset({"repository_dispatch", "schedule", "workflow_dispatch"})
MAX_ARTIFACTS = 10_000
MAX_INPUT_BYTES = 536_870_912
DEFAULT_COOLDOWN_MINUTES = 30


class QueueError(RuntimeError):
    pass


@dataclass(frozen=True)
class Artifact:
    artifact_id: int
    name: str
    size_in_bytes: int
    digest: str
    expired: bool
    created_at: datetime
    run_id: int
    head_branch: str
    head_sha: str

    @property
    def order(self) -> tuple[datetime, int]:
        return (self.created_at, self.artifact_id)


class QueueApi(Protocol):
    def list_artifacts(self) -> list[Artifact]: ...

    def get_run(self, run_id: int) -> dict[str, Any]: ...

    def get_source_run(self, run_id: int) -> dict[str, Any]: ...


def _positive_int(value: Any, label: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value <= 0:
        raise QueueError(f"{label} must be a positive integer")
    return value


def _text(value: Any, label: str) -> str:
    if not isinstance(value, str) or not value:
        raise QueueError(f"{label} must be a non-empty string")
    return value


def _timestamp(value: Any, label: str) -> datetime:
    text = _text(value, label)
    try:
        parsed = datetime.fromisoformat(text.replace("Z", "+00:00"))
    except ValueError as exc:
        raise QueueError(f"{label} must be an ISO-8601 timestamp") from exc
    if parsed.tzinfo is None:
        raise QueueError(f"{label} must include a timezone")
    return parsed


def parse_artifact(value: Any) -> Artifact:
    if not isinstance(value, dict):
        raise QueueError("artifact must be an object")
    workflow_run = value.get("workflow_run")
    if not isinstance(workflow_run, dict):
        raise QueueError("artifact.workflow_run must be an object")
    digest = _text(value.get("digest"), "artifact.digest")
    head_sha = _text(workflow_run.get("head_sha"), "artifact.workflow_run.head_sha")
    if not DIGEST.fullmatch(digest) or not SHA.fullmatch(head_sha):
        raise QueueError("artifact has an invalid digest or head SHA")
    expired = value.get("expired")
    if not isinstance(expired, bool):
        raise QueueError("artifact.expired must be a boolean")
    return Artifact(
        artifact_id=_positive_int(value.get("id"), "artifact.id"),
        name=_text(value.get("name"), "artifact.name"),
        size_in_bytes=_positive_int(
            value.get("size_in_bytes"), "artifact.size_in_bytes"
        ),
        digest=digest,
        expired=expired,
        created_at=_timestamp(value.get("created_at"), "artifact.created_at"),
        run_id=_positive_int(workflow_run.get("id"), "artifact.workflow_run.id"),
        head_branch=_text(
            workflow_run.get("head_branch"), "artifact.workflow_run.head_branch"
        ),
        head_sha=head_sha,
    )


def valid_owner(
    run: dict[str, Any],
    *,
    repository: str,
    artifact: Artifact,
    workflow: str,
    events: frozenset[str],
    conclusions: frozenset[str],
    allow_in_progress: bool = False,
) -> bool:
    if not isinstance(run, dict):
        return False
    terminal = (
        run.get("status") == "completed" and run.get("conclusion") in conclusions
    )
    active = (
        allow_in_progress
        and run.get("status") == "in_progress"
        and run.get("conclusion") is None
    )
    return bool(
        run.get("id") == artifact.run_id
        and (terminal or active)
        and run.get("event") in events
        and run.get("path") == workflow
        and run.get("head_branch") == "master"
        and run.get("head_sha") == artifact.head_sha
        and (run.get("head_repository") or {}).get("full_name") == repository
    )


def _valid_sources(
    api: QueueApi,
    artifacts: list[Artifact],
    *,
    repository: str,
    pattern: re.Pattern[str],
    workflow: str,
    events: frozenset[str],
    conclusions: frozenset[str],
    allow_in_progress: bool = False,
) -> dict[int, list[Artifact]]:
    selected: dict[int, list[Artifact]] = {}
    run_cache: dict[int, dict[str, Any]] = {}
    for artifact in artifacts:
        match = pattern.fullmatch(artifact.name)
        if match is None or artifact.expired:
            continue
        source_run_id = int(match.group("source"))
        run = run_cache.get(artifact.run_id)
        if run is None:
            run = api.get_run(artifact.run_id)
            run_cache[artifact.run_id] = run
        if valid_owner(
            run,
            repository=repository,
            artifact=artifact,
            workflow=workflow,
            events=events,
            conclusions=conclusions,
            allow_in_progress=allow_in_progress,
        ):
            selected.setdefault(source_run_id, []).append(artifact)
    return selected


def reviewed_sources(
    api: QueueApi, artifacts: list[Artifact], *, repository: str
) -> set[int]:
    return set(
        _valid_sources(
            api,
            artifacts,
            repository=repository,
            pattern=REPORT_NAME,
            workflow=DRAIN_WORKFLOW,
            events=DRAIN_EVENTS,
            conclusions=frozenset({"success", "failure"}),
            allow_in_progress=True,
        )
    )


def select_pending(
    api: QueueApi,
    *,
    repository: str,
    now: datetime | None = None,
    cooldown: timedelta = timedelta(minutes=DEFAULT_COOLDOWN_MINUTES),
) -> tuple[Artifact, int] | None:
    artifacts = api.list_artifacts()
    if len(artifacts) > MAX_ARTIFACTS:
        raise QueueError(f"visual review artifact inventory exceeds {MAX_ARTIFACTS}")
    reviewed = reviewed_sources(api, artifacts, repository=repository)
    attempts = _valid_sources(
        api,
        artifacts,
        repository=repository,
        pattern=ATTEMPT_NAME,
        workflow=DRAIN_WORKFLOW,
        events=DRAIN_EVENTS,
        conclusions=frozenset({"failure"}),
        allow_in_progress=True,
    )
    current_time = now or datetime.now(timezone.utc)
    cooling = {
        source_run_id
        for source_run_id, values in attempts.items()
        if max(values, key=lambda item: item.order).created_at + cooldown > current_time
    }
    pending = _valid_sources(
        api,
        artifacts,
        repository=repository,
        pattern=INPUT_NAME,
        workflow=PREPARE_WORKFLOW,
        events=PREPARE_EVENTS,
        conclusions=frozenset({"success", "failure"}),
    )
    candidates = [
        (artifact, source_run_id)
        for source_run_id, values in pending.items()
        if source_run_id not in reviewed and source_run_id not in cooling
        for artifact in values
        if artifact.size_in_bytes <= MAX_INPUT_BYTES
    ]
    if not candidates:
        return None
    source_run_cache: dict[int, dict[str, Any]] = {}

    def priority(candidate: tuple[Artifact, int]) -> tuple[int, datetime, int]:
        artifact, source_run_id = candidate
        source_run = source_run_cache.get(source_run_id)
        if source_run is None:
            source_run = api.get_source_run(source_run_id)
            source_run_cache[source_run_id] = source_run
        branch = source_run.get("head_branch") if isinstance(source_run, dict) else None
        certifiable_anchor = bool(
            source_run.get("status") == "completed"
            and source_run.get("conclusion") == "success"
            and isinstance(branch, str)
            and ANCHOR_SOURCE_BRANCH.fullmatch(branch)
        )
        return (0 if certifiable_anchor else 1, artifact.created_at, artifact.artifact_id)

    return min(candidates, key=priority)


class GitHubApi:
    def __init__(self, *, repository: str, token: str, api_url: str) -> None:
        self.repository = repository
        self.token = token
        self.api_url = api_url.rstrip("/")
        self._runs: dict[int, dict[str, Any]] = {}

    def _request(self, path: str) -> Any:
        request = urllib.request.Request(
            f"{self.api_url}{path}",
            headers={
                "Accept": "application/vnd.github+json",
                "Authorization": f"Bearer {self.token}",
                "X-GitHub-Api-Version": "2022-11-28",
                "User-Agent": "Quick-Skin-visual-review-queue/1",
            },
        )
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                payload = response.read()
        except (urllib.error.HTTPError, urllib.error.URLError) as exc:
            raise QueueError(f"GitHub API request failed: {path}") from exc
        try:
            return json.loads(payload)
        except json.JSONDecodeError as exc:
            raise QueueError("GitHub API returned invalid JSON") from exc

    def list_artifacts(self) -> list[Artifact]:
        artifacts: list[Artifact] = []
        for page in range(1, 101):
            query = urllib.parse.urlencode({"per_page": 100, "page": page})
            payload = self._request(
                f"/repos/{self.repository}/actions/artifacts?{query}"
            )
            if not isinstance(payload, dict) or not isinstance(
                payload.get("artifacts"), list
            ):
                raise QueueError("artifact inventory response is invalid")
            batch = payload["artifacts"]
            artifacts.extend(
                parse_artifact(item)
                for item in batch
                if isinstance(item, dict)
                and isinstance(item.get("name"), str)
                and item["name"].startswith("visual-review")
            )
            if len(artifacts) > MAX_ARTIFACTS:
                raise QueueError(f"artifact inventory exceeds {MAX_ARTIFACTS}")
            if len(batch) < 100:
                return artifacts
        raise QueueError(f"artifact inventory exceeds {MAX_ARTIFACTS}")

    def get_run(self, run_id: int) -> dict[str, Any]:
        cached = self._runs.get(run_id)
        if cached is not None:
            return cached
        payload = self._request(f"/repos/{self.repository}/actions/runs/{run_id}")
        if not isinstance(payload, dict):
            raise QueueError("workflow run response is invalid")
        self._runs[run_id] = payload
        return payload

    def get_source_run(self, run_id: int) -> dict[str, Any]:
        return self.get_run(run_id)


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--github-output", type=Path, required=True)
    parser.add_argument(
        "--cooldown-minutes",
        type=int,
        default=DEFAULT_COOLDOWN_MINUTES,
    )
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        repository = args.repository.strip()
        if not REPOSITORY.fullmatch(repository):
            raise QueueError("repository must use owner/name form")
        if not 1 <= args.cooldown_minutes <= 24 * 60:
            raise QueueError("cooldown must be between 1 and 1440 minutes")
        token = os.environ.get("GH_TOKEN", "")
        if not token:
            raise QueueError("GH_TOKEN is required")
        selected = select_pending(
            GitHubApi(
                repository=repository,
                token=token,
                api_url=os.environ.get("GITHUB_API_URL", "https://api.github.com"),
            ),
            repository=repository,
            cooldown=timedelta(minutes=args.cooldown_minutes),
        )
        with args.github_output.open("a", encoding="utf-8") as output:
            if selected is None:
                output.write("eligible=false\n")
                return 0
            artifact, source_run_id = selected
            output.write("eligible=true\n")
            output.write(f"artifact_id={artifact.artifact_id}\n")
            output.write(f"artifact_name={artifact.name}\n")
            output.write(f"artifact_digest={artifact.digest.removeprefix('sha256:')}\n")
            output.write(f"artifact_size={artifact.size_in_bytes}\n")
            output.write(f"artifact_run_id={artifact.run_id}\n")
            output.write(f"implementation_sha={artifact.head_sha}\n")
            output.write(f"source_run_id={source_run_id}\n")
        return 0
    except (OSError, QueueError) as exc:
        print(f"Visual review queue error: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
