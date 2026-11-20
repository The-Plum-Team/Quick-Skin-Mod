from __future__ import annotations

import re
import unittest
from pathlib import Path

from scripts.architecture.source_inventory import java_source


ROOT = Path(__file__).resolve().parents[3]
JAVA_COMMENT = re.compile(r"/\*.*?\*/|//[^\n]*", re.DOTALL)


def code(name: str) -> str:
    return JAVA_COMMENT.sub("", java_source(name, repository=ROOT).read_text(encoding="utf-8"))


class CpmModelCapePolicyTest(unittest.TestCase):
    """A CPM model chosen in the skin menu takes the cape off the menu's model too."""

    def test_choosing_a_cpm_model_takes_the_cape_off_the_menus_model(self) -> None:
        # The menu's model keeps the cape it was last handed. A skin hands it its own cape when it
        # is put on; a CPM model, which wears no Quick Skin cape, only cleared the saved cape, so
        # outside a world the model went on wearing the cape of the skin chosen before it until
        # the menu was opened again.
        source = code("client/gui/screen/PlayerSkinMenuScreen.java")
        start = source.index("private boolean applySelection()")
        model = source.index("if (metadata.isCpmModel())", start)
        skin = source.index("isSkinAlreadyActive", model)
        branch = source[model:skin]
        worn = branch.index("SkinCapes.wearNone(localWearTarget());")
        shown = branch.index('previewCape("");')
        self.assertLess(worn, shown)
        # Nothing may leave the branch between the two: the model would keep the cape
        self.assertNotIn("return", branch[worn:shown])

    def test_no_cape_on_the_menus_model_is_an_empty_cape_id(self) -> None:
        source = code("client/gui/screen/PlayerSkinMenuScreen.java")
        start = source.index("private void previewCape(String capeId)")
        end = source.index("getCapeLocationFromId(String capeId) {", start)
        preview = source[start:end]
        self.assertIn("capeId.isEmpty()", preview)
        self.assertIn("playerPreviewPanel.updateCape(null, null);", preview)


if __name__ == "__main__":
    unittest.main()
