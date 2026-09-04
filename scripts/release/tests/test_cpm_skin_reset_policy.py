from __future__ import annotations

import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
PLAYER_APPEARANCE_SERVICE = (
    ROOT
    / "common"
    / "src"
    / "main"
    / "java"
    / "com"
    / "quickskin"
    / "mod"
    / "client"
    / "services"
    / "PlayerAppearanceService.java"
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
SESSION_SCENARIO = (
    ROOT
    / "common"
    / "src"
    / "e2e"
    / "java"
    / "com"
    / "quickskin"
    / "mod"
    / "e2e"
    / "scenario"
    / "SessionScenario.java"
)


class CpmSkinResetPolicyTest(unittest.TestCase):
    def test_explicit_skin_clear_refreshes_cpm_even_without_a_texture_location(self) -> None:
        source = PLAYER_APPEARANCE_SERVICE.read_text(encoding="utf-8")
        apply_look = source[
            source.index("public void applyLook(") : source.index(
                "public void applySkin(", source.index("public void applyLook(")
            )
        ]

        self.assertEqual(
            1,
            apply_look.count("CPMCompatIntegration.forceReRegisterSkins(playerId);"),
        )
        location_guard = apply_look.index("if (skinLocation != null) {")
        cpm_refresh = apply_look.index(
            "CPMCompatIntegration.forceReRegisterSkins(playerId);"
        )
        model_only = apply_look.index("} else if (model != null) {")
        self.assertLess(location_guard, cpm_refresh)
        self.assertLess(cpm_refresh, model_only)
        self.assertIn(
            "\n            CPMCompatIntegration.forceReRegisterSkins(playerId);",
            apply_look,
            "the CPM refresh must remain outside the nullable skin-location block",
        )
        self.assertIn("A cleared skin has no location", apply_look)

    def test_legacy_player_info_discards_stale_skin_before_reregistering(self) -> None:
        source = LEGACY_PLAYER_INFO_MIXIN.read_text(encoding="utf-8")
        refresh = source[
            source.index("public void quickskin$forceReRegisterSkins()") : source.index(
                "/**", source.index("public void quickskin$forceReRegisterSkins()")
            )
        ]

        skin_clear = refresh.index(
            "textureLocations.remove(MinecraftProfileTexture.Type.SKIN);"
        )
        model_clear = refresh.index("skinModel = null;")
        pending_reset = refresh.index("pendingTextures = false;")
        reregister = refresh.index("registerTextures();")
        self.assertLess(skin_clear, model_clear)
        self.assertLess(model_clear, pending_reset)
        self.assertLess(pending_reset, reregister)
        self.assertNotIn("textureLocations.clear()", refresh)

    def test_session_surfaces_allow_cpm_player_info_to_use_its_bridge(self) -> None:
        source = SESSION_SCENARIO.read_text(encoding="utf-8")
        paper_doll = source[source.index("private static String paperDollProblem(") :]
        paper_doll = paper_doll[
            : paper_doll.index("private static String describePaperDoll(")
        ]

        cpm_branch = paper_doll.index("if (cpm) {")
        renderer_service_check = paper_doll.index(
            "!String.valueOf(serviceSkin).equals(rendererSkin)"
        )
        legacy_equality = paper_doll.index("!infoSkin.equals(rendererSkin)")
        self.assertLess(cpm_branch, renderer_service_check)
        self.assertLess(renderer_service_check, legacy_equality)
        self.assertIn("while PlayerInfo exposes the CPM bridge", paper_doll)


if __name__ == "__main__":
    unittest.main()
