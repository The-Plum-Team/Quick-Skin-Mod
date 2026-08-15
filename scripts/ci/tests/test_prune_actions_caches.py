from __future__ import annotations

import os
import sys
import unittest
import urllib.parse
from pathlib import Path
from typing import Any
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts" / "ci"))

from prune_actions_caches import (  # noqa: E402
    ACTIVE_RUN_STATUSES,
    ApiError,
    CacheEntry,
    GitHubApi,
    PruneError,
    parse_args,
    prune,
    select_candidates,
    select_superseded_generations,
)


def cache(
    cache_id: int,
    ref: str,
    *,
    size: int = 10,
    key: str | None = None,
    version: str = "cache-version-1",
    created_at: str = "2026-08-01T00:00:00Z",
    last_accessed_at: str = "2026-08-01T00:00:00Z",
) -> CacheEntry:
    return CacheEntry(
        cache_id=cache_id,
        ref=ref,
        key=key or f"gradle-{cache_id}",
        version=version,
        size_in_bytes=size,
        created_at=created_at,
        last_accessed_at=last_accessed_at,
    )


def gradle_key(
    sha: str,
    *,
    platform: str = "Linux-X64",
    job: str = "build",
    workflow_hash: str = "95041b765eeb2f81cb29b3de7add34da",
) -> str:
    return f"gradle-home-v1|{platform}|{job}[{workflow_hash}]-{sha}"


class FakeApi:
    def __init__(self, caches: list[CacheEntry]) -> None:
        self.default_branch = "master"
        self.branches = {"master"}
        self.active_snapshots: list[set[str]] = [set()]
        self.caches = list(caches)
        self.revalidated = {item.cache_id: item for item in caches}
        self.recreated: set[str] = set()
        self.missing_on_revalidation: set[str] = set()
        self.any_active_snapshots: list[bool] = [False]
        self.successful_builds: set[tuple[str, str]] = set()
        self.delete_404: set[int] = set()
        self.deleted: list[int] = []
        self.branch_checks: list[str] = []
        self.cache_checks: list[int] = []
        self.active_calls = 0
        self.any_active_calls = 0
        self.list_branches_calls = 0
        self.list_caches_calls = 0
        self.successful_build_checks: list[tuple[str, str]] = []

    def get_default_branch(self) -> str:
        return self.default_branch

    def list_branches(self) -> set[str]:
        self.list_branches_calls += 1
        return set(self.branches)

    def list_active_run_branches(self) -> set[str]:
        index = min(self.active_calls, len(self.active_snapshots) - 1)
        self.active_calls += 1
        return set(self.active_snapshots[index])

    def list_caches(self) -> list[CacheEntry]:
        self.list_caches_calls += 1
        return list(self.caches)

    def has_successful_build(self, branch: str, sha: str) -> bool:
        self.successful_build_checks.append((branch, sha))
        return (branch, sha) in self.successful_builds

    def has_any_active_run(self) -> bool:
        index = min(self.any_active_calls, len(self.any_active_snapshots) - 1)
        self.any_active_calls += 1
        return self.any_active_snapshots[index]

    def branch_exists(self, branch: str) -> bool:
        self.branch_checks.append(branch)
        if branch in self.missing_on_revalidation:
            return False
        return branch in self.branches or branch in self.recreated

    def get_cache(self, expected: CacheEntry) -> CacheEntry | None:
        self.cache_checks.append(expected.cache_id)
        return self.revalidated.get(expected.cache_id)

    def delete_cache(self, cache_id: int) -> bool:
        if cache_id in self.delete_404:
            return False
        self.deleted.append(cache_id)
        return True


