from __future__ import annotations

import json
import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
MATRIX = json.loads(
    (ROOT / "release" / "release-matrix.json").read_text(encoding="utf-8")
)

PLAYER_SKIN_MIXINS = (
    "MixinAbstractClientPlayer.java",
    "PlayerInfoMixin.java",
    "SkinManagerMixin.java",
)
CUSTOM_CAPE_VARIABLES = (
    "customCape",
    "currentCapeLocation",
    "capeLoc",
    "capeLocation",
)
DIRECT_ORIGINAL_ELYTRA_ARGUMENT = re.compile(
    r"(?m)^\s*(?:original|originalSkin)\.elytra(?:Texture)?\(\),\s*$"
)
STONECUTTER_IF = re.compile(
    r"^\s*//\?\s*if\s+(?P<operator><=|>=|<|>|==|=)\s*"
    r"(?P<version>\d+(?:\.\d+)*)\s*\{\s*$"
)
STONECUTTER_ELSE_IF = re.compile(
    r"^\s*//\?\}\s*else if\s+(?P<operator><=|>=|<|>|==|=)\s*"
    r"(?P<version>\d+(?:\.\d+)*)\s*\{\s*$"
)
STONECUTTER_ELSE = re.compile(r"^\s*//\?\}\s*else\s*\{\s*$")
STONECUTTER_END = re.compile(r"^\s*//\?\}\s*$")


def _version_key(version: str) -> tuple[int, ...]:
    return tuple(int(part) for part in version.split("."))


def _matches_version(version: str, operator: str, boundary: str) -> bool:
    current = _version_key(version)
    target = _version_key(boundary)
    if operator == "<":
        return current < target
    if operator == "<=":
        return current <= target
    if operator == ">":
        return current > target
    if operator == ">=":
        return current >= target
    return current == target


def active_stonecutter_source(source: str, version: str) -> str:
    """Select active single-version Stonecutter branches for static inspection."""
    frames: list[dict[str, bool]] = []
    active_lines: list[str] = []
    for line in source.splitlines():
        if match := STONECUTTER_IF.match(line):
            parent_active = frames[-1]["active"] if frames else True
            matched = _matches_version(
                version,
                match.group("operator"),
                match.group("version"),
            )
            frames.append({
                "parent_active": parent_active,
                "matched": matched,
                "active": parent_active and matched,
            })
            continue
        if match := STONECUTTER_ELSE_IF.match(line):
            if not frames:
                raise ValueError("Stonecutter else-if without matching if")
            frame = frames[-1]
            matched = _matches_version(
                version,
                match.group("operator"),
                match.group("version"),
            )
            frame["active"] = (
                frame["parent_active"] and not frame["matched"] and matched
            )
            frame["matched"] = frame["matched"] or matched
            continue
        if STONECUTTER_ELSE.match(line):
            if not frames:
                raise ValueError("Stonecutter else without matching if")
            frame = frames[-1]
            frame["active"] = frame["parent_active"] and not frame["matched"]
            frame["matched"] = True
            continue
        if STONECUTTER_END.match(line):
            if not frames:
                raise ValueError("Stonecutter end without matching if")
            frames.pop()
            continue
        if not frames or frames[-1]["active"]:
            active_lines.append(line)
    if frames:
        raise ValueError("unterminated Stonecutter conditional")
    return "\n".join(active_lines)


def cape_elytra_binding_failures(
    source: str,
    version: str | None = None,
) -> list[str]:
    """Return deterministic policy failures for a PlayerSkin override source."""
    if version is not None:
        source = active_stonecutter_source(source, version)
    if "new PlayerSkin(" not in source or "hasCustomCape" not in source:
        return []

    failures: list[str] = []
    if DIRECT_ORIGINAL_ELYTRA_ARGUMENT.search(source):
        failures.append("a PlayerSkin constructor preserves the original Elytra directly")
    if "elytraTexture = null;" not in source:
        failures.append("a missing custom cape does not clear the profile Elytra")

    custom_cape_start = source.find("if (hasCustomCape)")
    custom_cape_end = source.find("if (anyOverride)", custom_cape_start)
    custom_cape_block = source[custom_cape_start:custom_cape_end]
    if "service.getAppearance" in custom_cape_block:
        failures.append("a pending custom cape is cleared only for explicit removal")

    for variable in CUSTOM_CAPE_VARIABLES:
        declaration = re.search(
            rf"\b(?:ResourceLocation|Identifier)\s+{variable}\b",
            source,
        )
        if declaration is None:
            continue
        binding = re.search(
            rf"elytraTexture\s*=\s*(?:new ClientAsset\.ResourceTexture\(\s*)?{variable}\b",
            source,
        )
        if binding is None:
            failures.append(f"{variable} is never bound to the profile Elytra")

    return failures


