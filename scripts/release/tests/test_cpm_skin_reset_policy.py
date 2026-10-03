from __future__ import annotations

import json
import re
import unittest
from pathlib import Path

from scripts.architecture.source_inventory import java_source


ROOT = Path(__file__).resolve().parents[3]
MATRIX = json.loads(
    (ROOT / "release" / "release-matrix.json").read_text(encoding="utf-8")
)
PLAYER_APPEARANCE_SERVICE = (
    java_source('client/services/PlayerAppearanceService.java', source_set='main', repository=ROOT)
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
CPM_INTEGRATION = (
    java_source('client/compat/CPMCompatIntegration.java', source_set='main', repository=ROOT)
)
PLAYER_RENDERER_MIXIN = (
    ROOT
    / "common"
    / "src"
    / "main"
    / "java"
    / "com"
    / "quickskin"
    / "mod"
    / "mixin"
    / "PlayerRendererMixin.java"
)
CPM_MODEL_WORKFLOW = (
    java_source('client/compat/CpmModelWorkflow.java', source_set='main', repository=ROOT)
)
PLAYER_SKIN_MENU_SCREEN = (
    java_source('client/gui/screen/PlayerSkinMenuScreen.java', source_set='main', repository=ROOT)
)
SAVED_APPEARANCE_RESTORER = (
    java_source('client/services/SavedAppearanceRestorer.java', source_set='main', repository=ROOT)
)
NETWORK_TEXTURE_CACHE = (
    java_source('client/storage/NetworkTextureCache.java', source_set='main', repository=ROOT)
)
MODEL_SERVICE = (
    java_source('client/services/ModelService.java', source_set='main', repository=ROOT)
)
CPM_LOOK = (
    java_source('client/compat/CpmLook.java', source_set='main', repository=ROOT)
)
CPM_LOOK_ARBITER = (
    java_source('client/services/CpmLookArbiter.java', source_set='main', repository=ROOT)
)
SKIN_MANAGER_MIXIN = (
    ROOT / "common" / "src" / "main" / "java" / "com" / "quickskin" / "mod" / "mixin"
    / "SkinManagerMixin.java"
)
LEGACY_SKIN_MANAGER_MIXIN = (
    ROOT / "common" / "src" / "legacy1_20_1" / "java" / "com" / "quickskin" / "mod" / "mixin"
    / "MixinSkinManager.java"
)
# NeoForge loads only its own mixin configuration, so this replaces the common SkinManagerMixin.
NEOFORGE_SKIN_MANAGER_MIXIN = (
    ROOT / "neoforge" / "src" / "main" / "java" / "com" / "quickskin" / "mod" / "neoforge"
    / "mixin" / "SkinManagerMixin.java"
)
CLIENT_EVENTS = (
    ROOT / "common" / "src" / "main" / "java" / "com" / "quickskin" / "mod" / "event"
    / "ClientEvents.java"
)
PLAYER_INFO_MIXINS = (
    ROOT / "common" / "src" / "main" / "java" / "com" / "quickskin" / "mod" / "mixin"
    / "PlayerInfoMixin.java",
    ROOT / "neoforge" / "src" / "main" / "java" / "com" / "quickskin" / "mod" / "neoforge"
    / "mixin" / "PlayerInfoMixin.java",
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

    def test_appearance_refresh_never_resets_cpm_to_skin_mode(self) -> None:
        integration = CPM_INTEGRATION.read_text(encoding="utf-8")
        self.assertIn("public static void forceReRegisterSkins", integration)
        self.assertIn("public static boolean resetToSkinMode()", integration)
        refresh = integration[
            integration.index("public static void forceReRegisterSkins") : integration.index(
                "public static boolean resetToSkinMode()"
            )
        ]

        # A model CPM has, from any source, survives every Quick Skin appearance update.
        self.assertNotIn("resetToSkinMode", refresh)
        self.assertIn("schedulePlayerCacheInvalidation();", refresh)
        self.assertIn("if (!localPlayer || !skinModeResetQueued.get()) {", refresh)

    def test_every_skin_click_resets_cpm_to_skin_mode(self) -> None:
        workflow = CPM_MODEL_WORKFLOW.read_text(encoding="utf-8")
        self.assertIn("public static void activateSkin(", workflow)
        activate_skin = workflow[
            workflow.index("public static void activateSkin(") : workflow.index(
                "public static void onModelDeleted("
            )
        ]
        # A Quick Skin skin is the latest look choice, whatever CPM had selected before it.
        guard = "if (CPMCompatIntegration.isAvailable()) {"
        self.assertIn(guard, activate_skin)
        self.assertLess(
            activate_skin.index(guard),
            activate_skin.index("CPMCompatIntegration.resetToSkinMode()"),
        )
        self.assertEqual(1, activate_skin.count("resetToSkinMode()"))
        self.assertNotIn("wasUsingCpmModel", activate_skin)
        # Without CPM the reset waits, so a model left in CPM's config is dropped when it returns.
        without_cpm = activate_skin[activate_skin.index("} else {") :]
        self.assertTrue(
            without_cpm.split()[3:5] == ["config.pendingCpmSkinModeReset", "="]
            and without_cpm.split()[5] == "true;",
            without_cpm[:120],
        )

    def test_skin_reset_asks_cpms_server_to_drop_an_assigned_model(self) -> None:
        integration = CPM_INTEGRATION.read_text(encoding="utf-8")
        reset = integration[
            integration.index("private static boolean performSkinModeReset()") : integration.index(
                "static boolean canReadSelectedModel()"
            )
        ]
        no_selection = reset[reset.index("if (selectedModel == null) {") : reset.index(
            'configClearValueMethod.invoke(configInstance, "selectedModel");'
        )]
        self.assertIn(
            'if (localServerModel() == null || !notifyServerIfInstalled("resetToSkinMode")) {',
            no_selection,
        )
        # The server's reply to Quick Skin's own reset is never read as a new look choice.
        self.assertEqual(2, reset.count("CpmLook.onQuickSkinReset();"))
        self.assertIn('getMethod("getModel", Object.class)', integration)
        self.assertIn('.getField("forcedSkin")', integration)

    def test_latest_look_choice_withholds_the_local_quick_skin_skin(self) -> None:
        look = CPM_LOOK.read_text(encoding="utf-8")
        decide = look[look.index("static Owner decide(") : look.index("public static Owner owner()")]
        order = [
            "if (!cpmInstalled || quickSkinResetPending) {",
            "if (!selectionReadable || (quickSkinHasModel && quickSkinModel == null)) {",
            "if (selectedModel != null) {",
            "return serverAssigned ? Owner.CPM : Owner.QUICK_SKIN;",
        ]
        positions = [decide.index(line) for line in order]
        self.assertEqual(sorted(positions), positions)
        observe = look[look.index("public static Owner observe()") : look.index(
            "public static void resetSession()"
        )]
        self.assertIn('config.activeCpmModelHash = "";', observe)

        service = PLAYER_APPEARANCE_SERVICE.read_text(encoding="utf-8")
        apply_look = service[service.index("public void applyLook(") :]
        gate = apply_look.index("CpmLookArbiter.withholdsSkin(playerId)")
        self.assertLess(apply_look.index("if (playerId == null) {"), gate)
        self.assertLess(gate, apply_look.index("// Get or create appearance"))
        self.assertIn(
            "!applyingNetworkUpdate && !reloadingTransparency", apply_look[: gate]
        )

        arbiter = CPM_LOOK_ARBITER.read_text(encoding="utf-8")
        tick = arbiter[arbiter.index("public static void tick()") : arbiter.index(
            "public static boolean withholdsSkin("
        )]
        self.assertIn("if (!CPMCompatIntegration.isAvailable() || !isLiveSession()) {", tick)
        self.assertIn("CpmLook.resetSession();", tick)
        self.assertIn('appearances.applySkin(local, "", null);', tick)
        self.assertIn("SavedAppearanceRestorer.restore(local);", tick)
        self.assertLess(tick.index("owner.withholdsQuickSkinSkin()"), tick.index(
            'appearances.applySkin(local, "", null);'
        ))

        # The saved skin stays the skin to return to; the local skin lookups must not wear it.
        modern = SKIN_MANAGER_MIXIN.read_text(encoding="utf-8")
        self.assertIn(
            "if (isLocalPlayer && !com.quickskin.mod.client.services.CpmLookArbiter"
            ".withholdsSkin(uuid)) {",
            modern,
        )
        if LEGACY_SKIN_MANAGER_MIXIN.exists():
            legacy = LEGACY_SKIN_MANAGER_MIXIN.read_text(encoding="utf-8")
            self.assertEqual(2, legacy.count("CpmLookArbiter.withholdsSkin(localUuid)"))
        # NeoForge's own copy uses the saved skin in a world on 1.21.4, 1.21.5 and from 1.21.9.
        neoforge = NEOFORGE_SKIN_MANAGER_MIXIN.read_text(encoding="utf-8")
        gate = "!com.quickskin.mod.client.services.CpmLookArbiter.withholdsSkin(uuid)"
        for guarded in (
            "if (uuid.equals(Minecraft.getInstance().getUser().getProfileId())\n"
            "                && " + gate + ") {",
            "|| !config.activeCapeHash.isEmpty())\n                && " + gate + ";",
            "if (isLocalPlayer && " + gate + ") {",
            "if (!hasServiceOverrides && isLocalPlayer\n                && " + gate + ") {",
        ):
            self.assertIn(guarded, neoforge)
        self.assertEqual(4, neoforge.count(gate))

        events = CLIENT_EVENTS.read_text(encoding="utf-8")
        client_tick = events[events.index("ClientTickEvent.CLIENT_POST.register(") :]
        client_tick = client_tick[: client_tick.index("});")]
        self.assertIn("CpmLookArbiter.tick();", client_tick)
        self.assertIn("CpmLookArbiter.bindLiveSession(", events)
        self.assertIn("ReplayModHelper.isInReplay()", events)

    def test_renderer_override_stands_down_for_any_cpm_model(self) -> None:
        integration = CPM_INTEGRATION.read_text(encoding="utf-8")
        self.assertIn("public static boolean isWearingCpmModel(", integration)
        self.assertIn("private static Object gameProfileOf(", integration)
        probe = integration[
            integration.index("public static boolean isWearingCpmModel(") : integration.index(
                "private static Object gameProfileOf("
            )
        ]
        # The condition of CPM's render gate on the entry CPM already has. The probe never loads
        # a player and never calls the gate itself, which starts resolving a new definition.
        self.assertIn("getLoadedPlayerMethod.invoke(loaderInstance, profile)", probe)
        self.assertIn("getModelDefinition0Method.invoke(player)", probe)
        self.assertIn("Boolean.TRUE.equals(doRenderMethod.invoke(definition))", probe)
        self.assertNotIn("getModelDefinitionMethod", probe)
        self.assertIn('.getMethod("getModelDefinition0")', integration)
        self.assertIn('.getMethod("doRender")', integration)
        # The renderer asks several times per frame: a bounded, short-lived answer per player.
        self.assertIn("PLAYER_MODEL_PROBE_TTL_NANOS = 100_000_000L;", integration)
        self.assertIn("if (playerModelProbes.size() >= MAX_PLAYER_MODEL_PROBES) {", probe)
        clear = integration[integration.index("public static void clearHttpTextureCache()") :]
        clear = clear[: clear.index("httpTextureCache.clear();")]
        self.assertIn("playerModelProbes.clear();", clear)
        self.assertIn("|| !isAvailable() ||", probe)
        self.assertNotIn("loadPlayer", probe)
        self.assertNotIn("getCurrentClientPlayer", probe)
        self.assertIn('getMethod("getLoadedPlayer", Object.class)', integration)
        self.assertNotIn("ownsLocalPlayerTexture", integration)

        # Every Stonecutter variant of the cancelling lookup carries the same stand-down.
        mixin = PLAYER_RENDERER_MIXIN.read_text(encoding="utf-8")
        lookups = mixin.count("service.hasActiveSkin(")
        guarded = re.findall(
            r"if \(service\.hasActiveSkin\((.+?)\)"
            r" && !CPMCompatIntegration\.isWearingCpmModel\(\1\)\) \{",
            mixin,
        )
        self.assertGreater(lookups, 0)
        self.assertEqual(lookups, len(guarded))
        self.assertIn("CPMCompatIntegration.shouldDeferToCPM()", mixin)

    def test_choosing_a_model_withdraws_the_applied_skin(self) -> None:
        menu = PLAYER_SKIN_MENU_SCREEN.read_text(encoding="utf-8")
        self.assertIn("public void onSkinSelected(", menu)
        selected = menu[menu.index("public void onSkinSelected(") :]
        model_branch = selected[
            selected.index("if (metadata.isCpmModel()) {") : selected.index(
                "com.quickskin.mod.config.ClientConfig config ="
            )
        ]
        self.assertIn("CpmModelWorkflow.activateModel(metadata)", model_branch)
        self.assertIn('appearances.applySkin(targetUUID, "", null);', model_branch)
        activate = model_branch.index("CpmModelWorkflow.activateModel(metadata)")
        refused = model_branch.index("showError(", activate)
        withdraw = model_branch.index('appearances.applySkin(targetUUID, "", null);')
        self.assertLess(refused, model_branch.index("return;", refused))
        self.assertLess(model_branch.index("return;", refused), withdraw)
        self.assertIn("appearances.hasActiveSkin(targetUUID)", model_branch)
        self.assertIn("ReplayModHelper.getTargetPlayerUUID()", model_branch)

        restorer = SAVED_APPEARANCE_RESTORER.read_text(encoding="utf-8")
        self.assertIn("!config.activeCpmModelHash.isEmpty()", restorer)
        self.assertIn("CpmLook.owner().withholdsQuickSkinSkin()", restorer)
        self.assertIn(".getAppearance(targetPlayerId) == null) {", restorer)
        self.assertIn(".applyLook(targetPlayerId, null, null, null);", restorer)
        self.assertLess(
            restorer.index(".applyLook(targetPlayerId, skinId, capeId, modelType);"),
            restorer.index(".applyLook(targetPlayerId, null, null, null);"),
        )

    def test_empty_skin_id_restores_the_vanilla_skin_and_model(self) -> None:
        source = PLAYER_APPEARANCE_SERVICE.read_text(encoding="utf-8")
        apply_look = source[
            source.index("public void applyLook(") : source.index(
                "} else if (model != null) {", source.index("public void applyLook(")
            )
        ]
        had_skin = apply_look.index(
            "boolean hadSkin = appearance.getSkinId() != null && !appearance.getSkinId().isEmpty();"
        )
        set_skin = apply_look.index("appearance.setSkinId(skinId);")
        empty = apply_look.index("if (skinId.isEmpty()) {")
        clear = apply_look.index("modelService.clearModelOverride(playerId);")
        refresh = apply_look.index("refreshVanillaSkinLookup(playerId);")
        override = apply_look.index("modelService.setModelOverride(playerId, requestedModel);")
        self.assertLess(had_skin, set_skin)
        self.assertLess(set_skin, empty)
        self.assertLess(empty, clear)
        self.assertLess(clear, refresh)
        self.assertLess(refresh, override)
        self.assertIn("if (hadSkin) {", apply_look[clear:refresh])
        self.assertIn("} else {", apply_look[refresh:override])
        self.assertEqual(1, apply_look.count("setModelOverride("))

        self.assertIn("QuickSkinSkinLookupAccess lookup", source)
        self.assertIn("lookup.quickskin$refreshSkinLookup();", source)
        self.assertIn("modelOverrides.remove(playerId);", MODEL_SERVICE.read_text(encoding="utf-8"))

        for path in PLAYER_INFO_MIXINS:
            mixin = path.read_text(encoding="utf-8")
            self.assertIn("implements com.quickskin.mod.client.compat.QuickSkinSkinLookupAccess", mixin)
            # Before 1.21.9 the lookup is final and built once; later getSkin() rebuilds a null one.
            self.assertIn("@Mutable", mixin)
            self.assertIn("this.skinLookup = createSkinLookup(this.profile);", mixin)
            self.assertIn("this.skinLookup = null;", mixin)

    def test_network_skin_arrival_refreshes_cpm_where_it_reads_quick_skin_skins(self) -> None:
        cache = NETWORK_TEXTURE_CACHE.read_text(encoding="utf-8")
        commit = cache[
            cache.index("private boolean commitPreparedTexture(") : cache.index(
                "public static final class PreparedTexture"
            )
        ]
        self.assertIn(
            'if (stored && existingOriginal == null && "skin".equals(textureType)) {', commit
        )
        self.assertIn("CPMCompatIntegration.onNetworkSkinStored();", commit)

        integration = CPM_INTEGRATION.read_text(encoding="utf-8")
        self.assertIn("public static void onNetworkSkinStored()", integration)
        hook = integration[
            integration.index("public static void onNetworkSkinStored()") : integration.index(
                "public static boolean isLocalPlayerWearingCpmModel()"
            )
        ]
        self.assertIn("CpmCapabilities.current().supportsHttpTextureBridge()", hook)
        self.assertIn("schedulePlayerCacheInvalidation();", hook)

    def test_legacy_player_info_discards_stale_skin_before_reregistering(self) -> None:
        active_common_overlays = frozenset(
            MATRIX["source_overlays"]["common"].values()
        )
        if "legacy1_20_1" not in active_common_overlays:
            self.assertFalse(LEGACY_PLAYER_INFO_MIXIN.exists())
            return

        self.assertTrue(LEGACY_PLAYER_INFO_MIXIN.is_file())
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
