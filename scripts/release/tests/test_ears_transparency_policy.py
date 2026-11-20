from __future__ import annotations

import re
import unittest
from pathlib import Path

from scripts.architecture.source_inventory import java_source


ROOT = Path(__file__).resolve().parents[3]
JAVA_COMMENT = re.compile(r"/\*.*?\*/|//[^\n]*", re.DOTALL)


def code(name: str) -> str:
    return JAVA_COMMENT.sub("", java_source(name, repository=ROOT).read_text(encoding="utf-8"))


class EarsTransparencyPolicyTest(unittest.TestCase):
    """Ears reads extra data from the alpha of a skin's body, which Quick Skin must not destroy."""

    def test_a_skin_is_saved_with_its_alpha_when_only_its_display_is_opaque(self) -> None:
        # With Ears installed every skin is shown without transparency in its body. The places
        # that save a skin ask a question of their own, which Ears' presence never answers with
        # yes: flattening the saved file would erase the data Ears keeps in that alpha.
        for name in (
            "client/gui/util/SkinImporter.java",
            "client/importing/PlayerOwnSkinBootstrap.java",
        ):
            with self.subTest(source=name):
                source = code(name)
                self.assertNotIn("shouldDisableSkinTransparency(", source)
                self.assertIn("shouldFlattenImportedSkins()", source)

    def test_the_places_that_show_a_skin_ask_whether_it_is_shown_opaque(self) -> None:
        for name in (
            "client/services/LocalAssetManager.java",
            "client/storage/NetworkTextureCache.java",
            "client/gui/widget/SkinBustTextures.java",
        ):
            with self.subTest(source=name):
                source = code(name)
                self.assertIn("shouldDisableSkinTransparency()", source)
                self.assertNotIn("shouldFlattenImportedSkins(", source)

    def test_ears_shows_skins_opaque_and_never_flattens_what_is_saved(self) -> None:
        source = code("config/ClientConfig.java")
        shown = source[source.index("public boolean shouldDisableSkinTransparency()") :]
        shown = shown[: shown.index("public boolean shouldFlattenImportedSkins()")]
        self.assertIn("return isEarsInstalled();", shown)
        saved = source[source.index("public boolean shouldFlattenImportedSkins()") :]
        saved = saved[: saved.index("\n    }\n") + 1]
        self.assertRegex(saved, r"if \(isEarsInstalled\(\)\) \{\s*return false;")

    def test_the_settings_box_cannot_store_ears_choice_as_the_players(self) -> None:
        source = code("client/gui/screen/SettingsScreen.java")
        self.assertIn("disableSkinTransparencyCheckbox.active = !earsInstalled;", source)
        self.assertRegex(
            source,
            r"if \(!ClientConfig\.isEarsInstalled\(\)\) \{\s*"
            r"config\.disableSkinTransparency = disableSkinTransparencyCheckbox\.selected\(\);",
        )


if __name__ == "__main__":
    unittest.main()
