#!/usr/bin/env python3
"""Select one authenticated pending AI visual review from the durable artifact queue."""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import time
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
INPUT_NAME = re.compile(
    r"^visual-review-input-(?P<source>[1-9][0-9]*)"
    r"(?:-(?P<generation>[0-9a-f]{40}))?$"
)
REPORT_NAME = re.compile(r"^visual-review-(?P<source>[1-9][0-9]*)$")
ATTEMPT_NAME = re.compile(r"^visual-review-attempt-(?P<source>[1-9][0-9]*)$")
WAVE_BLOCK_NAME = re.compile(
    r"^visual-review-wave-block-(?P<generation>[0-9a-f]{40})$"
)
CERTIFICATE_NAME = re.compile(
    r"^visual-anchor-certification-(?P<generation>[0-9a-f]{40})$"
)
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
MAX_NAMED_ARTIFACTS = 1_000
MAX_CAPACITY_FANOUT = 256
REQUEST_ATTEMPTS = 4
RATE_LIMIT_REQUEST_ATTEMPTS = 4
MAX_RATE_LIMIT_WAIT_SECONDS = 300
RETRYABLE_HTTP_STATUSES = frozenset({408, 429, 500, 502, 503, 504})


class QueueError(RuntimeError):
    pass


class GitHubRateLimitError(QueueError):
    """The protected queue is intact, but GitHub temporarily cannot serve it."""


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

    def list_artifacts_named(self, name: str) -> list[Artifact]: ...

    def get_artifact(self, artifact_id: int) -> Artifact | None: ...

    def get_run(self, run_id: int) -> dict[str, Any]: ...

    def get_source_run(self, run_id: int) -> dict[str, Any]: ...

    def get_branch_sha(self, branch: str) -> str: ...

    def get_pull(self, number: int) -> dict[str, Any]: ...


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


def blocked_generations(
    api: QueueApi, artifacts: list[Artifact], *, repository: str
) -> set[str]:
    """Return exact master generations stopped by a protected confirmed-defect marker."""

    blocks: set[str] = set()
    run_cache: dict[int, dict[str, Any]] = {}
    for artifact in artifacts:
        match = WAVE_BLOCK_NAME.fullmatch(artifact.name)
        if match is None or artifact.expired:
            continue
        generation = match.group("generation")
        # The owner run still binds the marker to its exact protected reviewer implementation; the
        # name binds its verdict to the product generation, which must remain stopped even when
        # master advances and a newer protected drainer encounters an older queued sibling.
        if artifact.size_in_bytes > 1_048_576:
            continue
        run = run_cache.get(artifact.run_id)
        if run is None:
            run = api.get_run(artifact.run_id)
            run_cache[artifact.run_id] = run
        if valid_owner(
            run,
            repository=repository,
            artifact=artifact,
            workflow=DRAIN_WORKFLOW,
            events=DRAIN_EVENTS,
            conclusions=frozenset({"failure"}),
            allow_in_progress=True,
        ):
            blocks.add(generation)
    return blocks


def certified_generations(
    api: QueueApi, artifacts: list[Artifact], *, repository: str
) -> set[str]:
    """Return generations with a successful protected semantic anchor certificate."""

    certified: set[str] = set()
    run_cache: dict[int, dict[str, Any]] = {}
    for artifact in artifacts:
        match = CERTIFICATE_NAME.fullmatch(artifact.name)
        if match is None or artifact.expired or artifact.size_in_bytes > 16_777_216:
            continue
        run = run_cache.get(artifact.run_id)
        if run is None:
            run = api.get_run(artifact.run_id)
            run_cache[artifact.run_id] = run
        if valid_owner(
            run,
            repository=repository,
            artifact=artifact,
            workflow=DRAIN_WORKFLOW,
            events=DRAIN_EVENTS,
            conclusions=frozenset({"success"}),
        ):
            certified.add(match.group("generation"))
    return certified


def input_generation(artifact: Artifact) -> str:
    """Generation encoded by a current input, with a legacy implementation-SHA fallback."""

    match = INPUT_NAME.fullmatch(artifact.name)
    if match is None:
        raise QueueError("visual review input name is invalid")
    return match.group("generation") or artifact.head_sha


