from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
import urllib.error
import urllib.parse
from dataclasses import replace
from io import BytesIO
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any
from unittest.mock import MagicMock, patch


ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts" / "ci"))

from visual_review_queue import (  # noqa: E402
    DRAIN_WORKFLOW,
    GitHubApi,
    GitHubRateLimitError,
    MAX_NAMED_ARTIFACTS,
    MAX_NAMED_PAGES,
    MAX_PENDING_KEYS,
    MAX_PRODUCER_RUN_PAGES,
    MAX_RUN_ARTIFACT_PAGES,
    PREPARE_WORKFLOW,
    Artifact,
    QueueError,
    blocked_generations,
    input_review_key,
    input_target,
    list_pending_candidates,
    main as queue_main,
    requested_owner_settling,
    select_pending,
    select_requested,
)
from test_workflow_security import job_block, step_script  # noqa: E402


REPOSITORY = "The-Plum-Team/Quick-Skin-Mod"
SHA = "a" * 40
NOW = datetime(2026, 8, 10, 12, 0, tzinfo=timezone.utc)


def artifact(
    artifact_id: int,
    name: str,
    *,
    run_id: int,
    minutes_ago: int,
    size: int = 1024,
) -> Artifact:
    return Artifact(
        artifact_id=artifact_id,
        name=name,
        size_in_bytes=size,
        digest="sha256:" + "b" * 64,
        expired=False,
        created_at=NOW - timedelta(minutes=minutes_ago),
        run_id=run_id,
        head_branch="master",
        head_sha=SHA,
    )


def raw_artifact(artifact_id: int, name: str, *, expired: bool) -> dict[str, Any]:
    return {
        "id": artifact_id,
        "name": name,
        "size_in_bytes": 1024,
        "digest": "sha256:" + "b" * 64,
        "expired": expired,
        "created_at": (NOW - timedelta(minutes=artifact_id)).isoformat(),
        "workflow_run": {"id": 10, "head_branch": "master", "head_sha": SHA},
    }


def owner(
    run_id: int,
    workflow: str,
    *,
    conclusion: str | None = "success",
    status: str = "completed",
) -> dict[str, Any]:
    return {
        "id": run_id,
        "status": status,
        "conclusion": conclusion,
        "event": "workflow_run" if workflow == PREPARE_WORKFLOW else "schedule",
        "path": workflow,
        "head_branch": "master",
        "head_sha": SHA,
        "head_repository": {"full_name": REPOSITORY},
    }


class FakeApi:
    def __init__(
        self,
        artifacts: list[Artifact],
        runs: dict[int, dict[str, Any]],
        *,
        branch_sha: str = SHA,
        pulls: dict[int, dict[str, Any]] | None = None,
    ) -> None:
        self.artifacts = artifacts
        self.runs = runs
        self.branch_sha = branch_sha
        self.pulls = pulls or {}

    def list_generation_inputs(self, generation: str) -> list[Artifact]:
        # Mirrors the production listing: only curator runs at that exact SHA are read.
        return [artifact for artifact in self.artifacts
                if artifact.head_sha == generation and artifact.name.startswith("visual-review-input-")]

    def list_artifacts_named(self, name: str) -> list[Artifact]:
        return [artifact for artifact in self.artifacts if artifact.name == name]

    def get_artifact(self, artifact_id: int) -> Artifact | None:
        return next(
            (
                artifact
                for artifact in self.artifacts
                if artifact.artifact_id == artifact_id
            ),
            None,
        )

    def get_run(self, run_id: int) -> dict[str, Any]:
        return self.runs[run_id]

    def get_source_run(self, run_id: int) -> dict[str, Any]:
        return self.runs.get(
            run_id,
            {
                "status": "completed",
                "conclusion": "success",
                "head_branch": "feature/example",
            },
        )

    def get_branch_sha(self, branch: str) -> str:
        self.assert_master(branch)
        return self.branch_sha

    @staticmethod
    def assert_master(branch: str) -> None:
        if branch != "master":
            raise AssertionError(f"unexpected branch lookup: {branch}")

    def get_pull(self, number: int) -> dict[str, Any]:
        return self.pulls[number]