class CandidateSelectionTest(unittest.TestCase):
    def test_only_missing_branch_refs_are_candidates(self) -> None:
        values = [
            cache(1, "refs/heads/master"),
            cache(2, "refs/heads/deleted"),
            cache(3, "refs/heads/running"),
            cache(4, "refs/pull/12/merge"),
            cache(5, "refs/tags/mc1.20.1-v3.0.0"),
        ]
        selected = select_candidates(
            values,
            existing_branches={"master"},
            active_run_branches={"running"},
        )
        self.assertEqual([item.cache_id for item in selected], [2])

    def test_empty_branch_ref_is_not_a_candidate(self) -> None:
        selected = select_candidates(
            [cache(1, "refs/heads/")],
            existing_branches={"master"},
            active_run_branches=set(),
        )
        self.assertEqual(selected, [])

    def test_live_branch_keeps_latest_successful_generation_per_restore_family(
        self,
    ) -> None:
        old_sha = "1" * 40
        keeper_sha = "2" * 40
        failed_sha = "3" * 40
        windows_sha = "4" * 40
        values = [
            cache(
                1,
                "refs/heads/stable",
                key=gradle_key(old_sha),
                created_at="2026-08-01T00:00:00Z",
            ),
            cache(
                2,
                "refs/heads/stable",
                key=gradle_key(keeper_sha),
                created_at="2026-08-02T00:00:00Z",
            ),
            cache(
                3,
                "refs/heads/stable",
                key=gradle_key(failed_sha),
                created_at="2026-08-03T00:00:00Z",
            ),
            cache(4, "refs/heads/stable", key="gradle-dependencies-v1-opaque"),
            cache(
                5,
                "refs/heads/stable",
                key=gradle_key(windows_sha, platform="Windows-X64"),
            ),
        ]

        candidates, protected = select_superseded_generations(
            values,
            existing_branches={"master", "stable"},
            active_run_branches=set(),
            successful_build_shas={
                "stable": {old_sha, keeper_sha, windows_sha},
            },
        )

        self.assertEqual([item.cache_id for item in candidates], [1, 3])
        self.assertEqual([item.cache_id for item in protected], [5, 2])

    def test_live_branch_without_successful_replacement_is_preserved(self) -> None:
        values = [
            cache(1, "refs/heads/stable", key=gradle_key("1" * 40)),
            cache(2, "refs/heads/stable", key=gradle_key("2" * 40)),
        ]

        candidates, protected = select_superseded_generations(
            values,
            existing_branches={"master", "stable"},
            active_run_branches=set(),
            successful_build_shas={},
        )

        self.assertEqual(candidates, [])
        self.assertEqual(protected, [])

    def test_cache_versions_form_independent_restore_families(self) -> None:
        first_sha = "1" * 40
        second_sha = "2" * 40
        values = [
            cache(
                1,
                "refs/heads/stable",
                key=gradle_key(first_sha),
                version="paths-and-compression-v1",
                created_at="2026-08-01T00:00:00Z",
            ),
            cache(
                2,
                "refs/heads/stable",
                key=gradle_key(second_sha),
                version="paths-and-compression-v2",
                created_at="2026-08-02T00:00:00Z",
            ),
        ]

        candidates, protected = select_superseded_generations(
            values,
            existing_branches={"master", "stable"},
            active_run_branches=set(),
            successful_build_shas={"stable": {first_sha, second_sha}},
        )

        self.assertEqual(candidates, [])
        self.assertEqual([item.cache_id for item in protected], [1, 2])

    def test_active_live_branch_and_unknown_key_formats_are_preserved(self) -> None:
        sha = "1" * 40
        values = [
            cache(1, "refs/heads/stable", key=gradle_key(sha)),
            cache(
                2,
                "refs/heads/other",
                key=f"gradle-home-v1|Linux-X64|build[not-a-hash]-{sha}",
            ),
        ]

        candidates, protected = select_superseded_generations(
            values,
            existing_branches={"master", "stable", "other"},
            active_run_branches={"stable"},
            successful_build_shas={"stable": {sha}, "other": {sha}},
        )

        self.assertEqual(candidates, [])
        self.assertEqual(protected, [])


