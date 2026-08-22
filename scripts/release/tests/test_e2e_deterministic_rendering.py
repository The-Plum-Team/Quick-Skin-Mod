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
WORLD_TICK = (
    ROOT
    / "e2e/server-template/datapack/data/qs_e2e/functions/tick.mcfunction"
)
WORLD_TICK_TAG = (
    ROOT
    / "e2e/server-template/datapack/data/minecraft/tags/functions/tick.json"
)
DEFAULT_SKIN_VIEW = (
    ROOT
    / "common/src/e2e/java/com/quickskin/mod/e2e/DefaultSkinEvidenceView.java"
)
E2E_HARNESS = (
    ROOT
    / "common/src/e2e/java/com/quickskin/mod/e2e/E2EHarness.java"
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
        self.assertIn("team modify qs_e2e collisionRule never", load_function)
        self.assertIn(
            "team join qs_e2e @a[team=!qs_e2e]",
            WORLD_TICK.read_text(encoding="utf-8"),
        )
        self.assertIn('"qs_e2e:tick"', WORLD_TICK_TAG.read_text(encoding="utf-8"))

    def test_world_player_interpolation_is_pinned_by_the_e2e_harness(self) -> None:
        source = DEFAULT_SKIN_VIEW.read_text(encoding="utf-8")

        self.assertIn("player.tickCount = FIXED_RENDER_TICK", source)
        self.assertIn("player.walkAnimation.setSpeed(0.0F)", source)
        self.assertIn("player.xo = player.xOld = player.getX()", source)
        self.assertIn(
            "DefaultSkinEvidenceView.pinStandingMotion(mc.player)",
            E2E_HARNESS.read_text(encoding="utf-8"),
        )


if __name__ == "__main__":
    unittest.main()
