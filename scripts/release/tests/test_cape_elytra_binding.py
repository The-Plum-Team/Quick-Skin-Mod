from __future__ import annotations

import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
MATRIX = json.loads(
    (ROOT / "release" / "release-matrix.json").read_text(encoding="utf-8")
)


class CapeElytraBindingTest(unittest.TestCase):
    def test_custom_cape_is_authoritative_for_profile_elytra(self) -> None:
        canonical_bindings = {
            "MixinAbstractClientPlayer.java": (
                "elytraTexture = customCape;",
                "elytraTexture = new ClientAsset.ResourceTexture(customCape, customCape);",
                "elytraTexture = capeLoc;",
            ),
            "SkinManagerMixin.java": (
                "elytraTexture = customCape;",
                "elytraTexture = new ClientAsset.ResourceTexture(customCape, customCape);",
                "elytraTexture = capeLoc;",
            ),
            "PlayerInfoMixin.java": (
                "elytraTexture = currentCapeLocation;",
                "elytraTexture = new ClientAsset.ResourceTexture(\n"
                "                        currentCapeLocation, currentCapeLocation);",
                "elytraTexture = capeTexture;",
            ),
        }
        mixin_root = (
            ROOT / "common" / "src" / "main" / "java"
            / "com" / "quickskin" / "mod" / "mixin"
        )
        for name, required in canonical_bindings.items():
            text = (mixin_root / name).read_text(encoding="utf-8")
            with self.subTest(mixin=name):
                for binding in required:
                    self.assertIn(binding, text)

        active_common_overlays = frozenset(
            MATRIX["source_overlays"]["common"].values()
        )
        if "legacy1_20_1" in active_common_overlays:
            legacy_root = (
                ROOT / "common" / "src" / "legacy1_20_1" / "java"
                / "com" / "quickskin" / "mod" / "mixin"
            )
            legacy_targets = {
                "MixinAbstractClientPlayer.java": "getElytraTextureLocation",
                "PlayerInfoMixin.java": "getElytraLocation",
            }
            for name, target in legacy_targets.items():
                text = (legacy_root / name).read_text(encoding="utf-8")
                with self.subTest(legacy_mixin=name):
                    self.assertIn(f'method = "{target}"', text)
                    self.assertIn(
                        "cir.setReturnValue(service.getCapeLocation(",
                        text,
                    )

        scenario = (
            ROOT / "common" / "src" / "e2e" / "java"
            / "com" / "quickskin" / "mod" / "e2e" / "scenario"
            / "FullScenario.java"
        ).read_text(encoding="utf-8")
        self.assertIn("String profileElytra = VanillaShim.elytraTexture(mc.player);", scenario)
        self.assertIn('" expected custom cape " + resolved', scenario)


if __name__ == "__main__":
    unittest.main()
