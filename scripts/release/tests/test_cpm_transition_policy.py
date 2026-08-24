from __future__ import annotations

import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
MIXIN_ROOT = (
    ROOT / "common" / "src" / "main" / "java" / "com" / "quickskin" / "mod" / "mixin"
)


class CpmTransitionPolicyTest(unittest.TestCase):
    def test_every_renderer_facing_skin_override_defers_to_cpm(self) -> None:
        for name in (
            "MixinAbstractClientPlayer.java",
            "PlayerInfoMixin.java",
            "PlayerRendererMixin.java",
            "SkinManagerMixin.java",
        ):
            with self.subTest(source=name):
                source = (MIXIN_ROOT / name).read_text(encoding="utf-8")
                self.assertIn("CPMCompatIntegration.shouldDeferToCPM()", source)

    def test_cpm_mixins_are_registered_in_the_optional_config(self) -> None:
        config_path = (
            ROOT
            / "common"
            / "src"
            / "main"
            / "resources"
            / "quickskin-ears.mixins.json"
        )
        config = json.loads(config_path.read_text(encoding="utf-8"))
        self.assertIs(config["required"], False)
        self.assertEqual(config["injectors"]["defaultRequire"], 0)
        configured = {name.rsplit(".", 1)[-1] for name in config["client"]}
        self.assertTrue(
            {"CpmRenderDepthMixin", "CpmSubmitCollectorMixin"} <= configured
        )

    def test_optional_plugin_fails_closed_for_unknown_mixin_names(self) -> None:
        plugin = (
            MIXIN_ROOT / "compat" / "EarsMixinPlugin.java"
        ).read_text(encoding="utf-8")
        self.assertIn('List.of("CpmRenderDepthMixin")', plugin)
        self.assertIn('"CpmSubmitCollectorMixin"', plugin)
        self.assertIn("return false;", plugin)


if __name__ == "__main__":
    unittest.main()
