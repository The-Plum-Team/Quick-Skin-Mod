from __future__ import annotations

import sys
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts" / "ci"))

from visual_review_queue import (  # noqa: E402
    DRAIN_WORKFLOW,
    PREPARE_WORKFLOW,
    Artifact,
    select_pending,
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
    def __init__(self, artifacts: list[Artifact], runs: dict[int, dict[str, Any]]) -> None:
        self.artifacts = artifacts
        self.runs = runs

    def list_artifacts(self) -> list[Artifact]:
        return list(self.artifacts)

    def get_run(self, run_id: int) -> dict[str, Any]:
        return self.runs[run_id]


class VisualReviewQueueTest(unittest.TestCase):
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
