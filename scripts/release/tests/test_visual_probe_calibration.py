from __future__ import annotations

import json
import sys
import tempfile
import unittest
from itertools import pairwise
from pathlib import Path

from scripts.architecture.source_inventory import java_source

from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "e2e"))
sys.path.insert(0, str(ROOT / "scripts" / "release"))

import packaged_runtime  # noqa: E402
from generate_e2e_cape_fixture import generate as generate_cape_fixture  # noqa: E402


# This is deliberately not generated from scenario-contract.json. It is the fixed calibration
# canary for the known 1600x900 Minecraft UI geometry. An intentional oracle change must update
# both the contract and this independently reviewed reference.
TEXT_CANARY = {
    # The menu has no panel: "Skins" over the N/M counter heads the left skin column, and the
    # player's name tag ("Alice") sits above the centred model. A real 1920-wide frame of the
    # counter keeps about 320 glyph pixels above luma 175 after the 1600x900 resize, and a nine
    # letter name about 310, so Alice's five letters clear 100 with room to spare.
    ("full", "client_a", "skin_menu_screen"): (
        ("skin column counter", (16, 96, 120, 156), 175, 200),
        ("player name tag", (600, 116, 1000, 162), 175, 100),
    ),
    # The cape menu draws at its own scale: "My Capes" heads the frame and the drop zone sits
    # below it. Every anchor lane keeps about 495 title and 1,087 drop-zone glyph pixels above
    # luma 159 after the 1600x900 resize.
    ("full", "client_a", "cape_menu_screen"): (
        ("cape menu title", (505, 35, 650, 75), 159, 300),
        ("cape drop-zone instructions", (585, 140, 880, 200), 159, 650),
    ),
    # The title and the hint share the editor's header strip, the title from its left edge and
    # the hint against its right edge. At GUI scale 3 the title spans x=20..252, y=17..37 on a
    # 1920x1080 framebuffer and x=24..302, y=21..44 on a native 1600x900 one, keeping 1,597 and
    # 1,845 pixels above luma 75; the hint spans x=1210..1577 and x=1134..1574 on the same rows
    # with 2,050 and 2,349. Both boxes end above the strip's accent line.
    ("full", "client_a", "cape_adjust_screen"): (
        ("cape editor title", (16, 14, 330, 50), 75, 900),
        ("cape editor instructions", (1100, 14, 1590, 50), 75, 1200),
    ),
    # The Fabric and Forge 1920x1080 anchor frames both retain 540 glyph pixels above
    # this threshold after the production 1600x900 resize.
    ("full", "client_a", "cape_fill_color_picker"): (
        ("fill colour hex value", (79, 543, 220, 576), 175, 380),
    ),
    # Every anchor lane retains about 3,570 bright tooltip pixels in this box, and only the
    # drop-zone copy's 1,087 without the tooltip.
    ("full", "client_a", "cape_tile_tooltip"): (
        ("cape tile tooltip copy", (600, 125, 875, 290), 159, 2500),
    ),
    # The source size ends the Zoom panel's heading row under the canvas and the output size sits
    # under the right panel's resolution buttons. The boxes are the union of the 1920x1080 and
    # native 1600x900 layouts at GUI scale 3, kept inside the panels (the source box stops at the
    # Zoom panel's right edge on the 1600x900 layout) and below the resolution buttons' labels;
    # "Source: 192x128" kept 682 and 1,257 pixels above luma 175 and "Output: 64x32" 679 and 1,119.
    ("full", "client_a", "bmo_padded_source_screen"): (
        ("BMO source dimensions", (820, 634, 1040, 662), 175, 300),
        ("BMO output dimensions", (1215, 514, 1430, 552), 175, 300),
    ),
    # The settings tabs sit in a sidebar and each setting is a row whose label starts at the
    # content's left edge; a label too long for one line wraps onto two inside its row. A 1.21.1
    # frame keeps 1,620 pixels of "Assign a key to open the skin menu" above the threshold after
    # the resize, 2,604 of the two-line server transparency label and 1,942 of the notice near
    # the dialog's bottom.
    ("full", "client_a", "settings_screen"): (
        ("Open Skin Menu setting label", (510, 210, 960, 240), 175, 1100),
    ),
    ("full", "client_a", "settings_server_tab"): (
        ("server transparency setting label", (510, 196, 1145, 244), 175, 1800),
        ("non-admin server notice", (685, 728, 1190, 757), 175, 1400),
    ),
    # The disabled label is dimmer than ordinary GUI text. It sits on the bottom-centre Confirm
    # button in the blocky button font: both 1.20.1 lanes kept 9,959 glyph pixels above luma 127,
    # all within x 573-1025 and y 733-765, for the observed three-digit countdown.
    ("server-policy", "client_a", "cooldown_skin_menu"): (
        ("cooldown button label", (560, 725, 1040, 775), 127, 240),
    ),
}