class PruneTest(unittest.TestCase):
    def test_cli_requires_apply_for_mutation(self) -> None:
        self.assertFalse(parse_args([]).apply)
        self.assertTrue(parse_args(["--apply"]).apply)

    def test_cli_trigger_event_defaults_to_the_runner_event(self) -> None:
        with patch.dict(os.environ, {"GITHUB_EVENT_NAME": "delete"}):
            self.assertEqual(parse_args([]).trigger_event, "delete")
        with patch.dict(os.environ, {}, clear=True):
            self.assertEqual(parse_args([]).trigger_event, "")
        self.assertEqual(
            parse_args(["--trigger-event", "schedule"]).trigger_event, "schedule"
        )

    def test_delete_event_short_circuits_on_the_cheap_active_run_probe(self) -> None:
        api = FakeApi([cache(1, "refs/heads/deleted")])
        api.any_active_snapshots = [True]

        result = prune(api, apply=True, trigger_event="delete")

        self.assertEqual(result["short_circuit"], "delete-event-active-run")
        self.assertEqual(result["trigger_event"], "delete")
        self.assertTrue(result["active_run_present"])
        self.assertEqual(result["deleted_ids"], [])
        self.assertEqual(api.deleted, [])
        self.assertEqual(api.any_active_calls, 1)
        self.assertEqual(api.active_calls, 0)
        self.assertEqual(api.list_branches_calls, 0)
        self.assertEqual(api.list_caches_calls, 0)

    def test_delete_event_without_active_runs_builds_the_full_plan(self) -> None:
        api = FakeApi([cache(1, "refs/heads/deleted")])

        result = prune(api, apply=True, trigger_event="delete")

        self.assertIsNone(result["short_circuit"])
        self.assertEqual(result["deleted_ids"], [1])
        self.assertEqual(api.deleted, [1])
        self.assertEqual(api.list_branches_calls, 1)
        self.assertEqual(api.list_caches_calls, 1)

    def test_schedule_event_keeps_the_complete_inventory_plan(self) -> None:
        api = FakeApi([cache(1, "refs/heads/deleted")])
        api.active_snapshots = [{"topic/using-master-fallback"}]
        api.any_active_snapshots = [True]

        result = prune(api, apply=True, trigger_event="schedule")

        self.assertIsNone(result["short_circuit"])
        self.assertTrue(result["active_run_present"])
        self.assertEqual(result["candidate_count"], 0)
        self.assertEqual(api.deleted, [])
        self.assertEqual(api.list_branches_calls, 1)
        self.assertEqual(api.list_caches_calls, 1)

    def test_dry_run_is_the_default_behavior_and_never_revalidates_or_deletes(self) -> None:
        api = FakeApi([cache(1, "refs/heads/deleted", size=25)])

        result = prune(api, apply=False)

        self.assertEqual(result["mode"], "dry-run")
        self.assertEqual(result["candidate_count"], 1)
        self.assertEqual(result["candidate_bytes"], 25)
        self.assertEqual(api.cache_checks, [])
        self.assertEqual(api.branch_checks, [])
        self.assertEqual(api.deleted, [])

    def test_active_topic_run_protects_default_branch_restore_caches(self) -> None:
        old_sha = "1" * 40
        keeper_sha = "2" * 40
        api = FakeApi(
            [
                cache(1, "refs/heads/master", key=gradle_key(old_sha)),
                cache(
                    2,
                    "refs/heads/master",
                    key=gradle_key(keeper_sha),
                    created_at="2026-08-02T00:00:00Z",
                ),
            ]
        )
        api.active_snapshots = [{"topic/using-master-fallback"}]
        api.successful_builds.add(("master", keeper_sha))

        result = prune(api, apply=False)

        self.assertTrue(result["active_run_present"])
        self.assertEqual(result["candidate_count"], 0)
        self.assertEqual(api.successful_build_checks, [])

    def test_active_pull_request_run_protects_release_base_caches(self) -> None:
        old_sha = "1" * 40
        keeper_sha = "2" * 40
        api = FakeApi(
            [
                cache(1, "refs/heads/stable", key=gradle_key(old_sha)),
                cache(
                    2,
                    "refs/heads/stable",
                    key=gradle_key(keeper_sha),
                    created_at="2026-08-02T00:00:00Z",
                ),
            ]
        )
        api.branches.add("stable")
        api.active_snapshots = [{"pull-request-head"}]
        api.successful_builds.add(("stable", keeper_sha))

        result = prune(api, apply=False)

        self.assertTrue(result["active_run_present"])
        self.assertEqual(result["candidate_count"], 0)
        self.assertEqual(api.successful_build_checks, [])

    def test_apply_rechecks_active_runs_and_protects_the_complete_restore_scope(self) -> None:
        api = FakeApi(
            [
                cache(1, "refs/heads/deleted-a"),
                cache(2, "refs/heads/deleted-b"),
            ]
        )
        api.any_active_snapshots = [False, True]

        result = prune(api, apply=True)

        self.assertEqual(api.deleted, [])
        self.assertEqual(result["deleted_ids"], [])
        self.assertIn({"id": 1, "reason": "active-run"}, result["skipped"])
        self.assertIn({"id": 2, "reason": "active-run"}, result["skipped"])

    def test_apply_deletes_serially_by_exact_id_after_revalidation(self) -> None:
        api = FakeApi(
            [
                cache(3, "refs/heads/z-deleted"),
                cache(2, "refs/heads/a-deleted"),
                cache(1, "refs/heads/a-deleted"),
            ]
        )

        result = prune(api, apply=True)

        self.assertEqual(api.cache_checks, [1, 2, 3])
        self.assertEqual(api.branch_checks, ["a-deleted", "a-deleted", "z-deleted"])
        self.assertEqual(api.deleted, [1, 2, 3])
        self.assertEqual(result["deleted_ids"], [1, 2, 3])

    def test_recreated_branch_is_preserved(self) -> None:
        api = FakeApi([cache(1, "refs/heads/deleted")])
        api.recreated.add("deleted")

        result = prune(api, apply=True)

        self.assertEqual(api.deleted, [])
        self.assertIn({"id": 1, "reason": "branch-recreated"}, result["skipped"])

    def test_run_starting_during_batch_is_preserved(self) -> None:
        api = FakeApi([cache(1, "refs/heads/deleted")])
        api.any_active_snapshots = [False, False, True]

        result = prune(api, apply=True)

        self.assertEqual(api.deleted, [])
        self.assertEqual(api.branch_checks, [])
        self.assertIn({"id": 1, "reason": "active-run-late"}, result["skipped"])

    def test_changed_cache_is_preserved(self) -> None:
        original = cache(1, "refs/heads/deleted")
        api = FakeApi([original])
        api.revalidated[1] = cache(
            1,
            "refs/heads/deleted",
            last_accessed_at="2026-08-02T00:00:00Z",
        )

        result = prune(api, apply=True)

        self.assertEqual(api.branch_checks, [])
        self.assertEqual(api.deleted, [])
        self.assertIn({"id": 1, "reason": "cache-changed"}, result["skipped"])

    def test_delete_404_is_idempotent(self) -> None:
        api = FakeApi([cache(1, "refs/heads/deleted")])
        api.delete_404.add(1)

        result = prune(api, apply=True)

        self.assertEqual(result["deleted_ids"], [])
        self.assertIn({"id": 1, "reason": "already-absent"}, result["skipped"])

    def test_live_branch_deletes_only_superseded_gradle_home_generation(self) -> None:
        old_sha = "1" * 40
        keeper_sha = "2" * 40
        old = cache(
            1,
            "refs/heads/stable",
            key=gradle_key(old_sha),
            created_at="2026-08-01T00:00:00Z",
            size=25,
        )
        keeper = cache(
            2,
            "refs/heads/stable",
            key=gradle_key(keeper_sha),
            created_at="2026-08-02T00:00:00Z",
            size=30,
        )
        unknown = cache(
            3,
            "refs/heads/stable",
            key="gradle-dependencies-v1-opaque",
            size=35,
        )
        api = FakeApi([old, keeper, unknown])
        api.branches.add("stable")
        api.successful_builds.update(
            {("stable", old_sha), ("stable", keeper_sha)}
        )

        result = prune(api, apply=True)

        self.assertEqual(result["protected_generation_ids"], [2])
        self.assertEqual(result["candidate_count"], 1)
        self.assertEqual(result["candidates"][0]["reason"], "superseded-gradle-home")
        self.assertEqual(result["deleted_ids"], [1])
        self.assertEqual(api.deleted, [1])
        self.assertEqual(api.cache_checks, [1, 2])
        self.assertEqual(api.any_active_calls, 3)

    def test_live_branch_revalidation_preserves_candidate_if_replacement_vanishes(
        self,
    ) -> None:
        old_sha = "1" * 40
        keeper_sha = "2" * 40
        old = cache(1, "refs/heads/stable", key=gradle_key(old_sha))
        keeper = cache(
            2,
            "refs/heads/stable",
            key=gradle_key(keeper_sha),
            created_at="2026-08-02T00:00:00Z",
        )
        api = FakeApi([old, keeper])
        api.branches.add("stable")
        api.successful_builds.add(("stable", keeper_sha))
        api.revalidated.pop(2)

        result = prune(api, apply=True)

        self.assertEqual(api.deleted, [])
        self.assertIn(
            {"id": 1, "reason": "replacement-absent"},
            result["skipped"],
        )

    def test_active_run_starting_during_live_revalidation_preserves_candidate(
        self,
    ) -> None:
        old_sha = "1" * 40
        keeper_sha = "2" * 40
        old = cache(1, "refs/heads/stable", key=gradle_key(old_sha))
        keeper = cache(
            2,
            "refs/heads/stable",
            key=gradle_key(keeper_sha),
            created_at="2026-08-02T00:00:00Z",
        )
        api = FakeApi([old, keeper])
        api.branches.add("stable")
        api.successful_builds.add(("stable", keeper_sha))
        api.any_active_snapshots = [False, False, True]

        result = prune(api, apply=True)

        self.assertEqual(api.deleted, [])
        self.assertIn({"id": 1, "reason": "active-run-late"}, result["skipped"])

    def test_live_branch_removed_before_revalidation_is_left_for_next_plan(self) -> None:
        old_sha = "1" * 40
        keeper_sha = "2" * 40
        old = cache(1, "refs/heads/stable", key=gradle_key(old_sha))
        keeper = cache(
            2,
            "refs/heads/stable",
            key=gradle_key(keeper_sha),
            created_at="2026-08-02T00:00:00Z",
        )
        api = FakeApi([old, keeper])
        api.branches.add("stable")
        api.successful_builds.add(("stable", keeper_sha))
        api.missing_on_revalidation.add("stable")

        result = prune(api, apply=True)

        self.assertEqual(api.deleted, [])
        self.assertIn(
            {"id": 1, "reason": "branch-removed-replan"}, result["skipped"]
        )

    def test_duplicate_cache_ids_fail_closed_before_deletion(self) -> None:
        api = FakeApi(
            [cache(1, "refs/heads/deleted-a"), cache(1, "refs/heads/deleted-b")]
        )

        with self.assertRaisesRegex(PruneError, "appeared more than once"):
            prune(api, apply=True)

        self.assertEqual(api.deleted, [])

    def test_count_limit_processes_a_deterministic_bounded_batch(self) -> None:
        api = FakeApi(
            [cache(1, "refs/heads/deleted"), cache(2, "refs/heads/deleted")]
        )

        result = prune(api, apply=True, max_delete_count=1)

        self.assertEqual(api.deleted, [1])
        self.assertEqual(result["discovered_candidate_count"], 2)
        self.assertEqual(result["candidate_count"], 1)
        self.assertEqual(result["limits"]["max_delete_count"], 1)
        self.assertEqual(result["limits"]["max_delete_bytes"], 10 * 1024**3)
        self.assertIn({"id": 2, "reason": "batch-limit"}, result["skipped"])

    def test_oversized_cache_is_deferred_without_blocking_the_job(self) -> None:
        api = FakeApi([cache(1, "refs/heads/deleted", size=101)])

        result = prune(api, apply=True, max_delete_bytes=100)

        self.assertEqual(api.cache_checks, [])
        self.assertEqual(api.branch_checks, [])
        self.assertEqual(api.deleted, [])
        self.assertEqual(result["candidate_count"], 0)
        self.assertIn({"id": 1, "reason": "batch-limit"}, result["skipped"])

    def test_callers_cannot_raise_the_hard_delete_limits(self) -> None:
        for kwargs in (
            {"max_delete_count": 76},
            {"max_delete_bytes": 10 * 1024**3 + 1},
        ):
            api = FakeApi([cache(1, "refs/heads/deleted")])
            with self.subTest(kwargs=kwargs), self.assertRaisesRegex(
                PruneError, "cannot exceed"
            ):
                prune(api, apply=True, **kwargs)
            self.assertEqual(api.deleted, [])

    def test_missing_default_branch_fails_closed(self) -> None:
        api = FakeApi([cache(1, "refs/heads/deleted")])
        api.branches.clear()

        with self.assertRaisesRegex(PruneError, "default branch is missing"):
            prune(api, apply=True)

        self.assertEqual(api.deleted, [])

    def test_unexpected_default_branch_fails_closed(self) -> None:
        api = FakeApi([cache(1, "refs/heads/deleted")])
        api.default_branch = "main"
        api.branches = {"main"}

        with self.assertRaisesRegex(PruneError, "expected default branch"):
            prune(api, apply=True)

        self.assertEqual(api.deleted, [])


