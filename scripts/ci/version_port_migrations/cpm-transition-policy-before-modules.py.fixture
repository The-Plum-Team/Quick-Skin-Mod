from __future__ import annotations

import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
MATRIX = json.loads(
    (ROOT / "release" / "release-matrix.json").read_text(encoding="utf-8")
)
MIXIN_ROOT = (
    ROOT / "common" / "src" / "main" / "java" / "com" / "quickskin" / "mod" / "mixin"
)


def uses_vanilla_translucent_hand_collector() -> bool:
    runtime_versions = {
        runtime["runtime_version"] for runtime in MATRIX["runtimes"]
    }
    if len(runtime_versions) != 1:
        raise AssertionError("release runtimes must share one Minecraft version")
    components = tuple(int(value) for value in runtime_versions.pop().split("."))
    return components[0] >= 26 or (
        len(components) == 3 and components[:2] == (1, 21) and components[2] >= 9
    )


CPM_INTEGRATION = (
    ROOT
    / "common"
    / "src"
    / "main"
    / "java"
    / "com"
    / "quickskin"
    / "mod"
    / "client"
    / "compat"
    / "CPMCompatIntegration.java"
)
CLIENT_EVENTS = (
    ROOT
    / "common"
    / "src"
    / "main"
    / "java"
    / "com"
    / "quickskin"
    / "mod"
    / "event"
    / "ClientEvents.java"
)