def active_player_skin_mixins(root: Path, matrix: dict) -> list[tuple[str, Path]]:
    """Resolve overlay-first mixins exactly as the versioned source sets do."""
    active: set[tuple[str, Path]] = set()
    for artifact in matrix["artifacts"]:
        version = artifact["artifact_version"]
        for module in {"common", artifact["loader"]}:
            module_root = root / module / "src"
            if not module_root.is_dir():
                continue
            overlay = matrix.get("source_overlays", {}).get(module, {}).get(version)
            overlay_root = module_root / overlay / "java" if overlay else None
            main_root = module_root / "main" / "java"
            for name in PLAYER_SKIN_MIXINS:
                matches = sorted(overlay_root.rglob(name)) if overlay_root else []
                if not matches and main_root.is_dir():
                    matches = sorted(main_root.rglob(name))
                active.update((version, match) for match in matches)
    return sorted(active)


class CapeElytraBindingTest(unittest.TestCase):
    def test_binding_policy_rejects_original_elytra_constructor_argument(self) -> None:
        source = """
            boolean hasCustomCape = true;
            ResourceLocation customCape = service.getCapeLocation(uuid);
            capeTexture = customCape;
            return new PlayerSkin(
                skinTexture,
                capeTexture,
                original.elytraTexture(),
                skinModel
            );
        """
        failures = cape_elytra_binding_failures(source)
        self.assertIn(
            "a PlayerSkin constructor preserves the original Elytra directly",
            failures,
        )
        self.assertIn(
            "a missing custom cape does not clear the profile Elytra",
            failures,
        )
        self.assertIn("customCape is never bound to the profile Elytra", failures)

    def test_binding_policy_accepts_mirrored_modern_cape(self) -> None:
        source = """
            boolean hasCustomCape = true;
            Identifier customCape = service.getCapeLocation(uuid);
            ClientAsset.Texture elytraTexture = original.elytra();
            if (customCape != null) {
                capeTexture = customCape;
                elytraTexture = new ClientAsset.ResourceTexture(customCape, customCape);
            } else {
                capeTexture = null;
                elytraTexture = null;
            }
            return new PlayerSkin(skinTexture, capeTexture, elytraTexture, skinModel);
        """
        self.assertEqual([], cape_elytra_binding_failures(source))

    def test_binding_policy_rejects_conditional_pending_clear(self) -> None:
        source = """
            boolean hasCustomCape = true;
            ResourceLocation customCape = service.getCapeLocation(uuid);
            ResourceLocation elytraTexture = original.elytraTexture();
            if (hasCustomCape) {
                if (customCape != null) {
                    capeTexture = customCape;
                    elytraTexture = customCape;
                } else {
                    PlayerAppearance appearance = service.getAppearance(uuid);
                    if (appearance != null) {
                        capeTexture = null;
                        elytraTexture = null;
                    }
                }
            }
            if (anyOverride) {
                return new PlayerSkin(skinTexture, capeTexture, elytraTexture, skinModel);
            }
        """
        self.assertIn(
            "a pending custom cape is cleared only for explicit removal",
            cape_elytra_binding_failures(source),
        )

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

        for version, mixin in active_player_skin_mixins(ROOT, MATRIX):
            text = mixin.read_text(encoding="utf-8")
            with self.subTest(active_mixin=mixin.relative_to(ROOT)):
                self.assertEqual(
                    [],
                    cape_elytra_binding_failures(text, version),
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
