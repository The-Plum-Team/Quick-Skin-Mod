from __future__ import annotations

import json
import unittest
from pathlib import Path

from scripts.architecture.source_inventory import java_source


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
LEGACY_SKIN_MANAGER_MIXIN = LEGACY_PLAYER_INFO_MIXIN.with_name("MixinSkinManager.java")
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

    def test_legacy_skin_info_override_resolves_files_through_the_cached_bridge(self) -> None:
        """SkullBlockRenderer calls getInsecureSkinInformation every frame per player head."""
        active_common_overlays = frozenset(
            MATRIX["source_overlays"]["common"].values()
        )
        if "legacy1_20_1" not in active_common_overlays:
            self.assertFalse(LEGACY_SKIN_MANAGER_MIXIN.exists())
            return

        self.assertTrue(LEGACY_SKIN_MANAGER_MIXIN.is_file())
        source = LEGACY_SKIN_MANAGER_MIXIN.read_text(encoding="utf-8")
        override = source[source.index("private void quickskin$overrideSkinInfo(") :]
        for token in (
            ".info(",
            ".warn(",
            "toFile().exists()",
            "Files.exists(",
            "getSourcePath(",
            "getOrCreateTempFile(",
            "new HashMap<>()",
        ):
            self.assertNotIn(token, override)
        self.assertIn("CPMCompatIntegration.resolveSkinFileUrl(hash)", override)

        bridge = java_source(
            "client/compat/CPMCompatIntegration.java", source_set="main", repository=ROOT
        ).read_text(encoding="utf-8")
        resolve = bridge[bridge.index("public static String resolveSkinFileUrl(") :]
        resolve = resolve[: resolve.index("public static void evictHttpTextureCache(")]
        self.assertIn("resolvedSkinFileUrls.get(hash)", resolve)
        self.assertLess(
            resolve.index("resolvedSkinFileUrls.get(hash)"), resolve.index("Files.exists(")
        )
        self.assertNotIn(".info(", resolve)

        evict = bridge[bridge.index("public static void evictHttpTextureCache(") :]
        evict = evict[: evict.index("public static void clearHttpTextureCache(")]
        self.assertIn("resolvedSkinFileUrls.remove(hash)", evict)
        self.assertLess(
            evict.index("resolvedSkinFileUrls.remove(hash)"),
            evict.index("supportsHttpTextureBridge()"),
            "the resolution cache must be dropped even when the bridge is degraded",
        )

        clear = bridge[bridge.index("public static void clearHttpTextureCache(") :]
        clear = clear[: clear.index("private static void logDegradedEmbeddedBridge(")]
        self.assertIn("resolvedSkinFileUrls.clear();", clear)


if __name__ == "__main__":
    unittest.main()
