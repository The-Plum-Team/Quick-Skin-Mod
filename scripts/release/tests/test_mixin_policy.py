from __future__ import annotations

import json
import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
CANONICAL_JAVA = ROOT / "common" / "src" / "main" / "java"
MATRIX = json.loads(
    (ROOT / "release" / "release-matrix.json").read_text(encoding="utf-8")
)
OVERLAY_NAMES = tuple(MATRIX["source_overlays"]["common"].values())
OVERLAY_JAVA = tuple(ROOT / "common" / "src" / name / "java" for name in OVERLAY_NAMES)
OVERLAY_RESOURCES = tuple(
    ROOT / "common" / "src" / name / "resources" for name in OVERLAY_NAMES
)
MIXIN_MARKER = re.compile(r"@Mixin\s*\(")
INJECTOR_MARKER = re.compile(r"@(Inject|ModifyArgs?|ModifyVariable|Redirect)\s*\(")
HANDLER_MARKER = re.compile(r"\b(quickskin\$[A-Za-z0-9_$]+)\s*\(")
JAVA_COMMENT = re.compile(r"//[^\n]*|/\*.*?\*/", re.DOTALL)
# A RETURN hook takes every return of its target, and another mod's transformer may add returns
# (CustomSkinLoader adds one to SkinManager.getInsecureSkinInformation on 1.20.1), so it declares
# no upper bound, which would stop the game in production; its expect still counts vanilla's.
RETURN_POINT = re.compile(r'@At\(\s*(?:value\s*=\s*)?"RETURN"')


def relative(path: Path) -> str:
    return path.relative_to(ROOT).as_posix()


def policy_id(path: Path) -> str:
    try:
        return "main:" + path.relative_to(CANONICAL_JAVA).as_posix()
    except ValueError:
        for source_root in OVERLAY_JAVA:
            try:
                return "overlay:" + path.relative_to(source_root).as_posix()
            except ValueError:
                continue
    raise AssertionError(f"Mixin source is outside an active source root: {path}")


CRITICAL_MIXINS = {
    "main:com/quickskin/mod/mixin/CapeLayerMixin.java",
    "main:com/quickskin/mod/mixin/ItemInHandRendererMixin.java",
    "main:com/quickskin/mod/mixin/MixinAbstractClientPlayer.java",
    "main:com/quickskin/mod/mixin/PlayerInfoMixin.java",
    "main:com/quickskin/mod/mixin/PlayerRendererMixin.java",
    "main:com/quickskin/mod/mixin/SkinManagerMixin.java",
    "overlay:com/quickskin/mod/mixin/MixinAbstractClientPlayer.java",
    "overlay:com/quickskin/mod/mixin/PlayerInfoMixin.java",
}

# These are real Quick Skin features, but losing one hook should degrade only that rendering or UI
# enhancement in production. Packaged E2E enables Mixin's debug count and turns expect=1 into a
# deterministic compatibility gate.
DEGRADABLE_MIXINS = {
    "main:com/quickskin/mod/mixin/GuiSkinRendererMixin.java",
    "main:com/quickskin/mod/mixin/PanoramaRendererMixin.java",
    # Nicknames (ADR 0015): a hook that fails leaves account names showing, never anything worse.
    "main:com/quickskin/mod/mixin/nickname/PlayerDisplayNameMixin.java",
    "main:com/quickskin/mod/mixin/nickname/PlayerListJoinMessageMixin.java",
    "main:com/quickskin/mod/mixin/nickname/ServerGamePacketListenerLeaveMessageMixin.java",
    "main:com/quickskin/mod/mixin/nickname/ServerPlayerTabNameMixin.java",
    # Hidden armour (ADR 0017): a hook that fails leaves the armour showing, as before.
    "main:com/quickskin/mod/mixin/armor/LocalArmorLayerMixin.java",
    "main:com/quickskin/mod/mixin/armor/LocalArmorRenderMixin.java",
    "main:com/quickskin/mod/mixin/armor/LocalElytraLayerMixin.java",
    "main:com/quickskin/mod/mixin/armor/LocalHeadLayerMixin.java",
    "main:com/quickskin/mod/mixin/armor/PreviewArmorPoseMixin.java",
    "main:com/quickskin/mod/mixin/armor/ServerEquipmentFilterMixin.java",
    # Forge 1.20.1 only: a hook that fails leaves other players seeing a hidden cosmetic piece.
    "overlay:com/quickskin/mod/mixin/armor/ConnectionCosmeticArmorFilterMixin.java",
    "main:com/quickskin/mod/mixin/PreviewEquipmentMixin.java",
    "main:com/quickskin/mod/mixin/PreviewSkinSwapPoseMixin.java",
    # 1.20.1 only: without it the notice for players without Quick Skin is in English.
    "main:com/quickskin/mod/mixin/ServerPlayerLanguageMixin.java",
    # The favourites fan: a hook that fails leaves the camera turning while the fan is open, or
    # Esc opening the pause menu over it.
    "main:com/quickskin/mod/mixin/input/MouseTurnMixin.java",
    "main:com/quickskin/mod/mixin/input/PauseGameMixin.java",
    # The menus: a hook that fails leaves the game's toasts showing over them, as before.
    "main:com/quickskin/mod/mixin/menu/MenuToastsMixin.java",
    # The immersive view (one canonical copy each, empty on the versions a class does not serve). A
    # camera, frame, screen or mouse hook that fails leaves the skin menu flat over the world, or
    # the camera at the eyes; a pose hook leaves the player in the world in the game's own pose,
    # with the cape and wings where a straight body would hang them.
    "main:com/quickskin/mod/mixin/immersive/ImmersiveCameraMixin.java",
    "main:com/quickskin/mod/mixin/immersive/ImmersiveCapeLayerMixin.java",
    "main:com/quickskin/mod/mixin/immersive/ImmersiveElytraLayerMixin.java",
    "main:com/quickskin/mod/mixin/immersive/ImmersiveGameRendererMixin.java",
    "main:com/quickskin/mod/mixin/immersive/ImmersiveGuiMixin.java",
    "main:com/quickskin/mod/mixin/immersive/ImmersiveLivingRendererMixin.java",
    "main:com/quickskin/mod/mixin/immersive/ImmersiveMinecraftMixin.java",
    "main:com/quickskin/mod/mixin/immersive/ImmersiveMouseMixin.java",
    "main:com/quickskin/mod/mixin/immersive/ImmersivePicturePassMixin.java",
    "main:com/quickskin/mod/mixin/immersive/ImmersivePlayerModelMixin.java",
    "main:com/quickskin/mod/mixin/immersive/ImmersiveScreenMixin.java",
    "main:com/quickskin/mod/mixin/immersive/ImmersivePlayerLightMixin.java",
    "main:com/quickskin/mod/mixin/immersive/ImmersiveLevelLightsMixin.java",
    "main:com/quickskin/mod/mixin/immersive/ImmersiveLightPoseMixin.java",
    "main:com/quickskin/mod/mixin/immersive/LegacyOpeningGameMixin.java",
    "main:com/quickskin/mod/mixin/immersive/LegacyOpeningLevelMixin.java",
    "main:com/quickskin/mod/mixin/immersive/LegacyOpeningPlayerMixin.java",
    "main:com/quickskin/mod/mixin/immersive/LegacyOpeningShadowMixin.java",
    "main:com/quickskin/mod/mixin/immersive/ImmersiveTerrainPipelineMixin.java",
    "main:com/quickskin/mod/mixin/immersive/ImmersiveTerrainShaderMixin.java",
    "main:com/quickskin/mod/mixin/immersive/ImmersiveTerrainChunksMixin.java",
    "main:com/quickskin/mod/mixin/immersive/ImmersiveTerrainProgramsMixin.java",
    "main:com/quickskin/mod/mixin/immersive/ImmersiveBlockEntityLightMixin.java",
    "main:com/quickskin/mod/mixin/immersive/ImmersiveBlockEntityMaterialMixin.java",
    "main:com/quickskin/mod/mixin/immersive/ImmersiveSignTextMixin.java",
    "main:com/quickskin/mod/mixin/immersive/ImmersiveWorldViewMixin.java",
    # A hook that fails leaves the player in the world wearing their own cape and wings while the
    # cape menu or the cape editor floats round them.
    "main:com/quickskin/mod/mixin/immersive/ImmersiveLookElytraMixin.java",
    "main:com/quickskin/mod/mixin/immersive/ImmersiveLookRendererMixin.java",
    # The immersive view's armour on the real player: a hook that fails leaves the pieces
    # unclickable on the player, or one another mod covers pointed out as worn.
    "main:com/quickskin/mod/mixin/immersive/ImmersiveArmorPlayerMixin.java",
    "main:com/quickskin/mod/mixin/immersive/ImmersiveArmorRendererMixin.java",
}

