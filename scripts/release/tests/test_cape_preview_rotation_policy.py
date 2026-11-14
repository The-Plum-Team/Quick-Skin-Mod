from __future__ import annotations

import re
import unittest
from pathlib import Path

from scripts.architecture.source_inventory import java_source


ROOT = Path(__file__).resolve().parents[3]
JAVA_COMMENT = re.compile(r"/\*.*?\*/|//[^\n]*", re.DOTALL)


class CapePreviewRotationPolicyTest(unittest.TestCase):
    """The cape menu and the cape editor show the player from behind, and nothing turns it."""

    @classmethod
    def setUpClass(cls) -> None:
        cls.widget = java_source("client/gui/widget/PlayerWidget.java", repository=ROOT).read_text(encoding="utf-8")

    def test_both_cape_screens_build_a_cape_preview_facing_away(self) -> None:
        for name in ("client/gui/screen/PlayerCapeMenuScreen.java", "client/gui/screen/CapeAdjustScreen.java"):
            with self.subTest(screen=name):
                source = JAVA_COMMENT.sub(
                    "", java_source(name, repository=ROOT).read_text(encoding="utf-8")
                )
                self.assertRegex(source, r"setContext\(\s*(?:[\w.]+\.)?WidgetContext\.CAPE_MENU\)")
                # Turned half a circle from the resting yaw of 20 degrees
                self.assertRegex(source, r"setRotationState\(20\.0f, 200\.0f\)|savedTargetRotation = 200\.0f")
                for turner in ("toggleRotation(", "setTargetYRotation(", "setRestingYRotation("):
                    self.assertFalse(turner in source, f"{name} calls {turner}")

    def test_every_drag_that_turns_the_model_asks_the_context_first(self) -> None:
        starts = [match.start() for match in re.finditer(r"isRotating = true;", self.widget)]
        self.assertEqual(2, len(starts))
        for start in starts:
            condition = self.widget[self.widget.rindex("else if (", 0, start) : start]
            self.assertIn("context.turnsWhenDragged()", condition)

    def test_a_cape_preview_is_the_one_context_that_does_not_turn(self) -> None:
        self.assertRegex(
            self.widget,
            r"public boolean turnsWhenDragged\(\) \{\s*return this != CAPE_MENU;\s*\}",
        )


if __name__ == "__main__":
    unittest.main()
