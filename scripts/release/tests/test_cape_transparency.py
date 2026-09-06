"""Independent image canaries for the real opaque-backface regression."""
from __future__ import annotations

import copy
import json
import sys
import tempfile
import unittest
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "e2e"))

import packaged_runtime
from cape_transparency import inspect_cape_underlay
from scenario_contract import ScenarioContractError, load_contract


class CapeTransparencyTest(unittest.TestCase):
    KEY = ("full", "client_a", "translucent_cape_worn")

    def setUp(self):
        self.probe = packaged_runtime.TRANSLUCENT_CAPE_PROBES[self.KEY]

    @staticmethod
    def frame(kind="plaid", dx=0, dy=0):
        image = Image.new("RGB", (1920, 1080), (92, 138, 202))
        draw = ImageDraw.Draw(image)
        for y in range(1080):
            draw.line((0, y, 1919, y), fill=(70 + y // 16, 110 + y // 12, 170 + y // 14))
        # Geometry and colours independently measured from the authenticated 1.20.1 capture.
        draw.rectangle((899 + dx, 582 + dy, 1019 + dx, 775 + dy), fill=(177, 155, 29))
        draw.rectangle((912 + dx, 594 + dy, 1007 + dx, 762 + dy), fill=(13, 67, 77))
        if kind == "missing":
            return Image.new("RGB", image.size, (92, 138, 202))
        for y in range(594, 763):
            if kind == "plaid":
                colour = (29, 81, 86) if (y - 617) % 35 < 12 else (21, 73, 79)
            elif kind == "gradient":
                level = (y - 594) // 5
                colour = (13 + level, 67 + level, 77 + level)
            elif kind == "noise":
                colour = (35, 89, 99) if y % 2 else (13, 67, 77)
            elif kind == "unblended":
                colour = (65, 65, 65) if (y - 617) % 35 < 12 else (25, 25, 25)
            else:
                continue
            draw.line((912 + dx, y + dy, 1007 + dx, y + dy), fill=colour)
        return image

    def test_contract_keeps_independently_calibrated_scope_and_thresholds(self):
        self.assertEqual((0.43, 0.50, 0.57, 0.74), self.probe.region)
        self.assertEqual(4.0, self.probe.minimum_contrast)
        self.assertEqual(5, self.probe.minimum_band_changes)

    def test_plaid_is_visible_through_teal_even_with_small_pose_offsets(self):
        for dx, dy in ((0, 0), (-12, -8), (14, 9)):
            with self.subTest(dx=dx, dy=dy):
                metrics = inspect_cape_underlay(self.frame(dx=dx, dy=dy), self.probe)
                self.assertGreaterEqual(metrics["band_changes"], 5)
                self.assertGreater(metrics["contrast"], 7)

    def test_flat_panel_missing_cape_gradient_noise_and_unblended_skin_fail(self):
        for kind in ("opaque", "missing", "gradient", "noise", "unblended"):
            with self.subTest(kind=kind), self.assertRaises(ValueError):
                inspect_cape_underlay(self.frame(kind), self.probe)

    def test_bands_outside_the_cape_cannot_supply_the_underlay(self):
        image = self.frame("opaque")
        draw = ImageDraw.Draw(image)
        for y in range(595, 762, 12):
            draw.rectangle((860, y, 893, y + 5), fill=(90, 90, 90))
            draw.rectangle((1026, y, 1060, y + 5), fill=(90, 90, 90))
        with self.assertRaisesRegex(ValueError, "hides the plaid underlay"):
            inspect_cape_underlay(image, self.probe)

    def test_packaged_validator_applies_probe_to_its_owned_capture(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "frame.png"
            self.frame().save(path)
            packaged_runtime.inspect_screenshot_for_step(path, *self.KEY)
            self.frame("opaque").save(path)
            with self.assertRaisesRegex(packaged_runtime.RuntimeFailure, "hides the plaid underlay"):
                packaged_runtime.inspect_screenshot_for_step(path, *self.KEY)
            # Another checkpoint must not inherit a fixture-specific condition.
            packaged_runtime.inspect_screenshot_for_step(path, "full", "client_a", "cape_import_standard")

    def test_malformed_or_disabled_probe_is_rejected(self):
        source = json.loads((ROOT / "e2e/scenario-contract.json").read_text())
        for field, value in (("minimum_contrast", 0), ("minimum_band_changes", 0),
                             ("minimum_band_changes", True), ("region", [0, 0, 0, 1]),
                             ("unrecognized", 1)):
            with self.subTest(field=field, value=value), tempfile.TemporaryDirectory() as directory:
                data = copy.deepcopy(source)
                scenario = next(item for item in data["scenarios"] if item["scenario"] == "full")
                role = next(item for item in scenario["roles"] if item["role"] == "client_a")
                step = next(item for item in role["steps"]
                            if item["id"] == "translucent_cape_worn")
                step["capture"]["probes"][0][field] = value
                path = Path(directory) / "contract.json"
                path.write_text(json.dumps(data))
                with self.assertRaises(ScenarioContractError):
                    load_contract(path)


if __name__ == "__main__":
    unittest.main()