OPAQUE_STARS_CANARY = (
    ("full", "client_a", "skin_menu_screen"),
    (0.25, 0.12, 0.35, 0.50),
    32.0,
    64,
    0.10,
)

TITLE_SPLASH_SCAN_CANARY = (0.50, 0.0, 1.0, 0.50)


class VisualProbeCalibrationTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def test_contract_matches_the_independent_1600x900_canary(self) -> None:
        actual_text = {
            key: tuple(probes)
            for key, probes in packaged_runtime.REQUIRED_GUI_TEXT_PROBES.items()
        }
        self.assertEqual(TEXT_CANARY, actual_text)

        key, region, mean_luma, bright_luma, bright_fraction = OPAQUE_STARS_CANARY
        probe = packaged_runtime.OPAQUE_STARS_PROBES[key]
        self.assertEqual(region, probe.region)
        self.assertEqual(mean_luma, probe.maximum_mean_luma)
        self.assertEqual(bright_luma, probe.bright_luma)
        self.assertEqual(bright_fraction, probe.maximum_bright_fraction)

    def test_fixed_frames_exercise_the_calibrated_probe_geometry(self) -> None:
        for index, (key, probes) in enumerate(TEXT_CANARY.items()):
            with self.subTest(key=key):
                image = Image.new("RGB", (1600, 900), (12, 14, 18))
                draw = ImageDraw.Draw(image)
                # Fixed coloured areas keep this recognisably image-like; probe glyphs below are
                # still authored from the independent constants above, not the runtime oracle.
                draw.rectangle((20, 20, 260, 150), fill=(25, 50, 90))
                draw.rectangle((1260, 680, 1570, 880), fill=(80, 20, 55))
                for _label, (left, top, right, bottom), _luma, minimum_pixels in probes:
                    width = max(1, right - left - 8)
                    rows = minimum_pixels // width + 2
                    self.assertLess(top + 4 + rows, bottom)
                    draw.rectangle(
                        (left + 4, top + 4, right - 5, top + 4 + rows),
                        fill=(255, 255, 255),
                    )
                path = self.root / f"text-canary-{index}.png"
                image.save(path, format="PNG")
                packaged_runtime.validate_required_gui_text(path, *key)

        key, region, _mean_luma, _bright_luma, _bright_fraction = OPAQUE_STARS_CANARY
        dark = Image.new("RGB", (1600, 900), (8, 8, 8))
        dark_path = self.root / "opaque-stars-dark.png"
        dark.save(dark_path, format="PNG")
        packaged_runtime.validate_opaque_stars_background(
            dark_path, packaged_runtime.OPAQUE_STARS_PROBES[key]
        )

        bright = dark.copy()
        draw = ImageDraw.Draw(bright)
        draw.rectangle(
            (
                int(region[0] * 1600),
                int(region[1] * 900),
                int(region[2] * 1600),
                int(region[3] * 900),
            ),
            fill=(180, 180, 180),
        )
        bright_path = self.root / "opaque-stars-washed.png"
        bright.save(bright_path, format="PNG")
        with self.assertRaisesRegex(
            packaged_runtime.RuntimeFailure,
            "OPAQUE_STARS background is unexpectedly bright or washed out",
        ):
            packaged_runtime.validate_opaque_stars_background(
                bright_path, packaged_runtime.OPAQUE_STARS_PROBES[key]
            )

    def test_each_text_probe_fails_closed_when_its_glyphs_are_absent(self) -> None:
        for frame_index, (key, probes) in enumerate(TEXT_CANARY.items()):
            for missing_index, (missing_label, _box, _luma, _pixels) in enumerate(probes):
                with self.subTest(key=key, missing=missing_label):
                    image = Image.new("RGB", (1600, 900), (12, 14, 18))
                    draw = ImageDraw.Draw(image)
                    for probe_index, (_label, box, _threshold, minimum_pixels) in enumerate(probes):
                        if probe_index == missing_index:
                            continue
                        left, top, right, _bottom = box
                        width = max(1, right - left - 8)
                        rows = minimum_pixels // width + 2
                        draw.rectangle(
                            (left + 4, top + 4, right - 5, top + 4 + rows),
                            fill=(255, 255, 255),
                        )
                    path = self.root / f"text-missing-{frame_index}-{missing_index}.png"
                    image.save(path, format="PNG")
                    with self.assertRaisesRegex(
                        packaged_runtime.RuntimeFailure,
                        "required GUI text is missing or unreadable",
                    ):
                        packaged_runtime.validate_required_gui_text(path, *key)

    def test_cape_editor_hint_probe_covers_both_real_layouts_but_not_controls(self) -> None:
        key = ("full", "client_a", "cape_adjust_screen")

        def write_frame(name: str, hint: tuple[int, int, int] | None) -> Path:
            image = Image.new("RGB", (1600, 900), (12, 14, 18))
            draw = ImageDraw.Draw(image)
            # The header strip, its accent line just under both boxes, and the title.
            draw.rectangle((0, 0, 1599, 50), fill=(46, 58, 74))
            draw.rectangle((0, 51, 1599, 53), fill=(111, 143, 176))
            draw.rectangle((30, 22, 249, 33), fill=(255, 255, 255))
            # The Source label at the Zoom panel's right edge is the nearest other copy; it
            # must not be enough to impersonate the missing hint.
            draw.rectangle((860, 640, 1060, 655), fill=(85, 255, 255))
            if hint is not None:
                left, top, right = hint
                # Three separated glyph-like strokes model the hint in each real layout.
                for y in (top, top + 6, top + 12):
                    draw.rectangle((left, y, right, y + 1), fill=(255, 255, 255))
            path = self.root / name
            image.save(path, format="PNG")
            return path

        for name, hint in (("1920-layout", (1210, 19, 1577)), ("1600-layout", (1134, 24, 1574))):
            with self.subTest(layout=name):
                packaged_runtime.validate_required_gui_text(
                    write_frame(f"{name}.png", hint), *key
                )

        with self.assertRaisesRegex(
            packaged_runtime.RuntimeFailure,
            "required GUI text is missing or unreadable",
        ):
            packaged_runtime.validate_required_gui_text(
                write_frame("controls-only.png", None), *key
            )

    def test_title_splash_probe_excludes_denser_yellow_panorama_content(self) -> None:
        """Regression canary for the real 1.21.10 flower-field false positive."""

        source = (
            ROOT
            / "common/src/e2e/java/com/quickskin/mod/e2e/scenario/TitleMenuSteps.java"
        ).read_text(encoding="utf-8")
        self.assertIn("int scanBottom = Math.max(1, height / 2);", source)
        self.assertIn("int scanLeft = width / 2;", source)
        self.assertIn("for (int y = scanTop; y < scanBottom; y++)", source)
        self.assertIn("for (int x = scanLeft; x < scanRight; x++)", source)

        width, height = 1920, 1080
        image = Image.new("RGB", (width, height), (18, 28, 42))
        draw = ImageDraw.Draw(image)
        # The false target is intentionally much denser than the title text, just as the 1.21.10
        # panorama was. A whole-frame densest-cluster strategy would pick this lower-left field.
        draw.rectangle((100, 680, 280, 750), fill=(255, 240, 20))
        # The only eligible title-chrome cluster is the upper-right splash.
        draw.rectangle((1125, 202, 1280, 273), fill=(255, 255, 0))

        left = int(TITLE_SPLASH_SCAN_CANARY[0] * width)
        top = int(TITLE_SPLASH_SCAN_CANARY[1] * height)
        right = int(TITLE_SPLASH_SCAN_CANARY[2] * width)
        bottom = int(TITLE_SPLASH_SCAN_CANARY[3] * height)
        eligible = [
            (x, y)
            for y in range(top, bottom)
            for x in range(left, right)
            if image.getpixel((x, y))[0] >= 200
            and image.getpixel((x, y))[1] >= 200
            and image.getpixel((x, y))[2] <= 90
        ]

        self.assertTrue(eligible)
        self.assertGreaterEqual(min(x for x, _y in eligible), width // 2)
        self.assertLess(max(y for _x, y in eligible), height // 2)
        self.assertEqual(
            (1125, 202, 1280, 273),
            (
                min(x for x, _y in eligible),
                min(y for _x, y in eligible),
                max(x for x, _y in eligible),
                max(y for _x, y in eligible),
            ),
        )

    def test_custom_elytra_evidence_pins_the_interpolated_rear_pose(self) -> None:
        """A logical crouch alone can hide one wing or an opaque inner-face rectangle."""

        source = (
            ROOT
            / "common/src/e2e/java/com/quickskin/mod/e2e/scenario/FullScenario.java"
        ).read_text(encoding="utf-8")
        bmo_section = source[source.index('Step.of("bundled_bmo_elytra")'):
                             source.index("// 6. animated cape")]
        hd_section = source[source.index('Step.of("elytra_hides_cape")'):
                            source.index("// 8b. the cape editor")]
        self.assertEqual(3, bmo_section.count(".settleTicks(12)"))
        # Both custom captures and the post-removal vanilla capture reapply the pose in action()
        # and ready(); the removal transition also restores it after opening the menu releases
        # movement keys, so no checkpoint can drift while textures/equipment settle.
        self.assertEqual(7, bmo_section.count("poseElytraForEvidence(mc);"))
        # The fully opaque HD fixture owns the same two-call hold and settle window. Without it,
        # overlapping standing wings can make the inner-face canvas regression look like a cape.
        self.assertEqual(2, hd_section.count("poseElytraForEvidence(mc);"))
        self.assertEqual(1, hd_section.count(".settleTicks(12)"))
        self.assertIn("assertCapeRoute(", hd_section)
        self.assertEqual(9, source.count("poseElytraForEvidence(mc);"))
        self.assertIn('Step.of("remove_cape_with_elytra")', bmo_section)
        self.assertIn('Step.of("vanilla_elytra_after_cape_removal")', bmo_section)
        self.assertIn('"quickskin.button.remove_cape"', bmo_section)
        self.assertIn("vanillaElytraFallbackProblem(mc, svc, uuid)", bmo_section)
        self.assertIn(
            "String profileElytra = VanillaShim.elytraTexture(mc.player);", source
        )
        self.assertIn("if (profileElytra != null)", source)
        self.assertIn("mc.player.setYRot(yaw);", source)
        self.assertIn("mc.player.yRotO = yaw;", source)
        self.assertIn("mc.player.setYHeadRot(yaw);", source)
        self.assertIn("mc.player.yHeadRotO = yaw;", source)
        self.assertIn("mc.player.setYBodyRot(yaw);", source)
        self.assertIn("mc.player.yBodyRotO = yaw;", source)
        self.assertIn(
            'return Step.Result.fail("elytra evidence camera/body yaw is not stably aligned")',
            source,
        )

    def test_model_geometry_evidence_is_close_stable_and_semantically_explicit(self) -> None:
        source = (
            ROOT
            / "common/src/e2e/java/com/quickskin/mod/e2e/scenario/FullScenario.java"
        ).read_text(encoding="utf-8")
        contract = json.loads(
            (ROOT / "e2e/scenario-contract.json").read_text(encoding="utf-8")
        )
        full = next(item for item in contract["scenarios"] if item["scenario"] == "full")
        role = next(item for item in full["roles"] if item["role"] == "client_a")
        steps = {item["id"]: item for item in role["steps"]}

        # The model-geometry section ends where the skin resolution/alpha checkpoints begin; those
        # deliberately do not pin the close FOV-50 rear view, so they stay outside this count.
        model_section = source[source.index('Step.of("model_slim")'):
                               source.index("// 3c. HD and transparent skins")]
        self.assertEqual(2, model_section.count("prepareModelEvidenceView(mc);"))
        self.assertEqual(2, model_section.count(".settleTicks(12)"))
        self.assertIn("MODEL_EVIDENCE_FOV = 50", source)
        self.assertIn("pinRearEvidenceView(mc);", source)
        self.assertIn("renderer model=", source)
        self.assertIn("FOV-50 rear view", steps["model_slim"]["capture"]["expectation"])
        self.assertIn("3-pixel base geometry", steps["model_slim"]["capture"]["expectation"])
        self.assertIn("inflated jacket outer layer", steps["model_slim"]["capture"]["expectation"])
        self.assertIn("4-pixel base geometry", steps["model_classic"]["capture"]["expectation"])

    def test_bmo_padding_and_aligned_uv_semantics_are_separate_checkpoints(self) -> None:
        """Padding and auxiliary UV faces must never share one ambiguous model expectation."""

        contract = json.loads(
            (ROOT / "e2e/scenario-contract.json").read_text(encoding="utf-8")
        )
        full = next(item for item in contract["scenarios"] if item["scenario"] == "full")
        role = next(item for item in full["roles"] if item["role"] == "client_a")
        steps = {item["id"]: item for item in role["steps"]}

        padded = steps["bmo_padded_source_screen"]["capture"]["expectation"]
        aligned = steps["bmo_adjust_screen"]["capture"]["expectation"]
        self.assertIn("padding on all four sides", padded)
        self.assertIn("Source: 128x64", padded)
        self.assertIn("Output: 64x32", padded)
        self.assertIn("output selector describes the final atlas", padded)
        self.assertIn("checkerboard visible only through transparent source pixels", padded)
        self.assertIn("before-crop checkpoint", padded)
        self.assertNotIn("auxiliary", padded)
        self.assertIn("auxiliary side, top and bottom UV faces", aligned)
        self.assertIn("not leaked padding", aligned)

        source = (
            ROOT
            / "common/src/e2e/java/com/quickskin/mod/e2e/scenario/FullScenario.java"
        ).read_text(encoding="utf-8")
        padded_start = source.index('Step.of("bmo_padded_source_screen")')
        aligned_start = source.index('Step.of("bmo_adjust_screen")')
        adjusted_start = source.index('Step.of("adjusted_bmo_cape")')
        self.assertLess(padded_start, aligned_start)
        self.assertEqual(
            1,
            source[padded_start:aligned_start].count(
                'screenshot(prefix + "full_05j_bmo_padded_source"'
            ),
        )
        self.assertEqual(
            1,
            source[aligned_start:adjusted_start].count(
                'screenshot(prefix + "full_05k_bmo_adjusted_editor"'
            ),
        )
        self.assertIn(
            'return Step.Result.fail("BMO transform is scale="',
            source[padded_start:aligned_start],
        )
        self.assertIn('"sourceDimensions"', source[padded_start:aligned_start])
        self.assertIn('"outputDimensions"', source[padded_start:aligned_start])
        screen = (
            java_source('client/gui/screen/CapeAdjustScreen.java', source_set='main', repository=ROOT)
        ).read_text(encoding="utf-8")
        self.assertIn("renderSourceTransparencyBackdrop(graphics);", screen)
        self.assertIn("renderSourceBoundary(graphics);", screen)
        self.assertIn("quickskin.cape.adjust_source_dimensions", screen)
        self.assertIn("quickskin.cape.adjust_output_resolution", screen)
        self.assertIn(
            "long composedDrift = countDifferingPixels(expected, composed);",
            source[aligned_start:adjusted_start],
        )

    def test_animated_cape_fixture_is_valid_and_visibly_changes_in_its_uv_face(self) -> None:
        """The former square GIF exposed only a clipped corner when sampled as a cape atlas."""

        gif = ROOT / "common/src/e2e/resources/qs_e2e_test_cape.gif"
        generated = self.root / "cape.gif"
        generate_cape_fixture(generated)
        self.assertEqual(gif.read_bytes(), generated.read_bytes())
        with Image.open(gif) as image:
            self.assertEqual((64, 32), image.size)
            self.assertEqual(2, image.n_frames)
            face_frames = []
            for frame in range(image.n_frames):
                image.seek(frame)
                rgb = image.convert("RGB")
                # Minecraft's visible rear cape face is x=1..10, y=1..16 in a 64x32 atlas.
                face_frames.append(rgb.crop((1, 1, 11, 17)))

                colored = [
                    (x, y)
                    for y in range(rgb.height)
                    for x in range(rgb.width)
                    if max(rgb.getpixel((x, y))) - min(rgb.getpixel((x, y))) > 80
                ]
                self.assertTrue(colored)
                self.assertTrue(all(1 <= x < 11 and 1 <= y < 17 for x, y in colored))

            self.assertTrue(
                any(left.tobytes() != right.tobytes() for left, right in pairwise(face_frames))
            )

        source = (
            ROOT
            / "common/src/e2e/java/com/quickskin/mod/e2e/scenario/FullScenario.java"
        ).read_text(encoding="utf-8")
        self.assertIn(".setAnimationFrame(", source)
        self.assertIn("map.get(expectedAnimationId())", source)
        self.assertIn("ANIMATED_EVIDENCE_FRAME_A = 0", source)
        self.assertIn("ANIMATED_EVIDENCE_FRAME_B = 1", source)
        animated_section = source[source.index('Step.of("animated_cape_apply")'):
                                  source.index("// 7. HD cape import")]
        self.assertEqual(2, animated_section.count(".settleTicks(12)"))

        manager_source = (
            java_source('client/services/AnimatedTextureManager.java', source_set='main', repository=ROOT)
        ).read_text(encoding="utf-8")
        self.assertIn("speedMultiplier == 0.0f", manager_source)
        self.assertIn("boolean setAnimationFrame(String animationId, int frame)", manager_source)


if __name__ == "__main__":
    unittest.main()