class PagingApi(GitHubApi):
    def __init__(self) -> None:
        super().__init__(repository="owner/repository", token="token", api_url="https://api")
        self.requests: list[tuple[str, str]] = []
        self.queued_total_count = 101

    def _request(self, method: str, path: str) -> Any:
        self.requests.append((method, path))
        parsed = urllib.parse.urlparse(path)
        query = urllib.parse.parse_qs(parsed.query)
        page = int(query.get("page", ["1"])[0])

        if parsed.path.endswith("/branches"):
            if page == 1:
                return [{"name": f"branch-{index}"} for index in range(100)]
            if page == 2:
                return [{"name": "branch-100"}]
        if parsed.path.endswith("/actions/caches"):
            if page == 1:
                return {
                    "actions_caches": [
                        self._cache_payload(index + 1) for index in range(100)
                    ]
                }
            if page == 2:
                return {"actions_caches": [self._cache_payload(101)]}
        if parsed.path.endswith("/actions/runs"):
            status = query["status"][0]
            if status == "queued" and page == 1:
                return {
                    "total_count": self.queued_total_count,
                    "workflow_runs": [
                        {
                            "status": "queued",
                            "head_branch": f"active-{index}",
                            "path": ".github/workflows/build-gate.yml",
                        }
                        for index in range(100)
                    ]
                }
            if status == "queued" and page == 2:
                return {
                    "total_count": self.queued_total_count,
                    "workflow_runs": [
                        {
                            "status": "queued",
                            "head_branch": "active-100",
                            "path": ".github/workflows/build-gate.yml",
                        }
                    ]
                }
            if status == "pending" and page == 1:
                return {
                    "total_count": 1,
                    "workflow_runs": [
                        {
                            "status": "pending",
                            "head_branch": "master",
                            "path": ".github/workflows/prune-actions-caches.yml",
                        }
                    ],
                }
            return {"total_count": 0, "workflow_runs": []}
        raise AssertionError(f"unexpected request: {method} {path}")

    @staticmethod
    def _cache_payload(cache_id: int) -> dict[str, Any]:
        return {
            "id": cache_id,
            "ref": f"refs/heads/deleted-{cache_id}",
            "key": f"key-{cache_id}",
            "version": f"version-{cache_id}",
            "size_in_bytes": cache_id,
            "created_at": "2026-08-01T00:00:00Z",
            "last_accessed_at": "2026-08-01T00:00:00Z",
        }


