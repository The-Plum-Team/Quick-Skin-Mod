from __future__ import annotations

import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
E2E_JAVA = ROOT / "common" / "src" / "e2e" / "java" / "com" / "quickskin" / "mod" / "e2e"
SHIM = E2E_JAVA / "VanillaShim.java"
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


class E2ECompatibilityPolicyTest(unittest.TestCase):
    def test_known_vanilla_drift_stays_in_the_compatibility_driver(self) -> None:
        forbidden = (
            re.compile(r"^import net\.minecraft\.client\.gui\.components\.SplashRenderer;", re.M),
            re.compile(r"Class\.forName\(\"net\.minecraft\."),
            re.compile(r"\.getMainRenderTarget\s*\("),
            re.compile(r"\.getSkinTextureLocation\s*\("),
            re.compile(r"\.getCloakTextureLocation\s*\("),
            re.compile(r"\.getElytraTextureLocation\s*\("),
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
        evidence_view = (E2E_JAVA / "DefaultSkinEvidenceView.java").read_text(
            encoding="utf-8"
        )
        for source in scenarios:
            with self.subTest(source=source.name):
                text = source.read_text(encoding="utf-8")
                self.assertIn(
                    ".ready(() -> VanillaShim.isExpectedDefaultSkinResolved(mc.player)",
                    text,
                )
                self.assertIn("DefaultSkinEvidenceView.hold(mc,", text)
                self.assertIn("default skin did not stabilize", text)

        self.assertIn("CameraType.THIRD_PERSON_BACK", evidence_view)
        self.assertIn("setYBodyRot(yaw)", evidence_view)
        self.assertIn("REMOTE_BEHIND_CAMERA_CLEARANCE", evidence_view)
        self.assertIn("lookX * remoteX + lookZ * remoteZ <= -0.95", evidence_view)
        self.assertIn(
            "VanillaShim.isTerrainRenderReady(mc, terrainPosition)", evidence_view
        )
        self.assertIn("public static boolean isTerrainRenderReady", shim)
        for renderer_alias in (
            '"isSectionCompiled"',
            '"isSectionCompiledAndVisible"',
            '"method_40050"',
            '"m_202430_"',
            '"hasRenderedAllChunks"',
            '"hasRenderedAllSections"',
            '"method_3281"',
            '"m_109825_"',
        ):
            with self.subTest(renderer_alias=renderer_alias):
                self.assertIn(renderer_alias, shim)

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

    def test_transient_overlay_adapter_pins_forge_srg_names(self) -> None:
        """Forge 1.20.1 executes remapped classes whose reflection names stay in SRG form."""

        shim = SHIM.read_text(encoding="utf-8")
        self.assertIn("public static String clearTransientOverlays", shim)
        for alias in ("m_91300_", "m_94919_", "m_93076_", "m_93795_"):
            with self.subTest(alias=alias):
                self.assertIn(f'"{alias}"', shim)

    def test_transient_overlay_adapter_supports_the_26_2_gui_split(self) -> None:
        """26.2 moved toast and chat ownership without exposing stable cross-version types."""

        shim = SHIM.read_text(encoding="utf-8")
        self.assertIn('findNoArg(gui.getClass(), "toastManager")', shim)
        self.assertIn('invokeUniqueNoArgOnFieldValue(gui, "getChat")', shim)

    def test_visual_review_receives_passed_runtime_assertion_evidence(self) -> None:
        evidence = (ROOT / "e2e/visual_evidence.py").read_text(encoding="utf-8")
        review = (ROOT / "e2e/visual_review.py").read_text(encoding="utf-8")
        checker = (ROOT / "e2e/check_visual_review.py").read_text(encoding="utf-8")
        semantic_prompt = (ROOT / "e2e/visual_review_semantic_prompt.md").read_text(
            encoding="utf-8"
        )
        verify_prompt = (
            ROOT / "e2e/visual_review_semantic_verify_prompt.md"
        ).read_text(encoding="utf-8")

        self.assertIn('"runtime_evidence": step_record["message"].strip()', evidence)
        self.assertIn('"runtime_evidence": frame["runtime_evidence"]', review)
        self.assertIn('"runtime_evidence"', checker)
        self.assertIn("final renderer-facing", semantic_prompt)
        self.assertIn("sleeve-to-sleeve span", semantic_prompt)
        self.assertIn("sleeve-to-sleeve span", verify_prompt)

    def test_elytra_texture_adapter_pins_exact_runtime_names(self) -> None:
        """Wrong reflection aliases can resolve unrelated methods with plausible values."""

        shim = SHIM.read_text(encoding="utf-8")
        self.assertIn("public static String elytraTexture", shim)
        self.assertIn(
            'case "getElytraTextureLocation" -> "method_3122";', shim
        )
        self.assertIn(
            'case "getElytraTextureLocation" -> "m_108563_";', shim
        )
        self.assertIn(
            'case "elytraTexture" -> findNoArg(skin.getClass(), acc, "comp_1628")',
            shim,
        )

    def test_texture_adapter_prefers_the_modern_renderer_skin(self) -> None:
        """A retained legacy getter may be null while modern PlayerSkin drives rendering."""

        shim = SHIM.read_text(encoding="utf-8")
        resolver = shim[shim.index("private static String resolveLoc(") :]
        self.assertLess(
            resolver.index("Method getSkin = findNoArg"),
            resolver.index("Method direct = findNoArg"),
            "modern PlayerSkin must be authoritative before the 1.20.1 fallback",
        )

    def test_propagation_observer_baseline_hides_the_already_custom_subject(self) -> None:
        scenario = (
            E2E_JAVA / "scenario" / "PropagationScenario.java"
        ).read_text(encoding="utf-8")
        evidence_view = (E2E_JAVA / "DefaultSkinEvidenceView.java").read_text(
            encoding="utf-8"
        )

        self.assertIn("DefaultSkinEvidenceView.hold(mc, observer)", scenario)
        self.assertIn("CameraType.THIRD_PERSON_BACK", evidence_view)
        self.assertIn("AbstractClientPlayer remote = findOther(mc);", evidence_view)
        self.assertIn("if (distance < REMOTE_BEHIND_CAMERA_CLEARANCE)", evidence_view)
        self.assertIn("lookX * remoteX + lookZ * remoteZ <= -0.95", evidence_view)
        self.assertIn(
            "remote subject present behind third-person camera", scenario
        )

    def test_arm_checkpoints_restore_first_person_after_full_body_baselines(self) -> None:
        for name in (
            "Phase0Smoke.java",
            "PropagationScenario.java",
            "PropagationLiveScenario.java",
        ):
            with self.subTest(source=name):
                text = (E2E_JAVA / "scenario" / name).read_text(encoding="utf-8")
                self.assertIn("DefaultSkinEvidenceView.enterFirstPerson(mc);", text)

    def test_optional_mod_scenario_drives_real_feature_workflows(self) -> None:
        harness = (E2E_JAVA / "E2EHarness.java").read_text(encoding="utf-8")
        scenario = (
            E2E_JAVA / "scenario" / "ModCompatibilityScenario.java"
        ).read_text(encoding="utf-8")
        feature = (
            E2E_JAVA / "scenario" / "ModCompatibilityFeature.java"
        ).read_text(encoding="utf-8")
        network_sync = (
            ROOT
            / "common"
            / "src"
            / "main"
            / "java"
            / "com"
            / "quickskin"
            / "mod"
            / "networking"
            / "NetworkSyncService.java"
        ).read_text(encoding="utf-8")
        assets = (E2E_JAVA / "TestAssets.java").read_text(encoding="utf-8")
        runtime = (ROOT / "e2e/packaged_runtime.py").read_text(encoding="utf-8")

        self.assertIn("feature::prepareBaseline", scenario)
        self.assertIn("feature::applyQuickSkinFeature", scenario)
        self.assertIn("ModCompatibilityScenario.prepareBeforeWorldJoin();", harness)
        self.assertLess(
            harness.index("ModCompatibilityScenario.prepareBeforeWorldJoin();"),
            harness.index("if (mc.player != null && mc.level != null)"),
        )
        wait_world = harness[
            harness.index("private void tickWaitWorld(Minecraft mc)") : harness.index(
                "// Diagnostic: log each screen transition"
            )
        ]
        self.assertNotIn("ScenarioId.MOD_COMPATIBILITY", wait_world)
        self.assertIn("ModCompatibilityFeature.prepareBeforeWorldJoin(modId);", scenario)
        self.assertIn("protectStartupRecordingBeforeWorldJoin", feature)
        for mod_id in (
            "cpm",
            "ears",
            "skin-layers-3d",
            "customnpcs",
            "essential",
            "replaymod",
        ):
            with self.subTest(mod_id=mod_id):
                self.assertIn(f'case "{mod_id}"', feature)

        for required_feature_proof in (
            "CPMCompatIntegration.parseCpmModelInfo",
            "CPMCompatIntegration.isLocalPlayerWearingCpmModel",
            "EarsCompatIntegration.getFeatures",
            "meshCacheContains",
            "manualRenderObserved",
            '"customnpcs".equals(type.getNamespace())',
            "EssentialCompatIntegration.findBottomEssentialWidget",
            "ReplayModBridge.getInterceptedPacketCount",
            "startReplay(finalizedReplayPath)",
        ):
            with self.subTest(proof=required_feature_proof):
                self.assertIn(required_feature_proof, feature)

        for custom_npcs_bridge_proof in (
            '"detectSkinConflict".equals(method.getName())',
            "parameters[0] != UUID.class",
            "!parameters[1].isInstance(location)",
            "method.getReturnType() != boolean.class",
            ".getConstructor(String.class, String.class)",
        ):
            with self.subTest(custom_npcs_bridge_proof=custom_npcs_bridge_proof):
                self.assertIn(custom_npcs_bridge_proof, feature)

        for window_handle_accessor in (
            '"getWindow"',
            '"handle"',
            '"method_4490"',
            '"m_85439_"',
        ):
            with self.subTest(window_handle_accessor=window_handle_accessor):
                self.assertIn(window_handle_accessor, feature)

        self.assertIn("com.unascribed.ears.common.EarsFeaturesWriterV1", assets)
        self.assertIn("rendererFeatures(playerRenderer)", feature)
        self.assertIn('"com.unascribed.ears.EarsLayerRenderer"', feature)
        self.assertIn('"com.unascribed.ears.EarsMod"', feature)
        self.assertIn("rendererLookupArgument", feature)
        self.assertIn("expectedType.isInstance(candidate)", feature)
        self.assertIn("com.tom.cpm.shared.editor.Exporter", assets)
        self.assertIn("summon customnpcs:customnpc", runtime)
        self.assertIn(
            '"com.quickskin.mod.client.compat.ReplayModHelper"', feature
        )
        self.assertIn("TestAssets.makeReplayAcknowledgedSkin()", feature)
        self.assertIn("public static Path makeReplayAcknowledgedSkin()", assets)
        self.assertIn("image.setRGB(0, 0, marker)", assets)
        self.assertIn("outside every player-model UV island", assets)
        self.assertIn("MIN_ACKNOWLEDGED_RECORDING_TAIL_MS = 12_000L", feature)
        self.assertIn("MAX_ACKNOWLEDGED_RECORDING_TAIL_POLLS = 20 * 20", feature)
        self.assertIn("recordedDurationMs - acknowledgedPayloadTimestampMs", feature)
        self.assertIn("acknowledgedPayloadTimestampMs", feature)
        self.assertIn("currentReplayTimestamp()", feature)
        self.assertIn("MISSING_RECORDED_PAYLOAD_GRACE_MS", feature)
        self.assertIn("without traversing the", feature)
        self.assertIn("isLatestAppearanceAcknowledged", feature)
        self.assertIn("latestAcknowledgedSyncToken = awaiting.token", network_sync)
        self.assertNotIn("LIVE_SYNC_REASSERT_POLL", feature)
        self.assertNotIn("reasserted distinct ReplayMod fixture", feature)
        self.assertLess(
            feature.index("applied hash-distinct plaid ReplayMod fixture"),
            feature.index("isLatestAppearanceAcknowledged"),
        )
        self.assertLess(
            feature.index("isLatestAppearanceAcknowledged"),
            feature.index("ReplayMod recording close requested"),
        )

    def test_cpm_cache_refresh_waits_for_the_extracted_frame_boundary(self) -> None:
        """A skin/model transition must not invalidate CPM's active extracted frame."""

        integration = CPM_INTEGRATION.read_text(encoding="utf-8")
        self.assertIn(
            'accessClass.getMethod("executeNextFrame", Runnable.class)', integration
        )
        self.assertIn(
            "cacheInvalidationQueued.compareAndSet(false, true)", integration
        )
        self.assertIn("schedulePlayerCacheInvalidation();", integration)
        capabilities = (
            ROOT / "common/src/main/java/com/quickskin/mod/client/compat/CpmCapabilities.java"
        ).read_text(encoding="utf-8")
        self.assertIn(
            "return renderPipeline != RenderPipeline.IMMEDIATE;", capabilities
        )
        self.assertIn("usesFabricDeferredPipeline()", integration)
        self.assertIn("CpmCapabilities.current().usesDeferredRendering()", integration)
        self.assertIn("return scheduleSkinModeReset();", integration)
        self.assertIn("executeNextFrameMethod.invoke(minecraftClientAccess, reset)", integration)
        self.assertIn('if (!notifyServerIfInstalled("resetToSkinMode"))', integration)
        self.assertIn("isCPMScreenOpen() || skinModeResetQueued.get()", integration)
        self.assertIn("skinModeResetApplied.set(true)", integration)
        self.assertIn("shouldSuppressStaleSubmission()", integration)
        self.assertIn("onRenderedFrameBoundary()", integration)
        self.assertIn("skinModeResetFrameBoundaries.incrementAndGet() < 2", integration)
        client_events = (ROOT / "common/src/main/java/com/quickskin/mod/event/ClientEvents.java").read_text(
            encoding="utf-8"
        )
        self.assertGreaterEqual(
            client_events.count("CPMCompatIntegration.onRenderedFrameBoundary();"), 2
        )
        force_refresh = integration[
            integration.index("public static void forceReRegisterSkins") :
            integration.index("/** Clears CPM's selectedModel key")
        ]
        self.assertNotIn("\n        invalidatePlayerCache();", force_refresh)

    def test_essential_raw_screenshot_supports_both_gpu_readback_eras(self) -> None:
        """Essential needs a silent framebuffer copy before and after 1.21.5's async API."""

        shim = SHIM.read_text(encoding="utf-8")
        self.assertIn("return writeRawScreenshot(image, gameDir, name);", shim)
        self.assertIn("Consumer<Object> writer = captured ->", shim)
        self.assertIn("for (int parameterCount : new int[] {2, 3})", shim)
        self.assertIn("closeRawScreenshot(captured);", shim)

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
