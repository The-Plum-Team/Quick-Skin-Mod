from __future__ import annotations

import json
import re
import unittest
from pathlib import Path
from scripts.architecture.source_inventory import java_sources


ROOT = Path(__file__).resolve().parents[3]
MATRIX = json.loads((ROOT / "release" / "release-matrix.json").read_text(encoding="utf-8"))
RGB_LITERAL = re.compile(r"(?<![0-9A-Fa-f])0x[0-9A-Fa-f]{6}(?![0-9A-Fa-f])")
ALPHA_PREFIX = re.compile(r"<<\s*24\s*\|\s*$")
RGB_CALLS = ("GuiTextColor.opaqueRgb", ".withColor")


def strip_java_comments(source: str) -> str:
    source = re.sub(
        r"/\*.*?\*/",
        lambda match: "\n" * match.group(0).count("\n"),
        source,
        flags=re.DOTALL,
    )
    return re.sub(r"//[^\n]*", "", source)


def ambiguous_rgb_literals(source: str) -> list[tuple[int, str]]:
    """Return bare RGB literals whose GUI-rendering semantics are not explicit."""

    clean = strip_java_comments(source)
    failures: list[tuple[int, str]] = []
    statement_start = 0
    for match in re.finditer(r";|\Z", clean):
        statement = clean[statement_start : match.start()]
        for literal in RGB_LITERAL.finditer(statement):
            open_parentheses: list[int] = []
            for index, character in enumerate(statement[: literal.start()]):
                if character == "(":
                    open_parentheses.append(index)
                elif character == ")" and open_parentheses:
                    open_parentheses.pop()

            enclosed_by_rgb_call = any(
                statement[:open_index].rstrip().endswith(RGB_CALLS)
                for open_index in reversed(open_parentheses)
            )
            explicitly_composed_alpha = ALPHA_PREFIX.search(
                statement[: literal.start()]
            )
            if enclosed_by_rgb_call or explicitly_composed_alpha:
                continue
            offset = statement_start + literal.start()
            failures.append((clean.count("\n", 0, offset) + 1, literal.group(0)))
        statement_start = match.end()
    return failures


def active_gui_sources() -> tuple[Path, ...]:
    overlays = MATRIX["source_overlays"]
    modules = {"common", *overlays}
    modules.update(artifact["loader"] for artifact in MATRIX["artifacts"])

    source_sets = {"main", *overlays.get("common", {}).values()}
    sources = {path for source_set in source_sets
               for path in java_sources(source_set=source_set, repository=ROOT)
               if "/client/gui/" in path.as_posix()}
    for module in modules:
        java_roots = [ROOT / module / "src" / "main" / "java"]
        java_roots.extend(
            ROOT / module / "src" / overlay / "java"
            for overlay in overlays.get(module, {}).values()
        )
        for java_root in java_roots:
            if not java_root.is_dir():
                continue
            for path in java_root.rglob("*.java"):
                if "/client/gui/" in path.as_posix():
                    sources.add(path)
    return tuple(sorted(sources))


class GuiTextColorPolicyTest(unittest.TestCase):
    def test_active_gui_sources_have_no_ambiguous_rgb_literals(self) -> None:
        sources = active_gui_sources()
        self.assertTrue(sources, "no active client GUI sources were discovered")

        failures: list[str] = []
        for path in sources:
            for line, literal in ambiguous_rgb_literals(path.read_text(encoding="utf-8")):
                failures.append(f"{path.relative_to(ROOT)}:{line}: ambiguous {literal}")
        self.assertEqual([], failures)

    def test_rejects_bare_rgb_constants_and_draw_arguments(self) -> None:
        source = """
            private static final int TITLE_COLOR = 0xFFFFFF;
            graphics.drawString(font, title, x, y, 0x55AAFF);
        """

        self.assertEqual(
            [(2, "0xFFFFFF"), (3, "0x55AAFF")],
            ambiguous_rgb_literals(source),
        )

    def test_accepts_explicit_rgb_and_argb_contexts(self) -> None:
        source = """
            int text = GuiTextColor.opaqueRgb(0xFFFFFF);
            component.withStyle(style -> style.withColor(0x55AAFF));
            showImportMessage(message, GuiTextColor.opaqueRgb(0xFFAA00), 60);
            int faded = alpha << 24 | 0xFFFFFF;
            graphics.drawString(font, title, x, y, 0xFFFFFFFF);
            graphics.fill(x0, y0, x1, y1, 0x80FFFFFF);
        """

        self.assertEqual([], ambiguous_rgb_literals(source))

    def test_an_explicit_literal_does_not_hide_an_ambiguous_sibling(self) -> None:
        source = """
            int mixed = enabled
                ? GuiTextColor.opaqueRgb(0xFFFFFF)
                : 0x55AAFF;
            component.withStyle(style -> style.withColor(0xCCCCCC));
            graphics.drawString(font, title, x, y, 0xFFAA00);
        """

        self.assertEqual(
            [(4, "0x55AAFF"), (6, "0xFFAA00")],
            ambiguous_rgb_literals(source),
        )


if __name__ == "__main__":
    unittest.main()