class DeleteApi(GitHubApi):
    def __init__(self, *, missing: bool) -> None:
        super().__init__(repository="owner/repository", token="token", api_url="https://api")
        self.missing = missing

    def _request(self, method: str, path: str) -> Any:
        self.assert_delete_request(method, path)
        if self.missing:
            raise ApiError(404, "already gone")
        return None

    @staticmethod
    def assert_delete_request(method: str, path: str) -> None:
        if method != "DELETE" or not path.endswith("/actions/caches/42"):
            raise AssertionError(f"unexpected request: {method} {path}")


class SuccessfulBuildApi(GitHubApi):
    def __init__(
        self,
        *,
        build_job_name: str = "Build and verify",
        build_job_conclusion: str = "success",
        event: str = "push",
    ) -> None:
        super().__init__(repository="owner/repository", token="token", api_url="https://api")
        self.build_job_name = build_job_name
        self.build_job_conclusion = build_job_conclusion
        self.event = event
        self.run_total_count = 1
        self.requests: list[tuple[str, str]] = []

    def _request(self, method: str, path: str) -> Any:
        self.requests.append((method, path))
        parsed = urllib.parse.urlparse(path)
        query = urllib.parse.parse_qs(parsed.query)
        page = int(query.get("page", ["1"])[0])
        if parsed.path.endswith("/actions/workflows/build-gate.yml/runs"):
            if page > 1:
                return {"total_count": self.run_total_count, "workflow_runs": []}
            return {
                "total_count": self.run_total_count,
                "workflow_runs": [
                    {
                        "id": 77,
                        "path": ".github/workflows/build-gate.yml",
                        "head_branch": "stable",
                        "head_sha": "1" * 40,
                        "status": "completed",
                        "conclusion": "success",
                        "event": self.event,
                        "head_repository": {"full_name": "owner/repository"},
                    }
                ],
            }
        if parsed.path.endswith("/actions/runs/77/jobs"):
            return {
                "total_count": 1,
                "jobs": [
                    {
                        "name": self.build_job_name,
                        "run_id": 77,
                        "head_branch": "stable",
                        "head_sha": "1" * 40,
                        "status": "completed",
                        "conclusion": self.build_job_conclusion,
                    }
                ],
            }
        raise AssertionError(f"unexpected request: {method} {path}")