# Like the nickname mixins, NeoForge loads the hidden-armour mixins from their own config.
ARMOR_MIXIN_PACKAGE = "com.quickskin.mod.mixin.armor"
NEOFORGE_ARMOR_CONFIG = ROOT / "neoforge" / "src" / "main" / "resources" / "quickskin-armor.mixins.json"

# And the favourites fan's input hooks from their own.
INPUT_MIXIN_PACKAGE = "com.quickskin.mod.mixin.input"
NEOFORGE_INPUT_CONFIG = ROOT / "neoforge" / "src" / "main" / "resources" / "quickskin-input.mixins.json"

# And the menus' hook on the game's toasts from its own.
MENU_MIXIN_PACKAGE = "com.quickskin.mod.mixin.menu"
NEOFORGE_MENU_CONFIG = ROOT / "neoforge" / "src" / "main" / "resources" / "quickskin-menu.mixins.json"

# And the immersive view's hooks from their own.
IMMERSIVE_MIXIN_PACKAGE = "com.quickskin.mod.mixin.immersive"
NEOFORGE_IMMERSIVE_CONFIG = (
    ROOT / "neoforge" / "src" / "main" / "resources" / "quickskin-immersive.mixins.json"
)
# A Stonecutter guard opens with `//? if`, closes with `//?}` and goes on with `//?} else`.
GUARD_OPEN = re.compile(r"^\s*//\?\s*if\b")
GUARD_CLOSE = re.compile(r"^\s*//\?\s*}")
GUARD_ELSE = re.compile(r"^\s*//\?\s*}\s*else\b")
TYPE_DECLARATION = re.compile(r"^public\s+(?:abstract\s+)?(?:class|interface)\s+(\w+)")

# NeoForge never reads quickskin.mixins.json, so it loads the nickname mixins from their own config.
NICKNAME_MIXIN_PACKAGE = "com.quickskin.mod.mixin.nickname"
NEOFORGE_NICKNAME_CONFIG = ROOT / "neoforge" / "src" / "main" / "resources" / "quickskin-nicknames.mixins.json"
NEOFORGE_MOD_TOMLS = (
    ROOT / "neoforge" / "src" / "main" / "resources" / "META-INF" / "neoforge.mods.toml",
    ROOT / "neoforge" / "src" / "legacy26_1" / "resources" / "META-INF" / "neoforge.mods.toml",
)

# Third-party integrations remain fail-local in production. Their targets are either selected by
# the optional-config plugin or are harmless vanilla interception points whose handlers no-op when
# the integration is absent.
OPTIONAL_MIXINS = {
    "main:com/quickskin/mod/mixin/compat/EtfPlayerTextureMixin.java",
    "main:com/quickskin/mod/mixin/compat/CpmModelDefinitionLoaderMixin.java",
    "main:com/quickskin/mod/mixin/compat/TaczPreviewAnimationMixin.java",
    "main:com/quickskin/mod/mixin/compat/CpmRenderDepthMixin.java",
    "main:com/quickskin/mod/mixin/compat/CpmSubmitCollectorMixin.java",
    "main:com/quickskin/mod/mixin/compat/EarsFiveBuzzMixin.java",
    "main:com/quickskin/mod/mixin/compat/EarsFiveMixin.java",
    "main:com/quickskin/mod/mixin/compat/EarsLayerRendererMixin.java",
    "main:com/quickskin/mod/mixin/compat/EarsModMixin.java",
    "main:com/quickskin/mod/mixin/compat/RealCameraTextureIdMixin.java",
    "main:com/quickskin/mod/mixin/compat/IrisTerrainProgramMixin.java",
    "main:com/quickskin/mod/mixin/compat/IrisTerrainShaderMixin.java",
    "main:com/quickskin/mod/mixin/compat/SodiumPlayerLightMixin.java",
    "main:com/quickskin/mod/mixin/compat/SodiumTerrainPassMixin.java",
    "main:com/quickskin/mod/mixin/compat/SodiumTerrainProgramMixin.java",
    "main:com/quickskin/mod/mixin/compat/SodiumTerrainShaderMixin.java",
    "main:com/quickskin/mod/mixin/compat/JourneyMapIgnSkinMixin.java",
    "main:com/quickskin/mod/mixin/compat/JourneyMapTextureCacheMixin.java",
    "main:com/quickskin/mod/mixin/compat/CosmeticArmorRenderMixin.java",
    "overlay:com/quickskin/mod/mixin/MixinSkinManager.java",
    "overlay:com/quickskin/mod/mixin/compat/ReplayModCompatMixin.java",
}

