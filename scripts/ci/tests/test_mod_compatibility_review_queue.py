from __future__ import annotations

import sys
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts" / "ci"))

from mod_compatibility_review_queue import (  # noqa: E402
    PLAN_NAME,
    REVIEW_WORKFLOW,
    SOURCE_WORKFLOW,
    list_pending,
)
from visual_review_queue import Artifact, QueueError  # noqa: E402


REPOSITORY = "The-Plum-Team/Quick-Skin-Mod"
SHA = "a" * 40
NOW = datetime(2026, 8, 16, 4, 0, tzinfo=timezone.utc)


def artifact(
    artifact_id: int,
    name: str,
    *,
    run_id: int,
    minutes_ago: int,
    head_sha: str = SHA,
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
        head_sha=head_sha,
    )


def owner(
    run_id: int,
    workflow: str,
    *,
    status: str = "completed",
    conclusion: str | None = "success",
    head_sha: str = SHA,
) -> dict[str, Any]:
    return {
        "id": run_id,
        "status": status,
        "conclusion": conclusion,
        "event": "repository_dispatch",
        "path": workflow,
        "head_branch": "master",
        "head_sha": head_sha,
        "head_repository": {"full_name": REPOSITORY},
    }


class FakeApi:
    def __init__(
        self,
        artifacts: list[Artifact],
        runs: dict[int, dict[str, Any]],
        *,
        master_sha: str = SHA,
    ) -> None:
        self.artifacts = artifacts
        self.runs = runs
        self.master_sha = master_sha

    def list_artifacts_named(self, name: str) -> list[Artifact]:
        return [item for item in self.artifacts if item.name == name]

    def get_run(self, run_id: int) -> dict[str, Any]:
        return self.runs[run_id]

    def get_branch_sha(self, branch: str) -> str:
        if branch != "master":
            raise AssertionError(f"unexpected branch lookup: {branch}")
        return self.master_sha


class ModCompatibilityReviewQueueTest(unittest.TestCase):
    def test_selects_oldest_authenticated_current_master_plan(self) -> None:
        newer = artifact(2, PLAN_NAME, run_id=20, minutes_ago=2)
        older = artifact(1, PLAN_NAME, run_id=10, minutes_ago=5)
        pending = list_pending(
            FakeApi(
                [newer, older],
                {
                    10: owner(10, SOURCE_WORKFLOW),
                    20: owner(20, SOURCE_WORKFLOW),
                },
            ),
            repository=REPOSITORY,
        )

        self.assertEqual([10, 20], [item.source_run_id for item in pending])

    def test_ignores_superseded_and_untrusted_plans(self) -> None:
        old = artifact(
            1,
            PLAN_NAME,
            run_id=10,
            minutes_ago=3,
            head_sha="c" * 40,
        )
        forged = artifact(2, PLAN_NAME, run_id=20, minutes_ago=2)
        pending = list_pending(
            FakeApi(
                [old, forged],
                {
                    10: owner(10, SOURCE_WORKFLOW, head_sha="c" * 40),
                    20: owner(20, ".github/workflows/build-gate.yml"),
                },
            ),
            repository=REPOSITORY,
        )

        self.assertEqual([], pending)

    def test_clean_completion_suppresses_the_source(self) -> None:
        plan = artifact(1, PLAN_NAME, run_id=10, minutes_ago=5)
        complete = artifact(
            2,
            "mod-compatibility-review-complete-10",
            run_id=20,
            minutes_ago=1,
        )
        pending = list_pending(
            FakeApi(
                [plan, complete],
                {
                    10: owner(10, SOURCE_WORKFLOW),
                    20: owner(20, REVIEW_WORKFLOW),
                },
            ),
            repository=REPOSITORY,
        )

        self.assertEqual([], pending)

    def test_fresh_completion_suppresses_recovery_before_owner_settles(self) -> None:
        plan = artifact(1, PLAN_NAME, run_id=10, minutes_ago=5)
        complete = artifact(
            2,
            "mod-compatibility-review-complete-10",
            run_id=20,
            minutes_ago=1,
        )
        pending = list_pending(
            FakeApi(
                [plan, complete],
                {
                    10: owner(10, SOURCE_WORKFLOW),
                    20: owner(
                        20,
                        REVIEW_WORKFLOW,
                        status="in_progress",
                        conclusion=None,
                    ),
                },
            ),
            repository=REPOSITORY,
        )

        self.assertEqual([], pending)

    def test_confirmed_block_suppresses_after_cancellation(self) -> None:
        plan = artifact(1, PLAN_NAME, run_id=10, minutes_ago=5)
        block = artifact(
            2,
            "mod-compatibility-wave-block-10",
            run_id=20,
            minutes_ago=1,
        )
        pending = list_pending(
            FakeApi(
                [plan, block],
                {
                    10: owner(10, SOURCE_WORKFLOW),
                    20: owner(
                        20,
                        REVIEW_WORKFLOW,
                        conclusion="cancelled",
                    ),
                },
            ),
            repository=REPOSITORY,
        )

        self.assertEqual([], pending)

    def test_forged_completion_marker_does_not_suppress_the_source(self) -> None:
        plan = artifact(1, PLAN_NAME, run_id=10, minutes_ago=5)
        forged = artifact(
            2,
            "mod-compatibility-review-complete-10",
            run_id=20,
            minutes_ago=1,
        )
        pending = list_pending(
            FakeApi(
                [plan, forged],
                {
                    10: owner(10, SOURCE_WORKFLOW),
                    20: owner(20, ".github/workflows/build-gate.yml"),
                },
            ),
            repository=REPOSITORY,
        )

        self.assertEqual([10], [item.source_run_id for item in pending])

    def test_duplicate_source_plans_fail_closed(self) -> None:
        plans = [
            artifact(1, PLAN_NAME, run_id=10, minutes_ago=5),
            artifact(2, PLAN_NAME, run_id=10, minutes_ago=4),
        ]

        with self.assertRaises(QueueError):
            list_pending(
                FakeApi(plans, {10: owner(10, SOURCE_WORKFLOW)}),
                repository=REPOSITORY,
            )


if __name__ == "__main__":
    unittest.main()
