from __future__ import annotations

import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
MATRIX = json.loads(
    (ROOT / "release" / "release-matrix.json").read_text(encoding="utf-8")
)
LEGACY_PLAYER_INFO_MIXIN = (
    ROOT
    / "common"
    / "src"
    / "legacy1_20_1"
    / "java"
    / "com"
    / "quickskin"
    / "mod"
    / "mixin"
    / "PlayerInfoMixin.java"
)
HOT_PATH_LOG_TOKENS = (".info(", ".warn(", ".debug(", ".trace(", "currentTimeMillis")


class LegacySkinHookLoggingPolicyTest(unittest.TestCase):
    """PlayerInfo's texture getters run on the render path on 1.20.1 (issue #1933).

    Every hook in the legacy PlayerInfoMixin must stay free of logging and clock reads; a
    per-instance throttle field is not an acceptable substitute because it merges an
    unprefixed field into PlayerInfo and still emits one line per player every few seconds.
    """

    def test_legacy_player_info_hooks_neither_log_nor_read_the_clock(self) -> None:
        active_common_overlays = frozenset(
            MATRIX["source_overlays"]["common"].values()
        )
        if "legacy1_20_1" not in active_common_overlays:
            self.assertFalse(LEGACY_PLAYER_INFO_MIXIN.exists())
            return

        self.assertTrue(LEGACY_PLAYER_INFO_MIXIN.is_file())
        source = LEGACY_PLAYER_INFO_MIXIN.read_text(encoding="utf-8")
        for forbidden in ("org.slf4j", "LoggerFactory", "lastLogTime", "shouldLog"):
            self.assertNotIn(forbidden, source)
        for token in HOT_PATH_LOG_TOKENS:
            self.assertNotIn(token, source)

        skin_hook = source[
            source.index("private void quickskin$onGetSkinLocation(") : source.index(
                "private void quickskin$onGetModelName("
            )
        ]
        self.assertIn("if (CPMCompatIntegration.shouldDeferToCPM()) return;", skin_hook)
        self.assertLess(
            skin_hook.index("if (CPMCompatIntegration.shouldDeferToCPM()) return;"),
            skin_hook.index("PlayerAppearanceService.getInstance()"),
        )


if __name__ == "__main__":
    unittest.main()