# Server-side accessors used to refresh a player's signed account skin for unmodded observers.
# They inject nothing; every field they name was checked in each matrix target's mapped JAR.
ACCESSOR_ONLY_MIXINS: set[str] = {
    "main:com/quickskin/mod/mixin/ChunkMapAccessor.java",
    "main:com/quickskin/mod/mixin/PlayerGameProfileAccessor.java",
    "main:com/quickskin/mod/mixin/TrackedEntityAccessor.java",
    # The skin menu's preview borrows the player renderers' equipment layers (ADR 0017).
    "main:com/quickskin/mod/mixin/armor/EntityRenderDispatcherPlayersMixin.java",
    "main:com/quickskin/mod/mixin/armor/LivingEntityRendererLayersMixin.java",
    # The immersive view's accessors, empty on the versions that do not need them.
    "main:com/quickskin/mod/mixin/immersive/ImmersiveLightDirectionsAccessor.java",
    "main:com/quickskin/mod/mixin/immersive/ImmersiveGameRendererAccessor.java",
    "main:com/quickskin/mod/mixin/immersive/ImmersiveMouseAccessor.java",
    "main:com/quickskin/mod/mixin/immersive/ImmersiveRenderStateAccessor.java",
    "main:com/quickskin/mod/mixin/immersive/ImmersiveTargetAccessor.java",
}

# CPM changed the call made by playerRenderPre. Both optional injection points are kept so one
# source supports both eras, which means neither alternative can truthfully declare expect=1.
ALTERNATIVE_HOOKS = {
    # Before 1.21.2 vanilla and Forge draw an armour piece through the six-argument overload and
    # NeoForge through one that adds the animation values; each loader calls exactly one of them.
    (
        "main:com/quickskin/mod/mixin/armor/LocalArmorLayerMixin.java",
        "quickskin$wearNoHiddenPiece",
    ),
    (
        "main:com/quickskin/mod/mixin/armor/LocalArmorLayerMixin.java",
        "quickskin$wearNoHiddenPieceWithAnimation",
    ),
    (
        "main:com/quickskin/mod/mixin/compat/CpmRenderDepthMixin.java",
        "quickskin$cpmLegacyPlayerRenderStart",
    ),
    (
        "main:com/quickskin/mod/mixin/compat/CpmRenderDepthMixin.java",
        "quickskin$cpmModernPlayerRenderStart",
    ),
    (
        "main:com/quickskin/mod/mixin/compat/CpmSubmitCollectorMixin.java",
        "quickskin$skipStaleExtractedModel",
    ),
    # Cosmetic Armor Reworked's original and its NeoForge fork read the slot with Mojang names,
    # its Fabric fork with intermediary ones; a runtime takes exactly one of the two.
    (
        "main:com/quickskin/mod/mixin/compat/CosmeticArmorRenderMixin.java",
        "quickskin$cosmeticUnlessHidden",
    ),
    (
        "main:com/quickskin/mod/mixin/compat/CosmeticArmorRenderMixin.java",
        "quickskin$cosmeticUnlessHiddenIntermediary",
    ),
}

# Audited vanilla bytecode multiplicities. Before 1.21.2 renderHand requests two buffers (arm and
# sleeve); 1.21.2 through 1.21.8 make one immediate arm draw. The collector used from 1.21.9 onward
# is deliberately not intercepted. SkinManager 1.20.1 has two RETURN opcodes in its one target method.
# TaCZ 1.1.8-hotfix asks for the camera type once in each of its four gun-event handlers; the one
# redirect names all four and takes the first call of each, so four is also the most it can match.
INJECTION_COUNT_OVERRIDES = {
    ("main:com/quickskin/mod/mixin/compat/SodiumPlayerLightMixin.java",
     "quickskin$preparePlayerNormals"): {2},
    # Audited mapped Minecraft JARs and their Forge/NeoForge builds: placeNewPlayer translates the
    # join message twice, once in each branch (the usual name and one renamed since last time).
    (
        "main:com/quickskin/mod/mixin/nickname/PlayerListJoinMessageMixin.java",
        "quickskin$nameWithAccountName",
    ): {2},
    # Audited mapped Minecraft JARs of every matrix target: the panorama's render method (from 26.1
    # extractRenderState) reads its spin field exactly twice, to advance it and to draw it.
    (
        "main:com/quickskin/mod/mixin/PanoramaRendererMixin.java",
        "quickskin$sharedPanoramaSpin",
    ): {2},
    (
        "main:com/quickskin/mod/mixin/compat/TaczPreviewAnimationMixin.java",
        "quickskin$firstPersonUnlessPreviewed",
    ): {4},
    (
        "main:com/quickskin/mod/mixin/ItemInHandRendererMixin.java",
        "quickskin$redirectRenderHandBuffer",
    ): {1, 2},
    (
        "overlay:com/quickskin/mod/mixin/MixinSkinManager.java",
        "quickskin$overrideSkinInfo",
    ): {2},
    (
        "main:com/quickskin/mod/mixin/SkinManagerMixin.java",
        "quickskin$modifyInsecureSkinLegacy",
    ): {2},
    # Audited mapped Minecraft JARs: createLookup has three returns from 1.21.9 onward.
    # Its async get method has two returns from 1.21.9 onward.
    (
        "main:com/quickskin/mod/mixin/SkinManagerMixin.java",
        "quickskin$modifyCreateLookup",
    ): {3},
    (
        "main:com/quickskin/mod/mixin/SkinManagerMixin.java",
        "quickskin$modifyGet",
    ): {2},
}

