from __future__ import annotations

import re
import unittest
from pathlib import Path

from scripts.architecture.source_inventory import java_source


ROOT = Path(__file__).resolve().parents[3]
JAVA_COMMENT = re.compile(r"/\*.*?\*/|//[^\n]*", re.DOTALL)


def code(name: str) -> str:
    return JAVA_COMMENT.sub("", java_source(name, repository=ROOT).read_text(encoding="utf-8"))


class CpmPreviewKeepsAskingPolicyTest(unittest.TestCase):
    """Outside a world, a CPM model's icon gives way to the model whenever CPM has built it."""

    def test_the_preview_goes_on_asking_cpm_while_the_icon_shows(self) -> None:
        # CPM does not say whether it is still building a model or has refused it, so the menu
        # waits a few seconds and then shows the model's icon. Only the preview widget's draw
        # asks CPM for the model. While the widget was hidden with the icon in its place, nothing
        # asked again: a first model that took longer to build than the wait (a busy machine)
        # stayed an icon until the menu was closed and opened again.
        panel = code("client/gui/panel/PlayerPreviewPanel.java")
        start = panel.index("private void syncCpmModelPreview()")
        end = panel.index("public boolean isModelShown()", start)
        sync = panel[start:end]
        self.assertIn("playerWidget.visible = cpmModelFile != null;", sync)
        self.assertNotIn("playerWidget.visible = modelShown", sync)
        self.assertIn("modelShown = cpmModelFile != null && (drawsSincePick < 2 || playerWidget.isCpmModelDrawn()", sync)

    def test_a_model_just_picked_is_not_an_icon_before_it_was_asked_for(self) -> None:
        # Whether CPM has a model ready is only known once the widget has drawn. Until then the
        # panel shows no icon: a model CPM had ready flashed its icon for a frame otherwise.
        panel = code("client/gui/panel/PlayerPreviewPanel.java")
        pick = panel[panel.index("if (isCpmModel && !isInGame) {"):panel.index("syncCpmModelPreview();")]
        self.assertIn("drawsSincePick = 0;", pick)
        self.assertIn("drawsSincePick++;", panel)

    def test_the_icon_and_the_screen_go_by_what_shows_not_by_the_widget(self) -> None:
        # The widget is drawn under the icon too, so its visibility no longer says whether the
        # model shows: the icon, the name tag, the armour pick and the turn of the model ask the
        # panel instead.
        panel = code("client/gui/panel/PlayerPreviewPanel.java")
        self.assertIn("if (inWorld || cpmIconLocation == null || modelShown) {", panel)
        self.assertIn("return playerWidget != null && playerWidget.visible && modelShown;", panel)
        screen = code("client/gui/screen/PlayerSkinMenuScreen.java")
        self.assertNotIn("widget.visible", screen)
        self.assertEqual(4, screen.count("playerPreviewPanel.isModelShown()"))


if __name__ == "__main__":
    unittest.main()
