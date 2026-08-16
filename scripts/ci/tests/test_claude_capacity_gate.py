from __future__ import annotations

import sys
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts" / "ci"))

from claude_capacity_gate import (  # noqa: E402
    COMPATIBILITY_REVIEW_WORKFLOW,
    PAUSE_NAME,
    READY_NAME,
    resolve_capacity,
)
from visual_review_queue import DRAIN_WORKFLOW, Artifact  # noqa: E402


REPOSITORY = "The-Plum-Team/Quick-Skin-Mod"
SHA = "a" * 40
NOW = datetime(2026, 8, 14, 7, 0, tzinfo=timezone.utc)


def artifact(
    artifact_id: int,
    name: str,
    *,
    run_id: int,
    minutes_ago: int,
    head_sha: str = SHA,
    size: int = 128,
    expired: bool = False,
) -> Artifact:
    return Artifact(
        artifact_id=artifact_id,
        name=name,
        size_in_bytes=size,
        digest="sha256:" + "b" * 64,
        expired=expired,
        created_at=NOW - timedelta(minutes=minutes_ago),
        run_id=run_id,
        head_branch="master",
        head_sha=head_sha,
    )


def owner(
    run_id: int,
    *,
    workflow: str = DRAIN_WORKFLOW,
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
    def __init__(self, artifacts: list[Artifact], runs: dict[int, dict[str, Any]]):
        self.artifacts = artifacts
        self.runs = runs

    def list_artifacts_named(self, name: str) -> list[Artifact]:
        return [item for item in self.artifacts if item.name == name]

    def get_run(self, run_id: int) -> dict[str, Any]:
        return self.runs[run_id]


class ClaudeCapacityGateTest(unittest.TestCase):
    def test_missing_marker_requires_one_probe(self) -> None:
        state = resolve_capacity(
            FakeApi([], {}),
            repository=REPOSITORY,
            implementation_sha=SHA,
            now=NOW,
        )

        self.assertEqual("unknown", state.state)
        self.assertFalse(state.ready)
        self.assertTrue(state.probe_required)

    def test_newest_authenticated_marker_controls_the_circuit(self) -> None:
        ready = artifact(1, READY_NAME, run_id=10, minutes_ago=4)
        pause = artifact(2, PAUSE_NAME, run_id=20, minutes_ago=2)
        state = resolve_capacity(
            FakeApi([ready, pause], {10: owner(10), 20: owner(20)}),
            repository=REPOSITORY,
            implementation_sha=SHA,
            now=NOW,
        )

        self.assertEqual("paused", state.state)
        self.assertFalse(state.ready)
        self.assertFalse(state.probe_required)

    def test_later_successful_probe_closes_an_older_pause(self) -> None:
        pause = artifact(1, PAUSE_NAME, run_id=10, minutes_ago=4)
        ready = artifact(2, READY_NAME, run_id=20, minutes_ago=1)
        state = resolve_capacity(
            FakeApi([pause, ready], {10: owner(10), 20: owner(20)}),
            repository=REPOSITORY,
            implementation_sha=SHA,
            now=NOW,
        )

        self.assertTrue(state.ready)
        self.assertFalse(state.probe_required)

    def test_expired_ready_and_pause_markers_require_a_fresh_probe(self) -> None:
        ready = artifact(1, READY_NAME, run_id=10, minutes_ago=11)
        pause = artifact(2, PAUSE_NAME, run_id=20, minutes_ago=31)
        state = resolve_capacity(
            FakeApi([ready, pause], {10: owner(10), 20: owner(20)}),
            repository=REPOSITORY,
            implementation_sha=SHA,
            now=NOW,
        )

        self.assertEqual("unknown", state.state)
        self.assertTrue(state.probe_required)

    def test_untrusted_or_different_implementation_markers_are_ignored(self) -> None:
        wrong_workflow = artifact(1, PAUSE_NAME, run_id=10, minutes_ago=1)
        wrong_sha = artifact(
            2,
            READY_NAME,
            run_id=20,
            minutes_ago=1,
            head_sha="c" * 40,
        )
        state = resolve_capacity(
            FakeApi(
                [wrong_workflow, wrong_sha],
                {
                    10: owner(10, workflow=".github/workflows/visual-review.yml"),
                    20: owner(20, head_sha="c" * 40),
                },
            ),
            repository=REPOSITORY,
            implementation_sha=SHA,
            now=NOW,
        )

        self.assertTrue(state.probe_required)

    def test_in_progress_owner_is_valid_after_marker_upload(self) -> None:
        pause = artifact(1, PAUSE_NAME, run_id=10, minutes_ago=1)
        state = resolve_capacity(
            FakeApi(
                [pause],
                {10: owner(10, status="in_progress", conclusion=None)},
            ),
            repository=REPOSITORY,
            implementation_sha=SHA,
            now=NOW,
        )

        self.assertEqual("paused", state.state)

    def test_compatibility_review_can_close_the_shared_circuit(self) -> None:
        pause = artifact(1, PAUSE_NAME, run_id=10, minutes_ago=1)
        state = resolve_capacity(
            FakeApi(
                [pause],
                {10: owner(10, workflow=COMPATIBILITY_REVIEW_WORKFLOW)},
            ),
            repository=REPOSITORY,
            implementation_sha=SHA,
            now=NOW,
        )

        self.assertEqual("paused", state.state)
        self.assertFalse(state.probe_required)


if __name__ == "__main__":
    unittest.main()