class PaginationTest(unittest.TestCase):
    def test_branch_and_cache_inventory_follow_every_page(self) -> None:
        api = PagingApi()

        branches = api.list_branches()
        caches = api.list_caches()

        self.assertEqual(len(branches), 101)
        self.assertIn("branch-100", branches)
        self.assertEqual(len(caches), 101)
        self.assertEqual(caches[-1].cache_id, 101)

    def test_every_active_status_is_queried_and_paginated(self) -> None:
        api = PagingApi()

        branches = api.list_active_run_branches()

        self.assertEqual(len(branches), 101)
        self.assertNotIn("master", branches)
        for status in ACTIVE_RUN_STATUSES:
            self.assertTrue(
                any(
                    f"status={status}" in path
                    for method, path in api.requests
                    if method == "GET"
                ),
                status,
            )
        queued_pages = [
            path
            for method, path in api.requests
            if method == "GET" and "status=queued" in path
        ]
        self.assertEqual(len(queued_pages), 2)

    def test_active_run_search_fails_closed_above_githubs_result_cap(self) -> None:
        api = PagingApi()
        api.queued_total_count = 1_001

        with self.assertRaisesRegex(PruneError, "API limit is 1000"):
            api.list_active_run_branches()

    def test_api_delete_treats_404_as_successful_idempotence(self) -> None:
        self.assertFalse(DeleteApi(missing=True).delete_cache(42))
        self.assertTrue(DeleteApi(missing=False).delete_cache(42))

    def test_successful_build_requires_the_real_build_job(self) -> None:
        api = SuccessfulBuildApi()

        self.assertTrue(api.has_successful_build("stable", "1" * 40))

        run_request = next(
            path
            for method, path in api.requests
            if method == "GET" and "/workflows/build-gate.yml/runs" in path
        )
        query = urllib.parse.parse_qs(urllib.parse.urlparse(run_request).query)
        self.assertEqual(query["branch"], ["stable"])
        self.assertEqual(query["head_sha"], ["1" * 40])
        self.assertEqual(query["status"], ["success"])

        attest_only = SuccessfulBuildApi(build_job_name="Attest tested build")
        self.assertFalse(attest_only.has_successful_build("stable", "1" * 40))
        skipped_build = SuccessfulBuildApi(build_job_conclusion="skipped")
        self.assertFalse(skipped_build.has_successful_build("stable", "1" * 40))
        read_only_event = SuccessfulBuildApi(event="pull_request")
        self.assertFalse(read_only_event.has_successful_build("stable", "1" * 40))

    def test_successful_build_search_fails_closed_above_local_result_cap(self) -> None:
        api = SuccessfulBuildApi()
        api.run_total_count = 101

        with self.assertRaisesRegex(PruneError, "API limit is 100"):
            api.has_successful_build("stable", "1" * 40)


