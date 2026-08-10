from __future__ import annotations

import sys
import unittest
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts" / "ci"))

from visual_review_impact import infrastructure_only  # noqa: E402


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
            changed("scripts/ci/visual_review_impact.py"),
            changed("scripts/ci/tests/test_visual_review_impact.py", status="added"),
            changed("docs/ai/PROJECT.md"),
        )
        self.assertTrue(infrastructure_only(inventory, changed_files=5))

    def test_runtime_or_unknown_path_requires_review(self) -> None:
        for path in (
            "common/src/main/java/com/quickskin/mod/QuickSkin.java",
            "e2e/visual_review_prompt.md",
            ".github/workflows/on-demand-e2e.yml",
            "scripts/ci/visual_review_queue.py",
            "README.md",
        ):
            with self.subTest(path=path):
                self.assertFalse(
                    infrastructure_only(pages(changed(path)), changed_files=1)
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
