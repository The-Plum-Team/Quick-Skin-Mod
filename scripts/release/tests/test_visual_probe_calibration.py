from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path

from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "e2e"))

import packaged_runtime  # noqa: E402


# This is deliberately not generated from scenario-contract.json. It is the fixed calibration
# canary for the known 1600x900 Minecraft UI geometry. An intentional oracle change must update
# both the contract and this independently reviewed reference.
TEXT_CANARY = {
    ("full", "client_a", "skin_menu_screen"): (
        ("skin catalog labels", (480, 195, 850, 300), 175, 500),
        # The E2E import guarantees one entry; the offline Alice skin may add a second. The
        # drop-zone centers in the remaining list area, so its copy has two legitimate Y offsets.
        ("skin drop-zone instructions", (560, 440, 745, 523), 175, 300),
    ),
    ("full", "client_a", "cape_menu_screen"): (
        ("cape menu title", (590, 100, 735, 140), 159, 174),
        ("cape drop-zone instructions", (590, 200, 885, 260), 159, 531),
    ),
    ("full", "client_a", "cape_adjust_screen"): (
        ("cape editor title", (675, 20, 925, 50), 75, 400),
        ("cape editor instructions", (335, 624, 725, 655), 75, 750),
    ),
    ("full", "client_a", "settings_screen"): (
        ("Open Skin Menu setting label", (445, 235, 655, 265), 175, 300),
    ),
}

OPAQUE_STARS_CANARY = (
    ("full", "client_a", "skin_menu_screen"),
    (0.03, 0.20, 0.20, 0.80),
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

    def test_title_splash_probe_excludes_denser_yellow_panorama_content(self) -> None:
        """Regression canary for the real 1.21.10 flower-field false positive."""

        source = (
            ROOT
            / "common/src/e2e/java/com/quickskin/mod/e2e/scenario/FullScenario.java"
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


if __name__ == "__main__":
    unittest.main()
