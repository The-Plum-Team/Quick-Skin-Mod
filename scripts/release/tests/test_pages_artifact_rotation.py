from __future__ import annotations

import os
import sys
import tempfile
import unittest
import urllib.error
from io import BytesIO
from pathlib import Path
from typing import Any
from unittest.mock import MagicMock, patch


ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts" / "pages"))

from rotate_artifacts import (  # noqa: E402
    ApiError,
    Artifact,
    BranchGeneration,
    CompatibilityGeneration,
    DeletionBudget,
    GitHubApi,
    RotationError,
    load_generations,
    load_compatibility_generations,
    retire_pages_run_transients,
    rotate_branch,
    rotate_generations,
    rotate_compatibility_branch,
    rotate_compatibility_generations,
    select_consumed_handoffs,
    select_old_handoffs,
    select_old_caches,
    select_old_compatibility_caches,
    select_old_compatibility_handoffs,
    select_pages_run_transients,
)
import select_artifact  # noqa: E402
from select_artifact import (  # noqa: E402
    PROBE_NO_EVIDENCE_EXIT,
    resolve_evidence,
    select_source,
)
from select_compatibility_artifact import (  # noqa: E402
    select_source as select_compatibility_source,
)


TARGET_SHA = "a" * 40
PAGES_SHA = "b" * 40
OLD_PAGES_SHA = "c" * 40
BRANCH = "forge-and-fabric-1.20.1"
REPOSITORY = "AkaNebur/Quick-Skin-Mod"


def artifact(
    artifact_id: int,
    name: str,
    created_at: str,
    *,
    run_id: int,
    head_branch: str,
    head_sha: str,
    expired: bool = False,
) -> Artifact:
    return Artifact(
        artifact_id=artifact_id,
        name=name,
        expired=expired,
        size_in_bytes=100,
        created_at=created_at,
        run_id=run_id,
        head_branch=head_branch,
        head_sha=head_sha,
    )


def run(
    run_id: int,
    *,
    workflow: str,
    event: str,
    branch: str,
    sha: str,
    conclusion: str = "success",
) -> dict[str, Any]:
    return {
        "id": run_id,
        "status": "completed",
        "conclusion": conclusion,
        "event": event,
        "path": workflow,
        "head_branch": branch,
        "head_sha": sha,
        "head_repository": {"full_name": REPOSITORY},
    }


class FakeApi:
    def __init__(
        self,
        *,
        keep: Artifact,
        inventories: dict[str, list[Artifact]],
        runs: dict[int, dict[str, Any]],
        run_artifacts: dict[int, list[Artifact]] | None = None,
        branch_sha: str = TARGET_SHA,
        branch_shas: dict[str, str] | None = None,
        branch_commits: list[str] | None = None,
        missing_on_delete: set[int] | None = None,
        artifact_overrides: dict[int, Artifact] | None = None,
    ) -> None:
        self.keep = keep
        self.inventories = inventories
        self.runs = runs
        self.run_artifacts = run_artifacts or {}
        self.branch_sha = branch_sha
        self.branch_shas = branch_shas
        self.branch_commits = branch_commits
        self.commit_requests = 0
        self.missing_on_delete = missing_on_delete or set()
        self.deleted: list[int] = []
        self.artifacts_by_id = {
            item.artifact_id: item
            for item in (
                [keep]
                + [item for values in inventories.values() for item in values]
                + [item for values in self.run_artifacts.values() for item in values]
            )
        }
        self.artifacts_by_id.update(artifact_overrides or {})

    def get_artifact(self, artifact_id: int) -> Artifact:
        if artifact_id not in self.artifacts_by_id:
            raise AssertionError(f"unexpected artifact lookup {artifact_id}")
        return self.artifacts_by_id[artifact_id]

    def list_artifacts(self, name: str) -> list[Artifact]:
        return list(self.inventories.get(name, []))

    def list_artifacts_for_run(self, run_id: int) -> list[Artifact]:
        return list(self.run_artifacts.get(run_id, []))

    def get_run(self, run_id: int) -> dict[str, Any]:
        return self.runs[run_id]

    def get_branch_sha(self, branch: str) -> str:
        if self.branch_shas is not None:
            if branch not in self.branch_shas:
                raise AssertionError(f"unexpected branch {branch}")
            return self.branch_shas[branch]
        self.assert_branch(branch)
        return self.branch_sha

    def list_branch_commits(self, branch: str, limit: int) -> list[str]:
        self.assert_branch(branch)
        if self.branch_commits is None:
            raise AssertionError("unexpected branch commit lookup")
        self.commit_requests += 1
        return list(self.branch_commits[:limit])

    def delete_artifact(self, artifact_id: int) -> None:
        if artifact_id in self.missing_on_delete:
            raise ApiError(404, "already deleted")
        self.deleted.append(artifact_id)

    def assert_branch(self, branch: str) -> None:
        if branch != BRANCH:
            raise AssertionError(f"unexpected branch {branch}")


