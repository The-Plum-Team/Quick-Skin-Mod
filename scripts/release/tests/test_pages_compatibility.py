from __future__ import annotations

import io
import sys
import tempfile
import unittest
import urllib.request
from pathlib import Path
from types import SimpleNamespace
from typing import Any
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts" / "pages"))

from collect_compatibility import (  # noqa: E402
    CollectionError,
    GitHubClient,
    REVIEW_WORKFLOW,
    _CredentialStrippingRedirect,
    _select_review_artifact,
    RemoteArtifact,
)
from compatibility_evidence import _verdict_is_clean  # noqa: E402


REPOSITORY = "AkaNebur/Quick-Skin-Mod"
CURRENT_SHA = "c" * 40
SOURCE_SHA = "a" * 40


def artifact(artifact_id: int, run_id: int, created_at: str) -> RemoteArtifact:
    return RemoteArtifact(
        artifact_id=artifact_id,
        name="mod-compatibility-review-complete-123",
        size=100,
        digest=f"sha256:{artifact_id:064x}",
        expired=False,
        created_at=created_at,
        run_id=run_id,
        head_branch="master",
        head_sha=SOURCE_SHA,
    )


def run(run_id: int, sha: str, *, conclusion: str = "success") -> dict[str, Any]:
    return {
        "id": run_id,
        "status": "completed",
        "conclusion": conclusion,
        "event": "workflow_dispatch",
        "path": REVIEW_WORKFLOW,
        "head_branch": "master",
        "head_sha": sha,
        "head_repository": {"full_name": REPOSITORY},
    }


class FakeReviewApi:
    def __init__(
        self,
        artifacts: list[RemoteArtifact],
        runs: dict[int, dict[str, Any]],
    ) -> None:
        self.artifacts = artifacts
        self.runs = runs

    def list_named_artifacts(self, name: str) -> list[RemoteArtifact]:
        return [item for item in self.artifacts if item.name == name]

    def get_run(self, run_id: int) -> dict[str, Any]:
        return self.runs[run_id]


class PagesCompatibilityTest(unittest.TestCase):
    def test_clean_verdict_may_keep_a_non_defect_review_note(self) -> None:
        verdict = {
            "semantic_valid": True,
            "matches_reference": True,
            "defect": False,
            "anomalies": ["The paired reference shows an unrelated camera angle."],
        }

        self.assertTrue(_verdict_is_clean(verdict))

    def test_defect_verdict_cannot_be_published(self) -> None:
        verdict = {
            "semantic_valid": False,
            "matches_reference": False,
            "defect": True,
            "anomalies": ["The expected model is missing."],
        }

        self.assertFalse(_verdict_is_clean(verdict))

    def test_artifact_download_stops_at_the_authenticated_size(self) -> None:
        class Response(io.BytesIO):
            def __enter__(self) -> "Response":
                return self

            def __exit__(self, *_args: object) -> None:
                self.close()

        client = GitHubClient(
            repository=REPOSITORY,
            token="secret",
            api_url="https://api.github.test",
        )
        client.opener = SimpleNamespace(  # type: ignore[assignment]
            open=lambda *_args, **_kwargs: Response(b"12345")
        )
        with tempfile.TemporaryDirectory() as temporary:
            destination = Path(temporary) / "artifact.zip"
            with self.assertRaises(CollectionError):
                client._request(
                    "/actions/artifacts/1/zip",
                    destination=destination,
                    maximum_bytes=4,
                )
            self.assertFalse(destination.exists())

    def test_artifact_redirect_drops_github_token_only_across_hosts(self) -> None:
        handler = _CredentialStrippingRedirect()
        request = urllib.request.Request(
            "https://api.github.com/repos/example/actions/artifacts/1/zip",
            headers={
                "Authorization": "Bearer secret",
                "X-GitHub-Api-Version": "2022-11-28",
            },
        )

        external = handler.redirect_request(
            request,
            None,
            302,
            "Found",
            {},
            "https://artifactcache.example.test/archive.zip?sig=bound",
        )
        self.assertIsNotNone(external)
        assert external is not None
        self.assertIsNone(external.get_header("Authorization"))
        self.assertIsNone(external.get_header("X-github-api-version"))
        self.assertIn("sig=bound", external.full_url)

        same_host = handler.redirect_request(
            request,
            None,
            302,
            "Found",
            {},
            "https://api.github.com/final",
        )
        self.assertIsNotNone(same_host)
        assert same_host is not None
        self.assertEqual("Bearer secret", same_host.get_header("Authorization"))

    def test_review_artifact_selector_accepts_duplicate_recovery_markers(self) -> None:
        older = artifact(1, 11, "2026-08-22T19:14:27Z")
        newest = artifact(2, 12, "2026-08-22T19:15:01Z")
        foreign_newer = artifact(3, 13, "2026-08-22T19:16:01Z")
        api = FakeReviewApi(
            [older, newest, foreign_newer],
            {
                11: run(11, SOURCE_SHA),
                12: run(12, SOURCE_SHA),
                13: run(13, "b" * 40),
            },
        )

        with patch("collect_compatibility._fetch_commits"), patch(
            "collect_compatibility._require_nonimpacting_ancestor"
        ):
            selected, owner_sha = _select_review_artifact(
                api,  # type: ignore[arg-type]
                name=older.name,
                repository=REPOSITORY,
                current_sha=CURRENT_SHA,
                repository_root=ROOT,
                maximum_size=1024,
                required_owner_sha=SOURCE_SHA,
            )

        self.assertEqual(newest, selected)
        self.assertEqual(SOURCE_SHA, owner_sha)

    def test_review_artifact_selector_accepts_clean_artifact_from_failed_run(self) -> None:
        completed_before_post_success_failure = artifact(
            4, 14, "2026-08-22T19:17:01Z"
        )
        api = FakeReviewApi(
            [completed_before_post_success_failure],
            {14: run(14, SOURCE_SHA, conclusion="failure")},
        )

        with patch("collect_compatibility._fetch_commits"), patch(
            "collect_compatibility._require_nonimpacting_ancestor"
        ):
            selected, owner_sha = _select_review_artifact(
                api,  # type: ignore[arg-type]
                name=completed_before_post_success_failure.name,
                repository=REPOSITORY,
                current_sha=CURRENT_SHA,
                repository_root=ROOT,
                maximum_size=1024,
                required_owner_sha=SOURCE_SHA,
            )

        self.assertEqual(completed_before_post_success_failure, selected)
        self.assertEqual(SOURCE_SHA, owner_sha)


if __name__ == "__main__":
    unittest.main()
