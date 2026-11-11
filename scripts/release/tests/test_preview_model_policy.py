from __future__ import annotations

import re
import unittest
from pathlib import Path

from scripts.architecture.source_inventory import java_source


ROOT = Path(__file__).resolve().parents[3]


def strip_java_comments(source: str) -> str:
    source = re.sub(r"/\*.*?\*/", lambda match: "\n" * match.group(0).count("\n"), source, flags=re.DOTALL)
    return re.sub(r"(?<!:)//(?![?]).*", "", source)


class PreviewModelPolicyTest(unittest.TestCase):
    """The preview's own posed models stay plain vanilla parts that no other mod animates.

    Entity Model Features wraps every root baked through the entity model set and drives it with
    the resource pack's animations on each draw, over the pose the preview set, and it replaces
    ``PlayerModel.renderCloak`` with the pack's cape model.
    """

    @classmethod
    def setUpClass(cls) -> None:
        cls.renderer = strip_java_comments(
            java_source("client/rendering/PlayerModelRenderer.java", repository=ROOT).read_text(encoding="utf-8")
        )

    def test_own_models_are_baked_from_vanilla_layer_definitions(self) -> None:
        self.assertNotIn("getEntityModels(", self.renderer)
        self.assertNotIn(".bakeLayer(", self.renderer)
        self.assertEqual(2, self.renderer.count("PlayerModel.createMesh(CubeDeformation.NONE, "))
        self.assertIn("ElytraModel.createLayer().bakeRoot()", self.renderer)
        self.assertIn("PlayerCapeModel.createCapeLayer().bakeRoot()", self.renderer)

    def test_own_cloak_is_not_drawn_through_render_cloak(self) -> None:
        self.assertNotIn("renderCloak(", self.renderer)
        self.assertIn('classicRoot.getChild("cloak")', self.renderer)
        self.assertIn('slimRoot.getChild("cloak")', self.renderer)


if __name__ == "__main__":
    unittest.main()