class WorkflowContractTest(unittest.TestCase):
    def test_pruner_runs_only_from_trusted_automatic_events(self) -> None:
        workflow = (
            ROOT / ".github" / "workflows" / "prune-actions-caches.yml"
        ).read_text(encoding="utf-8")

        self.assertIn('cron: "29 9 * * *"', workflow)
        self.assertRegex(workflow, r"(?m)^  delete:\s*$")
        self.assertNotIn("workflow_dispatch:", workflow)
        self.assertNotIn("workflow_run:", workflow)
        self.assertIn("permissions: {}", workflow)
        self.assertIn("actions: write", workflow)
        self.assertIn("contents: read", workflow)
        self.assertNotIn("contents: write", workflow)
        self.assertIn("github.event.ref_type == 'branch'", workflow)
        self.assertIn("quick-skin-actions-cache-pruning", workflow)
        self.assertIn("cancel-in-progress: false", workflow)
        self.assertIn("timeout-minutes: 75", workflow)

        authenticate = workflow.index("Authenticate protected cleanup implementation")
        checkout = workflow.index("Check out the exact protected cleanup implementation")
        self.assertLess(authenticate, checkout)
        self.assertIn("protected_gh_api_retry()", workflow)
        self.assertIn("gh api rate_limit --jq .resources.core.reset", workflow)
        self.assertIn("reset_at - now + 2 + skew", workflow)
        self.assertIn("wait_seconds > 3700", workflow)
        self.assertIn(
            'repository_json="$(protected_gh_api_retry "repos/$GITHUB_REPOSITORY")"',
            workflow,
        )
        self.assertIn(
            'run_json="$(protected_gh_api_retry \\\n'
            '            "repos/$GITHUB_REPOSITORY/actions/runs/$GITHUB_RUN_ID")"',
            workflow,
        )
        self.assertIn(
            'implementation_sha="$(protected_gh_api_retry \\\n'
            '            "repos/$GITHUB_REPOSITORY/branches/master" --jq .commit.sha)"',
            workflow,
        )
        self.assertIn('.default_branch == "master"', workflow)
        self.assertIn('.path == ".github/workflows/prune-actions-caches.yml"', workflow)
        self.assertIn('.head_branch == "master"', workflow)
        self.assertIn("branches/master", workflow)
        self.assertIn("ref: ${{ steps.trusted.outputs.implementation_sha }}", workflow)
        self.assertIn("persist-credentials: false", workflow)
        self.assertIn("prune_actions_caches.py", workflow)
        self.assertNotIn("gradle/actions/setup-gradle@", workflow)
        self.assertNotIn("./gradlew", workflow)
        self.assertNotIn("GRADLE_USER_HOME", workflow)
        self.assertIn("--apply", workflow)
        self.assertIn("--max-delete-count 75", workflow)
        self.assertIn("--max-delete-bytes 10737418240", workflow)
        self.assertIn("Prune bounded Actions caches", workflow)


if __name__ == "__main__":
    unittest.main()