class CpmTransitionPolicyTest(unittest.TestCase):
    def test_cpm_safety_config_is_prepared_before_background_model_loading(self) -> None:
        source = CPM_INTEGRATION.read_text(encoding="utf-8")
        client_events = CLIENT_EVENTS.read_text(encoding="utf-8")
        availability = source[
            source.index("private static void checkAvailability()") : source.index(
                "private static boolean classFileExists"
            )
        ]
        self.assertIn("initializeConfigReflection();", availability)
        self.assertIn("prepareForBackgroundModelLoading();", client_events)
        self.assertLess(
            client_events.index("prepareForBackgroundModelLoading();"),
            client_events.index("ClientPlayerEvent.CLIENT_PLAYER_JOIN.register"),
        )
        tick_retry = source[
            source.index("public static void prepareForBackgroundModelLoading()") : source.index(
                "private static void checkAvailability()"
            )
        ]
        self.assertIn("hasConfigHandles();", tick_retry)

        config_initialization = source[
            source.index("private static void initializeConfigReflection()") : source.index(
                "private static void initializeNetworkReflection()"
            )
        ]
        for root in (
            "globalSettings",
            "friendSettings",
            "serverSettings",
            "safetyProfiles",
            "friendList",
            "blockedList",
        ):
            with self.subTest(config_root=root):
                self.assertIn(f'"{root}"', source)
        self.assertIn("configGetEntryMethod.invoke(configInstance, root)", config_initialization)
        self.assertIn("backgroundConfigPrepared = true", config_initialization)

        config_readiness = source[
            source.index("private static boolean hasConfigHandles()") : source.index(
                "private static void logConfigUnavailable()"
            )
        ]
        self.assertEqual(config_readiness.count("&& backgroundConfigPrepared"), 2)

    def test_every_renderer_facing_skin_override_defers_to_cpm(self) -> None:
        for name in (
            "MixinAbstractClientPlayer.java",
            "PlayerInfoMixin.java",
            "PlayerRendererMixin.java",
            "SkinManagerMixin.java",
        ):
            with self.subTest(source=name):
                source = (MIXIN_ROOT / name).read_text(encoding="utf-8")
                self.assertIn("CPMCompatIntegration.shouldDeferToCPM()", source)

    def test_immediate_hand_redirect_matches_vanilla_multiplicity(self) -> None:
        source = (MIXIN_ROOT / "ItemInHandRendererMixin.java").read_text(
            encoding="utf-8"
        )
        redirect = source.index("@Redirect(", source.index("public class"))
        handler = source.index("quickskin$redirectRenderHandBuffer", redirect)
        annotation = source[redirect:handler]

        self.assertIn(
            "//? if <1.21.9 {\n@Mixin(value = PlayerRenderer.class", source
        )
        self.assertIn("//?} else {\n@Mixin(value = AvatarRenderer.class", source)
        self.assertLess(
            source.index("//? if <1.21.9 {", source.index("public class")),
            redirect,
        )
        legacy_guard = annotation.index("//? if <1.21.2 {")
        legacy_expect = annotation.index("expect = 2", legacy_guard)
        modern_branch = annotation.index("//?} else {", legacy_expect)
        modern_expect = annotation.index("expect = 1", modern_branch)
        mandatory = annotation.index("require = 1", modern_expect)

        self.assertLess(legacy_guard, legacy_expect)
        self.assertLess(legacy_expect, modern_branch)
        self.assertLess(modern_branch, modern_expect)
        self.assertLess(modern_expect, mandatory)
        self.assertIn("allow = 2", annotation[legacy_expect:modern_branch])
        self.assertIn("allow = 1", annotation[modern_expect:mandatory])
        self.assertNotIn("require = 0", annotation)

    def test_cpm_first_person_hand_keeps_the_model_owned_render_type(self) -> None:
        hand_source = (MIXIN_ROOT / "ItemInHandRendererMixin.java").read_text(
            encoding="utf-8"
        )
        integration = CPM_INTEGRATION.read_text(encoding="utf-8")
        scenario = (
            ROOT
            / "common/src/e2e/java/com/quickskin/mod/e2e/scenario/"
            / "CpmFirstPersonScenario.java"
        ).read_text(encoding="utf-8")
        feature = (
            ROOT
            / "common/src/e2e/java/com/quickskin/mod/e2e/scenario/"
            / "ModCompatibilityFeature.java"
        ).read_text(encoding="utf-8")

        guard = "CPMCompatIntegration.shouldPreserveFirstPersonHandRenderType()"
        self.assertIn(guard, hand_source)
        self.assertLess(
            hand_source.index(guard),
            hand_source.index("TextureAlphaDetector.hasTransparency"),
        )
        preservation = integration[
            integration.index("shouldPreserveFirstPersonHandRenderType()") : integration.index(
                "/** Monotonic E2E-visible proof"
            )
        ]
        self.assertIn("activeCpmModelHash", preservation)
        self.assertIn("isCPMActivelyRendering()", preservation)
        self.assertIn("isLocalPlayerWearingCpmModel()", preservation)
        self.assertIn("firstPersonRenderTypePreservations.incrementAndGet()", preservation)
        self.assertIn(".action(feature::beginFirstPersonRecheck)", scenario)
        self.assertIn(
            "!usesModernCollector && preservationCount <= firstPersonRenderTypeCheckpoint",
            feature,
        )
        self.assertIn("usesModernFirstPersonCollector()", feature)
        self.assertIn('String prefix = "1.21."', feature)
        self.assertIn(">= 9", feature)
        self.assertIn(
            '"Quick Skin left CPM\'s model-owned modern collector untouched"',
            feature,
        )

    def test_neoforge_immediate_hand_redirect_preserves_cpm_render_type(self) -> None:
        neoforge_source_path = (
            ROOT
            / "neoforge/src/main/java/com/quickskin/mod/neoforge/mixin/"
            / "PlayerRendererMixin.java"
        )
        if not neoforge_source_path.is_file():
            return

        neoforge_source = neoforge_source_path.read_text(encoding="utf-8")
        guard = "CPMCompatIntegration.shouldPreserveFirstPersonHandRenderType()"
        self.assertIn(guard, neoforge_source)
        self.assertNotIn(
            "if (CPMCompatIntegration.shouldDeferToCPM()) "
            "return instance.getBuffer(renderType);",
            neoforge_source,
        )
        self.assertNotIn(
            "if (CPMCompatIntegration.isCPMActivelyRendering()) "
            "return instance.getBuffer(renderType);",
            neoforge_source,
        )

    def test_modern_first_person_collectors_remain_owned_by_model_mods(self) -> None:
        paths = [MIXIN_ROOT / "ItemInHandRendererMixin.java"]
        neoforge_renderer = (
            ROOT
            / "neoforge"
            / "src"
            / "main"
            / "java"
            / "com"
            / "quickskin"
            / "mod"
            / "neoforge"
            / "mixin"
            / "PlayerRendererMixin.java"
        )
        if neoforge_renderer.is_file():
            paths.append(neoforge_renderer)

        for path in paths:
            with self.subTest(source=path.relative_to(ROOT).as_posix()):
                source = path.read_text(encoding="utf-8")
                self.assertIn("quickskin$redirectRenderHandBuffer", source)
                if not uses_vanilla_translucent_hand_collector() and MATRIX[
                    "runtimes"
                ][0]["runtime_version"] != "1.20.1":
                    continue
                legacy_guard = source.index("//? if <", source.index("public class"))
                redirect = source.index("@Redirect(", legacy_guard)
                self.assertLess(legacy_guard, redirect)
                self.assertNotIn("quickskin$redirectSubmitModelPart", source)
                self.assertNotIn("SubmitNodeCollector;submitModelPart", source)

    def test_cpm_mixins_are_registered_in_the_optional_config(self) -> None:
        config_path = (
            ROOT
            / "common"
            / "src"
            / "main"
            / "resources"
            / "quickskin-ears.mixins.json"
        )
        config = json.loads(config_path.read_text(encoding="utf-8"))
        self.assertIs(config["required"], False)
        self.assertEqual(config["injectors"]["defaultRequire"], 0)
        configured = {name.rsplit(".", 1)[-1] for name in config["client"]}
        self.assertTrue(
            {
                "CpmModelDefinitionLoaderMixin",
                "CpmRenderDepthMixin",
                "CpmSubmitCollectorMixin",
            }
            <= configured
        )

    def test_optional_plugin_enables_safe_replay_bridge_and_fails_closed_for_unknown_names(self) -> None:
        plugin = (
            MIXIN_ROOT / "compat" / "EarsMixinPlugin.java"
        ).read_text(encoding="utf-8")
        self.assertIn('"CpmModelDefinitionLoaderMixin"', plugin)
        self.assertIn('"CpmRenderDepthMixin"', plugin)
        self.assertIn('"CpmSubmitCollectorMixin"', plugin)
        self.assertIn('"ReplayModCompatMixin"', plugin)
        self.assertIn("mixinNamed(mixinClassName, REPLAY_MOD_COMPAT_MIXIN)", plugin)
        self.assertIn("return false;", plugin)


if __name__ == "__main__":
    unittest.main()
