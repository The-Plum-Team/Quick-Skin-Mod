from __future__ import annotations

import re
import tempfile
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
WORLD_DATA = ROOT / "e2e/server-template/datapack/data"
WORLD_GAMERULE_VARIANTS = (
    frozenset(
        {
            "gamerule doWeatherCycle false",
            "gamerule doDaylightCycle false",
            "gamerule spawnRadius 0",
        }
    ),
    frozenset(
        {
            "gamerule minecraft:advance_weather false",
            "gamerule minecraft:advance_time false",
            "gamerule minecraft:respawn_radius 0",
        }
    ),
)
DEFAULT_SKIN_VIEW = (
    ROOT
    / "common/src/e2e/java/com/quickskin/mod/e2e/DefaultSkinEvidenceView.java"
)
E2E_HARNESS = (
    ROOT
    / "common/src/e2e/java/com/quickskin/mod/e2e/E2EHarness.java"
)
FULL_SCENARIO = (
    ROOT
    / "common/src/e2e/java/com/quickskin/mod/e2e/scenario/FullScenario.java"
)
VANILLA_SHIM = ROOT / "common/src/e2e/java/com/quickskin/mod/e2e/VanillaShim.java"


def world_function_paths(data_root: Path) -> tuple[Path, Path, Path, Path]:
    """Resolve the one coherent function layout allowed by the target pack format."""

    layouts = []
    for directory in ("functions", "function"):
        paths = (
            data_root / "qs_e2e" / directory / "load.mcfunction",
            data_root / "qs_e2e" / directory / "tick.mcfunction",
            data_root / "minecraft" / "tags" / directory / "load.json",
            data_root / "minecraft" / "tags" / directory / "tick.json",
        )
        if all(path.is_file() for path in paths):
            layouts.append(paths)
    if len(layouts) != 1:
        raise ValueError(
            "the E2E datapack must contain exactly one complete singular or plural "
            "function layout"
        )
    return layouts[0]


def world_gamerules(load_function: str) -> frozenset[str]:
    """Resolve the one complete deterministic gamerule vocabulary in the function."""

    lines = frozenset(line.strip() for line in load_function.splitlines())
    variants = [rules for rules in WORLD_GAMERULE_VARIANTS if rules <= lines]
    if len(variants) != 1:
        raise ValueError(
            "the E2E load function must contain exactly one complete legacy or "
            "namespaced gamerule set"
        )
    return variants[0]


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
        options = OPTIONS.read_text(encoding="utf-8")
        self.assertIn("panoramaScrollSpeed:0.0", options)
        self.assertNotIn("panoramaSpeed:0.0", options)

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
        world_load, world_tick, _world_load_tag, world_tick_tag = world_function_paths(
            WORLD_DATA
        )
        load_function = world_load.read_text(encoding="utf-8")

        self.assertIn("level-seed=quickskin-e2e", properties)
        self.assertIn(world_gamerules(load_function), WORLD_GAMERULE_VARIANTS)
        self.assertIn("team modify qs_e2e collisionRule never", load_function)
        self.assertIn(
            "team join qs_e2e @a[team=!qs_e2e]",
            world_tick.read_text(encoding="utf-8"),
        )
        self.assertIn('"qs_e2e:tick"', world_tick_tag.read_text(encoding="utf-8"))

    def test_world_function_layout_accepts_both_pack_format_spellings(self) -> None:
        for directory in ("functions", "function"):
            with self.subTest(directory=directory), tempfile.TemporaryDirectory() as tmp:
                data_root = Path(tmp)
                paths = (
                    data_root / "qs_e2e" / directory / "load.mcfunction",
                    data_root / "qs_e2e" / directory / "tick.mcfunction",
                    data_root / "minecraft" / "tags" / directory / "load.json",
                    data_root / "minecraft" / "tags" / directory / "tick.json",
                )
                for path in paths:
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_text("test\n", encoding="utf-8")
                self.assertEqual(paths, world_function_paths(data_root))

    def test_world_gamerules_accept_both_version_vocabularies(self) -> None:
        for rules in WORLD_GAMERULE_VARIANTS:
            with self.subTest(rules=rules):
                self.assertEqual(rules, world_gamerules("\n".join(sorted(rules))))

    def test_world_player_interpolation_is_pinned_by_the_e2e_harness(self) -> None:
        source = DEFAULT_SKIN_VIEW.read_text(encoding="utf-8")
        shim = VANILLA_SHIM.read_text(encoding="utf-8")

        self.assertIn("player.tickCount = FIXED_RENDER_TICK", source)
        self.assertIn("player.walkAnimation.setSpeed(0.0F)", source)
        self.assertIn("VanillaShim.resetWalkDistance(player)", source)
        self.assertNotIn("player.walkDist =", source)
        self.assertNotIn("player.walkDistO =", source)
        for mapping_name in (
            "field_5973",
            "field_6039",
            "field_53039",
            "field_53038",
            "field_62569",
            "field_62570",
            "f_19787_",
            "f_19867_",
        ):
            self.assertIn(f'"{mapping_name}"', shim)
        self.assertIn('"avatarState", "method_74192"', shim)
        self.assertIn("player.xo = player.xOld = player.getX()", source)
        self.assertIn(
            "DefaultSkinEvidenceView.pinStandingMotion(mc.player)",
            E2E_HARNESS.read_text(encoding="utf-8"),
        )

    def test_capture_is_dispatched_after_the_counted_render_pass_completes(self) -> None:
        source = E2E_HARNESS.read_text(encoding="utf-8")
        rendered_callback = source.split("private void onRenderedFrame()", 1)[1].split(
            "private void dispatchReadyCapture", 1
        )[0]

        self.assertIn("renderedFrame++;", rendered_callback)
        self.assertNotIn("VanillaShim.screenshot", rendered_callback)
        self.assertIn("private void dispatchReadyCapture(Minecraft mc)", source)
        self.assertIn("dispatchReadyCapture(mc);", source)
        self.assertIn("captured completed rendered frame", source)

    def test_hud_preview_targets_the_authored_lower_right_region(self) -> None:
        source = FULL_SCENARIO.read_text(encoding="utf-8")

        self.assertIn("positionHudOverlayForEvidence(mc, ClientConfig.getInstance())", source)
        self.assertIn("Math.round(screenWidth * 0.89f)", source)
        self.assertIn("Math.round(screenHeight * 0.96f)", source)
        self.assertIn('overlayCachedInt("cachedModelCenterX")', source)
        self.assertIn("overlayGeometryFailure(mc)", source)


if __name__ == "__main__":
    unittest.main()