def source_is_eligible(
    api: QueueApi,
    *,
    artifact: Artifact,
    source_run_id: int,
    certified: set[str],
    repository: str,
) -> bool:
    """Reject superseded generations and closed PR evidence before model admission."""

    source_run = api.get_source_run(source_run_id)
    if not isinstance(source_run, dict):
        return False
    branch = source_run.get("head_branch")
    event = source_run.get("event")
    generation = input_generation(artifact)
    current_master = api.get_branch_sha("master")
    if not SHA.fullmatch(current_master) or generation != current_master:
        return False
    release_anchor = bool(
        isinstance(branch, str)
        and (
            ANCHOR_SOURCE_BRANCH.fullmatch(branch)
            or (event == "schedule" and branch == "master")
        )
    )
    if release_anchor:
        if generation in certified:
            return False

    # GitHub preserves the pull_request association on completed workflow runs. If it is
    # available, require that exact head to remain open; evidence for a closed or superseded PR
    # cannot affect the current product and must not spend reviewer capacity.
    pull_requests = source_run.get("pull_requests")
    if event == "pull_request" and isinstance(pull_requests, list) and pull_requests:
        exact_open = False
        for association in pull_requests:
            if not isinstance(association, dict):
                continue
            number = association.get("number")
            if isinstance(number, bool) or not isinstance(number, int) or number <= 0:
                continue
            pull = api.get_pull(number)
            head = pull.get("head") if isinstance(pull, dict) else None
            repo = head.get("repo") if isinstance(head, dict) else None
            if (
                pull.get("state") == "open"
                and head.get("sha") == source_run.get("head_sha")
                and head.get("ref") == branch
                and isinstance(repo, dict)
                and repo.get("full_name") == repository
            ):
                exact_open = True
                break
        if not exact_open:
            return False
    return True


def select_pending(
    api: QueueApi,
    *,
    repository: str,
    now: datetime | None = None,
    cooldown: timedelta = timedelta(minutes=DEFAULT_COOLDOWN_MINUTES),
    requested_artifact_id: int | None = None,
) -> tuple[Artifact, int] | None:
    candidates = list_pending_candidates(
        api,
        repository=repository,
        now=now,
        cooldown=cooldown,
    )
    if requested_artifact_id is not None:
        if requested_artifact_id <= 0:
            raise QueueError("requested artifact id must be a positive integer")
        requested = [
            candidate
            for candidate in candidates
            if candidate[0].artifact_id == requested_artifact_id
        ]
        if len(requested) > 1:
            raise QueueError("requested artifact id is ambiguous")
        return requested[0] if requested else None
    return candidates[0] if candidates else None


def list_pending_candidates(
    api: QueueApi,
    *,
    repository: str,
    now: datetime | None = None,
    cooldown: timedelta = timedelta(minutes=DEFAULT_COOLDOWN_MINUTES),
) -> list[tuple[Artifact, int]]:
    """Return every eligible source once, with a certifiable anchor first."""

    artifacts = api.list_artifacts()
    if len(artifacts) > MAX_ARTIFACTS:
        raise QueueError(f"visual review artifact inventory exceeds {MAX_ARTIFACTS}")
    reviewed = reviewed_sources(api, artifacts, repository=repository)
    blocked = blocked_generations(api, artifacts, repository=repository)
    certified = certified_generations(api, artifacts, repository=repository)
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
    raw_candidates: list[tuple[Artifact, int]] = []
    for source_run_id, values in pending.items():
        if source_run_id in reviewed or source_run_id in cooling:
            continue
        for artifact in values:
            if (
                artifact.size_in_bytes <= MAX_INPUT_BYTES
                and input_generation(artifact) not in blocked
                and source_is_eligible(
                    api,
                    artifact=artifact,
                    source_run_id=source_run_id,
                    certified=certified,
                    repository=repository,
                )
            ):
                raw_candidates.append((artifact, source_run_id))
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

    # A retried curator can leave multiple still-authenticated inputs for one source run. Dispatch
    # only its newest immutable artifact; a duplicate source must never multiply model sessions.
    newest_by_source: dict[int, Artifact] = {}
    for artifact, source_run_id in raw_candidates:
        current = newest_by_source.get(source_run_id)
        if current is None or artifact.order > current.order:
            newest_by_source[source_run_id] = artifact
    candidates = [
        (artifact, source_run_id)
        for source_run_id, artifact in newest_by_source.items()
    ]
    return sorted(candidates, key=priority)


