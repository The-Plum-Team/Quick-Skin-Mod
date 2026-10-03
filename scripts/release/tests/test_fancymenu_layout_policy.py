from __future__ import annotations

import re
import unittest
from pathlib import Path

from scripts.architecture.source_inventory import java_source


ROOT = Path(__file__).resolve().parents[3]
FANCYMENU = re.compile(r"fancymenu|keksuccino", re.IGNORECASE)
INIT_POST = "ClientGuiEvent.INIT_POST.register("
RENDER_PRE = "ClientGuiEvent.RENDER_PRE.register("
RENDER_POST = "ClientGuiEvent.RENDER_POST.register("
GENERATED_PARTS = {"build", "versions", ".gradle", "generated"}


def strip_java_comments(source: str) -> str:
    source = re.sub(r"/\*.*?\*/", lambda match: "\n" * match.group(0).count("\n"), source, flags=re.DOTALL)
    return re.sub(r"(?<!:)//(?![?]).*", "", source)


def block_after(source: str, marker: str) -> str:
    """Return the brace-delimited block that starts at the first '{' after ``marker``."""

    start = source.index(marker)
    opening = source.index("{", start)
    depth = 0
    for index in range(opening, len(source)):
        if source[index] == "{":
            depth += 1
        elif source[index] == "}":
            depth -= 1
            if depth == 0:
                return source[opening : index + 1]
    raise AssertionError(f"unbalanced block after {marker!r}")


def between(source: str, start_marker: str, end_marker: str) -> str:
    """Return the text from ``start_marker`` up to ``end_marker``.

    Event registrations are cut by position, not by braces: Stonecutter branches inside them
    contain both versions of a line and leave the braces unbalanced.
    """

    start = source.index(start_marker)
    return source[start : source.index(end_marker, start + len(start_marker))]


def authored_files(*patterns: str) -> list[Path]:
    files: list[Path] = []
    for pattern in patterns:
        for path in ROOT.glob(pattern):
            relative = path.relative_to(ROOT).parts
            if path.is_file() and not GENERATED_PARTS.intersection(relative):
                files.append(path)
    return files


class FancyMenuLayoutPolicyTest(unittest.TestCase):
    """FancyMenu support is reflection-only and must never change the preview without FancyMenu."""

    @classmethod
    def setUpClass(cls) -> None:
        cls.player_widget = strip_java_comments(
            java_source("client/gui/widget/PlayerWidget.java", repository=ROOT).read_text(encoding="utf-8")
        )
        cls.menu_integration = strip_java_comments(
            java_source("client/gui/integration/MenuIntegration.java", repository=ROOT).read_text(encoding="utf-8")
        )

    def test_fancymenu_is_never_a_build_or_mixin_dependency(self) -> None:
        build_inputs = authored_files(
            "*.gradle.kts",
            "*/build.gradle.kts",
            "modules/*/build.gradle.kts",
            "gradle/**/*",
            "architecture/modules.json",
            "release/release-matrix.json",
            "*/src/main/resources/*.mixins.json",
            "*/src/main/resources/**/*.toml",
            "*/src/main/resources/fabric.mod.json",
        )
        self.assertTrue(build_inputs)
        offenders = [str(path.relative_to(ROOT)) for path in build_inputs
                     if FANCYMENU.search(path.read_text(encoding="utf-8", errors="replace"))]
        self.assertEqual([], offenders)

        java_files = authored_files("common/src/**/*.java", "fabric/src/**/*.java", "forge/src/**/*.java",
                                    "neoforge/src/**/*.java", "modules/*/src/**/*.java")
        self.assertTrue(java_files)
        importing = [str(path.relative_to(ROOT)) for path in java_files
                     if re.search(r"^\s*import\s+(static\s+)?de\.keksuccino\.", path.read_text(encoding="utf-8"), re.M)]
        self.assertEqual([], importing)

    def test_preview_reports_zero_size_unless_layout_bounds_are_published(self) -> None:
        for getter, accessor in (("getWidth", "super.getWidth()"), ("getHeight", "super.getHeight()")):
            body = block_after(self.player_widget, f"public int {getter}()")
            self.assertRegex(
                body,
                r"return\s+reportsModelBounds\s*&&\s*layoutBoundsPublished\s*\?\s*"
                + re.escape(accessor) + r"\s*:\s*0\s*;",
            )
        publish = block_after(self.player_widget, "public void publishLayoutBounds()")
        self.assertIn("if (reportsModelBounds)", publish)

    def test_layout_size_is_published_after_init_and_only_with_fancymenu(self) -> None:
        init_post = between(self.menu_integration, INIT_POST, RENDER_PRE)
        render_pre = between(self.menu_integration, RENDER_PRE, RENDER_POST)

        # Overlap checks such as In-Game Account Switcher's run inside Screen.init; the real size
        # must only appear from the first rendered frame on.
        self.assertFalse("publishLayoutBounds" in init_post, "INIT_POST must not publish the layout size")
        self.assertTrue("publishLayoutBounds()" in render_pre, "RENDER_PRE must publish the layout size")
        self.assertEqual(1, self.menu_integration.count("publishLayoutBounds("))

        self.assertEqual(1, self.menu_integration.count("reportModelBoundsForLayout("))
        guarded = block_after(init_post, "if (FancyMenuWidgets.isPresent())")
        self.assertIn("reportModelBoundsForLayout(screen)", guarded)
        self.assertIn("FancyMenuWidgets.PREVIEW_ID", guarded)

    def test_hidden_preview_hides_its_own_controls_but_not_change_skin(self) -> None:
        apply = block_after(self.menu_integration, "private static void applyPreviewControlVisibility(")
        for control in ("rotateButton", "animationToggleButton", "animationButtons"):
            self.assertIn(control, apply)
        self.assertNotIn("changeSkinButton", apply)

        render_pre = between(self.menu_integration, RENDER_PRE, RENDER_POST)
        self.assertRegex(render_pre, r"if\s*\(\s*shown\s*!=\s*previewControlsShown\s*\)")
        dropdown = block_after(self.menu_integration, "private static void updateAnimationDropdownState()")
        self.assertIn("isAnimationDropdownOpen && previewControlsShown", dropdown)


if __name__ == "__main__":
    unittest.main()
