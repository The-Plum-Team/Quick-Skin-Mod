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
    _latest_attempt_artifact,
    _select_lane_completion,
    _select_review_artifact,
    RemoteArtifact,
)
from compatibility_evidence import (  # noqa: E402
    CompatibilityEvidenceError,
    _verdict_is_clean,
    build_bundle,
    validate_bundle,
)
import json  # noqa: E402
import mod_base_fixtures  # noqa: E402


REPOSITORY = "AkaNebur/Quick-Skin-Mod"
CURRENT_SHA = "c" * 40
SOURCE_SHA = "a" * 40


def artifact(
    artifact_id: int,
    run_id: int,
    created_at: str,
    *,
    name: str = "mod-compatibility-review-complete-123",
) -> RemoteArtifact:
    return RemoteArtifact(
        artifact_id=artifact_id,
        name=name,
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
    def test_lane_completion_prefers_exact_capsule_markers_and_retains_historical_attempts(self) -> None:
        legacy_name = "mod-compatibility-lane-complete-123-fabric-ears"
        legacy = artifact(20, 11, "2026-08-22T19:17:27Z", name=legacy_name)
        exact = artifact(21, 12, "2026-08-22T19:16:27Z", name=f"{legacy_name}--100")
        cases = ((1, [legacy], legacy), (2, [legacy], legacy),
                 (1, [legacy, exact], exact), (2, [legacy, exact], exact))
        for attempt, markers, expected in cases:
            with self.subTest(attempt=attempt, markers=len(markers)):
                capsule = artifact(100, 123, "2026-08-22T19:14:27Z",
                    name=f"mod-compatibility-review-input-123-fabric-ears-{attempt}")
                api = FakeReviewApi(markers, {11: run(11, SOURCE_SHA), 12: run(12, SOURCE_SHA)})
                arguments = dict(capsule=capsule, source_run_id=123, lane_id="fabric-ears",
                    repository=REPOSITORY, current_sha=CURRENT_SHA,
                    repository_root=ROOT, source_implementation_sha=SOURCE_SHA)
                with patch("collect_compatibility._fetch_commits"), patch(
                        "collect_compatibility._require_nonimpacting_ancestor"):
                    selected, _sha = _select_lane_completion(api, **arguments)
                    self.assertEqual(expected, selected)

    def test_publication_rejects_a_legacy_report_for_a_different_capsule(self) -> None:
        lane_id = "fabric-ears"
        files = (
            "capsule/review-input/visual-review-manifest.json", "capsule/curation-proof.json",
            "report/visual-review-report.json", "report/review-input/visual-review-manifest.json",
            "report/curation-proof.json", "report/visual-review-completion.json",
            "completion/mod-compatibility-lane-complete.json", "metadata.json",
        )
        for drift, error in (("report/curation-proof.json", "report proof drifted"),
                             ("report/review-input/visual-review-manifest.json", "report manifest drifted")):
            with self.subTest(drift=drift), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                plan = root / "plan.json"
                plan.write_text("{}")
                lane = root / "lanes" / lane_id
                for name in files:
                    path = lane / name
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_text("{}")
                (lane / drift).write_text('{"old_capsule":true}')
                # Admission is already independently authenticated; exercise the actual
                # publication boundary with the old normalized report and new capsule bytes.
                with patch("compatibility_evidence.validate_plan", return_value=(
                    {"bundle_key": "mc1.20.1"}, {lane_id: {"compatibility_mod": "ears"}}, []
                )), self.assertRaisesRegex(CompatibilityEvidenceError, error):
                    build_bundle(plan_path=plan, lanes_root=root / "lanes", output_root=root / "public",
                        repository=REPOSITORY, compatibility_run_id=123, implementation_sha=SOURCE_SHA,
                        publication_run_id=456, scenario_contract_path=ROOT / "e2e/scenario-contract.json",
                        compatibility_contract_path=ROOT / "e2e/mod-compatibility-contract.json")
                self.assertFalse((root / "public/mc1.20.1").exists())

    def test_latest_attempt_artifact_selects_the_newest_completed_lane_attempt(
        self,
    ) -> None:
        prefix = "mod-compatibility-review-input-123-fabric-lane-"
        first = artifact(1, 123, "2026-08-22T19:14:27Z", name=f"{prefix}1")
        second = artifact(2, 123, "2026-08-22T19:15:27Z", name=f"{prefix}2")
        future = artifact(3, 123, "2026-08-22T19:16:27Z", name=f"{prefix}3")

        selected = _latest_attempt_artifact(
            [first, future, second],
            name_prefix=prefix,
            run_id=123,
            maximum_attempt=2,
            maximum_size=1024,
        )

        self.assertEqual(second, selected)

    def test_latest_attempt_artifact_rejects_a_duplicate_attempt(self) -> None:
        prefix = "mod-compatibility-review-input-123-fabric-lane-"
        duplicate = [
            artifact(1, 123, "2026-08-22T19:14:27Z", name=f"{prefix}2"),
            artifact(2, 123, "2026-08-22T19:15:27Z", name=f"{prefix}2"),
        ]

        with self.assertRaisesRegex(CollectionError, "duplicate authenticated"):
            _latest_attempt_artifact(
                duplicate,
                name_prefix=prefix,
                run_id=123,
                maximum_attempt=2,
                maximum_size=1024,
            )

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



class NativeBundleSchemaTest(unittest.TestCase):
    """The shared (schema 6) native bundle mod-base's ``mod-compatibility`` family wraps."""

    @classmethod
    def setUpClass(cls) -> None:
        cls.temporary = tempfile.TemporaryDirectory()
        cls.root = Path(cls.temporary.name) / "compatibility"
        bundle = cls.root / "mc1.20.1"
        bundle.mkdir(parents=True)
        cls.manifest = mod_base_fixtures.compatibility_bundle(
            bundle, key="mc1.20.1", repository=REPOSITORY, coverage_sha=SOURCE_SHA, target_sha=SOURCE_SHA,
            publication_run={"event": "schedule", "created_at": "2026-09-08T20:10:00Z"}, publication_run_id=44,
            images=(mod_base_fixtures.fixture_png(0), mod_base_fixtures.fixture_png(1)))
        cls.path = bundle / "manifest.json"

    @classmethod
    def tearDownClass(cls) -> None:
        cls.temporary.cleanup()

    def validate(self, mutate: Any = None) -> dict[str, Any]:
        value = json.loads(json.dumps(self.manifest))
        if mutate is not None:
            mutate(value)
        self.path.write_text(json.dumps(value))
        try:
            return validate_bundle(self.root, "mc1.20.1", expected_repository=REPOSITORY,
                                   expected_coverage_sha=SOURCE_SHA)
        finally:
            self.path.write_text(json.dumps(self.manifest))

    def test_the_recorded_publication_run_is_optional_but_strict(self) -> None:
        self.assertEqual({"event": "schedule", "created_at": "2026-09-08T20:10:00Z"},
                         self.validate()["provenance"]["publication_run"])
        self.assertNotIn("publication_run",
                         self.validate(lambda value: value["provenance"].pop("publication_run"))["provenance"])
        for mutate in (lambda value: value["provenance"]["publication_run"].update(event="Push"),
                       lambda value: value["provenance"]["publication_run"].update(created_at="yesterday"),
                       lambda value: value["provenance"]["publication_run"].update(display_title=" "),
                       lambda value: value["provenance"]["publication_run"].update(run_id=44),
                       lambda value: value["provenance"].update(unknown=True)):
            with self.subTest(mutate=mutate), self.assertRaises(CompatibilityEvidenceError):
                self.validate(mutate)

    def test_retired_public_schemas_are_refused(self) -> None:
        for version in (1, 2, 3, 4, 7):
            with self.subTest(version=version), self.assertRaisesRegex(CompatibilityEvidenceError, "identity"):
                self.validate(lambda value: value.update(schema_version=version))


if __name__ == "__main__":
    unittest.main()