def select_requested(
    api: QueueApi,
    *,
    repository: str,
    requested_artifact_id: int,
    now: datetime | None = None,
    cooldown: timedelta = timedelta(minutes=DEFAULT_COOLDOWN_MINUTES),
) -> tuple[Artifact, int] | None:
    """Authenticate one exact wake without scanning the repository-wide queue.

    Direct wakes already carry an immutable artifact id and are isolated by that id's workflow
    concurrency group. Query only that capsule and the three exact marker names that can make it
    ineligible. Generic scheduled recovery still uses :func:`select_pending` so it can prioritize
    the oldest source across the complete durable queue.
    """

    if requested_artifact_id <= 0:
        raise QueueError("requested artifact id must be a positive integer")
    requested = api.get_artifact(requested_artifact_id)
    if requested is None:
        # A duplicate wake may start after the successful drain deleted its exact capsule.
        return None
    match = INPUT_NAME.fullmatch(requested.name)
    if (
        match is None
        or requested.expired
        or requested.size_in_bytes > MAX_INPUT_BYTES
    ):
        return None
    source_run_id = int(match.group("source"))
    generation = input_generation(requested)
    related: list[Artifact] = []
    for name in (
        f"visual-review-{source_run_id}",
        f"visual-review-attempt-{source_run_id}",
        f"visual-review-wave-block-{generation}",
        f"visual-anchor-certification-{generation}",
    ):
        named = api.list_artifacts_named(name)
        if len(named) > MAX_NAMED_ARTIFACTS:
            raise QueueError(f"visual review artifact name exceeds {MAX_NAMED_ARTIFACTS}")
        related.extend(named)

    if source_run_id in reviewed_sources(api, related, repository=repository):
        return None
    if generation in blocked_generations(api, related, repository=repository):
        return None
    certified = certified_generations(api, related, repository=repository)
    attempts = _valid_sources(
        api,
        related,
        repository=repository,
        pattern=ATTEMPT_NAME,
        workflow=DRAIN_WORKFLOW,
        events=DRAIN_EVENTS,
        conclusions=frozenset({"failure"}),
        allow_in_progress=True,
    )
    current_time = now or datetime.now(timezone.utc)
    if any(
        artifact.created_at + cooldown > current_time
        for artifact in attempts.get(source_run_id, [])
    ):
        return None
    pending = _valid_sources(
        api,
        [requested],
        repository=repository,
        pattern=INPUT_NAME,
        workflow=PREPARE_WORKFLOW,
        events=PREPARE_EVENTS,
        conclusions=frozenset({"success", "failure"}),
    )
    authenticated = pending.get(source_run_id, [])
    if len(authenticated) != 1 or authenticated[0].artifact_id != requested_artifact_id:
        return None
    if not source_is_eligible(
        api,
        artifact=requested,
        source_run_id=source_run_id,
        certified=certified,
        repository=repository,
    ):
        return None
    return requested, source_run_id