ALLOW_COUNT_OVERRIDES = {
    # Audited builds: the original reads a slot three times (1.20.1, 1.21.1) or once (from 1.21.3),
    # its fork four times, twice in handlePreRenderPlayer.
    (
        "main:com/quickskin/mod/mixin/compat/CosmeticArmorRenderMixin.java",
        "quickskin$cosmeticUnlessHidden",
    ): {4},
    (
        "main:com/quickskin/mod/mixin/compat/CosmeticArmorRenderMixin.java",
        "quickskin$cosmeticUnlessHiddenIntermediary",
    ): {4},
    (
        "main:com/quickskin/mod/mixin/compat/CpmSubmitCollectorMixin.java",
        "quickskin$skipStaleExtractedModel",
    ): {8},
}


def assignment_values(context: str, name: str) -> set[int]:
    context = JAVA_COMMENT.sub("", context)
    return {
        int(value)
        for value in re.findall(rf"\b{re.escape(name)}\s*=\s*(-?\d+)", context)
    }


class MixinPolicyTest(unittest.TestCase):
    def mixin_sources(self) -> tuple[Path, ...]:
        sources: list[Path] = []
        for source_root in (CANONICAL_JAVA, *OVERLAY_JAVA):
            for path in source_root.rglob("*.java"):
                if MIXIN_MARKER.search(path.read_text(encoding="utf-8")):
                    sources.append(path)
        return tuple(sorted(sources))

    def configs_named(self, name: str) -> tuple[Path, ...]:
        canonical = ROOT / "common" / "src" / "main" / "resources" / name
        overrides = tuple(root / name for root in OVERLAY_RESOURCES if (root / name).is_file())
        return (canonical, *overrides)

    def test_every_mixin_source_has_an_explicit_policy(self) -> None:
        classified = (
            CRITICAL_MIXINS
            | DEGRADABLE_MIXINS
            | OPTIONAL_MIXINS
            | ACCESSOR_ONLY_MIXINS
        )
        self.assertFalse(CRITICAL_MIXINS & DEGRADABLE_MIXINS)
        self.assertFalse(CRITICAL_MIXINS & OPTIONAL_MIXINS)
        self.assertFalse(DEGRADABLE_MIXINS & OPTIONAL_MIXINS)
        for path in self.mixin_sources():
            with self.subTest(source=relative(path)):
                self.assertIn(policy_id(path), classified)

    def test_every_injector_declares_bounded_counts(self) -> None:
        injector_sources = CRITICAL_MIXINS | DEGRADABLE_MIXINS | OPTIONAL_MIXINS
        for path in self.mixin_sources():
            source_name = policy_id(path)
            text = path.read_text(encoding="utf-8")
            annotations = list(INJECTOR_MARKER.finditer(text))
            if source_name in ACCESSOR_ONLY_MIXINS:
                self.assertFalse(annotations, relative(path))
                continue
            self.assertIn(source_name, injector_sources)
            self.assertTrue(annotations, source_name)
            for annotation in annotations:
                handler = HANDLER_MARKER.search(text, annotation.end())
                self.assertIsNotNone(handler, f"{source_name}: injector has no handler")
                assert handler is not None
                handler_name = handler.group(1)
                context = text[annotation.start() : handler.start()]
                expected_require = 1 if source_name in CRITICAL_MIXINS else 0
                expected_counts = INJECTION_COUNT_OVERRIDES.get(
                    (source_name, handler_name), {1}
                )
                with self.subTest(source=source_name, handler=handler_name):
                    self.assertEqual(assignment_values(context, "require"), {expected_require})
                    if RETURN_POINT.search(JAVA_COMMENT.sub("", context)):
                        self.assertEqual(assignment_values(context, "allow"), set())
                    else:
                        self.assertEqual(
                            assignment_values(context, "allow"),
                            ALLOW_COUNT_OVERRIDES.get((source_name, handler_name), expected_counts),
                        )
                    if (source_name, handler_name) in ALTERNATIVE_HOOKS:
                        self.assertEqual(assignment_values(context, "expect"), {0})
                    else:
                        self.assertEqual(assignment_values(context, "expect"), expected_counts)

    def test_core_and_optional_configs_have_different_failure_policies(self) -> None:
        core_configs = self.configs_named("quickskin.mixins.json")
        optional_configs = self.configs_named("quickskin-ears.mixins.json")

        for path in core_configs:
            with self.subTest(config=relative(path)):
                config = json.loads(path.read_text(encoding="utf-8"))
                self.assertIs(config["required"], True)
                self.assertEqual(config["injectors"]["defaultRequire"], 1)

        for path in optional_configs:
            with self.subTest(config=relative(path)):
                config = json.loads(path.read_text(encoding="utf-8"))
                self.assertIs(config["required"], False)
                self.assertEqual(config["injectors"]["defaultRequire"], 0)
                self.assertIn("RealCameraTextureIdMixin", config["client"])
                self.assertIn("JourneyMapIgnSkinMixin", config["client"])
                self.assertIn("JourneyMapTextureCacheMixin", config["client"])

    def test_configured_mixins_exist_and_dynamic_mixins_are_audited(self) -> None:
        configs = self.configs_named("quickskin.mixins.json") + self.configs_named(
            "quickskin-ears.mixins.json"
        )
        configured: set[str] = set()
        for path in configs:
            config = json.loads(path.read_text(encoding="utf-8"))
            configured.update(name.rsplit(".", 1)[-1] for name in config.get("client", []))
            configured.update(name.rsplit(".", 1)[-1] for name in config.get("mixins", []))

        plugin = (
            ROOT
            / "common"
            / "src"
            / "main"
            / "java"
            / "com"
            / "quickskin"
            / "mod"
            / "mixin"
            / "compat"
            / "EarsMixinPlugin.java"
        ).read_text(encoding="utf-8")
        self.assertIn('"CpmModelDefinitionLoaderMixin"', plugin)
        self.assertIn('"CpmRenderDepthMixin"', plugin)
        self.assertIn('"RealCameraTextureIdMixin"', plugin)
        self.assertIn('"JourneyMapIgnSkinMixin"', plugin)
        self.assertIn('"JourneyMapTextureCacheMixin"', plugin)
        configured.add("CpmRenderDepthMixin")

        source_classes = {source.stem for source in self.mixin_sources()}
        self.assertTrue(configured <= source_classes)

    def test_third_party_mixins_use_the_optional_config(self) -> None:
        source_roots = (CANONICAL_JAVA, *OVERLAY_JAVA)
        resource_roots = (
            ROOT / "common" / "src" / "main" / "resources",
            *OVERLAY_RESOURCES,
        )
        canonical_resources = resource_roots[0]
        for source_root, resource_root in zip(source_roots, resource_roots, strict=True):
            compat_root = source_root / "com" / "quickskin" / "mod" / "mixin" / "compat"
            compat_mixins = {
                path.stem
                for path in compat_root.glob("*.java")
                if MIXIN_MARKER.search(path.read_text(encoding="utf-8"))
            }
            core_path = resource_root / "quickskin.mixins.json"
            optional_path = resource_root / "quickskin-ears.mixins.json"
            if not core_path.is_file():
                core_path = canonical_resources / core_path.name
            if not optional_path.is_file():
                optional_path = canonical_resources / optional_path.name
            core = json.loads(core_path.read_text(encoding="utf-8"))
            optional = json.loads(optional_path.read_text(encoding="utf-8"))
            core_names = {name.rsplit(".", 1)[-1] for name in core["client"]}
            optional_names = {name.rsplit(".", 1)[-1] for name in optional["client"]}
            dynamic_names = {
                "CpmModelDefinitionLoaderMixin",
                "CpmRenderDepthMixin",
            }
            with self.subTest(resources=relative(resource_root)):
                self.assertTrue(compat_mixins.isdisjoint(core_names))
                self.assertTrue(compat_mixins <= optional_names | dynamic_names)

    def test_neoforge_loads_the_nickname_mixins_every_core_config_lists(self) -> None:
        nickname_sources = {
            path.stem
            for path in (CANONICAL_JAVA / "com/quickskin/mod/mixin/nickname").glob("*.java")
            if MIXIN_MARKER.search(path.read_text(encoding="utf-8"))
        }
        self.assertTrue(nickname_sources)
        for path in self.configs_named("quickskin.mixins.json"):
            with self.subTest(config=relative(path)):
                config = json.loads(path.read_text(encoding="utf-8"))
                listed = {
                    name.split(".", 1)[1]
                    for name in config["mixins"]
                    if name.startswith("nickname.")
                }
                self.assertEqual(listed, nickname_sources)
        neoforge = json.loads(NEOFORGE_NICKNAME_CONFIG.read_text(encoding="utf-8"))
        self.assertEqual(neoforge["package"], NICKNAME_MIXIN_PACKAGE)
        self.assertIs(neoforge["required"], True)
        self.assertEqual(set(neoforge["mixins"]), nickname_sources)
        self.assertNotIn("client", neoforge)
        for toml in NEOFORGE_MOD_TOMLS:
            with self.subTest(toml=relative(toml)):
                self.assertIn(
                    'config = "quickskin-nicknames.mixins.json"', toml.read_text(encoding="utf-8")
                )

    def test_every_loader_loads_the_armor_mixins(self) -> None:
        def armor_mixins(java_root: Path) -> set[str]:
            return {
                path.stem
                for path in (java_root / "com/quickskin/mod/mixin/armor").glob("*.java")
                if MIXIN_MARKER.search(path.read_text(encoding="utf-8"))
            }

        armor_sources = armor_mixins(CANONICAL_JAVA)
        self.assertTrue(armor_sources)
        for path in self.configs_named("quickskin.mixins.json"):
            with self.subTest(config=relative(path)):
                config = json.loads(path.read_text(encoding="utf-8"))
                listed = {
                    name.split(".", 1)[1]
                    for name in config["mixins"] + config["client"]
                    if name.startswith("armor.")
                }
                # An overlay's config also lists the armour mixins only that overlay has
                overlay_java = path.parent.parent / "java"
                expected = armor_sources | (
                    armor_mixins(overlay_java) if overlay_java in OVERLAY_JAVA else set()
                )
                self.assertEqual(listed, expected)
                self.assertIn("armor.ServerEquipmentFilterMixin", config["mixins"])
        neoforge = json.loads(NEOFORGE_ARMOR_CONFIG.read_text(encoding="utf-8"))
        self.assertEqual(neoforge["package"], ARMOR_MIXIN_PACKAGE)
        self.assertIs(neoforge["required"], True)
        self.assertEqual(set(neoforge["mixins"]) | set(neoforge["client"]), armor_sources)
        self.assertEqual(neoforge["mixins"], ["ServerEquipmentFilterMixin"])
        for toml in NEOFORGE_MOD_TOMLS:
            with self.subTest(toml=relative(toml)):
                self.assertIn('config = "quickskin-armor.mixins.json"', toml.read_text(encoding="utf-8"))

    def test_neoforge_preview_mixin_matches_the_common_copy(self) -> None:
        # NeoForge never loads the common mixin config, so its own GuiSkinRendererMixin is the one
        # that draws the skin menu's PiP preview there. A fix made in one copy only (3D Skin
        # Layers' injected meshes, #1403) leaves NeoForge drawing the old way.
        def body(path: Path) -> str:
            source = path.read_text(encoding="utf-8")
            source = re.sub(r"^package [\w.]+;$", "package _;", source, count=1, flags=re.MULTILINE)
            start = source.index("/**\n * ")
            end = source.index("*/", start) + 2
            return source[:start] + source[end:]

        common = ROOT / "common/src/main/java/com/quickskin/mod/mixin/GuiSkinRendererMixin.java"
        neoforge = ROOT / "neoforge/src/main/java/com/quickskin/mod/neoforge/mixin/GuiSkinRendererMixin.java"
        self.assertEqual(body(common), body(neoforge))

    def test_every_loader_loads_the_input_mixins(self) -> None:
        input_sources = {
            path.stem
            for path in (CANONICAL_JAVA / "com/quickskin/mod/mixin/input").glob("*.java")
            if MIXIN_MARKER.search(path.read_text(encoding="utf-8"))
        }
        self.assertTrue(input_sources)
        for path in self.configs_named("quickskin.mixins.json"):
            with self.subTest(config=relative(path)):
                config = json.loads(path.read_text(encoding="utf-8"))
                listed = {name.split(".", 1)[1] for name in config["client"] if name.startswith("input.")}
                self.assertEqual(listed, input_sources)
        neoforge = json.loads(NEOFORGE_INPUT_CONFIG.read_text(encoding="utf-8"))
        self.assertEqual(neoforge["package"], INPUT_MIXIN_PACKAGE)
        self.assertEqual(set(neoforge["client"]), input_sources)
        self.assertNotIn("mixins", neoforge)
        for toml in NEOFORGE_MOD_TOMLS:
            with self.subTest(toml=relative(toml)):
                self.assertIn('config = "quickskin-input.mixins.json"', toml.read_text(encoding="utf-8"))

    def test_every_loader_loads_the_immersive_mixins(self) -> None:
        # One config lists the immersive mixins for every NeoForge version, and the common configs
        # list them for every Fabric version and Forge 1.20.1. A listed class missing on one version
        # stops the game there ("specified mixin was not found"), so each mixin has one canonical
        # copy, never an overlay one, and its type is declared outside every Stonecutter guard:
        # a version it does not hook yet keeps it, empty.
        immersive_root = CANONICAL_JAVA / "com/quickskin/mod/mixin/immersive"
        immersive_sources = {
            path.stem
            for path in immersive_root.glob("*.java")
            if MIXIN_MARKER.search(path.read_text(encoding="utf-8"))
        }
        self.assertTrue(immersive_sources)
        for overlay_java in OVERLAY_JAVA:
            with self.subTest(overlay=relative(overlay_java)):
                self.assertFalse(list((overlay_java / "com/quickskin/mod/mixin/immersive").glob("*.java")))
        for path in sorted(immersive_root.glob("*.java")):
            with self.subTest(source=relative(path)):
                depth = 0
                declared = None
                for line in path.read_text(encoding="utf-8").splitlines():
                    if GUARD_ELSE.match(line):
                        continue
                    if GUARD_CLOSE.match(line):
                        depth -= 1
                        continue
                    if GUARD_OPEN.match(line):
                        depth += 1
                        continue
                    match = TYPE_DECLARATION.match(line)
                    if match:
                        declared = (match.group(1), depth)
                        break
                self.assertEqual(declared, (path.stem, 0))
        for path in self.configs_named("quickskin.mixins.json"):
            with self.subTest(config=relative(path)):
                config = json.loads(path.read_text(encoding="utf-8"))
                listed = {
                    name.split(".", 1)[1]
                    for name in config["client"]
                    if name.startswith("immersive.")
                }
                self.assertEqual(listed, immersive_sources)
                self.assertFalse([name for name in config["mixins"] if name.startswith("immersive.")])
        neoforge = json.loads(NEOFORGE_IMMERSIVE_CONFIG.read_text(encoding="utf-8"))
        self.assertEqual(neoforge["package"], IMMERSIVE_MIXIN_PACKAGE)
        self.assertIs(neoforge["required"], True)
        self.assertEqual(neoforge["injectors"]["defaultRequire"], 0)
        # The immersive hooks share methods with the common config's (PreviewSkinSwapPoseMixin on
        # the player and cape models): the same priority keeps them in the same order against
        # other mods' hooks on every loader.
        common_priorities = {
            json.loads(path.read_text(encoding="utf-8")).get("priority")
            for path in self.configs_named("quickskin.mixins.json")
        }
        self.assertEqual(common_priorities, {2000})
        self.assertEqual(neoforge.get("priority"), 2000)
        self.assertEqual(set(neoforge["client"]), immersive_sources)
        self.assertNotIn("mixins", neoforge)
        for toml in NEOFORGE_MOD_TOMLS:
            with self.subTest(toml=relative(toml)):
                self.assertIn(
                    'config = "quickskin-immersive.mixins.json"', toml.read_text(encoding="utf-8")
                )

    def test_immersive_guard_blocks_never_hold_only_comments(self) -> None:
        # The immersive files keep an empty guarded block per version family for the ports to fill.
        # Stonecutter takes a block that holds nothing but // lines for commented-out code and
        # uncomments it where the block is active, which breaks that version's build: an empty
        # block stays empty.
        line_comment = re.compile(r"^\s*//(?!\?)")
        sources = sorted((CANONICAL_JAVA / "com/quickskin/mod/mixin/immersive").glob("*.java")) + [
            ROOT / "modules/preview-rendering/src/main/java/com/quickskin/mod/client/rendering" / name
            for name in ("WorldPose.java", "WorldArmor.java", "WorldLook.java")
        ] + [
            ROOT / "modules/ui-foundation/src/main/java/com/quickskin/mod/client/immersive" / name
            for name in ("ImmersiveSurface.java", "ImmersiveMode.java")
        ]
        for path in sources:
            with self.subTest(source=relative(path)):
                depth = 0
                block: list[str] = []
                comment_only: list[str] = []
                for line in path.read_text(encoding="utf-8").splitlines() + ["//?}"]:
                    opens = GUARD_OPEN.match(line)
                    goes_on = GUARD_ELSE.match(line)
                    closes = GUARD_CLOSE.match(line) and not goes_on
                    if opens or goes_on or closes:
                        if depth > 0 and any(line_comment.match(kept) for kept in block) and all(
                            line_comment.match(kept) or not kept.strip() for kept in block
                        ):
                            comment_only.append(block[0].strip())
                        block = []
                        depth += 1 if opens else -1 if closes else 0
                    else:
                        block.append(line)
                self.assertEqual(comment_only, [])

    def test_every_loader_loads_the_menu_mixins(self) -> None:
        menu_sources = {
            path.stem
            for path in (CANONICAL_JAVA / "com/quickskin/mod/mixin/menu").glob("*.java")
            if MIXIN_MARKER.search(path.read_text(encoding="utf-8"))
        }
        self.assertTrue(menu_sources)
        for path in self.configs_named("quickskin.mixins.json"):
            with self.subTest(config=relative(path)):
                config = json.loads(path.read_text(encoding="utf-8"))
                listed = {name.split(".", 1)[1] for name in config["client"] if name.startswith("menu.")}
                self.assertEqual(listed, menu_sources)
        neoforge = json.loads(NEOFORGE_MENU_CONFIG.read_text(encoding="utf-8"))
        self.assertEqual(neoforge["package"], MENU_MIXIN_PACKAGE)
        self.assertEqual(set(neoforge["client"]), menu_sources)
        self.assertNotIn("mixins", neoforge)
        for toml in NEOFORGE_MOD_TOMLS:
            with self.subTest(toml=relative(toml)):
                self.assertIn('config = "quickskin-menu.mixins.json"', toml.read_text(encoding="utf-8"))

    def test_menu_toasts_are_held_and_not_only_hidden(self) -> None:
        # From 1.21.2 the game advances its toasts in update(), apart from drawing them: a hook on
        # the draw alone would let a toast run out its time behind the menu and never be seen.
        source = JAVA_COMMENT.sub(
            "",
            (CANONICAL_JAVA / "com/quickskin/mod/mixin/menu/MenuToastsMixin.java").read_text(
                encoding="utf-8"
            ),
        )
        self.assertEqual(
            set(re.findall(r'method = "(\w+)"', source)),
            {"update", "render", "extractRenderState"},
        )
        self.assertEqual(source.count("MenuGuiScale.isScaled(GuiCompat.currentScreen())"), 2)

    def test_neoforge_loads_every_server_mixin_of_its_targets(self) -> None:
        # NeoForge never reads quickskin.mixins.json, so each server mixin its targets' common
        # config lists must come from a config a NeoForge mods.toml declares; otherwise a cast to
        # an accessor (ADR 0012's ChunkMapAccessor, for one) throws on every NeoForge server.
        neoforge_versions = {
            artifact["artifact_version"]
            for artifact in MATRIX["artifacts"]
            if artifact["loader"] == "neoforge"
        }
        self.assertTrue(neoforge_versions)
        routes = MATRIX["source_overlays"]["common"]
        expected: set[str] = set()
        for version in neoforge_versions:
            overlay = routes.get(version)
            root = ROOT / "common" / "src" / (overlay or "main") / "resources"
            path = root / "quickskin.mixins.json"
            if not path.is_file():
                path = ROOT / "common" / "src" / "main" / "resources" / "quickskin.mixins.json"
            config = json.loads(path.read_text(encoding="utf-8"))
            expected |= {f"{config['package']}.{name}" for name in config["mixins"]}
        self.assertIn("com.quickskin.mod.mixin.ChunkMapAccessor", expected)
        resources = ROOT / "neoforge" / "src" / "main" / "resources"
        for toml in NEOFORGE_MOD_TOMLS:
            with self.subTest(toml=relative(toml)):
                declared = re.findall(r'^config = "([^"]+)"$', toml.read_text(encoding="utf-8"), re.M)
                loaded: set[str] = set()
                for name in declared:
                    candidates = (
                        toml.parents[1] / name,
                        resources / name,
                        ROOT / "common" / "src" / "main" / "resources" / name,
                    )
                    path = next((c for c in candidates if c.is_file()), candidates[-1])
                    self.assertTrue(path.is_file(), name)
                    config = json.loads(path.read_text(encoding="utf-8"))
                    loaded |= {
                        f"{config['package']}.{mixin}" for mixin in config.get("mixins", [])
                    }
                self.assertEqual(expected - loaded, set())

    def test_saved_cape_stands_in_only_on_the_title_screen(self) -> None:
        # In a world the session's appearance decides the local player's cape: a session that
        # adopted a saved record without a cape must not show the config's cape locally.
        for path in (
            CANONICAL_JAVA / "com/quickskin/mod/mixin/CapeLayerMixin.java",
            ROOT / "neoforge/src/main/java/com/quickskin/mod/neoforge/mixin/CapeLayerMixin.java",
        ):
            with self.subTest(path=relative(path)):
                source = JAVA_COMMENT.sub("", path.read_text(encoding="utf-8"))
                gates = re.findall(
                    r"boolean (?:hasConfigCape = !previewing|useConfigCape =)[^;]*;", source
                )
                self.assertTrue(gates)
                for gate in gates:
                    self.assertIn("level == null", gate)
                self.assertNotRegex(source, r"&&\s*isLocalPlayer\s*\)")

    CAPE_MIXINS = (
        CANONICAL_JAVA / "com/quickskin/mod/mixin/CapeLayerMixin.java",
        ROOT / "neoforge/src/main/java/com/quickskin/mod/neoforge/mixin/CapeLayerMixin.java",
    )
    CAPE_DRAW = re.compile(r"\b(?:renderCloak|renderToBuffer|submitModel)\(")

    def cape_handlers(self, path: Path) -> list[tuple[str, str]]:
        """The declaration (its Stonecutter variants) and the body of each handler that draws a cape."""
        source = JAVA_COMMENT.sub("", path.read_text(encoding="utf-8"))
        handlers = []
        for opening in re.finditer(r"CallbackInfo ci\) \{", source):
            declaration = source[source.rindex("@Inject", 0, opening.start()) : opening.start()]
            if "quickskin$renderCustomCape(" in declaration:
                body = source[opening.end() : source.index("\n    }\n", opening.end())]
                handlers.append((declaration, body))
        self.assertTrue(handlers, relative(path))
        return handlers

    def test_the_cape_layer_is_never_cut_short(self) -> None:
        # Another mod may pair a hook at the start of vanilla's cape layer with one at its end
        # (Touch'n Motion pushes a pose and pops it). A cancelled call runs the first without the
        # second and the game stops with "Pose stack not empty" once the player is drawn. Quick
        # Skin draws its cape and tells vanilla, for that call, that the cape part is off.
        for path in self.CAPE_MIXINS:
            source = JAVA_COMMENT.sub("", path.read_text(encoding="utf-8"))
            with self.subTest(path=relative(path)):
                self.assertNotIn("cancel", source)
                self.assertEqual(
                    source.count('at = @At("HEAD")'), source.count('at = @At("RETURN")')
                )
                self.assertEqual(
                    source.count('at = @At("RETURN")'),
                    source.count("private void quickskin$afterVanillaCape(CallbackInfo ci) {"),
                )
            for index, (declaration, body) in enumerate(self.cape_handlers(path)):
                with self.subTest(path=relative(path), handler=index):
                    # Nothing is drawn before a call another mod cut short is closed
                    self.assertLess(
                        body.index("quickskin$giveCapeBack();"), self.CAPE_DRAW.search(body).start()
                    )
                    # After its own draw the handler's last statement leaves vanilla nothing
                    self.assertRegex(
                        body.rstrip(), r"quickskin\$leaveNoCapeToVanilla\([\w$]+\);$"
                    )
                    self.assertNotRegex(declaration, r"cancellable")
        for path in (
            CANONICAL_JAVA / "com/quickskin/mod/mixin/PreviewEquipmentMixin.java",
            ROOT / "neoforge/src/main/java/com/quickskin/mod/neoforge/mixin/PreviewEquipmentMixin.java",
        ):
            with self.subTest(path=relative(path)):
                source = path.read_text(encoding="utf-8")
                hook = source[source.index("isModelPartShown(") :]
                self.assertIn("part == PlayerModelPart.CAPE && CapeLayerTakeover.covers(this)", hook)
                self.assertIn("cir.setReturnValue(false);", hook)

    def test_a_hidden_piece_never_cuts_its_layer_short(self) -> None:
        # The layers that draw what the local player wears skip a piece the player hides from
        # everyone. As with the cape layer, another mod may pair a hook at a layer's start with
        # one at its end: Touch'n Motion does on the elytra layer, which the hidden chest piece
        # used to cancel, and the game stopped with "Pose stack not empty" at the player's next
        # step in third person. Each layer reads the hidden slot as empty and returns by itself.
        for name in ("LocalArmorLayerMixin", "LocalElytraLayerMixin", "LocalHeadLayerMixin"):
            path = CANONICAL_JAVA / "com/quickskin/mod/mixin/armor" / f"{name}.java"
            source = JAVA_COMMENT.sub("", path.read_text(encoding="utf-8"))
            with self.subTest(path=relative(path)):
                self.assertNotIn("cancel", source)
                self.assertNotIn("@Inject", source)
                self.assertNotIn("CallbackInfo", source)
                handlers = re.findall(r"@ModifyVariable\(.*?\)\s*private ItemStack (quickskin\$\w+)\(ItemStack worn,", source, re.DOTALL)
                self.assertTrue(handlers)
                self.assertEqual(len(handlers), source.count("@ModifyVariable("))
                self.assertEqual(len(handlers), source.count('at = @At("STORE"), ordinal = 0'))
                self.assertEqual(
                    len(handlers),
                    len(re.findall(r"return PlayerModelRenderer\.hidesOwnPiece\(entity, [\w.]+\) \? ItemStack\.EMPTY : worn;", source)),
                )

    def test_an_invisible_player_wears_no_cape(self) -> None:
        # Quick Skin's handler replaces vanilla's cape layer, and with it vanilla's own rule that
        # an invisible player shows no cape. Every handler has to apply it before it draws, with
        # what that version hands it: the player before 1.21.2, the render state afterwards.
        for path in self.CAPE_MIXINS:
            for index, (declaration, body) in enumerate(self.cape_handlers(path)):
                with self.subTest(path=relative(path), handler=index):
                    before_drawing = body[: self.CAPE_DRAW.search(body).start()]
                    subjects = []
                    if "AbstractClientPlayer player," in declaration:
                        subjects.append(("player", r"player\.isInvisible\(\)"))
                    if "RenderState renderState," in declaration:
                        subjects.append(("renderState", r"renderState\.isInvisible"))
                    self.assertTrue(subjects)
                    for wearer, invisible in subjects:
                        # The other Stonecutter variant of the same condition may follow it
                        self.assertRegex(
                            before_drawing,
                            r"if \(!(?:quickskin\$)?previewing && \(" + invisible
                            + r"\s*\|\| !com\.quickskin\.mod\.client\.compat\.AccessoryCapeVisibility\.rule\(\)\.allows\(" + wearer
                            + r"\)\)\) \{(?:\s*if \(![^{}]*\) \{)?"
                            + r"\s*quickskin\$leaveNoCapeToVanilla\([\w$]+\);\s*return;",
                        )

    def test_ears_five_lookup_selects_every_overload(self) -> None:
        source = (
            CANONICAL_JAVA / "com/quickskin/mod/mixin/compat/EarsFiveMixin.java"
        ).read_text(encoding="utf-8")
        # A bare name selects only the first declared overload, which this handler does not match.
        self.assertIn('method = "getEarsFeatures*"', source)

    def test_packaged_clients_enable_expect_counting(self) -> None:
        runtime = (ROOT / "e2e" / "packaged_runtime.py").read_text(encoding="utf-8")
        self.assertIn('"-Dmixin.debug.countInjections=true"', runtime)

    def test_etf_bridge_is_registered_for_every_target(self) -> None:
        # An overlay config replaces the canonical one, and the plugin rejects unknown mixins, so
        # a missing entry in either silently disables the bridge on those targets.
        for path in self.configs_named("quickskin-ears.mixins.json"):
            with self.subTest(config=relative(path)):
                config = json.loads(path.read_text(encoding="utf-8"))
                self.assertIn("EtfPlayerTextureMixin", config["client"])
        plugin = (
            CANONICAL_JAVA / "com/quickskin/mod/mixin/compat/EarsMixinPlugin.java"
        ).read_text(encoding="utf-8")
        self.assertIn('"EtfPlayerTextureMixin"', plugin)

    def test_fabric_renderer_hook_cancels_only_when_vanilla_differs(self) -> None:
        # An unconditional cancel at HEAD skips other mods' RETURN callbacks on this lookup,
        # which is where Entity Texture Features builds a player's skin features.
        source = (
            CANONICAL_JAVA / "com/quickskin/mod/mixin/PlayerRendererMixin.java"
        ).read_text(encoding="utf-8")
        calls = [line for line in source.splitlines() if "cir.setReturnValue(" in line]
        self.assertGreaterEqual(len(calls), 2)
        for call in calls:
            self.assertIn(".equals(vanillaSkin)", call)


if __name__ == "__main__":
    unittest.main()