class VisualReviewQueueTest(unittest.TestCase):
    def test_newer_already_reviewed_input_can_reopen_only_during_older_marker_expiry_window(self) -> None:
        newer_input = artifact(1, f"visual-review-input-55-{SHA}--mc1.21.1",
                               run_id=10, minutes_ago=10)
        older_report = artifact(2, "visual-review-55--mc1.21.1", run_id=20, minutes_ago=20)
        api = FakeApi([newer_input, older_report],
                      {10: owner(10, PREPARE_WORKFLOW), 20: owner(20, DRAIN_WORKFLOW)})
        self.assertEqual([], list_pending_candidates(api, repository=REPOSITORY, now=NOW))
        api.artifacts = [newer_input, replace(older_report, expired=True)]
        self.assertEqual([(newer_input, 55)], list_pending_candidates(api, repository=REPOSITORY, now=NOW))
        api.artifacts = [replace(newer_input, expired=True), replace(older_report, expired=True)]
        self.assertEqual([], list_pending_candidates(api, repository=REPOSITORY, now=NOW))

    def test_retained_full_wave_is_suppressed_until_cancelled_owner_reopens_recovery(self) -> None:
        rows = json.loads((Path(__file__).parent / "fixtures/visual-review-stage-reference.json").read_text())["rows"]
        inputs = [artifact(index + 1, f"visual-review-input-55-{SHA}--{row['bundle']}",
                           run_id=10, minutes_ago=20) for index, row in enumerate(rows)]
        reports = [artifact(index + 101, f"visual-review-55--{row['bundle']}",
                            run_id=index + 201, minutes_ago=10) for index, row in enumerate(rows)]
        self.assertEqual(16, len(inputs))
        for status, conclusion in (("completed", "success"), ("completed", "failure"),
                                   ("in_progress", None), ("queued", None), ("pending", None),
                                   ("completed", "cancelled")):
            with self.subTest(status=status, conclusion=conclusion):
                runs = {10: owner(10, PREPARE_WORKFLOW)}
                runs.update({item.run_id: owner(item.run_id, DRAIN_WORKFLOW,
                                                status=status, conclusion=conclusion)
                             for item in reports})
                api = FakeApi(inputs + reports, runs)
                with patch.object(api, "get_run", wraps=api.get_run) as get_run:
                    pending = list_pending_candidates(api, repository=REPOSITORY, now=NOW)
                    self.assertEqual(16 if conclusion == "cancelled" else 0, len(pending))
                    # The production GitHubApi memoizes owners: one curator plus 16 reviewers.
                    self.assertEqual(17, len({call.args[0] for call in get_run.call_args_list}))
                with patch.object(api, "list_generation_inputs", side_effect=AssertionError("exact wake must not scan")):
                    for item in inputs:
                        selected = select_requested(api, repository=REPOSITORY,
                                                    requested_artifact_id=item.artifact_id, now=NOW)
                        self.assertEqual((item, 55) if conclusion == "cancelled" else None, selected)

    def test_history_unrelated_to_pending_targets_does_not_query_its_owners(self) -> None:
        pending = artifact(1, f"visual-review-input-55-{SHA}--mc1.21.8", run_id=10, minutes_ago=15)
        history = [artifact(100 + index, f"visual-review-{1000 + index}--mc1.21.8",
                            run_id=2000 + index, minutes_ago=60 * 24 * 100) for index in range(250)]
        runs = {10: owner(10, PREPARE_WORKFLOW),
                **{item.run_id: owner(item.run_id, DRAIN_WORKFLOW) for item in history}}
        api = FakeApi([*history, pending], runs)
        with patch.object(api, "get_run", wraps=api.get_run) as owners:
            self.assertEqual([(pending, 55)], list_pending_candidates(api, repository=REPOSITORY, now=NOW))
        self.assertEqual([10], [call.args[0] for call in owners.call_args_list])

    def test_aged_legacy_and_current_generation_capsules_stay_eligible(self) -> None:
        for suffix in ("", "-" + SHA, f"-{SHA}--mc1.21.8"):
            with self.subTest(suffix=suffix):
                pending = artifact(1, "visual-review-input-55" + suffix, run_id=10,
                                   minutes_ago=60 * 24 * 200)
                api = FakeApi([pending], {10: owner(10, PREPARE_WORKFLOW)})
                self.assertEqual([(pending, 55)], list_pending_candidates(api, repository=REPOSITORY, now=NOW))

    def test_sweep_reads_only_curator_runs_of_the_current_generation(self) -> None:
        # The protected curator skips any generation other than its own SHA, so a sweep reads only
        # the curator runs at current master. A capsule whose name and producer SHA disagree is
        # still admitted by its exact wake, which authenticates the capsule by ID.
        other = "d" * 40
        crossed = artifact(1, f"visual-review-input-55-{other}", run_id=10, minutes_ago=15)
        api = FakeApi([crossed], {10: owner(10, PREPARE_WORKFLOW)}, branch_sha=other)
        with patch.object(api, "list_generation_inputs", wraps=api.list_generation_inputs) as listed:
            self.assertEqual([], list_pending_candidates(api, repository=REPOSITORY, now=NOW))
        self.assertEqual([other], [call.args[0] for call in listed.call_args_list])
        self.assertEqual((crossed, 55), select_requested(api, repository=REPOSITORY,
                                                         requested_artifact_id=1, now=NOW))

    def test_sweep_looks_markers_up_by_exact_name_only(self) -> None:
        capsules = [
            artifact(1, f"visual-review-input-55-{SHA}--mc1.20.1", run_id=10, minutes_ago=15),
            artifact(2, f"visual-review-input-56-{SHA}", run_id=11, minutes_ago=15),
            artifact(3, f"visual-review-input-57-{'c' * 40}--mc1.20.1", run_id=10, minutes_ago=15),
        ]
        api = FakeApi(capsules, {10: owner(10, PREPARE_WORKFLOW), 11: owner(11, PREPARE_WORKFLOW)})
        with patch.object(api, "list_artifacts_named", wraps=api.list_artifacts_named) as named:
            pending = list_pending_candidates(api, repository=REPOSITORY, now=NOW)
        self.assertEqual({1, 2}, {item.artifact_id for item, _ in pending})
        self.assertEqual(
            sorted({"visual-review-55--mc1.20.1", "visual-review-attempt-55--mc1.20.1",
                    "visual-review-56", "visual-review-attempt-56",
                    f"visual-review-wave-block-{SHA}", f"visual-anchor-certification-{SHA}"}),
            [call.args[0] for call in named.call_args_list])

    def test_sweep_fails_closed_above_the_pending_key_bound(self) -> None:
        capsules = [artifact(index, f"visual-review-input-{index}-{SHA}", run_id=10, minutes_ago=15)
                    for index in range(1, MAX_PENDING_KEYS + 2)]
        api = FakeApi(capsules, {10: owner(10, PREPARE_WORKFLOW)})
        with patch.object(api, "list_artifacts_named", side_effect=AssertionError("unbounded")):
            with self.assertRaises(QueueError) as caught:
                list_pending_candidates(api, repository=REPOSITORY, now=NOW)
        self.assertIn(str(MAX_PENDING_KEYS), str(caught.exception))

    def test_related_markers_still_authenticate_foreign_owners(self) -> None:
        pending = artifact(1, f"visual-review-input-55-{SHA}--mc1.21.8", run_id=10, minutes_ago=15)
        report = artifact(2, "visual-review-55--mc1.21.8", run_id=20, minutes_ago=10)
        foreign = owner(20, DRAIN_WORKFLOW)
        foreign["head_repository"] = {"full_name": "foreign/repository"}
        api = FakeApi([pending, report], {10: owner(10, PREPARE_WORKFLOW), 20: foreign})
        with patch.object(api, "get_run", wraps=api.get_run) as owners:
            self.assertEqual([(pending, 55)], list_pending_candidates(api, repository=REPOSITORY, now=NOW))
        self.assertEqual({10, 20}, {call.args[0] for call in owners.call_args_list})

    def test_shared_targets_keep_independent_newest_capsules_for_one_source(self) -> None:
        capsules = [
            artifact(1, f"visual-review-input-55-{SHA}--mc1.20.1", run_id=10, minutes_ago=15),
            artifact(2, f"visual-review-input-55-{SHA}--mc1.21.8", run_id=10, minutes_ago=10),
            artifact(3, f"visual-review-input-55-{SHA}--mc1.20.1", run_id=11, minutes_ago=5),
        ]
        api = FakeApi(capsules, {10: owner(10, PREPARE_WORKFLOW), 11: owner(11, PREPARE_WORKFLOW)})
        pending = list_pending_candidates(api, repository=REPOSITORY, now=NOW)
        self.assertEqual({2, 3}, {item.artifact_id for item, _source in pending})
        self.assertEqual({55}, {source for _item, source in pending})
        self.assertEqual({"55--mc1.20.1", "55--mc1.21.8"}, {input_review_key(item) for item, _ in pending})
        self.assertEqual({"mc1.20.1", "mc1.21.8"}, {input_target(item) for item, _ in pending})

    def test_shared_target_report_does_not_complete_siblings_or_legacy_entry(self) -> None:
        capsules = [
            artifact(1, f"visual-review-input-55-{SHA}--mc1.20.1", run_id=10, minutes_ago=15),
            artifact(2, f"visual-review-input-55-{SHA}--mc1.21.8", run_id=10, minutes_ago=15),
            artifact(3, f"visual-review-input-55-{SHA}", run_id=10, minutes_ago=15),
        ]
        for name, expected in (("visual-review-55--mc1.20.1", {2, 3}),
                               ("visual-review-55--mc1.21.8", {1, 3}),
                               ("visual-review-55", {1, 2})):
            with self.subTest(marker=name):
                api = FakeApi(capsules + [artifact(4, name, run_id=20, minutes_ago=2)],
                              {10: owner(10, PREPARE_WORKFLOW), 20: owner(20, DRAIN_WORKFLOW)})
                pending = list_pending_candidates(api, repository=REPOSITORY, now=NOW)
                self.assertEqual(expected, {item.artifact_id for item, _ in pending})
                for item in capsules:
                    selected = select_requested(api, repository=REPOSITORY,
                                                requested_artifact_id=item.artifact_id, now=NOW)
                    self.assertEqual(item.artifact_id in expected, selected is not None)

    def test_shared_target_cooldown_and_direct_queries_are_scoped_to_that_target(self) -> None:
        capsules = [
            artifact(1, f"visual-review-input-55-{SHA}--mc1.20.1", run_id=10, minutes_ago=15),
            artifact(2, f"visual-review-input-55-{SHA}--mc1.21.8", run_id=10, minutes_ago=15),
            artifact(3, "visual-review-attempt-55--mc1.20.1", run_id=20, minutes_ago=2),
        ]
        api = FakeApi(capsules, {10: owner(10, PREPARE_WORKFLOW),
                                20: owner(20, DRAIN_WORKFLOW, conclusion="failure")})
        self.assertEqual({2}, {item.artifact_id for item, _ in
                               list_pending_candidates(api, repository=REPOSITORY, now=NOW)})
        with patch.object(api, "list_generation_inputs", side_effect=AssertionError("direct wake scanned the queue")), \
             patch.object(api, "list_artifacts_named", wraps=api.list_artifacts_named) as named:
            self.assertIsNotNone(select_requested(api, repository=REPOSITORY, requested_artifact_id=2, now=NOW))
        self.assertEqual({"visual-review-55--mc1.21.8", "visual-review-attempt-55--mc1.21.8",
                          f"visual-review-wave-block-{SHA}", f"visual-anchor-certification-{SHA}"},
                         {call.args[0] for call in named.call_args_list})
        self.assertEqual({1, 2}, {item.artifact_id for item, _ in list_pending_candidates(
            api, repository=REPOSITORY, now=NOW + timedelta(minutes=31))})

    def test_shared_target_names_require_a_canonical_key_and_exact_generation(self) -> None:
        invalid = ("mc1.020.1", "mc1.21.8/other", "mc1.21.8--extra", "mc1", "master", "../mc1.20.1")
        for key in invalid:
            with self.subTest(key=key):
                item = artifact(1, f"visual-review-input-55-{SHA}--{key}", run_id=10, minutes_ago=15)
                api = FakeApi([item], {10: owner(10, PREPARE_WORKFLOW)})
                self.assertEqual([], list_pending_candidates(api, repository=REPOSITORY, now=NOW))
                self.assertIsNone(select_requested(api, repository=REPOSITORY, requested_artifact_id=1, now=NOW))
        item = artifact(1, f"visual-review-input-55-{SHA}--mc1.20.1", run_id=10, minutes_ago=15)
        api = FakeApi([item], {10: owner(10, PREPARE_WORKFLOW)}, branch_sha="b" * 40)
        self.assertEqual([], list_pending_candidates(api, repository=REPOSITORY, now=NOW))

    def test_shared_target_report_requires_the_protected_owner(self) -> None:
        item = artifact(1, f"visual-review-input-55-{SHA}--mc1.20.1", run_id=10, minutes_ago=15)
        report = artifact(2, "visual-review-55--mc1.20.1", run_id=20, minutes_ago=2)
        untrusted = owner(20, DRAIN_WORKFLOW)
        untrusted["head_repository"] = {"full_name": "someone/else"}
        api = FakeApi([item, report], {10: owner(10, PREPARE_WORKFLOW), 20: untrusted})
        self.assertEqual([(item, 55)], list_pending_candidates(api, repository=REPOSITORY, now=NOW))

    def test_recovery_sweep_lists_curator_runs_then_their_own_artifacts(self) -> None:
        api = GitHubApi(
            repository=REPOSITORY,
            token="test-token",
            api_url="https://api.github.test",
        )
        runs = [
            {"id": 31, "head_sha": SHA, "status": "completed", "conclusion": "success"},
            {"id": 32, "head_sha": SHA, "status": "completed", "conclusion": "failure"},
            {"id": 33, "head_sha": SHA, "status": "completed", "conclusion": "skipped"},
            {"id": 34, "head_sha": SHA, "status": "completed", "conclusion": "cancelled"},
            {"id": 35, "head_sha": SHA, "status": "in_progress", "conclusion": None},
            {"id": 36, "head_sha": "c" * 40, "status": "completed", "conclusion": "success"},
        ]
        responses = {
            f"/repos/{REPOSITORY}/actions/workflows/visual-review.yml/runs?head_sha={SHA}&per_page=100&page=1":
                {"total_count": len(runs), "workflow_runs": runs},
            f"/repos/{REPOSITORY}/actions/runs/31/artifacts?per_page=100&page=1":
                {"artifacts": [raw_artifact(1, f"visual-review-input-55-{SHA}--mc1.20.1", expired=False),
                               raw_artifact(2, "visual-review-55--mc1.20.1", expired=False),
                               {"name": "packaged-e2e-unrelated"}]},
            f"/repos/{REPOSITORY}/actions/runs/32/artifacts?per_page=100&page=1":
                {"artifacts": [raw_artifact(3, f"visual-review-input-56-{SHA}", expired=True)]},
        }
        api._request = MagicMock(side_effect=lambda path: responses[path])  # type: ignore[method-assign]

        listed = api.list_generation_inputs(SHA)

        self.assertEqual([1, 3], [item.artifact_id for item in listed])
        requested = [call.args[0] for call in api._request.call_args_list]
        self.assertEqual(list(responses), requested)
        self.assertFalse(any("/actions/artifacts?" in path for path in requested))

    def test_recovery_sweep_listing_fails_closed_at_its_page_bounds(self) -> None:
        api = GitHubApi(
            repository=REPOSITORY,
            token="test-token",
            api_url="https://api.github.test",
        )
        full_runs = {"workflow_runs": [
            {"id": 40 + index, "head_sha": SHA, "status": "completed", "conclusion": "skipped"}
            for index in range(100)]}
        api._request = MagicMock(return_value=full_runs)  # type: ignore[method-assign]
        with self.assertRaises(QueueError) as caught:
            api.list_generation_inputs(SHA)
        self.assertIn("producer runs", str(caught.exception))
        self.assertEqual(MAX_PRODUCER_RUN_PAGES, api._request.call_count)

        def full_artifacts(path: str) -> dict[str, Any]:
            if "/workflows/" in path:
                return {"workflow_runs": [
                    {"id": 77, "head_sha": SHA, "status": "completed", "conclusion": "success"}]}
            return {"artifacts": [{"name": "packaged-e2e-unrelated"} for _ in range(100)]}

        api._request = MagicMock(side_effect=full_artifacts)  # type: ignore[method-assign]
        with self.assertRaises(QueueError) as caught:
            api.list_generation_inputs(SHA)
        self.assertIn("run artifacts", str(caught.exception))
        self.assertEqual(1 + MAX_RUN_ARTIFACT_PAGES, api._request.call_count)

        for bad in ({}, {"workflow_runs": {}}, {"workflow_runs": ["x"]},
                    {"workflow_runs": [{"id": 1}] * 101}):
            with self.subTest(response=bad):
                api._request = MagicMock(return_value=bad)  # type: ignore[method-assign]
                with self.assertRaises(QueueError):
                    api.list_generation_inputs(SHA)
        with self.assertRaises(QueueError):
            api.list_generation_inputs("master")

    def test_named_listing_returns_live_records_and_ignores_expired_history(self) -> None:
        # GitHub keeps expired records in the listing forever, so mod-compatibility-plan reached
        # 1,031 records (842 expired) and every scheduled compatibility recovery failed closed.
        api = GitHubApi(
            repository=REPOSITORY,
            token="test-token",
            api_url="https://api.github.test",
        )
        pages = [
            {"artifacts": [raw_artifact(index, "plan", expired=index > 2)
                           for index in range(1, 101)]},
            {"artifacts": [raw_artifact(101, "plan", expired=True),
                           raw_artifact(102, "plan", expired=False),
                           raw_artifact(103, "other", expired=False)]},
        ]
        api._request = MagicMock(side_effect=pages)  # type: ignore[method-assign]

        named = api.list_artifacts_named("plan")

        self.assertEqual([1, 2, 102], [item.artifact_id for item in named])
        self.assertTrue(all(not item.expired for item in named))
        self.assertEqual(2, api._request.call_count)

    def test_named_listing_still_fails_closed_on_too_many_live_records(self) -> None:
        api = GitHubApi(
            repository=REPOSITORY,
            token="test-token",
            api_url="https://api.github.test",
        )
        api._request = MagicMock(  # type: ignore[method-assign]
            return_value={
                "artifacts": [
                    raw_artifact(index, "plan", expired=False)
                    for index in range(1, 101)
                ]
            }
        )

        with self.assertRaises(QueueError) as caught:
            api.list_artifacts_named("plan")

        self.assertIn(str(MAX_NAMED_ARTIFACTS), str(caught.exception))
        self.assertEqual(MAX_NAMED_ARTIFACTS // 100 + 1, api._request.call_count)

    def test_named_listing_fails_closed_when_the_page_bound_is_exhausted(self) -> None:
        api = GitHubApi(
            repository=REPOSITORY,
            token="test-token",
            api_url="https://api.github.test",
        )
        api._request = MagicMock(  # type: ignore[method-assign]
            return_value={
                "artifacts": [
                    raw_artifact(index, "plan", expired=True)
                    for index in range(1, 101)
                ]
            }
        )

        with self.assertRaises(QueueError) as caught:
            api.list_artifacts_named("plan")

        self.assertIn("listing pages", str(caught.exception))
        self.assertEqual(MAX_NAMED_PAGES, api._request.call_count)

    def test_installation_rate_limit_is_classified_as_retryable(self) -> None:
        error = urllib.error.HTTPError(
            "https://api.github.test",
            403,
            "forbidden",
            {"X-RateLimit-Remaining": "0"},
            BytesIO(b'{"message":"API rate limit exceeded for installation"}'),
        )

        self.assertTrue(GitHubApi._retryable_http_error(error))

    def test_installation_rate_limit_has_a_longer_bounded_retry_window(self) -> None:
        api = GitHubApi(
            repository=REPOSITORY,
            token="token",
            api_url="https://api.github.test",
        )
        response = MagicMock()
        response.__enter__.return_value = response
        response.read.return_value = b'{"ok":true}'

        def rate_limit() -> urllib.error.HTTPError:
            return urllib.error.HTTPError(
                "https://api.github.test/repos/example",
                403,
                "forbidden",
                {
                    "X-RateLimit-Remaining": "0",
                    "X-RateLimit-Reset": "1800000060",
                },
                BytesIO(b'{"message":"API rate limit exceeded for installation"}'),
            )

        with patch(
            "visual_review_queue.urllib.request.urlopen",
            side_effect=[*(rate_limit() for _ in range(3)), response],
        ), patch("visual_review_queue.time.sleep") as sleep, patch(
            "visual_review_queue.time.time", return_value=1_800_000_000
        ):
            self.assertEqual(api._request("/repos/example"), {"ok": True})

        self.assertEqual(sleep.call_count, 3)
        self.assertTrue(all(60 <= call.args[0] <= 63 for call in sleep.call_args_list))

    def test_exhausted_installation_rate_limit_is_distinct_from_bad_evidence(
        self,
    ) -> None:
        api = GitHubApi(
            repository=REPOSITORY,
            token="token",
            api_url="https://api.github.test",
        )

        def rate_limit() -> urllib.error.HTTPError:
            return urllib.error.HTTPError(
                "https://api.github.test/repos/example",
                403,
                "forbidden",
                {"X-RateLimit-Remaining": "0"},
                BytesIO(b'{"message":"API rate limit exceeded for installation"}'),
            )

        with patch(
            "visual_review_queue.urllib.request.urlopen",
            side_effect=[rate_limit() for _ in range(12)],
        ), patch("visual_review_queue.time.sleep") as sleep:
            with self.assertRaises(GitHubRateLimitError):
                api._request("/repos/example")

        self.assertEqual(sleep.call_count, 3)

    def test_distant_primary_reset_defers_without_polling(self) -> None:
        api = GitHubApi(
            repository=REPOSITORY,
            token="token",
            api_url="https://api.github.test",
        )
        error = urllib.error.HTTPError(
            "https://api.github.test/repos/example",
            403,
            "forbidden",
            {
                "X-RateLimit-Remaining": "0",
                "X-RateLimit-Reset": "1800003600",
            },
            BytesIO(b'{"message":"API rate limit exceeded for installation"}'),
        )

        with patch(
            "visual_review_queue.urllib.request.urlopen", side_effect=error
        ), patch("visual_review_queue.time.sleep") as sleep, patch(
            "visual_review_queue.time.time", return_value=1_800_000_000
        ):
            with self.assertRaises(GitHubRateLimitError):
                api._request("/repos/example")

        sleep.assert_not_called()

    def test_exact_wake_queries_only_its_capsule_and_related_markers(self) -> None:
        requested = artifact(
            2, "visual-review-input-200", run_id=20, minutes_ago=10
        )
        unrelated = artifact(
            3, "visual-review-input-300", run_id=30, minutes_ago=90
        )
        api = FakeApi(
            [unrelated, requested],
            {
                20: owner(20, PREPARE_WORKFLOW),
                30: owner(30, PREPARE_WORKFLOW),
            },
        )

        self.assertEqual(
            (requested, 200),
            select_requested(
                api,
                repository=REPOSITORY,
                requested_artifact_id=requested.artifact_id,
            ),
        )

    def test_exact_wake_is_clean_after_capsule_cleanup(self) -> None:
        self.assertIsNone(
            select_requested(
                FakeApi([], {}),
                repository=REPOSITORY,
                requested_artifact_id=999,
            )
        )

    def test_certified_release_generation_never_reenters_semantic_review(self) -> None:
        requested = artifact(
            2,
            f"visual-review-input-200-{SHA}",
            run_id=20,
            minutes_ago=10,
        )
        certificate = artifact(
            3,
            f"visual-anchor-certification-{SHA}",
            run_id=30,
            minutes_ago=5,
        )
        runs = {
            20: owner(20, PREPARE_WORKFLOW),
            30: owner(30, DRAIN_WORKFLOW),
            200: {
                "status": "completed",
                "conclusion": "success",
                "event": "workflow_dispatch",
                "head_branch": "automation/sync/forge-and-fabric-1.20.1/200-1",
                "head_sha": "c" * 40,
            },
        }
        api = FakeApi([requested, certificate], runs)

        self.assertIsNone(select_pending(api, repository=REPOSITORY, now=NOW))
        self.assertIsNone(
            select_requested(
                api,
                repository=REPOSITORY,
                requested_artifact_id=requested.artifact_id,
                now=NOW,
            )
        )

    def test_superseded_release_anchor_and_closed_pull_request_are_skipped(self) -> None:
        old_generation = "c" * 40
        superseded = artifact(
            2,
            f"visual-review-input-200-{old_generation}",
            run_id=20,
            minutes_ago=20,
        )
        closed_pr = artifact(3, "visual-review-input-300", run_id=30, minutes_ago=10)
        runs = {
            20: owner(20, PREPARE_WORKFLOW),
            30: owner(30, PREPARE_WORKFLOW),
            200: {
                "status": "completed",
                "conclusion": "success",
                "event": "workflow_dispatch",
                "head_branch": "automation/sync/forge-and-fabric-1.20.1/200-1",
                "head_sha": "d" * 40,
            },
            300: {
                "status": "completed",
                "conclusion": "success",
                "event": "pull_request",
                "head_branch": "feature/closed",
                "head_sha": SHA,
                "pull_requests": [{"number": 42}],
            },
        }
        pulls = {
            42: {
                "state": "closed",
                "head": {
                    "sha": SHA,
                    "ref": "feature/closed",
                    "repo": {"full_name": REPOSITORY},
                },
            }
        }

        self.assertEqual(
            [],
            list_pending_candidates(
                FakeApi([superseded, closed_pr], runs, pulls=pulls),
                repository=REPOSITORY,
                now=NOW,
            ),
        )

    def test_superseded_non_anchor_generation_is_skipped_for_every_wake(self) -> None:
        old_generation = "c" * 40
        superseded = artifact(
            2,
            f"visual-review-input-200-{old_generation}",
            run_id=20,
            minutes_ago=20,
        )
        api = FakeApi(
            [superseded],
            {
                20: owner(20, PREPARE_WORKFLOW),
                200: {
                    "status": "completed",
                    "conclusion": "success",
                    "event": "workflow_dispatch",
                    "head_branch": "automation/sync/fabric-and-neoforge-1.21.7/200-1",
                    "head_sha": "d" * 40,
                },
            },
        )

        self.assertEqual(
            [],
            list_pending_candidates(api, repository=REPOSITORY, now=NOW),
        )
        self.assertIsNone(
            select_requested(
                api,
                repository=REPOSITORY,
                requested_artifact_id=superseded.artifact_id,
                now=NOW,
            )
        )

    def test_exact_wake_honors_report_cooldown_and_generation_block(self) -> None:
        requested = artifact(
            2, "visual-review-input-200", run_id=20, minutes_ago=10
        )
        report = artifact(3, "visual-review-200", run_id=30, minutes_ago=5)
        attempt = artifact(
            4, "visual-review-attempt-200", run_id=40, minutes_ago=5
        )
        block = artifact(
            5, f"visual-review-wave-block-{SHA}", run_id=50, minutes_ago=5
        )
        base_runs = {20: owner(20, PREPARE_WORKFLOW)}

        for marker, marker_owner in (
            (report, owner(30, DRAIN_WORKFLOW, conclusion="failure")),
            (
                attempt,
                owner(40, DRAIN_WORKFLOW, conclusion=None, status="in_progress"),
            ),
            (
                block,
                owner(50, DRAIN_WORKFLOW, conclusion=None, status="in_progress"),
            ),
        ):
            self.assertIsNone(
                select_requested(
                    FakeApi(
                        [requested, marker],
                        {**base_runs, marker.run_id: marker_owner},
                    ),
                    repository=REPOSITORY,
                    requested_artifact_id=requested.artifact_id,
                    now=NOW,
                )
            )

    def test_certifiable_anchor_preempts_an_older_advisory_review(self) -> None:
        advisory = artifact(1, "visual-review-input-100", run_id=10, minutes_ago=90)
        anchor = artifact(2, "visual-review-input-200", run_id=20, minutes_ago=10)
        runs = {
            10: owner(10, PREPARE_WORKFLOW),
            20: owner(20, PREPARE_WORKFLOW),
            100: {
                "status": "completed",
                "conclusion": "success",
                "head_branch": "feature/example",
            },
            200: {
                "status": "completed",
                "conclusion": "success",
                "head_branch": (
                    "automation/sync/forge-and-fabric-1.20.1/123-1"
                ),
            },
        }

        self.assertEqual(
            (anchor, 200),
            select_pending(FakeApi([advisory, anchor], runs), repository=REPOSITORY),
        )

        self.assertEqual(
            [(anchor, 200), (advisory, 100)],
            list_pending_candidates(
                FakeApi([advisory, anchor], runs), repository=REPOSITORY
            ),
        )

    def test_pending_fanout_keeps_only_newest_input_for_each_source(self) -> None:
        older = artifact(1, "visual-review-input-100", run_id=10, minutes_ago=20)
        newer = artifact(2, "visual-review-input-100", run_id=11, minutes_ago=10)
        other = artifact(3, "visual-review-input-200", run_id=20, minutes_ago=15)
        api = FakeApi(
            [older, newer, other],
            {
                10: owner(10, PREPARE_WORKFLOW),
                11: owner(11, PREPARE_WORKFLOW),
                20: owner(20, PREPARE_WORKFLOW),
                100: {
                    "status": "completed",
                    "conclusion": "success",
                    "head_branch": "feature/one",
                },
                200: {
                    "status": "completed",
                    "conclusion": "success",
                    "head_branch": "feature/two",
                },
            },
        )

        self.assertEqual(
            [(other, 200), (newer, 100)],
            list_pending_candidates(api, repository=REPOSITORY, now=NOW),
        )

    def test_requested_artifact_selects_its_exact_authenticated_entry(self) -> None:
        oldest = artifact(1, "visual-review-input-100", run_id=10, minutes_ago=90)
        requested = artifact(
            2, "visual-review-input-200", run_id=20, minutes_ago=10
        )
        api = FakeApi(
            [oldest, requested],
            {
                10: owner(10, PREPARE_WORKFLOW),
                20: owner(20, PREPARE_WORKFLOW),
            },
        )

        self.assertEqual(
            (requested, 200),
            select_pending(
                api,
                repository=REPOSITORY,
                requested_artifact_id=requested.artifact_id,
            ),
        )

    def test_requested_artifact_must_still_be_eligible(self) -> None:
        pending = artifact(1, "visual-review-input-100", run_id=10, minutes_ago=90)
        report = artifact(2, "visual-review-100", run_id=20, minutes_ago=5)
        api = FakeApi(
            [pending, report],
            {
                10: owner(10, PREPARE_WORKFLOW),
                20: owner(20, DRAIN_WORKFLOW, conclusion="failure"),
            },
        )

        self.assertIsNone(
            select_pending(
                api,
                repository=REPOSITORY,
                requested_artifact_id=pending.artifact_id,
            )
        )
        self.assertIsNone(
            select_pending(
                FakeApi(
                    [pending],
                    {10: owner(10, PREPARE_WORKFLOW)},
                ),
                repository=REPOSITORY,
                requested_artifact_id=999,
            )
        )

    def test_confirmed_defect_marker_stops_only_its_exact_generation(self) -> None:
        blocked_input = artifact(
            1, "visual-review-input-100", run_id=10, minutes_ago=90
        )
        next_input = Artifact(
            **{
                **artifact(
                    2, "visual-review-input-200", run_id=20, minutes_ago=80
                ).__dict__,
                "head_sha": "c" * 40,
            }
        )
        block = artifact(
            3,
            f"visual-review-wave-block-{SHA}",
            run_id=30,
            minutes_ago=5,
        )
        runs = {
            10: owner(10, PREPARE_WORKFLOW),
            20: {**owner(20, PREPARE_WORKFLOW), "head_sha": "c" * 40},
            30: owner(
                30,
                DRAIN_WORKFLOW,
                conclusion=None,
                status="in_progress",
            ),
        }
        api = FakeApi(
            [blocked_input, next_input, block],
            runs,
            branch_sha="c" * 40,
        )

        self.assertEqual(
            {SHA},
            blocked_generations(api, api.artifacts, repository=REPOSITORY),
        )
        self.assertEqual(
            (next_input, 200),
            select_pending(api, repository=REPOSITORY, now=NOW),
        )
        self.assertIsNone(
            select_pending(
                api,
                repository=REPOSITORY,
                now=NOW,
                requested_artifact_id=blocked_input.artifact_id,
            )
        )

    def test_rejects_wave_block_from_another_owner_or_generation(self) -> None:
        pending = artifact(1, "visual-review-input-100", run_id=10, minutes_ago=90)
        wrong_owner = artifact(
            2,
            f"visual-review-wave-block-{SHA}",
            run_id=20,
            minutes_ago=5,
        )
        wrong_generation = artifact(
            3,
            f"visual-review-wave-block-{'c' * 40}",
            run_id=30,
            minutes_ago=4,
        )
        wrong_generation = Artifact(
            **{**wrong_generation.__dict__, "head_sha": "c" * 40}
        )
        api = FakeApi(
            [pending, wrong_owner, wrong_generation],
            {
                10: owner(10, PREPARE_WORKFLOW),
                20: owner(20, PREPARE_WORKFLOW),
                30: {
                    **owner(30, DRAIN_WORKFLOW, conclusion="failure"),
                    "head_sha": "c" * 40,
                },
            },
        )

        self.assertEqual(
            {"c" * 40},
            blocked_generations(api, api.artifacts, repository=REPOSITORY),
        )
        self.assertEqual((pending, 100), select_pending(api, repository=REPOSITORY))

    def test_generation_suffix_separates_generation_from_reviewer_sha(self) -> None:
        generation = "d" * 40
        blocked_input = artifact(
            1,
            f"visual-review-input-100-{generation}",
            run_id=10,
            minutes_ago=90,
        )
        block = artifact(
            2,
            f"visual-review-wave-block-{generation}",
            run_id=20,
            minutes_ago=5,
        )
        api = FakeApi(
            [blocked_input, block],
            {
                10: owner(10, PREPARE_WORKFLOW),
                20: owner(
                    20,
                    DRAIN_WORKFLOW,
                    conclusion=None,
                    status="in_progress",
                ),
            },
            branch_sha=generation,
        )

        self.assertEqual(
            {generation},
            blocked_generations(api, api.artifacts, repository=REPOSITORY),
        )
        self.assertIsNone(select_pending(api, repository=REPOSITORY))

    def test_selects_oldest_unreviewed_input_after_attempt_cooldown(self) -> None:
        reviewed = artifact(1, "visual-review-input-100", run_id=10, minutes_ago=90)
        cooling = artifact(2, "visual-review-input-200", run_id=20, minutes_ago=80)
        eligible = artifact(3, "visual-review-input-300", run_id=30, minutes_ago=70)
        report = artifact(4, "visual-review-100", run_id=40, minutes_ago=60)
        attempt = artifact(
            5,
            "visual-review-attempt-200",
            run_id=50,
            minutes_ago=5,
        )
        api = FakeApi(
            [eligible, attempt, report, cooling, reviewed],
            {
                10: owner(10, PREPARE_WORKFLOW),
                20: owner(20, PREPARE_WORKFLOW),
                30: owner(30, PREPARE_WORKFLOW),
                40: owner(40, DRAIN_WORKFLOW, conclusion="failure"),
                50: owner(
                    50,
                    DRAIN_WORKFLOW,
                    conclusion=None,
                    status="in_progress",
                ),
            },
        )

        selected = select_pending(api, repository=REPOSITORY, now=NOW)

        self.assertEqual((eligible, 300), selected)

    def test_expired_cooldown_releases_the_original_oldest_input(self) -> None:
        pending = artifact(1, "visual-review-input-200", run_id=20, minutes_ago=80)
        attempt = artifact(
            2,
            "visual-review-attempt-200",
            run_id=50,
            minutes_ago=31,
        )
        api = FakeApi(
            [pending, attempt],
            {
                20: owner(20, PREPARE_WORKFLOW),
                50: owner(50, DRAIN_WORKFLOW, conclusion="failure"),
            },
        )

        self.assertEqual(
            (pending, 200),
            select_pending(api, repository=REPOSITORY, now=NOW),
        )

    def test_rejects_wrong_workflow_owners_and_oversized_inputs(self) -> None:
        wrong = artifact(1, "visual-review-input-100", run_id=10, minutes_ago=90)
        oversized = artifact(
            2,
            "visual-review-input-200",
            run_id=20,
            minutes_ago=80,
            size=536_870_913,
        )
        api = FakeApi(
            [wrong, oversized],
            {
                10: owner(10, DRAIN_WORKFLOW),
                20: owner(20, PREPARE_WORKFLOW),
            },
        )

        self.assertIsNone(select_pending(api, repository=REPOSITORY, now=NOW))

    def test_accepts_a_curated_input_when_only_the_later_wake_failed(self) -> None:
        pending = artifact(1, "visual-review-input-100", run_id=10, minutes_ago=20)
        api = FakeApi(
            [pending],
            {10: owner(10, PREPARE_WORKFLOW, conclusion="failure")},
        )

        self.assertEqual(
            (pending, 100),
            select_pending(api, repository=REPOSITORY, now=NOW),
        )


class ExactWakeSettlingTest(unittest.TestCase):
    """An ineligible exact wake polls again only while its curator owner is still settling."""

    def test_only_an_unsettled_curator_owner_keeps_an_ineligible_wake_polling(self) -> None:
        capsule = artifact(1, f"visual-review-input-55-{SHA}--mc1.21.1", run_id=10, minutes_ago=1)
        report = artifact(2, "visual-review-55--mc1.21.1", run_id=20, minutes_ago=1)
        for status in ("requested", "waiting", "pending", "queued", "in_progress"):
            with self.subTest(status=status):
                api = FakeApi([capsule], {10: owner(10, PREPARE_WORKFLOW, status=status, conclusion=None)})
                self.assertIsNone(select_requested(api, repository=REPOSITORY, requested_artifact_id=1, now=NOW))
                self.assertTrue(requested_owner_settling(api, requested_artifact_id=1))
        api = FakeApi([capsule], {10: owner(10, PREPARE_WORKFLOW)})
        self.assertEqual((capsule, 55), select_requested(api, repository=REPOSITORY,
                                                         requested_artifact_id=1, now=NOW))
        # Terminal ineligibility never polls: reviewed, cancelled or foreign owners, missing or
        # expired capsules.
        terminal = {
            "reviewed": FakeApi([capsule, report], {10: owner(10, PREPARE_WORKFLOW),
                                                    20: owner(20, DRAIN_WORKFLOW)}),
            "cancelled": FakeApi([capsule], {10: owner(10, PREPARE_WORKFLOW, conclusion="cancelled")}),
            "foreign": FakeApi([capsule], {10: owner(10, DRAIN_WORKFLOW, status="in_progress",
                                                     conclusion=None)}),
            "missing": FakeApi([], {}),
            "expired": FakeApi([replace(capsule, expired=True)],
                               {10: owner(10, PREPARE_WORKFLOW, status="in_progress", conclusion=None)}),
        }
        for reason, api in terminal.items():
            with self.subTest(reason=reason):
                self.assertIsNone(select_requested(api, repository=REPOSITORY, requested_artifact_id=1, now=NOW))
                self.assertFalse(requested_owner_settling(api, requested_artifact_id=1))
        with self.assertRaises(QueueError):
            requested_owner_settling(FakeApi([], {}), requested_artifact_id=0)

    def run_main(self, api: FakeApi, *arguments: str) -> list[str]:
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "output"
            with patch("visual_review_queue.GitHubApi", return_value=api), \
                 patch.dict("os.environ", {"GH_TOKEN": "test-token"}):
                self.assertEqual(0, queue_main(["--repository", REPOSITORY,
                                                "--github-output", str(output), *arguments]))
            return output.read_text(encoding="utf-8").splitlines()

    def test_main_reports_owner_settling_only_for_ineligible_exact_wakes(self) -> None:
        capsule = artifact(1, f"visual-review-input-55-{SHA}--mc1.21.1", run_id=10, minutes_ago=1)
        settling = FakeApi([capsule], {10: owner(10, PREPARE_WORKFLOW, status="in_progress", conclusion=None)})
        self.assertEqual(["eligible=false", "owner_settling=true"],
                         self.run_main(settling, "--requested-artifact-id", "1"))
        report = artifact(2, "visual-review-55--mc1.21.1", run_id=20, minutes_ago=1)
        reviewed = FakeApi([capsule, report], {10: owner(10, PREPARE_WORKFLOW), 20: owner(20, DRAIN_WORKFLOW)})
        self.assertEqual(["eligible=false", "owner_settling=false"],
                         self.run_main(reviewed, "--requested-artifact-id", "1"))
        eligible = self.run_main(FakeApi([capsule], {10: owner(10, PREPARE_WORKFLOW)}),
                                 "--requested-artifact-id", "1")
        self.assertEqual("eligible=true", eligible[0])
        self.assertFalse(any(line.startswith("owner_settling=") for line in eligible))
        # A generic sweep never polls, so it reports no settling state.
        self.assertEqual(["eligible=false"], self.run_main(reviewed))

    def test_duplicate_wake_of_a_reviewed_capsule_costs_seven_requests_once(self) -> None:
        api = GitHubApi(repository=REPOSITORY, token="test-token", api_url="https://api.github.test")
        capsule_name = f"visual-review-input-55-{SHA}--mc1.21.1"
        capsule = raw_artifact(1, capsule_name, expired=False)
        report = {**raw_artifact(2, "visual-review-55--mc1.21.1", expired=False),
                  "workflow_run": {"id": 20, "head_branch": "master", "head_sha": SHA}}

        def named(name: str, artifacts: list[dict[str, Any]]) -> tuple[str, dict[str, Any]]:
            query = urllib.parse.urlencode({"name": name, "per_page": 100, "page": 1})
            return f"/repos/{REPOSITORY}/actions/artifacts?{query}", {"artifacts": artifacts}

        responses = dict([
            (f"/repos/{REPOSITORY}/actions/artifacts/1", capsule),
            named("visual-review-55--mc1.21.1", [report]),
            named("visual-review-attempt-55--mc1.21.1", []),
            named(f"visual-review-wave-block-{SHA}", []),
            named(f"visual-anchor-certification-{SHA}", []),
            (f"/repos/{REPOSITORY}/actions/runs/20", owner(20, DRAIN_WORKFLOW)),
            (f"/repos/{REPOSITORY}/actions/runs/10", owner(10, PREPARE_WORKFLOW)),
        ])
        api._request = MagicMock(side_effect=lambda path, **_: responses[path])  # type: ignore[method-assign]

        self.assertIsNone(select_requested(api, repository=REPOSITORY, requested_artifact_id=1, now=NOW))
        self.assertFalse(requested_owner_settling(api, requested_artifact_id=1))
        requested = [call.args[0] for call in api._request.call_args_list]
        # The capsule is read once; the settling check adds only its curator owner.
        self.assertEqual(list(responses), requested)


DRAIN = "visual-review-drain.yml"


def run_drain_step(job: str, step: str, helper: str, environment: dict[str, str]
                   ) -> tuple[subprocess.CompletedProcess[str], list[str], dict[str, str], str]:
    """Execute one checked-in drain step with only its GitHub and Python boundary faked."""

    script = step_script(DRAIN, job, step)
    with tempfile.TemporaryDirectory() as temporary:
        root = Path(temporary)
        fake = root / "scripts" / "ci" / "github_api_retry.sh"
        fake.parent.mkdir(parents=True)
        fake.write_text(helper, encoding="utf-8")
        runner = root / "runner"
        runner.mkdir()
        calls, output, summary = root / "calls", root / "output", root / "summary"
        result = subprocess.run(
            ["bash", "-c", "set -euo pipefail\n" + script], cwd=root, capture_output=True,
            text=True, timeout=15,
            env={"PATH": os.environ["PATH"], "RUNNER_TEMP": str(runner),
                 "GITHUB_OUTPUT": str(output), "GITHUB_STEP_SUMMARY": str(summary),
                 "GITHUB_REPOSITORY": REPOSITORY, "GITHUB_RUN_ID": "900",
                 "FIXTURE_CALLS": str(calls), **environment})
        values = (dict(line.split("=", 1) for line in output.read_text().splitlines())
                  if output.exists() else {})
        return (result, calls.read_text().splitlines() if calls.exists() else [], values,
                summary.read_text() if summary.exists() else "")


@unittest.skipUnless(shutil.which("jq") and shutil.which("bash"), "the drain shell uses Bash and jq")
class ExactWakePollingShellTest(unittest.TestCase):
    """The drain repeats an exact selection only while the selector reports a settling owner."""

    HELPER = r"""
github_api_background_admission() { return 0; }
github_api_retry() { return 96; }
python3() {
  [[ "$1" == scripts/ci/visual_review_queue.py ]] || return 97
  printf 'selector\n' >> "$FIXTURE_CALLS"
  local queue="$FIXTURE_QUEUE"
  if [[ -n "${FIXTURE_SETTLED_AFTER:-}" ]] &&
      (( $(grep -cx selector "$FIXTURE_CALLS") > FIXTURE_SETTLED_AFTER )); then
    queue="$FIXTURE_QUEUE_SETTLED"
  fi
  while (( $# )); do
    if [[ "$1" == --github-output ]]; then printf '%s' "$queue" >> "$2"; fi
    shift
  done
  return "${FIXTURE_SELECTOR_STATUS:-0}"
}
sleep() { :; }
"""
    NAME = f"visual-review-input-12-{SHA}--mc1.21.1"
    ELIGIBLE = f"eligible=true\nartifact_id=7\nartifact_name={NAME}\ngeneration_sha={SHA}\n"

    def select(self, **environment: str) -> tuple[subprocess.CompletedProcess[str], list[str], dict[str, str]]:
        result, calls, values, _summary = run_drain_step(
            "select", "Select the oldest authenticated non-cooling queue entry", self.HELPER,
            {"GITHUB_EVENT_NAME": "repository_dispatch", "REQUESTED_ARTIFACT_ID": "7",
             "REQUESTED_ARTIFACT_NAME": self.NAME, "REQUESTED_GENERATION_SHA": SHA,
             "REQUESTED_RESUME_QUEUE": "", **environment})
        return result, calls, values

    def test_terminally_ineligible_wake_selects_once(self) -> None:
        # 2026-10-15: duplicates of reviewed or blocked capsules selected twelve times each.
        result, calls, values = self.select(FIXTURE_QUEUE="eligible=false\nowner_settling=false\n")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("false", values["eligible"])
        self.assertEqual(["selector"], calls)

    def test_wake_polls_while_its_curator_owner_settles(self) -> None:
        result, calls, values = self.select(FIXTURE_QUEUE="eligible=false\nowner_settling=true\n",
                                            FIXTURE_SETTLED_AFTER="2", FIXTURE_QUEUE_SETTLED=self.ELIGIBLE)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("true", values["eligible"])
        self.assertEqual("7", values["artifact_id"])
        self.assertEqual(["selector"] * 3, calls)
        # An owner that never settles is polled at most twelve times; the capsule stays queued.
        result, calls, values = self.select(FIXTURE_QUEUE="eligible=false\nowner_settling=true\n")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("false", values["eligible"])
        self.assertEqual(["selector"] * 12, calls)

    def test_selector_errors_keep_their_bounded_retries_and_fail_visibly(self) -> None:
        result, calls, _values = self.select(FIXTURE_QUEUE="", FIXTURE_SELECTOR_STATUS="2")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(["selector"] * 12, calls)
        result, calls, values = self.select(FIXTURE_QUEUE="", FIXTURE_SELECTOR_STATUS="75")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("false", values["eligible"])
        self.assertEqual(["selector"], calls)


@unittest.skipUnless(shutil.which("jq") and shutil.which("bash"), "the drain shell uses Bash and jq")
class CapacityFanOutShellTest(unittest.TestCase):
    """A fresh probe fans the queue out only while no other drain run is active."""

    HELPER = r"""
github_api_retry() {
  if [[ "$1" == --method ]]; then
    [[ "$2" == POST && "$3" == "repos/$GITHUB_REPOSITORY/dispatches" && "$4" == --input ]] || return 96
    printf 'dispatch %s\n' "$(jq -r .client_payload.artifact_id "$5")" >> "$FIXTURE_CALLS"
    return 0
  fi
  [[ "$1" == "repos/$GITHUB_REPOSITORY/actions/workflows/visual-review-drain.yml/runs?per_page=50" &&
     "$2" == --jq && "$3" == *'.id != 900'* && "$3" == *'"queued"'* && "$3" == *'"in_progress"'* &&
     "$3" == *'"pending"'* && "$3" == *'"waiting"'* && "$3" == *'"requested"'* ]] || return 96
  printf 'active-runs\n' >> "$FIXTURE_CALLS"
  [[ "$FIXTURE_ACTIVE" != fail ]] || return 1
  printf '%s\n' "$FIXTURE_ACTIVE"
}
python3() {
  [[ "$1" == scripts/ci/visual_review_queue.py ]] || return 97
  printf 'selector\n' >> "$FIXTURE_CALLS"
  while (( $# )); do
    if [[ "$1" == --list-pending-json ]]; then printf '%s\n' "$FIXTURE_PENDING" > "$2"; fi
    shift
  done
}
"""
    PENDING = json.dumps([{"artifact_id": artifact_id,
                           "artifact_name": f"visual-review-input-12-{SHA}--mc1.21.{artifact_id}",
                           "generation_sha": SHA} for artifact_id in (7, 8, 9)])

    def fan_out(self, *, resume: str, active: str) -> tuple[subprocess.CompletedProcess[str], list[str], str]:
        result, calls, _values, summary = run_drain_step(
            "resume-capacity-queue", "Dispatch every authenticated pending capsule independently",
            self.HELPER, {"CURRENT_ARTIFACT_ID": "7", "RESUME_QUEUE": resume,
                          "FIXTURE_ACTIVE": active, "FIXTURE_PENDING": self.PENDING})
        return result, calls, summary

    def test_fresh_probe_fans_out_only_while_no_other_drain_is_active(self) -> None:
        for resume in ("false", ""):
            with self.subTest(resume=resume):
                result, calls, summary = self.fan_out(resume=resume, active="0")
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual(["active-runs", "selector", "dispatch 8", "dispatch 9"], calls)
                self.assertEqual("", summary)
                # 2026-10-15: the probing wake redispatched its 16 queued siblings.
                for active in ("16", "1", "fail"):
                    result, calls, summary = self.fan_out(resume=resume, active=active)
                    self.assertEqual(0, result.returncode, result.stderr)
                    self.assertEqual(["active-runs"], calls)
                    self.assertIn("Capacity fan-out skipped", summary)

    def test_sweep_resume_request_keeps_its_own_stranded_backlog_decision(self) -> None:
        result, calls, _summary = self.fan_out(resume="true", active="5")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(["selector", "dispatch 8", "dispatch 9"], calls)
        result, calls, _summary = self.fan_out(resume="$(touch pwned)", active="0")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual([], calls)

    def test_fan_out_reads_the_sweep_resume_request_without_credentials(self) -> None:
        resume = job_block(DRAIN, "resume-capacity-queue")
        self.assertIn("RESUME_QUEUE: ${{ needs.select.outputs.resume_queue }}", resume)
        self.assertIn("actions: read", resume)
        self.assertNotIn("CLAUDE_CODE_OAUTH_TOKEN", resume)


if __name__ == "__main__":
    unittest.main()
