from __future__ import annotations

import json
import unittest
from pathlib import Path

from scripts.architecture.source_inventory import java_source


ROOT = Path(__file__).resolve().parents[3]
MATRIX = json.loads(
    (ROOT / "release" / "release-matrix.json").read_text(encoding="utf-8")
)
LEGACY_SKIN_MANAGER_MIXIN = (
    ROOT
    / "common"
    / "src"
    / "legacy1_20_1"
    / "java"
    / "com"
    / "quickskin"
    / "mod"
    / "mixin"
    / "MixinSkinManager.java"
)
NULL_PROFILE_GUARD = "if (profile == null || profile.getId() == null) return;"


def _method_body(source: str, signature: str, next_marker: str) -> str:
    start = source.index(signature)
    return source[start : source.index(next_marker, start)]


class LegacyNullProfilePolicyTest(unittest.TestCase):
    """A name-only GameProfile (a player_head whose SkullOwner is a bare name) has no UUID.

    Vanilla builds such profiles while a skull lookup is unresolved, and CPM hands them to
    SkinManager.registerSkins on 1.20.1. Appearance lookups are keyed by UUID, so a null id
    must resolve to "no appearance" instead of reaching ConcurrentHashMap with a null key.
    """

    def test_client_appearance_lookups_tolerate_a_null_player_id(self) -> None:
        repository = java_source(
            "common/data/PlayerAppearanceRepository.java", source_set="main", repository=ROOT
        ).read_text(encoding="utf-8")
        get_appearance = _method_body(
            repository,
            "public PlayerAppearance getAppearance(",
            "public synchronized void setAppearance(",
        )
        guard = "if (playerId == null) return null;"
        self.assertIn(guard, get_appearance)
        self.assertLess(
            get_appearance.index(guard), get_appearance.index("appearances.get(playerId)")
        )

        model_service = java_source(
            "client/services/ModelService.java", source_set="main", repository=ROOT
        ).read_text(encoding="utf-8")
        get_override = _method_body(
            model_service, "public String getModelOverride(", "public boolean hasModelOverride("
        )
        has_override = _method_body(
            model_service, "public boolean hasModelOverride(", "public void clearAll("
        )
        self.assertIn("if (playerId == null) return null;", get_override)
        self.assertIn("if (playerId == null) return false;", has_override)

    def test_legacy_skin_manager_guards_every_profile_handler(self) -> None:
        active_common_overlays = frozenset(
            MATRIX["source_overlays"]["common"].values()
        )
        if "legacy1_20_1" not in active_common_overlays:
            self.assertFalse(LEGACY_SKIN_MANAGER_MIXIN.exists())
            return

        self.assertTrue(LEGACY_SKIN_MANAGER_MIXIN.is_file())
        source = LEGACY_SKIN_MANAGER_MIXIN.read_text(encoding="utf-8")
        handlers = {
            "quickskin$wrapRegisterSkins": _method_body(
                source,
                "private void quickskin$wrapRegisterSkins(",
                "private void quickskin$overrideSkinInfo(",
            ),
            "quickskin$overrideSkinInfo": source[
                source.index("private void quickskin$overrideSkinInfo(") :
            ],
        }
        for name, body in handlers.items():
            with self.subTest(handler=name):
                self.assertEqual(1, body.count(NULL_PROFILE_GUARD))
                before_guard = body[: body.index(NULL_PROFILE_GUARD)]
                for profile_use in (
                    "profile.getId()",
                    "profile.getName()",
                    "getAppearance(",
                    "CPMLOG.",
                ):
                    self.assertNotIn(
                        profile_use,
                        before_guard,
                        f"{name} must guard the null profile id before {profile_use}",
                    )


if __name__ == "__main__":
    unittest.main()
