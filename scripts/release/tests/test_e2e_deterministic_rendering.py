from __future__ import annotations

import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
BACKGROUND = (
    ROOT
    / "common/src/main/java/com/quickskin/mod/client/gui/util/BackgroundRenderer.java"
)
PANORAMA = (
    ROOT
    / "common/src/main/java/com/quickskin/mod/client/gui/util/PanoramaTimeSync.java"
)
PLAYER = (
    ROOT
    / "common/src/main/java/com/quickskin/mod/client/rendering/PlayerModelRenderer.java"
)
OPTIONS = ROOT / "e2e/options.txt.template"
SERVER_PROPERTIES = ROOT / "e2e/server-template/server.properties"
WORLD_LOAD = (
    ROOT
    / "e2e/server-template/datapack/data/qs_e2e/functions/load.mcfunction"
)


class E2EDeterministicRenderingTest(unittest.TestCase):
    def test_star_scroll_is_frozen_only_for_e2e(self) -> None:
        source = BACKGROUND.read_text(encoding="utf-8")

        self.assertIn('Boolean.getBoolean("quickskin.e2e.enabled")', source)
        self.assertIn("? E2E_FIXED_GUI_TICK / 20.0", source)
        self.assertIn(": (tickCount + partialTick) / 20.0", source)
        self.assertIn("DETERMINISTIC_E2E_RENDER ? 0.0F : partialTick", source)

    def test_panorama_freezes_every_motion_field_only_for_e2e(self) -> None:
        source = PANORAMA.read_text(encoding="utf-8")

        self.assertIn('Boolean.getBoolean("quickskin.e2e.enabled")', source)
        self.assertIn("panoramaMotionFields", source)
        self.assertIn("field.setFloat(renderer, E2E_FIXED_PANORAMA_TIME)", source)
        self.assertIn("Util.getMillis() / 1000.0f", source)
        self.assertIn("panoramaSpeed:0.0", OPTIONS.read_text(encoding="utf-8"))

    def test_preview_pose_is_fixed_without_replacing_live_clocks(self) -> None:
        source = PLAYER.read_text(encoding="utf-8")

        self.assertIn('Boolean.getBoolean("quickskin.e2e.enabled")', source)
        self.assertIn("playerToRender.tickCount = E2E_FIXED_PREVIEW_TICK", source)
        self.assertEqual(
            2,
            len(
                re.findall(
                    r"if \(DETERMINISTIC_E2E_RENDER\) \{\s*"
                    r"playerToRender\.tickCount = originalTickCount;",
                    source,
                )
            ),
        )
        self.assertIn("? E2E_FIXED_ANIMATION_TIME_MS", source)
        self.assertIn(": System.currentTimeMillis()", source)
        self.assertIn("DETERMINISTIC_E2E_RENDER ? 1.0f : 0.15f", source)

    def test_disposable_world_uses_a_fixed_spawn(self) -> None:
        properties = SERVER_PROPERTIES.read_text(encoding="utf-8")
        load_function = WORLD_LOAD.read_text(encoding="utf-8")

        self.assertIn("level-seed=quickskin-e2e", properties)
        self.assertIn("gamerule spawnRadius 0", load_function)


if __name__ == "__main__":
    unittest.main()