class GitHubApi:
    def __init__(self, *, repository: str, token: str, api_url: str) -> None:
        self.repository = repository
        self.token = token
        self.api_url = api_url.rstrip("/")
        self._runs: dict[int, dict[str, Any]] = {}
        self._branches: dict[str, str] = {}
        self._pulls: dict[int, dict[str, Any]] = {}

    @staticmethod
    def _rate_limited_http_error(
        exc: urllib.error.HTTPError, detail: str
    ) -> bool:
        if exc.code not in {403, 429}:
            return False
        return bool(
            exc.headers.get("X-RateLimit-Remaining", "") == "0"
            or exc.headers.get("Retry-After", "")
            or "rate limit" in detail.lower()
        )

    @classmethod
    def _retryable_http_error(cls, exc: urllib.error.HTTPError) -> bool:
        try:
            detail = exc.read().decode("utf-8", errors="replace")
        except OSError:
            detail = ""
        if exc.code in RETRYABLE_HTTP_STATUSES:
            return True
        return cls._rate_limited_http_error(exc, detail)

    @staticmethod
    def _retry_delay(
        path: str,
        attempt: int,
    ) -> float:
        # Deterministic sub-second skew prevents a parallel release wave from retrying in lockstep.
        jitter = (sum(path.encode("utf-8")) % 997) / 997
        return min(2**attempt, 30) + jitter

    @staticmethod
    def _rate_limit_retry_delay(
        path: str, attempt: int, *, headers: Any
    ) -> float:
        # GitHub explicitly requires primary callers to wait until X-RateLimit-Reset and secondary
        # callers without Retry-After to wait at least one minute. Never cap that declared delay and
        # accidentally poll the exhausted bucket early.
        jitter = (sum(path.encode("utf-8")) % 997) / 997
        delay = max(60, 2**attempt)
        retry_after = headers.get("Retry-After", "")
        reset_at = headers.get("X-RateLimit-Reset", "")
        try:
            delay = max(delay, int(retry_after))
        except (TypeError, ValueError):
            pass
        try:
            delay = max(delay, int(reset_at) - int(time.time()) + 2)
        except (TypeError, ValueError):
            pass
        return max(delay, 0) + jitter

    def _request(self, path: str, *, missing_ok: bool = False) -> Any:
        last_error: BaseException | None = None
        transient_attempts = 0
        rate_limit_attempts = 0
        while True:
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
                break
            except urllib.error.HTTPError as exc:
                if exc.code == 404 and missing_ok:
                    return None
                last_error = exc
                try:
                    detail = exc.read().decode("utf-8", errors="replace")
                except OSError:
                    detail = ""
                rate_limited = self._rate_limited_http_error(exc, detail)
                retryable = rate_limited or exc.code in RETRYABLE_HTTP_STATUSES
                if rate_limited:
                    retry_attempt = rate_limit_attempts
                    rate_limit_attempts += 1
                    exhausted = rate_limit_attempts >= RATE_LIMIT_REQUEST_ATTEMPTS
                    retry_delay = self._rate_limit_retry_delay(
                        path, retry_attempt, headers=exc.headers
                    )
                    if retry_delay > MAX_RATE_LIMIT_WAIT_SECONDS:
                        raise GitHubRateLimitError(
                            f"GitHub API rate limit reset is beyond the queue wait bound: {path}"
                        ) from last_error
                else:
                    retry_attempt = transient_attempts
                    transient_attempts += 1
                    exhausted = transient_attempts >= REQUEST_ATTEMPTS
                    retry_delay = self._retry_delay(path, retry_attempt)
            except (urllib.error.URLError, TimeoutError) as exc:
                last_error = exc
                rate_limited = False
                retryable = True
                retry_attempt = transient_attempts
                transient_attempts += 1
                exhausted = transient_attempts >= REQUEST_ATTEMPTS
                retry_delay = self._retry_delay(path, retry_attempt)
            if exhausted and rate_limited:
                raise GitHubRateLimitError(
                    f"GitHub API rate limit exhausted: {path}"
                ) from last_error
            if not retryable or exhausted:
                raise QueueError(f"GitHub API request failed: {path}") from last_error
            time.sleep(retry_delay)
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
                and (
                    item["name"].startswith("visual-review")
                    or item["name"].startswith("visual-anchor-certification-")
                )
            )
            if len(artifacts) > MAX_ARTIFACTS:
                raise QueueError(f"artifact inventory exceeds {MAX_ARTIFACTS}")
            if len(batch) < 100:
                return artifacts
        raise QueueError(f"artifact inventory exceeds {MAX_ARTIFACTS}")

    def list_artifacts_named(self, name: str) -> list[Artifact]:
        if not name or len(name) > 255:
            raise QueueError("artifact name is invalid")
        artifacts: list[Artifact] = []
        for page in range(1, 12):
            query = urllib.parse.urlencode(
                {"name": name, "per_page": 100, "page": page}
            )
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
                if isinstance(item, dict) and item.get("name") == name
            )
            if len(artifacts) > MAX_NAMED_ARTIFACTS:
                raise QueueError(
                    f"visual review artifact name exceeds {MAX_NAMED_ARTIFACTS}"
                )
            if len(batch) < 100:
                return artifacts
        raise QueueError(f"visual review artifact name exceeds {MAX_NAMED_ARTIFACTS}")

    def get_artifact(self, artifact_id: int) -> Artifact | None:
        if artifact_id <= 0:
            raise QueueError("artifact id must be a positive integer")
        payload = self._request(
            f"/repos/{self.repository}/actions/artifacts/{artifact_id}",
            missing_ok=True,
        )
        return None if payload is None else parse_artifact(payload)

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

    def get_branch_sha(self, branch: str) -> str:
        cached = self._branches.get(branch)
        if cached is not None:
            return cached
        encoded = urllib.parse.quote(branch, safe="")
        payload = self._request(
            f"/repos/{self.repository}/branches/{encoded}"
        )
        try:
            sha = payload["commit"]["sha"]
        except (KeyError, TypeError) as exc:
            raise QueueError("branch response is invalid") from exc
        if not isinstance(sha, str) or not SHA.fullmatch(sha):
            raise QueueError("branch response has an invalid SHA")
        self._branches[branch] = sha
        return sha

    def get_pull(self, number: int) -> dict[str, Any]:
        if number <= 0:
            raise QueueError("pull request number must be positive")
        cached = self._pulls.get(number)
        if cached is not None:
            return cached
        payload = self._request(f"/repos/{self.repository}/pulls/{number}")
        if not isinstance(payload, dict):
            raise QueueError("pull request response is invalid")
        self._pulls[number] = payload
        return payload


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--github-output", type=Path)
    parser.add_argument("--list-pending-json", type=Path)
    parser.add_argument(
        "--cooldown-minutes",
        type=int,
        default=DEFAULT_COOLDOWN_MINUTES,
    )
    parser.add_argument("--requested-artifact-id", type=int)
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
        api = GitHubApi(
            repository=repository,
            token=token,
            api_url=os.environ.get("GITHUB_API_URL", "https://api.github.com"),
        )
        selection_arguments = {
            "repository": repository,
            "cooldown": timedelta(minutes=args.cooldown_minutes),
        }
        if args.list_pending_json is not None:
            if args.github_output is not None or args.requested_artifact_id is not None:
                raise QueueError(
                    "pending-list mode cannot write step outputs or select one artifact"
                )
            candidates = list_pending_candidates(api, **selection_arguments)
            if len(candidates) > MAX_CAPACITY_FANOUT:
                raise QueueError(
                    f"Claude capacity fan-out exceeds {MAX_CAPACITY_FANOUT} entries"
                )
            payload = [
                {
                    "artifact_id": artifact.artifact_id,
                    "artifact_name": artifact.name,
                    "generation_sha": input_generation(artifact),
                }
                for artifact, _source_run_id in candidates
            ]
            with args.list_pending_json.open("x", encoding="utf-8") as output:
                json.dump(
                    payload,
                    output,
                    ensure_ascii=True,
                    separators=(",", ":"),
                    sort_keys=True,
                )
                output.write("\n")
            return 0
        if args.github_output is None:
            raise QueueError("--github-output is required when selecting one artifact")
        selected = (
            select_requested(
                api,
                requested_artifact_id=args.requested_artifact_id,
                **selection_arguments,
            )
            if args.requested_artifact_id is not None
            else select_pending(api, **selection_arguments)
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
            output.write(f"generation_sha={input_generation(artifact)}\n")
            output.write(f"source_run_id={source_run_id}\n")
        return 0
    except GitHubRateLimitError as exc:
        print(f"Visual review queue deferred: {exc}", file=sys.stderr)
        return 75
    except (OSError, QueueError) as exc:
        print(f"Visual review queue error: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
