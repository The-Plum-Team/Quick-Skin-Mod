from __future__ import annotations

import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
MIXIN_ROOT = (
    ROOT / "common" / "src" / "main" / "java" / "com" / "quickskin" / "mod" / "mixin"
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