class PagesArtifactRotationTest(unittest.TestCase):
    def test_pages_api_retries_installation_rate_limit_then_returns_clean_json(self) -> None:
        api = GitHubApi(
            repository=REPOSITORY,
            token="token",
            api_url="https://api.github.test",
        )
        response = MagicMock()
        response.__enter__.return_value = response
        response.read.return_value = b'{"ok":true}'
        rate_limit = urllib.error.HTTPError(
            "https://api.github.test/repos/example",
            403,
            "forbidden",
            {"X-RateLimit-Remaining": "0"},
            BytesIO(b'{"message":"API rate limit exceeded for installation"}'),
        )

        with patch("rotate_artifacts.urllib.request.urlopen", side_effect=[rate_limit, response]), patch(
            "rotate_artifacts.time.sleep"
        ) as sleep:
            self.assertEqual(api._request("GET", "/repos/example"), {"ok": True})

        sleep.assert_called_once()

    def setUp(self) -> None:
        self.keep = artifact(
            200,
            f"pages-cache-{BRANCH}--{TARGET_SHA}",
            "2026-08-03T12:00:00Z",
            run_id=900,
            head_branch="master",
            head_sha=PAGES_SHA,
        )
        self.generation = BranchGeneration(
            branch=BRANCH,
            target_sha=TARGET_SHA,
            coverage_sha=TARGET_SHA,
            target_run_id=800,
            keep=self.keep,
        )
        self.compatibility_keep = artifact(
            210,
            f"pages-mod-compatibility-cache-{BRANCH}",
            "2026-08-03T12:10:00Z",
            run_id=900,
            head_branch="master",
            head_sha=PAGES_SHA,
        )
        self.compatibility_generation = CompatibilityGeneration(
            branch=BRANCH,
            coverage_sha=TARGET_SHA,
            compatibility_run_id=810,
            publication_run_id=820,
            keep=self.compatibility_keep,
        )

    def test_cache_selector_keeps_current_newer_foreign_and_expired_artifacts(self) -> None:
        expected_name = f"pages-cache-{BRANCH}"
        values = [
            artifact(
                100,
                expected_name,
                "2026-08-03T11:00:00Z",
                run_id=700,
                head_branch="master",
                head_sha=OLD_PAGES_SHA,
            ),
            artifact(
                199,
                f"{expected_name}--{OLD_PAGES_SHA}",
                self.keep.created_at,
                run_id=701,
                head_branch="master",
                head_sha=OLD_PAGES_SHA,
            ),
            self.keep,
            artifact(
                300,
                f"{expected_name}--{TARGET_SHA}",
                "2026-08-03T13:00:00Z",
                run_id=901,
                head_branch="master",
                head_sha=PAGES_SHA,
            ),
            artifact(
                50,
                expected_name,
                "2026-08-03T10:00:00Z",
                run_id=600,
                head_branch="master",
                head_sha=OLD_PAGES_SHA,
                expired=True,
            ),
            artifact(
                40,
                "pages-cache-another-branch",
                "2026-08-03T10:00:00Z",
                run_id=500,
                head_branch="master",
                head_sha=OLD_PAGES_SHA,
            ),
            artifact(
                41,
                f"{expected_name}--collision--{OLD_PAGES_SHA}",
                "2026-08-03T10:00:00Z",
                run_id=501,
                head_branch="master",
                head_sha=OLD_PAGES_SHA,
            ),
            artifact(
                42,
                f"{expected_name}--not-a-sha",
                "2026-08-03T10:00:00Z",
                run_id=502,
                head_branch="master",
                head_sha=OLD_PAGES_SHA,
            ),
        ]
        selected = select_old_caches(values, branch=BRANCH, keep=self.keep)
        self.assertEqual([item.artifact_id for item in selected], [100, 199])

    def test_generation_binds_cache_name_to_coverage_and_run_to_target(self) -> None:
        manifest = {
            "provenance": {
                "target": {"sha": OLD_PAGES_SHA, "run_id": 800},
                "coverage_sha": TARGET_SHA,
            }
        }
        expected = BranchGeneration(
            branch=BRANCH,
            target_sha=OLD_PAGES_SHA,
            coverage_sha=TARGET_SHA,
            target_run_id=800,
            keep=self.keep,
        )
        with tempfile.TemporaryDirectory() as temporary:
            evidence_root = Path(temporary)
            (evidence_root / BRANCH).mkdir()
            with patch("rotate_artifacts.validate_bundle", return_value=manifest):
                generations = load_generations(
                    evidence_root=evidence_root,
                    repository=REPOSITORY,
                    pages_run_id=900,
                    pages_run_sha=PAGES_SHA,
                    trigger_artifacts=[self.keep],
                )
                self.assertEqual(generations, [expected])

                mismatched = artifact(
                    201,
                    f"pages-cache-{BRANCH}--{PAGES_SHA}",
                    self.keep.created_at,
                    run_id=900,
                    head_branch="master",
                    head_sha=PAGES_SHA,
                )
                with self.assertRaises(RotationError):
                    load_generations(
                        evidence_root=evidence_root,
                        repository=REPOSITORY,
                        pages_run_id=900,
                        pages_run_sha=PAGES_SHA,
                        trigger_artifacts=[mismatched],
                    )

                (evidence_root / "unexpected.txt").write_text(
                    "not evidence", encoding="utf-8"
                )
                with self.assertRaises(RotationError):
                    load_generations(
                        evidence_root=evidence_root,
                        repository=REPOSITORY,
                        pages_run_id=900,
                        pages_run_sha=PAGES_SHA,
                        trigger_artifacts=[self.keep],
                    )

    def test_compatibility_generation_is_bound_to_manifest_coverage_sha(self) -> None:
        manifest = {
            "provenance": {
                "coverage_sha": TARGET_SHA,
                "compatibility_run_id": 810,
                "publication_run_id": 820,
            }
        }
        with tempfile.TemporaryDirectory() as temporary:
            evidence_root = Path(temporary)
            (evidence_root / BRANCH).mkdir()
            with patch(
                "rotate_artifacts.validate_compatibility_bundle",
                return_value=manifest,
            ):
                generations = load_compatibility_generations(
                    evidence_root=evidence_root,
                    repository=REPOSITORY,
                    pages_run_id=900,
                    pages_run_sha=PAGES_SHA,
                    trigger_artifacts=[self.compatibility_keep],
                )
        self.assertEqual(generations, [self.compatibility_generation])

    def test_carried_generation_rotates_at_coverage_head_and_consumes_target_handoff(
        self,
    ) -> None:
        carried = BranchGeneration(
            branch=BRANCH,
            target_sha=OLD_PAGES_SHA,
            coverage_sha=TARGET_SHA,
            target_run_id=800,
            keep=self.keep,
        )
        old_cache = artifact(
            100,
            f"pages-cache-{BRANCH}",
            "2026-08-03T10:00:00Z",
            run_id=700,
            head_branch="master",
            head_sha=OLD_PAGES_SHA,
        )
        consumed_handoff = artifact(
            110,
            f"pages-e2e-{BRANCH}",
            "2026-08-03T11:00:00Z",
            run_id=800,
            head_branch=BRANCH,
            head_sha=OLD_PAGES_SHA,
        )
        api = FakeApi(
            keep=self.keep,
            inventories={
                old_cache.name: [old_cache, self.keep],
                consumed_handoff.name: [consumed_handoff],
            },
            runs={
                700: run(
                    700,
                    workflow=".github/workflows/pages.yml",
                    event="schedule",
                    branch="master",
                    sha=OLD_PAGES_SHA,
                ),
                800: run(
                    800,
                    workflow=".github/workflows/on-demand-e2e.yml",
                    event="workflow_dispatch",
                    branch=BRANCH,
                    sha=OLD_PAGES_SHA,
                ),
                900: run(
                    900,
                    workflow=".github/workflows/pages.yml",
                    event="workflow_dispatch",
                    branch="master",
                    sha=PAGES_SHA,
                )
            },
            branch_sha=TARGET_SHA,
        )

        deleted = rotate_branch(
            api,
            carried,
            repository=REPOSITORY,
            pages_run_id=900,
            pages_run_sha=PAGES_SHA,
            delete_delay_seconds=0,
        )

        self.assertEqual(deleted, [100, 110])
        self.assertEqual(api.deleted, [100, 110])

    def test_compatibility_rotation_retires_only_older_authenticated_artifacts(self) -> None:
        cache_name = f"pages-mod-compatibility-cache-{BRANCH}"
        handoff_name = f"pages-mod-compatibility-{BRANCH}"
        old_cache = artifact(
            101,
            cache_name,
            "2026-08-03T10:00:00Z",
            run_id=700,
            head_branch="master",
            head_sha=OLD_PAGES_SHA,
        )
        old_handoff = artifact(
            102,
            handoff_name,
            "2026-08-03T11:00:00Z",
            run_id=820,
            head_branch="master",
            head_sha=OLD_PAGES_SHA,
        )
        newer_handoff = artifact(
            301,
            handoff_name,
            "2026-08-03T13:00:00Z",
            run_id=821,
            head_branch="master",
            head_sha=PAGES_SHA,
        )
        self.assertEqual(
            select_old_compatibility_caches(
                [old_cache, self.compatibility_keep],
                branch=BRANCH,
                keep=self.compatibility_keep,
            ),
            [old_cache],
        )
        self.assertEqual(
            select_old_compatibility_handoffs(
                [old_handoff, newer_handoff],
                branch=BRANCH,
                keep=self.compatibility_keep,
            ),
            [old_handoff],
        )
        api = FakeApi(
            keep=self.compatibility_keep,
            inventories={
                cache_name: [old_cache, self.compatibility_keep],
                handoff_name: [old_handoff, newer_handoff],
            },
            runs={
                700: run(
                    700,
                    workflow=".github/workflows/pages.yml",
                    event="workflow_dispatch",
                    branch="master",
                    sha=OLD_PAGES_SHA,
                    conclusion="failure",
                ),
                820: run(
                    820,
                    workflow=".github/workflows/mod-compatibility-review.yml",
                    event="workflow_dispatch",
                    branch="master",
                    sha=OLD_PAGES_SHA,
                ),
                900: run(
                    900,
                    workflow=".github/workflows/pages.yml",
                    event="workflow_dispatch",
                    branch="master",
                    sha=PAGES_SHA,
                ),
            },
        )
        deleted = rotate_compatibility_branch(
            api,
            self.compatibility_generation,
            repository=REPOSITORY,
            pages_run_id=900,
            pages_run_sha=PAGES_SHA,
            delete_delay_seconds=0,
        )
        self.assertEqual(deleted, [101, 102])
        self.assertEqual(api.deleted, [101, 102])

    def test_compatibility_selector_prefers_newest_authenticated_generation(self) -> None:
        handoff = artifact(
            400,
            f"pages-mod-compatibility-{BRANCH}",
            "2026-08-03T12:20:00Z",
            run_id=920,
            head_branch="master",
            head_sha=PAGES_SHA,
        )
        api = FakeApi(
            keep=self.compatibility_keep,
            inventories={
                handoff.name: [handoff],
                self.compatibility_keep.name: [self.compatibility_keep],
            },
            runs={
                900: run(
                    900,
                    workflow=".github/workflows/pages.yml",
                    event="workflow_dispatch",
                    branch="master",
                    sha=PAGES_SHA,
                ),
                920: run(
                    920,
                    workflow=".github/workflows/mod-compatibility-review.yml",
                    event="workflow_dispatch",
                    branch="master",
                    sha=PAGES_SHA,
                ),
            },
        )
        self.assertEqual(
            select_compatibility_source(api, repository=REPOSITORY, branch=BRANCH),
            handoff,
        )

    def test_handoff_selector_deletes_only_the_consumed_exact_run(self) -> None:
        expected_name = f"pages-e2e-{BRANCH}"
        consumed = artifact(
            110,
            expected_name,
            "2026-08-03T11:30:00Z",
            run_id=800,
            head_branch=BRANCH,
            head_sha=TARGET_SHA,
        )
        newer_run = artifact(
            120,
            expected_name,
            "2026-08-03T11:45:00Z",
            run_id=801,
            head_branch=BRANCH,
            head_sha=TARGET_SHA,
        )
        selected = select_consumed_handoffs(
            [consumed, newer_run],
            branch=BRANCH,
            target_run_id=800,
            target_sha=TARGET_SHA,
            keep=self.keep,
        )
        self.assertEqual(selected, [consumed])

    def test_old_handoff_selector_keeps_the_current_lossless_generation(self) -> None:
        expected_name = f"pages-e2e-{BRANCH}"
        old = artifact(
            100,
            expected_name,
            "2026-08-03T10:30:00Z",
            run_id=700,
            head_branch=BRANCH,
            head_sha=OLD_PAGES_SHA,
        )
        current = artifact(
            110,
            expected_name,
            "2026-08-03T11:30:00Z",
            run_id=800,
            head_branch=BRANCH,
            head_sha=TARGET_SHA,
        )
        newer = artifact(
            120,
            expected_name,
            "2026-08-03T12:30:00Z",
            run_id=801,
            head_branch=BRANCH,
            head_sha=TARGET_SHA,
        )

        selected = select_old_handoffs(
            [newer, current, old],
            branch=BRANCH,
            keep=current,
        )

        self.assertEqual([old], selected)

    def test_pages_selector_is_bounded_to_current_run_and_release_inventory(self) -> None:
        expected = [
            artifact(
                300,
                f"collected-pages-{BRANCH}",
                "2026-08-03T11:30:00Z",
                run_id=900,
                head_branch="master",
                head_sha=PAGES_SHA,
            ),
            artifact(
                301,
                "github-pages",
                "2026-08-03T11:40:00Z",
                run_id=900,
                head_branch="master",
                head_sha=PAGES_SHA,
            ),
        ]
        excluded = [
            self.keep,
            artifact(
                302,
                "collected-pages-forge-and-fabric-9.9.9",
                "2026-08-03T11:30:00Z",
                run_id=900,
                head_branch="master",
                head_sha=PAGES_SHA,
            ),
            artifact(
                303,
                "github-pages",
                "2026-08-03T11:30:00Z",
                run_id=899,
                head_branch="master",
                head_sha=PAGES_SHA,
            ),
            artifact(
                304,
                f"collected-pages-{BRANCH}",
                "2026-08-03T11:30:00Z",
                run_id=900,
                head_branch="master",
                head_sha=OLD_PAGES_SHA,
            ),
        ]
        selected = select_pages_run_transients(
            [*excluded, *reversed(expected)],
            generations=[self.generation],
            pages_run_id=900,
            pages_run_sha=PAGES_SHA,
        )
        self.assertEqual(selected, expected)

    def test_source_selection_prefers_cache_newer_than_consumed_handoff(self) -> None:
        handoff = artifact(
            110,
            f"pages-e2e-{BRANCH}",
            "2026-08-03T11:30:00Z",
            run_id=800,
            head_branch=BRANCH,
            head_sha=TARGET_SHA,
        )
        legacy = artifact(
            300,
            f"pages-cache-{BRANCH}",
            "2026-08-03T13:00:00Z",
            run_id=700,
            head_branch="master",
            head_sha=OLD_PAGES_SHA,
        )
        api = FakeApi(
            keep=self.keep,
            inventories={
                handoff.name: [handoff],
                self.keep.name: [self.keep],
                legacy.name: [legacy],
            },
            runs={
                800: run(
                    800,
                    workflow=".github/workflows/on-demand-e2e.yml",
                    event="workflow_dispatch",
                    branch=BRANCH,
                    sha=TARGET_SHA,
                ),
                900: run(
                    900,
                    workflow=".github/workflows/pages.yml",
                    event="workflow_run",
                    branch="master",
                    sha=PAGES_SHA,
                ),
            },
        )
        selected = select_source(
            api,
            repository=REPOSITORY,
            branch=BRANCH,
            current_sha=TARGET_SHA,
        )
        self.assertEqual(selected, self.keep)
        self.assertNotIn(700, api.runs)
        self.assertEqual(
            handoff,
            select_source(
                api,
                repository=REPOSITORY,
                branch=BRANCH,
                current_sha=TARGET_SHA,
                require_raw=True,
            ),
        )

    def test_source_selection_prefers_a_newer_same_sha_handoff(self) -> None:
        handoff = artifact(
            300,
            f"pages-e2e-{BRANCH}",
            "2026-08-03T13:00:00Z",
            run_id=801,
            head_branch=BRANCH,
            head_sha=TARGET_SHA,
        )
        api = FakeApi(
            keep=self.keep,
            inventories={handoff.name: [handoff], self.keep.name: [self.keep]},
            runs={
                801: run(
                    801,
                    workflow=".github/workflows/on-demand-e2e.yml",
                    event="workflow_dispatch",
                    branch=BRANCH,
                    sha=TARGET_SHA,
                ),
                900: run(
                    900,
                    workflow=".github/workflows/pages.yml",
                    event="workflow_dispatch",
                    branch="master",
                    sha=PAGES_SHA,
                ),
            },
        )
        selected = select_source(
            api,
            repository=REPOSITORY,
            branch=BRANCH,
            current_sha=TARGET_SHA,
        )
        self.assertEqual(selected, handoff)

    def test_source_selection_excludes_a_later_cache_for_an_old_sha(self) -> None:
        handoff = artifact(
            110,
            f"pages-e2e-{BRANCH}",
            "2026-08-03T11:30:00Z",
            run_id=800,
            head_branch=BRANCH,
            head_sha=TARGET_SHA,
        )
        stale = artifact(
            400,
            f"pages-cache-{BRANCH}--{OLD_PAGES_SHA}",
            "2026-08-03T14:00:00Z",
            run_id=901,
            head_branch="master",
            head_sha=PAGES_SHA,
        )
        api = FakeApi(
            keep=self.keep,
            inventories={handoff.name: [handoff], stale.name: [stale]},
            runs={
                800: run(
                    800,
                    workflow=".github/workflows/on-demand-e2e.yml",
                    event="workflow_dispatch",
                    branch=BRANCH,
                    sha=TARGET_SHA,
                )
            },
        )
        selected = select_source(
            api,
            repository=REPOSITORY,
            branch=BRANCH,
            current_sha=TARGET_SHA,
        )
        self.assertEqual(selected, handoff)
        self.assertNotIn(901, api.runs)

    def _continuation_api(self, *, commits: list[str]) -> "FakeApi":
        """A branch whose head owns no evidence while its parent still does."""

        cache = artifact(
            410,
            f"pages-cache-{BRANCH}--{OLD_PAGES_SHA}",
            "2026-08-03T14:00:00Z",
            run_id=902,
            head_branch="master",
            head_sha=OLD_PAGES_SHA,
        )
        return FakeApi(
            keep=self.keep,
            inventories={cache.name: [cache]},
            runs={
                902: run(
                    902,
                    workflow=".github/workflows/pages.yml",
                    event="workflow_run",
                    branch="master",
                    sha=OLD_PAGES_SHA,
                )
            },
            branch_commits=commits,
        )

    def test_continuation_nominates_the_newest_ancestor_that_owns_evidence(self) -> None:
        api = self._continuation_api(commits=[TARGET_SHA, OLD_PAGES_SHA])
        evidence = resolve_evidence(
            api,
            repository=REPOSITORY,
            branch=BRANCH,
            current_sha=TARGET_SHA,
            allow_continuation=True,
        )
        # The nomination reports the head the evidence was written for, never the current
        # head, so the caller still validates provenance against the run that produced it.
        self.assertEqual(evidence.coverage_sha, OLD_PAGES_SHA)
        self.assertEqual(evidence.artifact.artifact_id, 410)
        self.assertEqual(api.commit_requests, 1)

    def test_continuation_refuses_a_head_the_branch_already_left(self) -> None:
        api = self._continuation_api(commits=[PAGES_SHA, TARGET_SHA, OLD_PAGES_SHA])
        with self.assertRaises(RotationError):
            resolve_evidence(
                api,
                repository=REPOSITORY,
                branch=BRANCH,
                current_sha=TARGET_SHA,
                allow_continuation=True,
            )

    def test_selection_never_continues_without_the_explicit_opt_in(self) -> None:
        api = self._continuation_api(commits=[TARGET_SHA, OLD_PAGES_SHA])
        with self.assertRaises(RotationError):
            resolve_evidence(
                api,
                repository=REPOSITORY,
                branch=BRANCH,
                current_sha=TARGET_SHA,
            )
        self.assertEqual(api.commit_requests, 0)

    def test_lossless_oracle_selection_never_continues(self) -> None:
        api = self._continuation_api(commits=[TARGET_SHA, OLD_PAGES_SHA])
        with self.assertRaises(RotationError):
            resolve_evidence(
                api,
                repository=REPOSITORY,
                branch=BRANCH,
                current_sha=TARGET_SHA,
                require_raw=True,
                allow_continuation=True,
            )
        self.assertEqual(api.commit_requests, 0)

    def test_exact_current_head_evidence_never_consults_the_lineage(self) -> None:
        cache = artifact(
            420,
            f"pages-cache-{BRANCH}--{TARGET_SHA}",
            "2026-08-03T15:00:00Z",
            run_id=903,
            head_branch="master",
            head_sha=PAGES_SHA,
        )
        api = FakeApi(
            keep=self.keep,
            inventories={cache.name: [cache]},
            runs={
                903: run(
                    903,
                    workflow=".github/workflows/pages.yml",
                    event="workflow_run",
                    branch="master",
                    sha=PAGES_SHA,
                )
            },
        )
        evidence = resolve_evidence(
            api,
            repository=REPOSITORY,
            branch=BRANCH,
            current_sha=TARGET_SHA,
            allow_continuation=True,
        )
        self.assertEqual(evidence.coverage_sha, TARGET_SHA)
        self.assertEqual(evidence.artifact.artifact_id, 420)

    def test_source_selection_skips_invalid_owner_and_supports_legacy_fallback(self) -> None:
        legacy_name = f"pages-cache-{BRANCH}"
        invalid = artifact(
            300,
            legacy_name,
            "2026-08-03T13:00:00Z",
            run_id=701,
            head_branch="master",
            head_sha=OLD_PAGES_SHA,
        )
        valid = artifact(
            200,
            legacy_name,
            "2026-08-03T12:00:00Z",
            run_id=700,
            head_branch="master",
            head_sha=OLD_PAGES_SHA,
        )
        api = FakeApi(
            keep=self.keep,
            inventories={legacy_name: [invalid, valid]},
            runs={
                701: run(
                    701,
                    workflow=".github/workflows/build-gate.yml",
                    event="workflow_dispatch",
                    branch="master",
                    sha=OLD_PAGES_SHA,
                ),
                700: run(
                    700,
                    workflow=".github/workflows/pages.yml",
                    event="schedule",
                    branch="master",
                    sha=OLD_PAGES_SHA,
                ),
            },
        )
        selected = select_source(
            api,
            repository=REPOSITORY,
            branch=BRANCH,
            current_sha=TARGET_SHA,
        )
        self.assertEqual(selected, valid)

    def test_rotation_preserves_a_fallback_until_successful_keep_is_verified(self) -> None:
        old_cache = artifact(
            100,
            f"pages-cache-{BRANCH}",
            "2026-08-03T11:00:00Z",
            run_id=700,
            head_branch="master",
            head_sha=OLD_PAGES_SHA,
        )
        handoff = artifact(
            110,
            f"pages-e2e-{BRANCH}",
            "2026-08-03T11:30:00Z",
            run_id=800,
            head_branch=BRANCH,
            head_sha=TARGET_SHA,
        )
        raw_source = artifact(
            120,
            "packaged-e2e-fabric-client",
            "2026-08-03T10:30:00Z",
            run_id=750,
            head_branch=f"automation/sync/{BRANCH}/run-1",
            head_sha="d" * 40,
        )
        api = FakeApi(
            keep=self.keep,
            inventories={
                old_cache.name: [old_cache, self.keep],
                handoff.name: [handoff],
            },
            runs={
                700: run(
                    700,
                    workflow=".github/workflows/pages.yml",
                    event="workflow_run",
                    branch="master",
                    sha=OLD_PAGES_SHA,
                ),
                800: run(
                    800,
                    workflow=".github/workflows/on-demand-e2e.yml",
                    event="workflow_dispatch",
                    branch=BRANCH,
                    sha=TARGET_SHA,
                ),
                900: run(
                    900,
                    workflow=".github/workflows/pages.yml",
                    event="workflow_dispatch",
                    branch="master",
                    sha=PAGES_SHA,
                ),
            },
            # Raw proof may still be consumed by a concurrent attestation and is left to its
            # one-day retention policy rather than Pages promotion.
            run_artifacts={750: [raw_source]},
        )
        deleted = rotate_branch(
            api,
            self.generation,
            repository=REPOSITORY,
            pages_run_id=900,
            pages_run_sha=PAGES_SHA,
            delete_delay_seconds=0,
        )
        self.assertEqual(deleted, [100, 110])
        self.assertEqual(api.deleted, [100, 110])
        self.assertNotIn(raw_source.artifact_id, api.deleted)

    def test_rotation_leaves_namespaced_caches_to_bounded_retention(self) -> None:
        namespaced_cache = artifact(
            100,
            f"pages-cache-{BRANCH}--{OLD_PAGES_SHA}",
            "2026-08-03T11:00:00Z",
            run_id=700,
            head_branch="master",
            head_sha=OLD_PAGES_SHA,
        )
        api = FakeApi(
            keep=self.keep,
            inventories={
                namespaced_cache.name: [namespaced_cache],
                f"pages-e2e-{BRANCH}": [],
            },
            runs={},
        )

        deleted = rotate_branch(
            api,
            self.generation,
            repository=REPOSITORY,
            pages_run_id=900,
            pages_run_sha=PAGES_SHA,
            delete_delay_seconds=0,
        )

        self.assertEqual(deleted, [])
        self.assertEqual(api.deleted, [])

    def test_rotation_replaces_but_never_compacts_the_lossless_reference(self) -> None:
        old_cache = artifact(
            100,
            f"pages-cache-{BRANCH}",
            "2026-08-03T10:00:00Z",
            run_id=700,
            head_branch="master",
            head_sha=OLD_PAGES_SHA,
        )
        old_handoff = artifact(
            109,
            f"pages-e2e-{BRANCH}",
            "2026-08-03T10:30:00Z",
            run_id=790,
            head_branch=BRANCH,
            head_sha=OLD_PAGES_SHA,
        )
        current_handoff = artifact(
            110,
            f"pages-e2e-{BRANCH}",
            "2026-08-03T11:30:00Z",
            run_id=800,
            head_branch=BRANCH,
            head_sha=TARGET_SHA,
        )
        api = FakeApi(
            keep=self.keep,
            inventories={
                old_cache.name: [old_cache, self.keep],
                current_handoff.name: [old_handoff, current_handoff],
            },
            runs={
                700: run(
                    700,
                    workflow=".github/workflows/pages.yml",
                    event="workflow_run",
                    branch="master",
                    sha=OLD_PAGES_SHA,
                ),
                790: run(
                    790,
                    workflow=".github/workflows/on-demand-e2e.yml",
                    event="workflow_dispatch",
                    branch=BRANCH,
                    sha=OLD_PAGES_SHA,
                ),
                800: run(
                    800,
                    workflow=".github/workflows/on-demand-e2e.yml",
                    event="workflow_dispatch",
                    branch=BRANCH,
                    sha=TARGET_SHA,
                ),
                900: run(
                    900,
                    workflow=".github/workflows/pages.yml",
                    event="workflow_dispatch",
                    branch="master",
                    sha=PAGES_SHA,
                ),
            },
        )

        deleted = rotate_branch(
            api,
            self.generation,
            repository=REPOSITORY,
            pages_run_id=900,
            pages_run_sha=PAGES_SHA,
            delete_delay_seconds=0,
            preserve_handoff_branch=BRANCH,
        )

        self.assertEqual([100, 109], deleted)
        self.assertEqual([100, 109], api.deleted)
        self.assertNotIn(current_handoff.artifact_id, api.deleted)

    def test_rotation_is_a_noop_if_the_release_head_changed(self) -> None:
        api = FakeApi(
            keep=self.keep,
            inventories={},
            runs={},
            branch_sha="d" * 40,
        )
        deleted = rotate_branch(
            api,
            self.generation,
            repository=REPOSITORY,
            pages_run_id=900,
            pages_run_sha=PAGES_SHA,
            delete_delay_seconds=0,
        )
        self.assertEqual(deleted, [])
        self.assertEqual(api.deleted, [])

    def test_one_deferred_branch_does_not_abort_the_remaining_rotations(self) -> None:
        other_branch = "forge-and-fabric-1.21.1"
        keep_other = artifact(
            210,
            f"pages-cache-{other_branch}--{TARGET_SHA}",
            "2026-08-03T12:00:00Z",
            run_id=900,
            head_branch="master",
            head_sha=PAGES_SHA,
        )
        generation_other = BranchGeneration(
            branch=other_branch,
            target_sha=TARGET_SHA,
            coverage_sha=TARGET_SHA,
            target_run_id=801,
            keep=keep_other,
        )
        old_cache = artifact(
            100,
            f"pages-cache-{BRANCH}",
            "2026-08-03T11:00:00Z",
            run_id=700,
            head_branch="master",
            head_sha=OLD_PAGES_SHA,
        )
        old_cache_other = artifact(
            101,
            f"pages-cache-{other_branch}",
            "2026-08-03T11:00:00Z",
            run_id=700,
            head_branch="master",
            head_sha=OLD_PAGES_SHA,
        )
        changed_keep = artifact(
            200,
            self.keep.name,
            "2026-08-03T12:30:00Z",
            run_id=900,
            head_branch="master",
            head_sha=PAGES_SHA,
        )
        api = FakeApi(
            keep=self.keep,
            inventories={
                old_cache.name: [old_cache, self.keep],
                old_cache_other.name: [old_cache_other, keep_other],
                f"pages-e2e-{BRANCH}": [],
                f"pages-e2e-{other_branch}": [],
            },
            runs={
                700: run(
                    700,
                    workflow=".github/workflows/pages.yml",
                    event="schedule",
                    branch="master",
                    sha=OLD_PAGES_SHA,
                ),
                900: run(
                    900,
                    workflow=".github/workflows/pages.yml",
                    event="workflow_dispatch",
                    branch="master",
                    sha=PAGES_SHA,
                ),
            },
            branch_shas={BRANCH: TARGET_SHA, other_branch: TARGET_SHA},
            # The first branch's keep artifact mutates mid-rotation, so its per-delete
            # revalidation raises; the second branch must still rotate normally.
            artifact_overrides={200: changed_keep},
        )
        summary, deferred = rotate_generations(
            api,
            [self.generation, generation_other],
            repository=REPOSITORY,
            pages_run_id=900,
            pages_run_sha=PAGES_SHA,
            delete_delay_seconds=0,
        )
        self.assertEqual(deferred, [BRANCH])
        self.assertEqual(summary, {BRANCH: [], other_branch: [101]})
        self.assertEqual(api.deleted, [101])

    def test_global_deletion_budget_bounds_all_rotation_families(self) -> None:
        cache_name = f"pages-cache-{BRANCH}"
        old_caches = [
            artifact(
                artifact_id,
                cache_name,
                f"2026-08-03T{hour:02d}:00:00Z",
                run_id=700,
                head_branch="master",
                head_sha=OLD_PAGES_SHA,
            )
            for artifact_id, hour in ((100, 9), (101, 10), (102, 11))
        ]
        api = FakeApi(
            keep=self.keep,
            inventories={
                cache_name: [*old_caches, self.keep],
                f"pages-e2e-{BRANCH}": [],
            },
            runs={
                700: run(
                    700,
                    workflow=".github/workflows/pages.yml",
                    event="schedule",
                    branch="master",
                    sha=OLD_PAGES_SHA,
                ),
                900: run(
                    900,
                    workflow=".github/workflows/pages.yml",
                    event="workflow_dispatch",
                    branch="master",
                    sha=PAGES_SHA,
                ),
            },
        )
        deletion_budget = DeletionBudget(2)

        summary, deferred = rotate_generations(
            api,
            [self.generation],
            repository=REPOSITORY,
            pages_run_id=900,
            pages_run_sha=PAGES_SHA,
            delete_delay_seconds=0,
            deletion_budget=deletion_budget,
        )
        compatibility_summary, compatibility_deferred = (
            rotate_compatibility_generations(
                api,
                [self.compatibility_generation],
                repository=REPOSITORY,
                pages_run_id=900,
                pages_run_sha=PAGES_SHA,
                delete_delay_seconds=0,
                deletion_budget=deletion_budget,
            )
        )

        self.assertEqual(summary, {BRANCH: [100, 101]})
        self.assertEqual(deferred, [BRANCH])
        self.assertEqual(compatibility_summary, {BRANCH: []})
        self.assertEqual(compatibility_deferred, [BRANCH])
        self.assertEqual(api.deleted, [100, 101])
        self.assertEqual(deletion_budget.remaining, 0)

    def test_delete_404_is_idempotent(self) -> None:
        old_cache = artifact(
            100,
            f"pages-cache-{BRANCH}",
            "2026-08-03T11:00:00Z",
            run_id=700,
            head_branch="master",
            head_sha=OLD_PAGES_SHA,
        )
        api = FakeApi(
            keep=self.keep,
            inventories={
                old_cache.name: [old_cache, self.keep],
                f"pages-e2e-{BRANCH}": [],
            },
            runs={
                700: run(
                    700,
                    workflow=".github/workflows/pages.yml",
                    event="schedule",
                    branch="master",
                    sha=OLD_PAGES_SHA,
                ),
                900: run(
                    900,
                    workflow=".github/workflows/pages.yml",
                    event="workflow_dispatch",
                    branch="master",
                    sha=PAGES_SHA,
                ),
            },
            missing_on_delete={100},
        )
        deleted = rotate_branch(
            api,
            self.generation,
            repository=REPOSITORY,
            pages_run_id=900,
            pages_run_sha=PAGES_SHA,
            delete_delay_seconds=0,
        )
        self.assertEqual(deleted, [])

    def test_pages_run_transients_retire_only_after_every_keep_is_revalidated(self) -> None:
        collected = artifact(
            300,
            f"collected-pages-{BRANCH}",
            "2026-08-03T11:30:00Z",
            run_id=900,
            head_branch="master",
            head_sha=PAGES_SHA,
        )
        deploy = artifact(
            301,
            "github-pages",
            "2026-08-03T11:40:00Z",
            run_id=900,
            head_branch="master",
            head_sha=PAGES_SHA,
        )
        trigger_artifacts = [self.keep, collected, deploy]
        api = FakeApi(
            keep=self.keep,
            inventories={},
            runs={
                900: run(
                    900,
                    workflow=".github/workflows/pages.yml",
                    event="workflow_dispatch",
                    branch="master",
                    sha=PAGES_SHA,
                )
            },
            run_artifacts={900: trigger_artifacts},
        )
        deleted = retire_pages_run_transients(
            api,
            generations=[self.generation],
            trigger_artifacts=trigger_artifacts,
            repository=REPOSITORY,
            pages_run_id=900,
            pages_run_sha=PAGES_SHA,
            delete_delay_seconds=0,
        )
        self.assertEqual(deleted, [300, 301])
        self.assertEqual(api.deleted, [300, 301])

    def test_pages_run_transients_defer_before_exceeding_validation_budget(self) -> None:
        collected = artifact(
            300,
            f"collected-pages-{BRANCH}",
            "2026-08-03T11:30:00Z",
            run_id=900,
            head_branch="master",
            head_sha=PAGES_SHA,
        )
        api = FakeApi(
            keep=self.keep,
            inventories={},
            runs={},
            run_artifacts={900: [self.keep, collected]},
        )

        with patch("rotate_artifacts.MAX_TRANSIENT_KEEP_VALIDATIONS", 0):
            with self.assertRaisesRegex(RotationError, "retention will retire"):
                retire_pages_run_transients(
                    api,
                    generations=[self.generation],
                    trigger_artifacts=[self.keep, collected],
                    repository=REPOSITORY,
                    pages_run_id=900,
                    pages_run_sha=PAGES_SHA,
                    delete_delay_seconds=0,
                )

        self.assertEqual(api.deleted, [])

    def test_pages_run_transients_defer_before_exceeding_deletion_budget(self) -> None:
        collected = artifact(
            300,
            f"collected-pages-{BRANCH}",
            "2026-08-03T11:30:00Z",
            run_id=900,
            head_branch="master",
            head_sha=PAGES_SHA,
        )
        api = FakeApi(
            keep=self.keep,
            inventories={},
            runs={},
            run_artifacts={900: [self.keep, collected]},
        )

        with self.assertRaisesRegex(RotationError, "global deletion budget"):
            retire_pages_run_transients(
                api,
                generations=[self.generation],
                trigger_artifacts=[self.keep, collected],
                repository=REPOSITORY,
                pages_run_id=900,
                pages_run_sha=PAGES_SHA,
                delete_delay_seconds=0,
                deletion_budget=DeletionBudget(0),
            )

        self.assertEqual(api.deleted, [])

    def test_artifact_identity_change_fails_closed_before_delete(self) -> None:
        collected = artifact(
            300,
            f"collected-pages-{BRANCH}",
            "2026-08-03T11:30:00Z",
            run_id=900,
            head_branch="master",
            head_sha=PAGES_SHA,
        )
        changed = artifact(
            300,
            "github-pages",
            "2026-08-03T11:30:00Z",
            run_id=900,
            head_branch="master",
            head_sha=PAGES_SHA,
        )
        api = FakeApi(
            keep=self.keep,
            inventories={},
            runs={
                900: run(
                    900,
                    workflow=".github/workflows/pages.yml",
                    event="workflow_dispatch",
                    branch="master",
                    sha=PAGES_SHA,
                )
            },
            run_artifacts={900: [self.keep, collected]},
            artifact_overrides={300: changed},
        )
        with self.assertRaisesRegex(RotationError, "artifact changed"):
            retire_pages_run_transients(
                api,
                generations=[self.generation],
                trigger_artifacts=[self.keep, collected],
                repository=REPOSITORY,
                pages_run_id=900,
                pages_run_sha=PAGES_SHA,
                delete_delay_seconds=0,
            )
        self.assertEqual(api.deleted, [])

    def test_wrong_owner_workflow_fails_closed_before_any_delete(self) -> None:
        old_cache = artifact(
            100,
            f"pages-cache-{BRANCH}",
            "2026-08-03T11:00:00Z",
            run_id=700,
            head_branch="master",
            head_sha=OLD_PAGES_SHA,
        )
        api = FakeApi(
            keep=self.keep,
            inventories={
                old_cache.name: [old_cache, self.keep],
                f"pages-e2e-{BRANCH}": [],
            },
            runs={
                700: run(
                    700,
                    workflow=".github/workflows/build-gate.yml",
                    event="workflow_dispatch",
                    branch="master",
                    sha=OLD_PAGES_SHA,
                )
            },
        )
        with self.assertRaises(RotationError):
            rotate_branch(
                api,
                self.generation,
                repository=REPOSITORY,
                pages_run_id=900,
                pages_run_sha=PAGES_SHA,
                delete_delay_seconds=0,
            )
        self.assertEqual(api.deleted, [])

    def test_artifact_listing_paginates_past_one_hundred(self) -> None:
        class StubApi(GitHubApi):
            def __init__(self) -> None:
                super().__init__(repository=REPOSITORY, token="token", api_url="https://api")
                self.pages: list[int] = []

            def _request(self, method: str, path: str) -> Any:
                self.assert_request(method, path)
                page = int(path.rsplit("page=", 1)[1])
                self.pages.append(page)
                count = 100 if page == 1 else 1
                return {
                    "artifacts": [
                        {
                            "id": page * 1000 + index + 1,
                            "name": "pages-cache-test",
                            "expired": False,
                            "size_in_bytes": 1,
                            "created_at": "2026-08-03T10:00:00Z",
                            "workflow_run": {
                                "id": 1,
                                "head_branch": "master",
                                "head_sha": PAGES_SHA,
                            },
                        }
                        for index in range(count)
                    ]
                }

            def assert_request(self, method: str, path: str) -> None:
                if (
                    method != "GET"
                    or "name=pages-cache-test" not in path
                    or "per_page=100" not in path
                ):
                    raise AssertionError(f"unexpected request: {method} {path}")

        api = StubApi()
        values = api.list_artifacts("pages-cache-test")
        self.assertEqual(len(values), 101)
        self.assertEqual(api.pages, [1, 2])


