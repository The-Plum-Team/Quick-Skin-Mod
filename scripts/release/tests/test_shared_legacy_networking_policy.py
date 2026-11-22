from __future__ import annotations

import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
NETWORKING = ROOT / "modules" / "networking" / "src"
ORIGINAL = NETWORKING / "legacy1_20_1" / "java"
COPY = NETWORKING / "legacy1_20_4" / "java"


class SharedLegacyNetworkingPolicyTest(unittest.TestCase):
    """Minecraft 1.20.4 speaks through the same pre-payload Architectury API as 1.20.1.

    An overlay is copied as it is, without version conditions, and a target follows exactly one
    overlay route, so 1.20.4 carries its own copy of the 1.20.1 networking overlay. The two
    copies are one implementation: a change made to one alone would leave the other target on
    the old behaviour without any compiler noticing.
    """

    def test_the_1_20_4_networking_overlay_is_the_1_20_1_overlay(self) -> None:
        original = sorted(path.relative_to(ORIGINAL) for path in ORIGINAL.rglob("*") if path.is_file())
        copy = sorted(path.relative_to(COPY) for path in COPY.rglob("*") if path.is_file())
        self.assertTrue(original, "the 1.20.1 networking overlay holds no file")
        self.assertEqual(original, copy)
        for relative in original:
            with self.subTest(file=relative.as_posix()):
                self.assertEqual(
                    (ORIGINAL / relative).read_bytes(),
                    (COPY / relative).read_bytes(),
                    f"{relative.as_posix()} differs between legacy1_20_1 and legacy1_20_4",
                )


if __name__ == "__main__":
    unittest.main()
