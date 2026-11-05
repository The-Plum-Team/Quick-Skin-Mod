#!/usr/bin/env python3
"""Prune unusable and superseded GitHub Actions caches conservatively.

Every cache for an absent branch is disposable.  For a live branch, only an
old ``setup-gradle`` Gradle-home generation with an unambiguous SHA-bearing key
is eligible, and only after a retained generation is tied to a successful Build
gate.  Any active workflow run preserves the complete cache inventory; unknown
cache-key formats, tags, and pull-request refs are always preserved.  Dry-run is the default;
callers must pass ``--apply`` to delete anything.
"""

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
from typing import Any, Protocol


REPOSITORY = re.compile(r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")
COMMIT_SHA = re.compile(r"^[0-9a-f]{40}$")
# setup-gradle keys a home as ``gradle-home-vN|<os>|<job>[<job instance>]-<sha>``.  The job
# instance hashes the workflow name and the job's matrix values, so every Build matrix target
# restores its own ``...|<job>[<instance>]`` prefix first: the instance is part of the restore
# family, and a generation of one target never replaces another target's.
GRADLE_HOME_KEY = re.compile(
    r"^(gradle-home-v[1-9][0-9]*\|[A-Za-z0-9_.-]+\|"
    r"[A-Za-z0-9_.-]+\[[0-9a-f]{32}\])-([0-9a-f]{40})$"
)
BRANCH_REF_PREFIX = "refs/heads/"
ACTIVE_RUN_STATUSES = ("requested", "pending", "queued", "in_progress", "waiting")
BUILD_WORKFLOW_FILE = "build-gate.yml"
BUILD_WORKFLOW_PATH = ".github/workflows/build-gate.yml"
BUILD_JOB_NAME = "Build and verify"
BUILD_WRITER_EVENTS = frozenset({"push", "workflow_dispatch"})
PRUNE_WORKFLOW_PATH = ".github/workflows/prune-actions-caches.yml"
PAGE_SIZE = 100
MAX_PAGES = 100
MAX_ACTIVE_RUN_RESULTS = 1_000
MAX_BUILD_RUNS_PER_SHA = 100
MAX_JOBS_PER_RUN = 100
DEFAULT_MAX_DELETE_COUNT = 75
DEFAULT_MAX_DELETE_BYTES = 10 * 1024 * 1024 * 1024


class PruneError(RuntimeError):
    """Raised when a safe pruning decision cannot be made."""


class ApiError(PruneError):
    def __init__(self, status: int, message: str) -> None:
        super().__init__(message)
        self.status = status


@dataclass(frozen=True)
class CacheEntry:
    cache_id: int
    ref: str
    key: str
    version: str
    size_in_bytes: int
    created_at: str
    last_accessed_at: str

    @classmethod
    def parse(cls, value: Any) -> "CacheEntry":
        if not isinstance(value, dict):
            raise PruneError("cache entry must be an object")
        return cls(
            cache_id=_positive_int(value.get("id"), "cache.id"),
            ref=_text(value.get("ref"), "cache.ref"),
            key=_text(value.get("key"), "cache.key"),
            version=_text(value.get("version"), "cache.version"),
            size_in_bytes=_non_negative_int(
                value.get("size_in_bytes"), "cache.size_in_bytes"
            ),
            created_at=_timestamp(value.get("created_at"), "cache.created_at"),
            last_accessed_at=_timestamp(
                value.get("last_accessed_at"), "cache.last_accessed_at"
            ),
        )

    @property
    def branch(self) -> str | None:
        if not self.ref.startswith(BRANCH_REF_PREFIX):
            return None
        branch = self.ref[len(BRANCH_REF_PREFIX) :]
        return branch or None

    @property
    def gradle_home_generation(self) -> tuple[str, str] | None:
        """Return the job-instance restore family and commit SHA for a known key."""
        match = GRADLE_HOME_KEY.fullmatch(self.key)
        if match is None:
            return None
        return match.group(1), match.group(2)

    def report(self) -> dict[str, Any]:
        return {
            "id": self.cache_id,
            "ref": self.ref,
            "key": self.key,
            "version": self.version,
            "size_in_bytes": self.size_in_bytes,
            "created_at": self.created_at,
            "last_accessed_at": self.last_accessed_at,
        }


class CacheApi(Protocol):
    def get_default_branch(self) -> str: ...

    def list_branches(self) -> set[str]: ...

    def list_active_run_branches(self) -> set[str]: ...

    def has_any_active_run(self) -> bool: ...

    def list_caches(self) -> list[CacheEntry]: ...

    def has_successful_build(self, branch: str, sha: str) -> bool: ...

    def branch_exists(self, branch: str) -> bool: ...

    def get_cache(self, expected: CacheEntry) -> CacheEntry | None: ...

    def delete_cache(self, cache_id: int) -> bool: ...


def _text(value: Any, label: str) -> str:
    if not isinstance(value, str) or not value:
        raise PruneError(f"{label} must be a non-empty string")
    return value


def _positive_int(value: Any, label: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value <= 0:
        raise PruneError(f"{label} must be a positive integer")
    return value


def _non_negative_int(value: Any, label: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        raise PruneError(f"{label} must be a non-negative integer")
    return value


def _timestamp(value: Any, label: str) -> str:
    text = _text(value, label)
    try:
        parsed = datetime.fromisoformat(text.replace("Z", "+00:00"))
    except ValueError as exc:
        raise PruneError(f"{label} must be an ISO-8601 timestamp") from exc
    if parsed.tzinfo is None:
        raise PruneError(f"{label} must include a timezone")
    return text


class GitHubApi:
    def __init__(self, *, repository: str, token: str, api_url: str) -> None:
        self.repository = repository
        self.token = token
        self.api_url = api_url.rstrip("/")

    def _repo_path(self, suffix: str) -> str:
        return f"/repos/{self.repository}{suffix}"

    def _request(self, method: str, path: str) -> Any:
        request = urllib.request.Request(
            f"{self.api_url}{path}",
            method=method,
            headers={
                "Accept": "application/vnd.github+json",
                "Authorization": f"Bearer {self.token}",
                "X-GitHub-Api-Version": "2022-11-28",
                "User-Agent": "Quick-Skin-Actions-cache-pruner/1",
            },
        )
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                body = response.read()
        except urllib.error.HTTPError as exc:
            detail = exc.read().decode("utf-8", errors="replace")
            raise ApiError(
                exc.code, f"GitHub API {method} {path} failed: {detail}"
            ) from exc
        except urllib.error.URLError as exc:
            raise PruneError(f"GitHub API {method} {path} failed: {exc}") from exc
        if not body:
            return None
        try:
            return json.loads(body)
        except json.JSONDecodeError as exc:
            raise PruneError(
                f"GitHub API {method} {path} returned invalid JSON"
            ) from exc

    def _paginate(
        self,
        path: str,
        *,
        field: str | None,
        query: dict[str, str] | None = None,
        max_search_results: int | None = None,
    ) -> list[Any]:
        values: list[Any] = []
        for page in range(1, MAX_PAGES + 1):
            parameters = {
                **(query or {}),
                "per_page": str(PAGE_SIZE),
                "page": str(page),
            }
            encoded = urllib.parse.urlencode(parameters)
            payload = self._request("GET", f"{path}?{encoded}")
            if max_search_results is not None:
                if not isinstance(payload, dict):
                    raise PruneError(f"paginated response for {path} is invalid")
                total_count = _non_negative_int(
                    payload.get("total_count"),
                    f"paginated response for {path}.total_count",
                )
                if total_count > max_search_results:
                    raise PruneError(
                        f"paginated search for {path} has {total_count} results; "
                        f"API limit is {max_search_results}"
                    )
            if field is None:
                batch = payload
            elif isinstance(payload, dict):
                batch = payload.get(field)
            else:
                batch = None
            if not isinstance(batch, list):
                raise PruneError(f"paginated response for {path} is invalid")
            values.extend(batch)
            if len(batch) < PAGE_SIZE:
                return values
        raise PruneError(f"paginated response for {path} exceeded {MAX_PAGES} pages")

    def get_default_branch(self) -> str:
        payload = self._request("GET", self._repo_path(""))
        if not isinstance(payload, dict):
            raise PruneError("repository response is invalid")
        return _text(payload.get("default_branch"), "repository.default_branch")

    def list_branches(self) -> set[str]:
        payload = self._paginate(self._repo_path("/branches"), field=None)
        branches: set[str] = set()
        for item in payload:
            if not isinstance(item, dict):
                raise PruneError("branch inventory item must be an object")
            branches.add(_text(item.get("name"), "branch.name"))
        return branches

    def _active_run_inventory(self) -> tuple[set[str], bool]:
        branches: set[str] = set()
        any_run = False
        path = self._repo_path("/actions/runs")
        for status in ACTIVE_RUN_STATUSES:
            query = {"status": status}
            payload = self._paginate(
                path,
                field="workflow_runs",
                query=query,
                max_search_results=MAX_ACTIVE_RUN_RESULTS,
            )
            for item in payload:
                if not isinstance(item, dict):
                    raise PruneError("workflow run inventory item must be an object")
                if item.get("status") != status:
                    raise PruneError(
                        f"workflow run status disagrees with {status!r} inventory"
                    )
                workflow_path = _text(item.get("path"), "workflow_run.path")
                if workflow_path == PRUNE_WORKFLOW_PATH:
                    # The protected cleanup job itself runs on master but never configures or
                    # restores Gradle. Counting it would permanently self-lock master rotation.
                    continue
                any_run = True
                head_branch = item.get("head_branch")
                if head_branch is None:
                    continue
                parsed_branch = _text(head_branch, "workflow_run.head_branch")
                branches.add(parsed_branch)
        return branches, any_run

    def list_active_run_branches(self) -> set[str]:
        return self._active_run_inventory()[0]

    def has_any_active_run(self) -> bool:
        return self._active_run_inventory()[1]

    def _list_caches(self, *, query: dict[str, str] | None = None) -> list[CacheEntry]:
        payload = self._paginate(
            self._repo_path("/actions/caches"),
            field="actions_caches",
            query=query,
        )
        return [CacheEntry.parse(item) for item in payload]

    def list_caches(self) -> list[CacheEntry]:
        return self._list_caches()

    def _run_has_successful_build_job(
        self, *, run_id: int, branch: str, sha: str
    ) -> bool:
        jobs = self._paginate(
            self._repo_path(f"/actions/runs/{run_id}/jobs"),
            field="jobs",
            max_search_results=MAX_JOBS_PER_RUN,
        )
        for item in jobs:
            if not isinstance(item, dict):
                raise PruneError("workflow job inventory item must be an object")
            if _text(item.get("name"), "workflow_job.name") != BUILD_JOB_NAME:
                continue
            if _positive_int(item.get("run_id"), "workflow_job.run_id") != run_id:
                raise PruneError(f"Build job run ID disagrees with run {run_id}")
            if _text(item.get("head_branch"), "workflow_job.head_branch") != branch:
                raise PruneError(f"Build job branch disagrees with {branch!r}")
            if _text(item.get("head_sha"), "workflow_job.head_sha") != sha:
                raise PruneError(f"Build job SHA disagrees with {sha!r}")
            if item.get("status") != "completed":
                raise PruneError(
                    f"successful workflow run {run_id} has an incomplete Build job"
                )
            # A successful attestation-only invocation has this real job present but skipped.
            # That is a valid response shape, but it proves no cache-writing Build execution.
            return _text(item.get("conclusion"), "workflow_job.conclusion") == "success"
        return False

    def has_successful_build(self, branch: str, sha: str) -> bool:
        _text(branch, "branch")
        if not COMMIT_SHA.fullmatch(sha):
            raise PruneError("Build SHA must be 40 lowercase hexadecimal characters")
        runs = self._paginate(
            self._repo_path(f"/actions/workflows/{BUILD_WORKFLOW_FILE}/runs"),
            field="workflow_runs",
            query={"branch": branch, "head_sha": sha, "status": "success"},
            max_search_results=MAX_BUILD_RUNS_PER_SHA,
        )
        for item in runs:
            if not isinstance(item, dict):
                raise PruneError("successful Build run inventory item must be an object")
            run_id = _positive_int(item.get("id"), "workflow_run.id")
            if _text(item.get("path"), "workflow_run.path") != BUILD_WORKFLOW_PATH:
                raise PruneError(f"run {run_id} has an unexpected workflow path")
            if _text(item.get("head_branch"), "workflow_run.head_branch") != branch:
                raise PruneError(f"run {run_id} branch disagrees with {branch!r}")
            if _text(item.get("head_sha"), "workflow_run.head_sha") != sha:
                raise PruneError(f"run {run_id} SHA disagrees with {sha!r}")
            if item.get("status") != "completed" or item.get("conclusion") != "success":
                raise PruneError(f"run {run_id} disagrees with the success filter")
            if item.get("event") not in BUILD_WRITER_EVENTS:
                # Read-only events cannot prove that this branch-scoped generation came from
                # one of the policy's historical writer events.
                continue
            head_repository = item.get("head_repository")
            if not isinstance(head_repository, dict):
                raise PruneError(f"run {run_id} head repository is invalid")
            if (
                _text(head_repository.get("full_name"), "workflow_run.repository")
                != self.repository
            ):
                raise PruneError(f"run {run_id} belongs to an unexpected repository")
            if self._run_has_successful_build_job(
                run_id=run_id, branch=branch, sha=sha
            ):
                return True
        return False

    def branch_exists(self, branch: str) -> bool:
        encoded = urllib.parse.quote(branch, safe="")
        try:
            payload = self._request(
                "GET", self._repo_path(f"/branches/{encoded}")
            )
        except ApiError as exc:
            if exc.status == 404:
                return False
            raise
        if not isinstance(payload, dict):
            raise PruneError("branch response is invalid")
        actual = _text(payload.get("name"), "branch.name")
        if actual != branch:
            raise PruneError(
                f"branch lookup for {branch!r} returned unexpected branch {actual!r}"
            )
        return True

    def get_cache(self, expected: CacheEntry) -> CacheEntry | None:
        matching = [
            cache
            for cache in self._list_caches(
                query={"ref": expected.ref, "key": expected.key}
            )
            if cache.cache_id == expected.cache_id
        ]
        if len(matching) > 1:
            raise PruneError(f"cache ID {expected.cache_id} appeared more than once")
        return matching[0] if matching else None

    def delete_cache(self, cache_id: int) -> bool:
        try:
            self._request(
                "DELETE", self._repo_path(f"/actions/caches/{cache_id}")
            )
        except ApiError as exc:
            if exc.status == 404:
                return False
            raise
        return True


def select_candidates(
    caches: list[CacheEntry],
    *,
    existing_branches: set[str],
    active_run_branches: set[str],
) -> list[CacheEntry]:
    candidates = [
        cache
        for cache in caches
        if cache.branch is not None
        and cache.branch not in existing_branches
        and cache.branch not in active_run_branches
    ]
    return sorted(candidates, key=_cache_sort_key)


def _cache_sort_key(cache: CacheEntry) -> tuple[str, datetime, int]:
    return (
        cache.ref,
        datetime.fromisoformat(cache.created_at.replace("Z", "+00:00")),
        cache.cache_id,
    )


def _validate_cache_inventory(caches: list[CacheEntry]) -> list[CacheEntry]:
    seen: set[int] = set()
    for cache in caches:
        if cache.cache_id in seen:
            raise PruneError(f"cache ID {cache.cache_id} appeared more than once")
        seen.add(cache.cache_id)
    return caches


def _successful_build_shas(
    api: CacheApi,
    caches: list[CacheEntry],
    *,
    existing_branches: set[str],
    active_run_branches: set[str],
) -> dict[str, set[str]]:
    identities = {
        (cache.branch, generation[1])
        for cache in caches
        if cache.branch is not None
        and cache.branch in existing_branches
        and cache.branch not in active_run_branches
        and (generation := cache.gradle_home_generation) is not None
    }
    successful: dict[str, set[str]] = {}
    for branch, sha in sorted(identities):
        if branch is None:  # Defensive: the comprehension excludes this case.
            raise PruneError("live Gradle cache lost its branch identity")
        if api.has_successful_build(branch, sha):
            successful.setdefault(branch, set()).add(sha)
    return successful


def select_superseded_generations(
    caches: list[CacheEntry],
    *,
    existing_branches: set[str],
    active_run_branches: set[str],
    successful_build_shas: dict[str, set[str]],
) -> tuple[list[CacheEntry], list[CacheEntry]]:
    """Select old known Gradle-home generations and their protected replacements."""
    groups: dict[tuple[str, str, str], list[CacheEntry]] = {}
    for cache in caches:
        branch = cache.branch
        generation = cache.gradle_home_generation
        if (
            branch is None
            or branch not in existing_branches
            or branch in active_run_branches
            or generation is None
        ):
            continue
        restore_family, _sha = generation
        groups.setdefault((branch, restore_family, cache.version), []).append(cache)

    candidates: list[CacheEntry] = []
    protected: list[CacheEntry] = []
    for (branch, _restore_family, _cache_version), group in sorted(groups.items()):
        successful = [
            cache
            for cache in group
            if cache.gradle_home_generation is not None
            and cache.gradle_home_generation[1]
            in successful_build_shas.get(branch, set())
        ]
        if not successful:
            # No proven replacement means every generation remains a fallback.
            continue
        keeper = max(successful, key=_cache_sort_key)
        protected.append(keeper)
        candidates.extend(cache for cache in group if cache.cache_id != keeper.cache_id)

    return (
        sorted(candidates, key=_cache_sort_key),
        sorted(protected, key=_cache_sort_key),
    )


def _validate_delete_limits(*, max_delete_count: int, max_delete_bytes: int) -> None:
    if max_delete_count <= 0:
        raise PruneError("max_delete_count must be positive")
    if max_delete_count > DEFAULT_MAX_DELETE_COUNT:
        raise PruneError(
            f"max_delete_count cannot exceed {DEFAULT_MAX_DELETE_COUNT}"
        )
    if max_delete_bytes <= 0:
        raise PruneError("max_delete_bytes must be positive")
    if max_delete_bytes > DEFAULT_MAX_DELETE_BYTES:
        raise PruneError(
            f"max_delete_bytes cannot exceed {DEFAULT_MAX_DELETE_BYTES}"
        )


def _bounded_batch(
    candidates: list[CacheEntry],
    *,
    max_delete_count: int,
    max_delete_bytes: int,
) -> tuple[list[CacheEntry], list[CacheEntry]]:
    _validate_delete_limits(
        max_delete_count=max_delete_count,
        max_delete_bytes=max_delete_bytes,
    )
    selected: list[CacheEntry] = []
    deferred: list[CacheEntry] = []
    selected_bytes = 0
    for cache in candidates:
        if (
            len(selected) >= max_delete_count
            or selected_bytes + cache.size_in_bytes > max_delete_bytes
        ):
            deferred.append(cache)
            continue
        selected.append(cache)
        selected_bytes += cache.size_in_bytes
    return selected, deferred


def prune(
    api: CacheApi,
    *,
    apply: bool,
    expected_default_branch: str = "master",
    max_delete_count: int = DEFAULT_MAX_DELETE_COUNT,
    max_delete_bytes: int = DEFAULT_MAX_DELETE_BYTES,
    delete_delay_seconds: float = 0.0,
    trigger_event: str = "",
) -> dict[str, Any]:
    if delete_delay_seconds < 0 or delete_delay_seconds > 10:
        raise PruneError("delete_delay_seconds must be between 0 and 10")
    _validate_delete_limits(
        max_delete_count=max_delete_count,
        max_delete_bytes=max_delete_bytes,
    )
    limits_report = {
        "max_pages": MAX_PAGES,
        "max_active_runs_per_status": MAX_ACTIVE_RUN_RESULTS,
        "max_build_runs_per_sha": MAX_BUILD_RUNS_PER_SHA,
        "max_jobs_per_run": MAX_JOBS_PER_RUN,
        "max_delete_count": max_delete_count,
        "max_delete_bytes": max_delete_bytes,
    }

    default_branch = api.get_default_branch()
    if default_branch != expected_default_branch:
        raise PruneError(
            f"expected default branch {expected_default_branch!r}, got {default_branch!r}"
        )

    # A branch-deletion event that fires while any potentially cache-consuming run is
    # active is a guaranteed no-op: the active-run rule below protects the complete cache
    # inventory. Answer that with the cheap repository-wide probe before paying for the
    # full branch, cache, and Build-history inventory; scheduled runs and unknown events
    # always build the complete plan.
    if trigger_event == "delete" and api.has_any_active_run():
        return {
            "mode": "apply" if apply else "dry-run",
            "limits": limits_report,
            "repository_default_branch": default_branch,
            "trigger_event": trigger_event,
            "short_circuit": "delete-event-active-run",
            "existing_branch_count": 0,
            "active_run_present": True,
            "active_run_branches": [],
            "discovered_candidate_count": 0,
            "discovered_candidate_bytes": 0,
            "candidate_count": 0,
            "candidate_bytes": 0,
            "candidates": [],
            "protected_generation_ids": [],
            "deleted_ids": [],
            "skipped": [],
        }

    existing_branches = api.list_branches()
    if default_branch not in existing_branches:
        raise PruneError("default branch is missing from the complete branch inventory")

    active_run_branches = api.list_active_run_branches()
    active_run_present = bool(active_run_branches) or api.has_any_active_run()
    caches = _validate_cache_inventory(api.list_caches())
    if active_run_present:
        # Every Actions run may restore the default branch, and pull-request runs may also restore
        # their base branch. Treating the whole repository as one active restore boundary avoids
        # racing a cache lookup/download whose consumer is not the cache's owning ref.
        orphan_candidates: list[CacheEntry] = []
        superseded_candidates: list[CacheEntry] = []
        protected_generations: list[CacheEntry] = []
    else:
        orphan_candidates = select_candidates(
            caches,
            existing_branches=existing_branches,
            active_run_branches=active_run_branches,
        )
        successful_build_shas = _successful_build_shas(
            api,
            caches,
            existing_branches=existing_branches,
            active_run_branches=active_run_branches,
        )
        superseded_candidates, protected_generations = select_superseded_generations(
            caches,
            existing_branches=existing_branches,
            active_run_branches=active_run_branches,
            successful_build_shas=successful_build_shas,
        )
    candidate_kinds = {
        cache.cache_id: "absent-branch" for cache in orphan_candidates
    }
    candidate_kinds.update(
        {cache.cache_id: "superseded-gradle-home" for cache in superseded_candidates}
    )
    protected_by_group = {
        (cache.branch, cache.gradle_home_generation[0], cache.version): cache
        for cache in protected_generations
        if cache.branch is not None and cache.gradle_home_generation is not None
    }
    replacement_by_candidate: dict[int, CacheEntry] = {}
    for cache in superseded_candidates:
        if cache.branch is None or cache.gradle_home_generation is None:
            raise PruneError(f"cache {cache.cache_id} lost its Gradle identity")
        replacement = protected_by_group.get(
            (cache.branch, cache.gradle_home_generation[0], cache.version)
        )
        if replacement is None:
            raise PruneError(f"cache {cache.cache_id} has no protected replacement")
        replacement_by_candidate[cache.cache_id] = replacement
    candidates = sorted(
        [*orphan_candidates, *superseded_candidates], key=_cache_sort_key
    )

    skipped: list[dict[str, Any]] = []
    if apply:
        # Close most of the inventory-to-delete gap before checking the global limits. Any run
        # that started while the initial inventory was being read protects the whole restore scope.
        if api.has_any_active_run():
            skipped.extend(
                {"id": cache.cache_id, "reason": "active-run"}
                for cache in candidates
            )
            candidates = []

    discovered_candidates = list(candidates)
    candidates, deferred = _bounded_batch(
        candidates,
        max_delete_count=max_delete_count,
        max_delete_bytes=max_delete_bytes,
    )
    skipped.extend(
        {"id": cache.cache_id, "reason": "batch-limit"} for cache in deferred
    )

    deleted_ids: list[int] = []
    if apply:
        for cache in candidates:
            branch = cache.branch
            if branch is None:  # Defensive: select_candidates already excludes this case.
                raise PruneError(f"cache {cache.cache_id} lost its branch identity")
            candidate_kind = candidate_kinds.get(cache.cache_id)
            if candidate_kind not in {"absent-branch", "superseded-gradle-home"}:
                raise PruneError(f"cache {cache.cache_id} lost its candidate policy")

            current = api.get_cache(cache)
            if current is None:
                skipped.append({"id": cache.cache_id, "reason": "already-absent"})
                continue
            if current != cache:
                skipped.append({"id": cache.cache_id, "reason": "cache-changed"})
                continue

            if candidate_kind == "absent-branch":
                # Recheck active work per candidate so a run that starts during a large batch
                # cannot lose any default/base cache it may still restore.
                if api.has_any_active_run():
                    skipped.append(
                        {"id": cache.cache_id, "reason": "active-run-late"}
                    )
                    continue

                # This is deliberately the final read before DELETE. A branch recreated after
                # the initial inventory keeps every cache formerly scoped to its name.
                if api.branch_exists(branch):
                    skipped.append(
                        {"id": cache.cache_id, "reason": "branch-recreated"}
                    )
                    continue
            else:
                if not api.branch_exists(branch):
                    skipped.append(
                        {"id": cache.cache_id, "reason": "branch-removed-replan"}
                    )
                    continue
                replacement = replacement_by_candidate.get(cache.cache_id)
                if replacement is None:
                    raise PruneError(
                        f"cache {cache.cache_id} lost its protected replacement"
                    )
                replacement_current = api.get_cache(replacement)
                if replacement_current is None:
                    skipped.append(
                        {"id": cache.cache_id, "reason": "replacement-absent"}
                    )
                    continue
                if replacement_current != replacement:
                    skipped.append(
                        {"id": cache.cache_id, "reason": "replacement-changed"}
                    )
                    continue
                # Keep this as the last policy read before DELETE. Any active workflow may be
                # restoring a default/base fallback, so the whole repository is protected.
                if api.has_any_active_run():
                    skipped.append(
                        {"id": cache.cache_id, "reason": "active-run-late"}
                    )
                    continue

            if not api.delete_cache(cache.cache_id):
                skipped.append({"id": cache.cache_id, "reason": "already-absent"})
                continue
            deleted_ids.append(cache.cache_id)
            if delete_delay_seconds:
                time.sleep(delete_delay_seconds)

    return {
        "mode": "apply" if apply else "dry-run",
        "limits": limits_report,
        "repository_default_branch": default_branch,
        "trigger_event": trigger_event,
        "short_circuit": None,
        "existing_branch_count": len(existing_branches),
        "active_run_present": active_run_present,
        "active_run_branches": sorted(active_run_branches),
        "discovered_candidate_count": len(discovered_candidates),
        "discovered_candidate_bytes": sum(
            cache.size_in_bytes for cache in discovered_candidates
        ),
        "candidate_count": len(candidates),
        "candidate_bytes": sum(cache.size_in_bytes for cache in candidates),
        "candidates": [
            {**cache.report(), "reason": candidate_kinds[cache.cache_id]}
            for cache in candidates
        ],
        "protected_generation_ids": [
            cache.cache_id for cache in protected_generations
        ],
        "deleted_ids": deleted_ids,
        "skipped": skipped,
    }


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--repository",
        default=os.environ.get("GITHUB_REPOSITORY", ""),
        help="GitHub repository in owner/name form (defaults to GITHUB_REPOSITORY)",
    )
    parser.add_argument(
        "--expected-default-branch",
        default="master",
        help="fail closed unless the repository uses this default branch",
    )
    parser.add_argument(
        "--apply",
        action="store_true",
        help="delete the planned cache IDs; omission performs a dry-run",
    )
    parser.add_argument(
        "--max-delete-count",
        type=int,
        default=DEFAULT_MAX_DELETE_COUNT,
        help="maximum exact cache IDs selected in one invocation",
    )
    parser.add_argument(
        "--max-delete-bytes",
        type=int,
        default=DEFAULT_MAX_DELETE_BYTES,
        help="maximum total candidate bytes selected in one invocation",
    )
    parser.add_argument(
        "--delete-delay-seconds",
        type=float,
        default=1.0,
    )
    parser.add_argument(
        "--trigger-event",
        default=os.environ.get("GITHUB_EVENT_NAME", ""),
        help=(
            "workflow trigger event (defaults to GITHUB_EVENT_NAME); a delete event "
            "may short-circuit on the cheap active-run probe"
        ),
    )
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        repository = _text(args.repository, "repository")
        if not REPOSITORY.fullmatch(repository):
            raise PruneError("repository must use the owner/name form")
        token = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
        if not token:
            raise PruneError("GH_TOKEN is required")
        api = GitHubApi(
            repository=repository,
            token=token,
            api_url=os.environ.get("GITHUB_API_URL", "https://api.github.com"),
        )
        result = prune(
            api,
            apply=args.apply,
            expected_default_branch=args.expected_default_branch,
            max_delete_count=args.max_delete_count,
            max_delete_bytes=args.max_delete_bytes,
            delete_delay_seconds=args.delete_delay_seconds,
            trigger_event=args.trigger_event,
        )
        print(json.dumps(result, indent=2, sort_keys=True))
        return 0
    except PruneError as exc:
        print(f"Actions cache pruning error: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
