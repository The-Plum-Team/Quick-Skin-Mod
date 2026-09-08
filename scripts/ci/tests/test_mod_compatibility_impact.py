from __future__ import annotations

import sys
import unittest
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts" / "ci"))

from mod_compatibility_impact import (  # noqa: E402
    ImpactError,
    classify_inventory,
    classify_paths,
)


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


class ModCompatibilityImpactTest(unittest.TestCase):
    def test_module_owned_sources_outside_the_compatibility_closure_carry_forward(self) -> None:
        hud = "modules/hud-preview/src/main/java/com/quickskin/mod/client/gui/overlay/HudPreviewIntegration.java"
        result = classify_paths([hud, "docs/ai/PROJECT.md"])
        self.assertFalse(result.compatibility_required)
        self.assertEqual([], list(result.impact_paths))
        self.assertEqual(["docs/ai/PROJECT.md", hud], list(result.paths))

    def test_module_owned_sources_inside_the_compatibility_closure_require_the_wave(self) -> None:
        for path in (
            "modules/client-textures/src/main/java/com/quickskin/mod/client/services/AnimatedTextureManager.java",
            "modules/networking/src/main/java/com/quickskin/mod/network/ClientNetworkHandler.java",
            "modules/cpm-integration/src/main/java/com/quickskin/mod/client/compat/CPMCompatIntegration.java",
            "modules/cape-menu/src/main/java/com/quickskin/mod/client/gui/screen/PlayerCapeMenuScreen.java",
        ):
            with self.subTest(path=path):
                result = classify_paths([path])
                self.assertTrue(result.compatibility_required)
                self.assertEqual([path], list(result.impact_paths))

    def test_assembly_harness_resource_and_unknown_paths_stay_fail_closed(self) -> None:
        for path in (
            "common/src/main/java/com/quickskin/mod/QuickSkin.java",
            "common/src/e2e/java/com/quickskin/mod/e2e/E2EHarness.java",
            "modules/hud-preview/src/main/resources/assets/quickskin/lang/en_us.json",
            "modules/hud-preview/src/test/java/com/quickskin/mod/HudTest.java",
            "modules/unknown-module/src/main/java/X.java",
            "fabric/src/main/java/com/quickskin/mod/fabric/QuickSkinFabric.java",
            "e2e/mod-compatibility-contract.json",
        ):
            with self.subTest(path=path):
                result = classify_paths([path])
                self.assertTrue(result.compatibility_required)
                self.assertEqual([path], list(result.impact_paths))

    def test_unprovable_coverage_requires_the_wave(self) -> None:
        hud = "modules/hud-preview/src/main/java/com/quickskin/mod/client/gui/overlay/HudPreviewIntegration.java"
        result = classify_paths([hud], graph=object())
        self.assertTrue(result.compatibility_required)
        self.assertEqual([hud], list(result.impact_paths))

    def test_visual_policy_and_docs_do_not_repeat_the_compatibility_wave(self) -> None:
        paths = [
            ".github/workflows/visual-review.yml",
            ".github/workflows/visual-review-drain.yml",
            ".github/workflows/mod-compatibility-review.yml",
            "e2e/mod_compatibility_review_prompt.md",
            "e2e/mod_compatibility_review_verify_prompt.md",
            "e2e/visual_review_prompt.md",
            "e2e/visual_review_runner.py",
            "scripts/ci/mod_compatibility_review_batch.py",
            "scripts/ci/mod_compatibility_impact.py",
            "scripts/ci/version_port_failure_policy.py",
            "scripts/ci/tests/test_mod_compatibility_impact.py",
            "docs/ai/PROJECT.md",
        ]
        result = classify_paths(paths)
        self.assertFalse(result.compatibility_required)
        self.assertEqual(sorted(paths), list(result.paths))
        self.assertEqual([], list(result.impact_paths))

    def test_product_build_runtime_and_compatibility_policy_require_wave(
        self,
    ) -> None:
        for path in (
            "common/src/main/java/com/quickskin/mod/QuickSkin.java",
            "build.gradle.kts",
            ".github/workflows/on-demand-e2e.yml",
            ".github/workflows/mod-compatibility-e2e.yml",
            "e2e/mod-compatibility-contract.json",
            "e2e/full-validation-baseline.json",
            "unknown/new-policy.txt",
        ):
            with self.subTest(path=path):
                result = classify_paths([path])
                self.assertTrue(result.compatibility_required)
                self.assertEqual((path,), result.impact_paths)

    def test_complete_inventory_records_renamed_from_path(self) -> None:
        result = classify_inventory(
            [
                changed(
                    "docs/ai/RENAMED.md",
                    status="renamed",
                    previous="common/src/main/java/Removed.java",
                )
            ],
            changed_files=1,
        )
        self.assertTrue(result.compatibility_required)
        self.assertIn("common/src/main/java/Removed.java", result.paths)

    def test_incomplete_or_malformed_inventory_fails_closed(self) -> None:
        cases: tuple[tuple[Any, int], ...] = (
            (None, 1),
            ([], 1),
            ([changed("docs/ai/ONE.md")], 2),
            ([changed("docs/ai/ONE.md", status="renamed")], 1),
            ([changed("docs/../common/Hidden.java")], 1),
            ([changed("docs/ai/ONE.md", status="unknown")], 1),
            (
                [changed("docs/ai/ONE.md"), changed("docs/ai/ONE.md")],
                2,
            ),
        )
        for payload, count in cases:
            with self.subTest(payload=payload, count=count):
                with self.assertRaises(ImpactError):
                    classify_inventory(payload, changed_files=count)


if __name__ == "__main__":
    unittest.main()
