from __future__ import annotations

import math
import sys
import tempfile
import unittest
from pathlib import Path

from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "e2e"))

import packaged_runtime  # noqa: E402


class PackagedRuntimeVisualContractTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def write_skin_menu(self, name: str, *, washed_out: bool, missing_text: bool = False) -> Path:
        width, height = 640, 360
        if washed_out:
            pixels = []
            for y in range(height):
                normalized_y = (y - height / 2) / (height / 2)
                for x in range(width):
                    normalized_x = (x - width / 2) / (width / 2)
                    distance = math.sqrt(normalized_x**2 + normalized_y**2)
                    luma = min(150, int(12 + 115 * distance))
                    pixels.append((luma, luma, luma))
            image = Image.new("RGB", (width, height))
            image.putdata(pixels)
        else:
            image = Image.new("RGB", (width, height), (0, 0, 0))
            draw = ImageDraw.Draw(image)
            for y in range(15, height, 30):
                for x in range(10, width, 32):
                    draw.rectangle((x, y, x + 3, y + 3), fill=(12, 12, 12))

        # A representative dark menu panel with several substantial control colours keeps the
        # fixture non-blank without touching the left-side background region under test.
        draw = ImageDraw.Draw(image)
        draw.rectangle((160, 35, 480, 325), fill=(5, 5, 5), outline=(105, 105, 105), width=2)
        draw.rectangle((174, 70, 350, 94), fill=(25, 25, 25), outline=(150, 150, 150))
        draw.rectangle((174, 104, 350, 130), fill=(95, 8, 130), outline=(170, 70, 170))
        draw.rectangle((180, 170, 345, 250), fill=(28, 28, 28), outline=(170, 170, 170))
        draw.rectangle((365, 145, 445, 270), fill=(35, 65, 115))
        draw.rectangle((174, 285, 460, 310), fill=(85, 85, 85), outline=(185, 185, 185))
        if not missing_text:
            # Bright glyph-like strokes inside the two required skin-menu text regions. The
            # surrounding fixture remains below the probe's luma threshold.
            for y, right in ((82, 252), (88, 268), (108, 244), (114, 260)):
                draw.line((200, y, right, y), fill=(255, 255, 255), width=2)
            for y, right in ((194, 276), (199, 288), (204, 270)):
                draw.line((230, y, right, y), fill=(255, 255, 255), width=2)
        image = image.resize(packaged_runtime.SCREENSHOT_SIZE, Image.Resampling.NEAREST)
        path = self.root / name
        image.save(path, format="PNG")
        return path

    def write_gui_text_probe_frame(
        self,
        name: str,
        key: tuple[str, str, str],
        *,
        omit_label: str | None = None,
    ) -> Path:
        width, height = 640, 360
        image = Image.new("RGB", (width, height), (12, 14, 18))
        draw = ImageDraw.Draw(image)
        draw.rectangle((20, 20, 180, 100), fill=(25, 45, 80))
        draw.rectangle((400, 220, 610, 340), fill=(70, 20, 55))
        scale_x = width / packaged_runtime.GUI_TEXT_REFERENCE_SIZE[0]
        scale_y = height / packaged_runtime.GUI_TEXT_REFERENCE_SIZE[1]
        for label, box, _minimum_luma, _minimum_pixels in (
            packaged_runtime.REQUIRED_GUI_TEXT_PROBES[key]
        ):
            if label == omit_label:
                continue
            left, top, right, bottom = box
            x0 = int(left * scale_x) + 2
            y0 = int(top * scale_y) + 2
            x1 = max(x0 + 8, int(right * scale_x) - 2)
            y1 = max(y0 + 3, int(bottom * scale_y) - 2)
            draw.rectangle((x0, y0, x1, y1), fill=(255, 255, 255))
        path = self.root / name
        image.save(path, format="PNG")
        return path

    def test_dark_starred_skin_menu_passes_semantic_pixel_contract(self) -> None:
        path = self.write_skin_menu("dark-stars.png", washed_out=False)

        metrics = packaged_runtime.inspect_screenshot_for_step(
            path, "full", "client_a", "skin_menu_screen"
        )

        self.assertEqual(
            packaged_runtime.SCREENSHOT_SIZE,
            (metrics["width"], metrics["height"]),
        )

    def test_radial_wash_fails_even_though_generic_integrity_passes(self) -> None:
        path = self.write_skin_menu("radial-wash.png", washed_out=True)

        packaged_runtime.inspect_screenshot(path)
        with self.assertRaisesRegex(
            packaged_runtime.RuntimeFailure,
            "OPAQUE_STARS background is unexpectedly bright or washed out",
        ):
            packaged_runtime.inspect_screenshot_for_step(
                path, "full", "client_a", "skin_menu_screen"
            )

    def test_missing_skin_menu_copy_fails_even_though_generic_integrity_passes(self) -> None:
        path = self.write_skin_menu("missing-text.png", washed_out=False, missing_text=True)

        packaged_runtime.inspect_screenshot(path)
        with self.assertRaisesRegex(
            packaged_runtime.RuntimeFailure,
            "required GUI text is missing or unreadable",
        ):
            packaged_runtime.inspect_screenshot_for_step(
                path, "full", "client_a", "skin_menu_screen"
            )

    def test_every_required_gui_text_probe_is_fail_closed(self) -> None:
        for key, probes in packaged_runtime.REQUIRED_GUI_TEXT_PROBES.items():
            with self.subTest(key=key, state="visible"):
                visible = self.write_gui_text_probe_frame(f"{key[2]}-visible.png", key)
                packaged_runtime.validate_required_gui_text(visible, *key)

            for label, _box, _minimum_luma, _minimum_pixels in probes:
                with self.subTest(key=key, omitted=label):
                    missing = self.write_gui_text_probe_frame(
                        f"{key[2]}-{label.replace(' ', '-')}.png",
                        key,
                        omit_label=label,
                    )
                    with self.assertRaisesRegex(
                        packaged_runtime.RuntimeFailure,
                        "required GUI text is missing or unreadable",
                    ):
                        packaged_runtime.validate_required_gui_text(missing, *key)


if __name__ == "__main__":
    unittest.main()
