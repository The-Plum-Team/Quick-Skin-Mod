#!/usr/bin/env python3
"""Retire superseded GitHub Pages evidence after an atomic site deployment."""

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
from datetime import datetime
from pathlib import Path
from typing import Any, Protocol


REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "scripts" / "release"))

from evidence import PublicEvidenceError, validate_bundle  # noqa: E402
from version_branches import parse_version_branch  # noqa: E402


REPOSITORY = re.compile(r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")
SHA = re.compile(r"^[0-9a-f]{40}$")
PAGES_WORKFLOW = ".github/workflows/pages.yml"
E2E_WORKFLOW = ".github/workflows/on-demand-e2e.yml"
PAGES_EVENTS = frozenset({"schedule", "workflow_dispatch", "workflow_run"})
REQUEST_ATTEMPTS = 4
RETRYABLE_HTTP_STATUSES = frozenset({408, 429, 500, 502, 503, 504})


class RotationError(RuntimeError):
    """Raised when evidence cannot be retired without weakening provenance."""


class ApiError(RotationError):
    def __init__(self, status: int, message: str) -> None:
        super().__init__(message)
        self.status = status


@dataclass(frozen=True)
class Artifact:
    artifact_id: int
    name: str
    expired: bool
    size_in_bytes: int
    created_at: str
    run_id: int
    head_branch: str
    head_sha: str

    @property
    def order(self) -> tuple[datetime, int]:
        return (_timestamp(self.created_at, "artifact.created_at"), self.artifact_id)

    @classmethod
    def parse(cls, value: Any) -> "Artifact":
        if not isinstance(value, dict):
            raise RotationError("artifact must be an object")
        workflow_run = value.get("workflow_run")
        if not isinstance(workflow_run, dict):
            raise RotationError("artifact.workflow_run must be an object")
        return cls(
            artifact_id=_positive_int(value.get("id"), "artifact.id"),
            name=_text(value.get("name"), "artifact.name"),
            expired=_boolean(value.get("expired"), "artifact.expired"),
            size_in_bytes=_positive_int(value.get("size_in_bytes"), "artifact.size_in_bytes"),
            created_at=_text(value.get("created_at"), "artifact.created_at"),
            run_id=_positive_int(workflow_run.get("id"), "artifact.workflow_run.id"),
            head_branch=_text(
                workflow_run.get("head_branch"), "artifact.workflow_run.head_branch"
            ),
            head_sha=_commit(workflow_run.get("head_sha"), "artifact.workflow_run.head_sha"),
        )


@dataclass(frozen=True)
class BranchGeneration:
    branch: str
    target_sha: str
    target_run_id: int
    keep: Artifact


class ArtifactApi(Protocol):
    def get_artifact(self, artifact_id: int) -> Artifact: ...

    def list_artifacts(self, name: str) -> list[Artifact]: ...

    def list_artifacts_with_prefix(self, prefix: str) -> list[Artifact]: ...

    def list_artifacts_for_run(self, run_id: int) -> list[Artifact]: ...

    def get_run(self, run_id: int) -> dict[str, Any]: ...

    def get_branch_sha(self, branch: str) -> str: ...

    def delete_artifact(self, artifact_id: int) -> None: ...


def _text(value: Any, label: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise RotationError(f"{label} must be a non-empty string")
    return value.strip()


def _boolean(value: Any, label: str) -> bool:
    if not isinstance(value, bool):
        raise RotationError(f"{label} must be a boolean")
    return value


def _positive_int(value: Any, label: str) -> int:
    if isinstance(value, bool):
        raise RotationError(f"{label} must be a positive integer")
    try:
        parsed = int(value)
    except (TypeError, ValueError) as exc:
        raise RotationError(f"{label} must be a positive integer") from exc
    if parsed <= 0 or str(parsed) != str(value):
        raise RotationError(f"{label} must be a positive integer")
    return parsed


def _commit(value: Any, label: str) -> str:
    text = _text(value, label)
    if not SHA.fullmatch(text):
        raise RotationError(f"{label} must be a lowercase 40-character commit SHA")
    return text


def _timestamp(value: Any, label: str) -> datetime:
    text = _text(value, label)
    try:
        parsed = datetime.fromisoformat(text.replace("Z", "+00:00"))
    except ValueError as exc:
        raise RotationError(f"{label} must be an ISO-8601 timestamp") from exc
    if parsed.tzinfo is None:
        raise RotationError(f"{label} must include a timezone")
    return parsed


def select_old_caches(
    artifacts: list[Artifact], *, branch: str, keep: Artifact
) -> list[Artifact]:
    legacy_name = f"pages-cache-{branch}"
    sha_name = re.compile(rf"{re.escape(legacy_name)}--[0-9a-f]{{40}}")
    return sorted(
        (
            artifact
            for artifact in artifacts
            if (artifact.name == legacy_name or sha_name.fullmatch(artifact.name))
            and not artifact.expired
            and artifact.artifact_id != keep.artifact_id
            and artifact.head_branch == "master"
            and artifact.order < keep.order
        ),
        key=lambda artifact: artifact.order,
    )


def select_consumed_handoffs(
    artifacts: list[Artifact],
    *,
    branch: str,
    target_run_id: int,
    target_sha: str,
    keep: Artifact,
) -> list[Artifact]:
    expected_name = f"pages-e2e-{branch}"
    return sorted(
        (
            artifact
            for artifact in artifacts
            if artifact.name == expected_name
            and not artifact.expired
            and artifact.run_id == target_run_id
            and artifact.head_branch == branch
            and artifact.head_sha == target_sha
            and artifact.order < keep.order
        ),
        key=lambda artifact: artifact.order,
    )


def select_old_handoffs(
    artifacts: list[Artifact], *, branch: str, keep: Artifact
) -> list[Artifact]:
    """Select only older raw generations after a validated raw replacement exists."""

    expected_name = f"pages-e2e-{branch}"
    return sorted(
        (
            artifact
            for artifact in artifacts
            if artifact.name == expected_name
            and not artifact.expired
            and artifact.artifact_id != keep.artifact_id
            and artifact.head_branch == branch
            and artifact.order < keep.order
        ),
        key=lambda artifact: artifact.order,
    )


def select_pages_run_transients(
    artifacts: list[Artifact],
    *,
    generations: list[BranchGeneration],
    pages_run_id: int,
    pages_run_sha: str,
) -> list[Artifact]:
    """Select fan-in/deploy artifacts made redundant by the successful Pages caches."""

    expected_names = {"github-pages"}
    expected_names.update(
        f"collected-pages-{generation.branch}" for generation in generations
    )
    return sorted(
        (
            artifact
            for artifact in artifacts
            if not artifact.expired
            and artifact.name in expected_names
            and artifact.run_id == pages_run_id
            and artifact.head_branch == "master"
            and artifact.head_sha == pages_run_sha
        ),
        key=lambda artifact: artifact.order,
    )


class GitHubApi:
    def __init__(self, *, repository: str, token: str, api_url: str) -> None:
        self.repository = repository
        self.token = token
        self.api_url = api_url.rstrip("/")

    @staticmethod
    def _retry_delay(path: str, attempt: int) -> float:
        jitter = (sum(path.encode("utf-8")) % 997) / 997
        return min(2**attempt, 30) + jitter

    @staticmethod
    def _retryable_http_error(
        exc: urllib.error.HTTPError, detail: str
    ) -> bool:
        if exc.code in RETRYABLE_HTTP_STATUSES:
            return True
        if exc.code != 403:
            return False
        return bool(
            exc.headers.get("X-RateLimit-Remaining", "") == "0"
            or exc.headers.get("Retry-After", "")
            or "rate limit" in detail.lower()
        )

    def _request(self, method: str, path: str) -> Any:
        last_error: BaseException | None = None
        last_status = 0
        last_detail = ""
        for attempt in range(REQUEST_ATTEMPTS):
            request = urllib.request.Request(
                f"{self.api_url}{path}",
                method=method,
                headers={
                    "Accept": "application/vnd.github+json",
                    "Authorization": f"Bearer {self.token}",
                    "X-GitHub-Api-Version": "2022-11-28",
                    "User-Agent": "Quick-Skin-Pages-evidence-rotation/1",
                },
            )
            try:
                with urllib.request.urlopen(request, timeout=30) as response:
                    body = response.read()
                break
            except urllib.error.HTTPError as exc:
                last_error = exc
                last_status = exc.code
                last_detail = exc.read().decode("utf-8", errors="replace")
                retryable = self._retryable_http_error(exc, last_detail)
            except (urllib.error.URLError, TimeoutError) as exc:
                last_error = exc
                last_status = 0
                last_detail = str(exc)
                retryable = True
            if not retryable or attempt + 1 == REQUEST_ATTEMPTS:
                raise ApiError(
                    last_status,
                    f"GitHub API {method} {path} failed: {last_detail}",
                ) from last_error
            time.sleep(self._retry_delay(path, attempt))
        else:  # pragma: no cover - the loop either breaks or raises
            raise ApiError(
                last_status,
                f"GitHub API {method} {path} failed: {last_detail}",
            ) from last_error
        if not body:
            return None
        try:
            return json.loads(body)
        except json.JSONDecodeError as exc:
            raise RotationError(f"GitHub API {method} {path} returned invalid JSON") from exc

    def _repo_path(self, suffix: str) -> str:
        return f"/repos/{self.repository}{suffix}"

    def get_artifact(self, artifact_id: int) -> Artifact:
        payload = self._request(
            "GET", self._repo_path(f"/actions/artifacts/{artifact_id}")
        )
        return Artifact.parse(payload)

    def list_artifacts(self, name: str) -> list[Artifact]:
        query = {"name": name}
        return self._list_artifacts(
            self._repo_path("/actions/artifacts"), query=query
        )

    def list_artifacts_with_prefix(self, prefix: str) -> list[Artifact]:
        return self._list_artifacts(
            self._repo_path("/actions/artifacts"), query={}, name_prefix=prefix
        )

    def list_artifacts_for_run(self, run_id: int) -> list[Artifact]:
        return self._list_artifacts(
            self._repo_path(f"/actions/runs/{run_id}/artifacts"), query={}
        )

    def _list_artifacts(
        self,
        path: str,
        *,
        query: dict[str, Any],
        name_prefix: str | None = None,
    ) -> list[Artifact]:
        artifacts: list[Artifact] = []
        for page in range(1, 1001):
            parameters = {**query, "per_page": 100, "page": page}
            encoded = urllib.parse.urlencode(parameters)
            payload = self._request("GET", f"{path}?{encoded}")
            if not isinstance(payload, dict) or not isinstance(payload.get("artifacts"), list):
                raise RotationError("artifact inventory response is invalid")
            batch = payload["artifacts"]
            for item in batch:
                if name_prefix is not None and (
                    not isinstance(item, dict)
                    or not isinstance(item.get("name"), str)
                    or not item["name"].startswith(name_prefix)
                ):
                    continue
                artifacts.append(Artifact.parse(item))
            if len(batch) < 100:
                return artifacts
        raise RotationError("artifact inventory exceeded 100,000 entries")

    def get_run(self, run_id: int) -> dict[str, Any]:
        payload = self._request("GET", self._repo_path(f"/actions/runs/{run_id}"))
        if not isinstance(payload, dict):
            raise RotationError("workflow run response is invalid")
        return payload

    def get_branch_sha(self, branch: str) -> str:
        encoded = urllib.parse.quote(branch, safe="")
        payload = self._request("GET", self._repo_path(f"/branches/{encoded}"))
        if not isinstance(payload, dict) or not isinstance(payload.get("commit"), dict):
            raise RotationError("branch response is invalid")
        return _commit(payload["commit"].get("sha"), "branch.commit.sha")

    def delete_artifact(self, artifact_id: int) -> None:
        self._request("DELETE", self._repo_path(f"/actions/artifacts/{artifact_id}"))


def _validate_run(
    run: dict[str, Any],
    *,
    repository: str,
    workflow: str,
    branch: str,
    sha: str,
    events: frozenset[str],
    require_success: bool,
) -> None:
    repository_name = (run.get("head_repository") or {}).get("full_name")
    if (
        run.get("status") != "completed"
        or (require_success and run.get("conclusion") != "success")
        or run.get("event") not in events
        or run.get("path") != workflow
        or run.get("head_branch") != branch
        or run.get("head_sha") != sha
        or repository_name != repository
    ):
        raise RotationError(f"workflow run {run.get('id')!r} failed provenance validation")


def _validate_keep(
    api: ArtifactApi,
    generation: BranchGeneration,
    *,
    repository: str,
    pages_run_id: int,
    pages_run_sha: str,
) -> None:
    if api.get_branch_sha(generation.branch) != generation.target_sha:
        raise RotationError(f"release head changed while rotating {generation.branch}")
    keep = api.get_artifact(generation.keep.artifact_id)
    if keep != generation.keep or keep.expired:
        raise RotationError(f"replacement cache changed while rotating {generation.branch}")
    owner = api.get_run(pages_run_id)
    _validate_run(
        owner,
        repository=repository,
        workflow=PAGES_WORKFLOW,
        branch="master",
        sha=pages_run_sha,
        events=PAGES_EVENTS,
        require_success=True,
    )


def _delete_exact_artifact(api: ArtifactApi, artifact: Artifact) -> bool:
    """Delete one immutable snapshot entry, treating a concurrent 404 as success."""

    try:
        current = api.get_artifact(artifact.artifact_id)
    except ApiError as exc:
        if exc.status == 404:
            return False
        raise
    if current != artifact or current.expired:
        raise RotationError(f"artifact changed before deletion: {artifact.artifact_id}")
    try:
        api.delete_artifact(artifact.artifact_id)
    except ApiError as exc:
        if exc.status == 404:
            return False
        raise
    return True


def rotate_branch(
    api: ArtifactApi,
    generation: BranchGeneration,
    *,
    repository: str,
    pages_run_id: int,
    pages_run_sha: str,
    delete_delay_seconds: float,
    preserve_handoff_branch: str | None = None,
) -> list[int]:
    if api.get_branch_sha(generation.branch) != generation.target_sha:
        print(f"head changed; rotation skipped for {generation.branch}")
        return []

    cache_name = f"pages-cache-{generation.branch}"
    handoff_name = f"pages-e2e-{generation.branch}"
    old_caches = select_old_caches(
        api.list_artifacts_with_prefix(f"{cache_name}--"),
        branch=generation.branch,
        keep=generation.keep,
    )
    old_caches.extend(
        select_old_caches(
            api.list_artifacts(cache_name), branch=generation.branch, keep=generation.keep
        )
    )
    old_caches = sorted(
        {artifact.artifact_id: artifact for artifact in old_caches}.values(),
        key=lambda artifact: artifact.order,
    )
    handoff_inventory = api.list_artifacts(handoff_name)
    consumed_handoffs = select_consumed_handoffs(
        handoff_inventory,
        branch=generation.branch,
        target_run_id=generation.target_run_id,
        target_sha=generation.target_sha,
        keep=generation.keep,
    )
    if generation.branch == preserve_handoff_branch:
        if len(consumed_handoffs) != 1:
            raise RotationError(
                f"lossless visual reference requires exactly one current handoff for "
                f"{generation.branch}"
            )
        retained_handoff = consumed_handoffs[0]
        handoffs = select_old_handoffs(
            handoff_inventory,
            branch=generation.branch,
            keep=retained_handoff,
        )
        _validate_run(
            api.get_run(retained_handoff.run_id),
            repository=repository,
            workflow=E2E_WORKFLOW,
            branch=generation.branch,
            sha=generation.target_sha,
            events=frozenset({"workflow_dispatch"}),
            require_success=True,
        )
    else:
        handoffs = consumed_handoffs
    for artifact in old_caches:
        _validate_run(
            api.get_run(artifact.run_id),
            repository=repository,
            workflow=PAGES_WORKFLOW,
            branch="master",
            sha=artifact.head_sha,
            events=PAGES_EVENTS,
            require_success=False,
        )
    for artifact in handoffs:
        _validate_run(
            api.get_run(artifact.run_id),
            repository=repository,
            workflow=E2E_WORKFLOW,
            branch=generation.branch,
            sha=artifact.head_sha,
            events=frozenset({"workflow_dispatch"}),
            require_success=True,
        )
    deleted: list[int] = []
    for artifact in (*old_caches, *handoffs):
        _validate_keep(
            api,
            generation,
            repository=repository,
            pages_run_id=pages_run_id,
            pages_run_sha=pages_run_sha,
        )
        if generation.branch == preserve_handoff_branch:
            current_handoff = api.get_artifact(retained_handoff.artifact_id)
            if current_handoff != retained_handoff or current_handoff.expired:
                raise RotationError(
                    f"lossless visual reference changed while rotating {generation.branch}"
                )
        if _delete_exact_artifact(api, artifact):
            deleted.append(artifact.artifact_id)
        if delete_delay_seconds:
            time.sleep(delete_delay_seconds)
    return deleted


def rotate_generations(
    api: ArtifactApi,
    generations: list[BranchGeneration],
    *,
    repository: str,
    pages_run_id: int,
    pages_run_sha: str,
    delete_delay_seconds: float,
    preserve_handoff_branch: str | None = None,
) -> tuple[dict[str, list[int]], list[str]]:
    """Rotate each branch independently so one moved head cannot strand the rest."""

    summary: dict[str, list[int]] = {}
    deferred: list[str] = []
    for generation in generations:
        try:
            summary[generation.branch] = rotate_branch(
                api,
                generation,
                repository=repository,
                pages_run_id=pages_run_id,
                pages_run_sha=pages_run_sha,
                delete_delay_seconds=delete_delay_seconds,
                preserve_handoff_branch=preserve_handoff_branch,
            )
        except RotationError as exc:
            # Every deletion is individually revalidated inside rotate_branch, so a
            # mid-rotation head or keep change only defers this branch's remaining
            # artifacts to the next successful deployment's rotation.
            print(
                f"Pages evidence rotation deferred for {generation.branch}: {exc}",
                file=sys.stderr,
            )
            summary[generation.branch] = []
            deferred.append(generation.branch)
    return summary, deferred


def retire_pages_run_transients(
    api: ArtifactApi,
    *,
    generations: list[BranchGeneration],
    trigger_artifacts: list[Artifact],
    repository: str,
    pages_run_id: int,
    pages_run_sha: str,
    delete_delay_seconds: float,
) -> list[int]:
    transients = select_pages_run_transients(
        trigger_artifacts,
        generations=generations,
        pages_run_id=pages_run_id,
        pages_run_sha=pages_run_sha,
    )
    deleted: list[int] = []
    for artifact in transients:
        for generation in generations:
            _validate_keep(
                api,
                generation,
                repository=repository,
                pages_run_id=pages_run_id,
                pages_run_sha=pages_run_sha,
            )
        if _delete_exact_artifact(api, artifact):
            deleted.append(artifact.artifact_id)
        if delete_delay_seconds:
            time.sleep(delete_delay_seconds)
    return deleted


def load_generations(
    *,
    evidence_root: Path,
    repository: str,
    pages_run_id: int,
    pages_run_sha: str,
    trigger_artifacts: list[Artifact],
) -> list[BranchGeneration]:
    try:
        entries = list(evidence_root.iterdir())
        exact_root = bool(entries) and all(
            not path.is_symlink()
            and path.is_dir()
            and parse_version_branch(path.name) is not None
            for path in entries
        )
    except OSError as exc:
        raise RotationError(f"cannot inspect cache generation: {exc}") from exc
    if not exact_root:
        raise RotationError("cache generation must contain only release-branch directories")
    branches = sorted(path.name for path in entries)

    generations: list[BranchGeneration] = []
    for branch in branches:
        try:
            manifest = validate_bundle(
                evidence_root,
                branch,
                expected_kind="compact",
                expected_repository=repository,
            )
        except PublicEvidenceError as exc:
            raise RotationError(str(exc)) from exc
        provenance = manifest["provenance"]["target"]
        target_sha = _commit(provenance.get("sha"), "manifest.provenance.target.sha")
        target_run_id = _positive_int(
            provenance.get("run_id"), "manifest.provenance.target.run_id"
        )
        expected_name = f"pages-cache-{branch}--{target_sha}"
        matching = [
            artifact
            for artifact in trigger_artifacts
            if artifact.name == expected_name
            and not artifact.expired
            and artifact.run_id == pages_run_id
            and artifact.head_branch == "master"
            and artifact.head_sha == pages_run_sha
        ]
        if len(matching) != 1:
            raise RotationError(
                f"Pages run must own exactly one current cache for {branch}: {len(matching)}"
            )
        generations.append(
            BranchGeneration(
                branch=branch,
                target_sha=target_sha,
                target_run_id=target_run_id,
                keep=matching[0],
            )
        )

    expected_names = {generation.keep.name for generation in generations}
    actual_names = {
        artifact.name
        for artifact in trigger_artifacts
        if artifact.name.startswith("pages-cache-") and not artifact.expired
    }
    if actual_names != expected_names:
        raise RotationError(
            "Pages run cache inventory disagrees with downloaded evidence: "
            f"expected={sorted(expected_names)}, actual={sorted(actual_names)}"
        )
    return generations


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence-root", type=Path, required=True)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--pages-run-id", required=True)
    parser.add_argument("--pages-run-sha", required=True)
    parser.add_argument("--delete-delay-seconds", type=float, default=1.0)
    parser.add_argument(
        "--preserve-raw-branch",
        help="retain the newest validated raw handoff for this visual-reference branch",
    )
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        repository = _text(args.repository, "repository")
        if not REPOSITORY.fullmatch(repository):
            raise RotationError("repository must use the owner/name form")
        pages_run_id = _positive_int(args.pages_run_id, "pages_run_id")
        pages_run_sha = _commit(args.pages_run_sha, "pages_run_sha")
        if args.delete_delay_seconds < 0 or args.delete_delay_seconds > 10:
            raise RotationError("delete delay must be between 0 and 10 seconds")
        preserve_raw_branch = args.preserve_raw_branch
        if (
            preserve_raw_branch is not None
            and parse_version_branch(preserve_raw_branch) is None
        ):
            raise RotationError("preserved raw branch must be a release branch")
        token = os.environ.get("GH_TOKEN", "")
        if not token:
            raise RotationError("GH_TOKEN is required")
        api = GitHubApi(
            repository=repository,
            token=token,
            api_url=os.environ.get("GITHUB_API_URL", "https://api.github.com"),
        )
        pages_run = api.get_run(pages_run_id)
        _validate_run(
            pages_run,
            repository=repository,
            workflow=PAGES_WORKFLOW,
            branch="master",
            sha=pages_run_sha,
            events=PAGES_EVENTS,
            require_success=True,
        )
        trigger_artifacts = api.list_artifacts_for_run(pages_run_id)
        generations = load_generations(
            evidence_root=args.evidence_root.resolve(),
            repository=repository,
            pages_run_id=pages_run_id,
            pages_run_sha=pages_run_sha,
            trigger_artifacts=trigger_artifacts,
        )
        summary, deferred = rotate_generations(
            api,
            generations,
            repository=repository,
            pages_run_id=pages_run_id,
            pages_run_sha=pages_run_sha,
            delete_delay_seconds=args.delete_delay_seconds,
            preserve_handoff_branch=preserve_raw_branch,
        )
        try:
            pages_run_deleted = retire_pages_run_transients(
                api,
                generations=generations,
                trigger_artifacts=trigger_artifacts,
                repository=repository,
                pages_run_id=pages_run_id,
                pages_run_sha=pages_run_sha,
                delete_delay_seconds=args.delete_delay_seconds,
            )
        except RotationError as exc:
            # Same defer-not-abort posture: the fan-in and deploy transients are short-lived
            # uploads, so a mid-rotation keep change leaves them to their own retention.
            print(f"Pages run transient retirement deferred: {exc}", file=sys.stderr)
            pages_run_deleted = []
        print(
            json.dumps(
                {
                    "deferred_branches": deferred,
                    "deleted_artifact_ids": summary,
                    "deleted_pages_run_artifact_ids": pages_run_deleted,
                },
                sort_keys=True,
            )
        )
        return 0
    except (RotationError, PublicEvidenceError) as exc:
        print(f"Pages evidence rotation error: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
