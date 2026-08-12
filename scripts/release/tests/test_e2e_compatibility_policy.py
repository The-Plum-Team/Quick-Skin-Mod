from __future__ import annotations

import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
E2E_JAVA = ROOT / "common" / "src" / "e2e" / "java" / "com" / "quickskin" / "mod" / "e2e"
SHIM = E2E_JAVA / "VanillaShim.java"


class E2ECompatibilityPolicyTest(unittest.TestCase):
    def test_known_vanilla_drift_stays_in_the_compatibility_driver(self) -> None:
        forbidden = (
            re.compile(r"^import net\.minecraft\.client\.gui\.components\.SplashRenderer;", re.M),
            re.compile(r"Class\.forName\(\"net\.minecraft\."),
            re.compile(r"\.getMainRenderTarget\s*\("),
            re.compile(r"\.getSkinTextureLocation\s*\("),
            re.compile(r"\.getCloakTextureLocation\s*\("),
        )
        offenders: list[str] = []
        for source in sorted(E2E_JAVA.rglob("*.java")):
            if source == SHIM:
                continue
            text = source.read_text(encoding="utf-8")
            for pattern in forbidden:
                if pattern.search(text):
                    offenders.append(f"{source.relative_to(ROOT)}: {pattern.pattern}")
        self.assertEqual(
            offenders,
            [],
            "Minecraft API drift must be absorbed by VanillaShim, not scenario code",
        )

    def test_driver_documents_and_owns_the_title_splash_adapter(self) -> None:
        text = SHIM.read_text(encoding="utf-8")
        self.assertIn("installDeterministicSplash", text)
        self.assertIn("net.minecraft.client.gui.components.SplashRenderer", text)
        self.assertIn(
            "SplashRenderer.class",
            text,
            "the splash type must be a class literal so the remapper rewrites it",
        )

    def test_default_skin_baselines_wait_for_the_uuid_selected_texture(self) -> None:
        shim = SHIM.read_text(encoding="utf-8")
        self.assertIn("DefaultPlayerSkin.class", shim)
        self.assertIn("expectedDefaultSkinTexture", shim)
        self.assertIn("isExpectedDefaultSkinResolved", shim)
        self.assertIn("getRecordComponents()", shim)

        scenarios = (
            E2E_JAVA / "scenario" / "Phase0Smoke.java",
            E2E_JAVA / "scenario" / "PropagationScenario.java",
            E2E_JAVA / "scenario" / "PropagationLiveScenario.java",
            E2E_JAVA / "scenario" / "FullScenario.java",
        )
        for source in scenarios:
            with self.subTest(source=source.name):
                text = source.read_text(encoding="utf-8")
                if source.name == "PropagationScenario.java":
                    self.assertIn(
                        ".ready(() -> VanillaShim.isExpectedDefaultSkinResolved(mc.player)",
                        text,
                    )
                    self.assertIn("&& (!observer || holdObserverBaseline(mc))", text)
                else:
                    self.assertIn(
                        ".ready(() -> VanillaShim.isExpectedDefaultSkinResolved(mc.player))",
                        text,
                    )
                self.assertIn("default skin did not stabilize", text)

        live = scenarios[2].read_text(encoding="utf-8")
        self.assertIn("VanillaShim.isExpectedDefaultSkinResolved(a)", live)
        self.assertIn("A's default skin did not stabilize BEFORE", live)

    def test_model_evidence_reads_the_renderer_facing_geometry(self) -> None:
        shim = SHIM.read_text(encoding="utf-8")
        scenario = (E2E_JAVA / "scenario" / "FullScenario.java").read_text(
            encoding="utf-8"
        )

        self.assertIn("public static String playerModel", shim)
        self.assertIn('"getModelName", "method_3121", "m_108564_"', shim)
        self.assertIn('"getSkin", "method_52814", "method_52810"', shim)
        self.assertIn('findNoArg(skin.getClass(), "model", "comp_1629")', shim)
        self.assertIn("skin.getClass().getRecordComponents()", shim)
        self.assertIn("enumValue.ordinal() == 0", shim)
        self.assertIn("enumValue.ordinal() == 1", shim)
        self.assertIn("prepareModelEvidenceView(mc);", scenario)
        self.assertIn('.ready(() -> holdModelEvidenceView(mc, "slim"))', scenario)
        self.assertIn('.ready(() -> holdModelEvidenceView(mc, "classic"))', scenario)
        self.assertIn("expectedModel.equals(VanillaShim.playerModel(mc.player))", scenario)
        self.assertIn("restoreModelEvidenceView(mc);", scenario)

    def test_propagation_observer_baseline_hides_the_already_custom_subject(self) -> None:
        scenario = (
            E2E_JAVA / "scenario" / "PropagationScenario.java"
        ).read_text(encoding="utf-8")

        self.assertIn("holdObserverBaseline(mc)", scenario)
        self.assertIn("CameraType.FIRST_PERSON", scenario)
        self.assertIn("AbstractClientPlayer subject = findOther(mc);", scenario)
        self.assertIn("if (distance < 3.0)", scenario)
        self.assertIn("lookX * subjectX + lookZ * subjectZ <= -0.95", scenario)
        self.assertIn(
            "remote subject present behind first-person camera", scenario
        )

    def test_string_class_lookups_declare_an_intermediary_fallback(self) -> None:
        """Fabric serves intermediary names at runtime; a Mojang name alone resolves only on Forge."""

        text = SHIM.read_text(encoding="utf-8")
        looked_up = set(re.findall(r'loadNamedClass\(\s*"([^"]+)"', text))
        guarded = set(re.findall(r'namedClass\.equals\(\s*"([^"]+)"\s*\)', text))
        self.assertEqual(
            set(),
            looked_up - guarded,
            "every string-resolved Minecraft class needs an intermediary fallback; "
            "prefer a class literal so the harness jar's remapper rewrites it",
        )


if __name__ == "__main__":
    unittest.main()
