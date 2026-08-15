from __future__ import annotations

import sys
import unittest
import urllib.error
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
    PREPARE_WORKFLOW,
    Artifact,
    blocked_generations,
    list_pending_candidates,
    select_pending,
    select_requested,
)


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

    def list_artifacts(self) -> list[Artifact]:
        return list(self.artifacts)

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
        api = FakeApi([blocked_input, next_input, block], runs)

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


if __name__ == "__main__":
    unittest.main()
