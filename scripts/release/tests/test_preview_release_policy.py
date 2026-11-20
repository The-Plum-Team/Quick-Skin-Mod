from __future__ import annotations

import re
import unittest
from pathlib import Path

from scripts.architecture.source_inventory import java_source


ROOT = Path(__file__).resolve().parents[3]
JAVA_COMMENT = re.compile(r"/\*.*?\*/|//[^\n]*", re.DOTALL)


def code(name: str) -> str:
    return JAVA_COMMENT.sub("", java_source(name, repository=ROOT).read_text(encoding="utf-8"))


class PreviewReleasePolicyTest(unittest.TestCase):
    """On 1.20.1 a mouse release that ends a preview's drag still reaches the screen."""

    def test_the_release_hook_never_keeps_the_release_from_the_screen(self) -> None:
        # The skin menu hides an armour piece when the mouse is let go on it (ADR 0017), and on
        # 1.20.1 the press on the model also starts the preview's turn. The hook that tells the
        # preview of the release must pass it on: Fabric keeps any interrupted release from the
        # screen, where Forge keeps only one interrupted as false, so an interrupt left the piece
        # on with Fabric 1.20.1 and the same build worked with Forge.
        source = code("event/ClientEvents.java")
        start = source.index("ClientScreenInputEvent.MOUSE_RELEASED_PRE.register(")
        end = source.index("ClientGuiEvent.RENDER_HUD.register(", start)
        hook = source[start:end]
        self.assertIn("activeWidget.mouseReleased(mouseX, mouseY, button);", hook)
        self.assertNotIn("interrupt", hook)
        self.assertIn("return EventResult.pass();", hook)


if __name__ == "__main__":
    unittest.main()
