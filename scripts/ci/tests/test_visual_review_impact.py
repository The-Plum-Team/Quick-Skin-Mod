from __future__ import annotations

import sys
import unittest
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts" / "ci"))

from visual_review_impact import classify_paths, infrastructure_only  # noqa: E402


def pages(*files: dict[str, Any]) -> list[dict[str, Any]]:
    return list(files)


def changed(
    path: str,
    *,
    status: str = "modified",
    previous: str | None = None,
) -> dict[str, Any]:
    value: dict[str, Any] = {"filename": path, "status": status}
    if previous is not None:
        value["previous_filename"] = previous
    return value


class VisualReviewImpactTest(unittest.TestCase):
    def test_accepts_complete_infrastructure_only_inventory(self) -> None:
        inventory = pages(
            changed(".github/workflows/visual-review.yml"),
            changed(".github/workflows/visual-review-drain.yml"),
            changed(".github/workflows/pages.yml"),
            changed("scripts/ci/github_api_retry.sh", status="added"),
            changed("scripts/ci/e2e_impact.py"),
            changed("scripts/ci/visual_review_queue.py"),
            changed("scripts/pages/rotate_artifacts.py"),
            changed("scripts/pages/select_artifact.py"),
            changed("scripts/release/tests/test_pages_artifact_rotation.py"),
            changed("scripts/ci/visual_review_impact.py"),
            changed("scripts/ci/tests/test_visual_review_impact.py", status="added"),
            changed("docs/ai/PROJECT.md"),
        )
        self.assertTrue(infrastructure_only(inventory, changed_files=12))

    def test_runtime_or_unknown_path_requires_review(self) -> None:
        for path in (
            "common/src/main/java/com/quickskin/mod/QuickSkin.java",
            "e2e/visual_review_prompt.md",
            ".github/workflows/on-demand-e2e.yml",
            "README.md",
        ):
            with self.subTest(path=path):
                self.assertFalse(
                    infrastructure_only(pages(changed(path)), changed_files=1)
                )

    def test_release_policy_tests_are_nonvisual_in_both_scopes(self) -> None:
        path = "scripts/release/tests/test_cape_elytra_binding.py"
        for scope in ("post-anchor-port", "replicated-port", "source-pr"):
            with self.subTest(scope=scope):
                self.assertTrue(
                    infrastructure_only(
                        pages(changed(path)), changed_files=1, scope=scope
                    )
                )
                self.assertFalse(
                    classify_paths([path], scope=scope).review_required
                )

    def test_release_implementation_remains_visual_reviewable(self) -> None:
        path = "scripts/release/version_port.py"
        for scope in ("replicated-port", "source-pr"):
            with self.subTest(scope=scope):
                self.assertFalse(
                    infrastructure_only(
                        pages(changed(path)), changed_files=1, scope=scope
                    )
                )

    def test_rename_between_release_test_and_runtime_requires_review(self) -> None:
        self.assertFalse(
            infrastructure_only(
                pages(
                    changed(
                        "common/src/main/java/com/quickskin/mod/QuickSkin.java",
                        status="renamed",
                        previous="scripts/release/tests/test_quick_skin.py",
                    )
                ),
                changed_files=1,
            )
        )

    def test_source_pr_scope_separates_compatibility_from_visual_policy(self) -> None:
        compatibility = pages(
            changed(".github/workflows/mod-compatibility-e2e.yml"),
            changed(".github/workflows/mod-compatibility-review.yml"),
            changed("scripts/ci/mod_compatibility_review_queue.py"),
            changed("scripts/ci/tests/test_workflow_security.py"),
            changed("docs/ai/PROJECT.md"),
        )
        self.assertTrue(
            infrastructure_only(
                compatibility, changed_files=5, scope="source-pr"
            )
        )
        for path in (
            ".github/workflows/visual-review.yml",
            "scripts/ci/visual_review_impact.py",
            "e2e/visual_review_prompt.md",
        ):
            with self.subTest(path=path):
                self.assertFalse(
                    infrastructure_only(
                        pages(changed(path)), changed_files=1, scope="source-pr"
                    )
                )

    def test_replicated_port_can_carry_nonvisual_continuation_policy(self) -> None:
        paths = [
            ".github/workflows/handle-version-port-result.yml",
            ".github/workflows/mod-compatibility-review.yml",
            ".github/workflows/sync-version-branches.yml",
            ".github/workflows/visual-review.yml",
            "scripts/ci/visual_nonimpact_certification.py",
            "scripts/ci/visual_review_impact.py",
            "scripts/ci/tests/test_visual_review_impact.py",
            "docs/ai/WORKFLOW.md",
        ]
        classification = classify_paths(paths, scope="replicated-port")
        self.assertFalse(classification.review_required)
        self.assertEqual(sorted(paths), list(classification.paths))
        self.assertEqual("replicated-port", classification.manifest()["scope"])

    def test_post_anchor_port_does_not_repeat_visual_policy_review(self) -> None:
        paths = [
            ".github/claude/package-lock.json",
            ".github/workflows/visual-review.yml",
            "e2e/check_visual_review.py",
            "e2e/visual_review.py",
            "e2e/visual_review_cache.py",
            "e2e/visual_review_prompt.md",
            "e2e/visual_review_runner.py",
            "e2e/visual_review_semantic_prompt.md",
            "e2e/visual_review_semantic_verify_prompt.md",
            "e2e/visual_review_verify_prompt.md",
            "scripts/ci/claude_capacity_gate.py",
            "scripts/ci/claude_capacity_probe.py",
            "scripts/ci/tests/test_workflow_security.py",
            "docs/ai/PROJECT.md",
        ]
        classification = classify_paths(paths, scope="post-anchor-port")
        self.assertFalse(classification.review_required)
        self.assertEqual(sorted(paths), list(classification.paths))

        for path in (
            "common/src/main/java/com/quickskin/mod/QuickSkin.java",
            ".github/workflows/on-demand-e2e.yml",
            "e2e/scenario-contract.json",
            "e2e/full-validation-baseline.json",
        ):
            with self.subTest(path=path):
                self.assertTrue(
                    classify_paths([path], scope="post-anchor-port").review_required
                )

    def test_exact_path_classification_fails_closed(self) -> None:
        for paths in (
            [],
            ["common/src/main/java/com/quickskin/mod/QuickSkin.java"],
            ["docs/../common/Hidden.java"],
        ):
            with self.subTest(paths=paths):
                self.assertTrue(
                    classify_paths(paths, scope="replicated-port").review_required
                )

    def test_rename_requires_both_paths_to_be_safe(self) -> None:
        self.assertTrue(
            infrastructure_only(
                pages(
                    changed(
                        "docs/ai/NEW.md",
                        status="renamed",
                        previous="docs/ai/OLD.md",
                    )
                ),
                changed_files=1,
            )
        )
        self.assertFalse(
            infrastructure_only(
                pages(
                    changed(
                        "docs/ai/OLD-CODE.md",
                        status="renamed",
                        previous="common/src/main/java/Removed.java",
                    )
                ),
                changed_files=1,
            )
        )
        self.assertFalse(
            infrastructure_only(
                pages(changed("docs/ai/MISSING.md", status="renamed")),
                changed_files=1,
            )
        )

    def test_incomplete_malformed_or_noncanonical_inventory_requires_review(self) -> None:
        cases: tuple[tuple[Any, int], ...] = (
            (None, 1),
            ([], 1),
            ({"files": []}, 1),
            ([{"missing": []}], 1),
            (pages(changed("docs/ai/ONE.md")), 2),
            (pages(changed("docs/../common/Hidden.java")), 1),
            (pages(changed("docs/ai/ONE.md", status="unknown")), 1),
            (
                pages(changed("docs/ai/ONE.md"), changed("docs/ai/ONE.md")),
                2,
            ),
        )
        for payload, count in cases:
            with self.subTest(payload=payload, count=count):
                self.assertFalse(
                    infrastructure_only(payload, changed_files=count)
                )

    def test_changed_file_bound_is_fail_open_to_review(self) -> None:
        inventory = pages(changed("docs/ai/PROJECT.md"))
        for count in (0, 101, True):
            with self.subTest(count=count):
                self.assertFalse(
                    infrastructure_only(inventory, changed_files=count)
                )