class SelectArtifactProbeTest(unittest.TestCase):
    def probe_argv(self) -> list[str]:
        return ["--repository", REPOSITORY, "--branch", BRANCH, "--probe"]

    def test_probe_exits_zero_when_current_evidence_exists(self) -> None:
        keep = artifact(
            200,
            f"pages-cache-{BRANCH}--{TARGET_SHA}",
            "2026-08-03T12:00:00Z",
            run_id=900,
            head_branch="master",
            head_sha=PAGES_SHA,
        )
        with patch.dict(os.environ, {"GH_TOKEN": "token"}), patch.object(
            select_artifact, "GitHubApi"
        ) as api_factory:
            api_factory.return_value.get_branch_sha.return_value = TARGET_SHA
            with patch.object(select_artifact, "select_source", return_value=keep):
                self.assertEqual(select_artifact.main(self.probe_argv()), 0)

    def test_probe_reports_missing_evidence_with_a_distinct_exit_code(self) -> None:
        self.assertEqual(PROBE_NO_EVIDENCE_EXIT, 3)
        with patch.dict(os.environ, {"GH_TOKEN": "token"}), patch.object(
            select_artifact, "GitHubApi"
        ) as api_factory:
            api_factory.return_value.get_branch_sha.return_value = TARGET_SHA
            with patch.object(
                select_artifact,
                "select_source",
                side_effect=RotationError(
                    f"no authenticated current evidence exists for {BRANCH}"
                ),
            ):
                self.assertEqual(
                    select_artifact.main(self.probe_argv()), PROBE_NO_EVIDENCE_EXIT
                )

    def test_probe_does_not_reclassify_api_failure_as_missing_evidence(self) -> None:
        with patch.dict(os.environ, {"GH_TOKEN": "token"}), patch.object(
            select_artifact, "GitHubApi"
        ) as api_factory:
            api_factory.return_value.get_branch_sha.return_value = TARGET_SHA
            with patch.object(
                select_artifact,
                "select_source",
                side_effect=ApiError(403, "installation rate limit"),
            ):
                self.assertEqual(select_artifact.main(self.probe_argv()), 2)

    def test_probe_keeps_the_ordinary_error_exit_for_bad_configuration(self) -> None:
        with patch.dict(os.environ, {"GH_TOKEN": ""}):
            self.assertEqual(select_artifact.main(self.probe_argv()), 2)

    def test_selection_still_requires_the_github_output_destination(self) -> None:
        with self.assertRaises(SystemExit):
            select_artifact.parse_args(["--repository", REPOSITORY, "--branch", BRANCH])
        self.assertTrue(select_artifact.parse_args(self.probe_argv()).probe)
        self.assertTrue(
            select_artifact.parse_args([*self.probe_argv(), "--require-raw"]).require_raw
        )

    def test_selection_outputs_the_exact_numeric_artifact_identity(self) -> None:
        keep = artifact(
            200,
            f"pages-cache-{BRANCH}--{TARGET_SHA}",
            "2026-08-03T12:00:00Z",
            run_id=900,
            head_branch="master",
            head_sha=PAGES_SHA,
        )
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "github-output"
            argv = [
                "--repository",
                REPOSITORY,
                "--branch",
                BRANCH,
                "--github-output",
                str(output),
            ]
            with patch.dict(os.environ, {"GH_TOKEN": "token"}), patch.object(
                select_artifact, "GitHubApi"
            ) as api_factory, patch.object(
                select_artifact, "select_source", return_value=keep
            ):
                api_factory.return_value.get_branch_sha.return_value = TARGET_SHA
                self.assertEqual(select_artifact.main(argv), 0)

            self.assertEqual(
                [
                    "artifact_id=200",
                    f"name=pages-cache-{BRANCH}--{TARGET_SHA}",
                    "run_id=900",
                    f"coverage_sha={TARGET_SHA}",
                    f"head_sha={TARGET_SHA}",
                    "size_in_bytes=100",
                ],
                output.read_text(encoding="utf-8").splitlines(),
            )


if __name__ == "__main__":
    unittest.main()
