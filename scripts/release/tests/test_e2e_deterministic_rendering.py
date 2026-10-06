from __future__ import annotations

import re
import tempfile
import unittest
from pathlib import Path

from scripts.architecture.source_inventory import java_source


ROOT = Path(__file__).resolve().parents[3]
BACKGROUND = (
    java_source('client/gui/util/BackgroundRenderer.java', source_set='main', repository=ROOT)
)
PANORAMA = (
    java_source('client/gui/util/PanoramaTimeSync.java', source_set='main', repository=ROOT)
)
PLAYER = (
    java_source('client/rendering/PlayerModelRenderer.java', source_set='main', repository=ROOT)
)
OPTIONS = ROOT / "e2e/options.txt.template"
SERVER_PROPERTIES = ROOT / "e2e/server-template/server.properties"
WORLD_DATA = ROOT / "e2e/server-template/datapack/data"
WORLD_GAMERULE_VARIANTS = (
    frozenset(
        {
            "gamerule doWeatherCycle false",
            "gamerule doDaylightCycle false",
            "gamerule doMobSpawning false",
            "gamerule spawnRadius 0",
        }
    ),
    frozenset(
        {
            "gamerule minecraft:advance_weather false",
            "gamerule minecraft:advance_time false",
            "gamerule minecraft:spawn_mobs false",
            "gamerule minecraft:respawn_radius 0",
        }
    ),
)
DEFAULT_SKIN_VIEW = (
    ROOT
    / "common/src/e2e/java/com/quickskin/mod/e2e/DefaultSkinEvidenceView.java"
)
E2E_HARNESS = (
    ROOT
    / "common/src/e2e/java/com/quickskin/mod/e2e/E2EHarness.java"
)
FULL_SCENARIO = (
    ROOT
    / "common/src/e2e/java/com/quickskin/mod/e2e/scenario/FullScenario.java"
)
VANILLA_SHIM = ROOT / "common/src/e2e/java/com/quickskin/mod/e2e/VanillaShim.java"


def world_function_paths(data_root: Path) -> tuple[Path, Path, Path, Path]:
    """Resolve the one coherent function layout allowed by the target pack format."""

    layouts = []
    for directory in ("functions", "function"):
        paths = (
            data_root / "qs_e2e" / directory / "load.mcfunction",
            data_root / "qs_e2e" / directory / "tick.mcfunction",
            data_root / "minecraft" / "tags" / directory / "load.json",
            data_root / "minecraft" / "tags" / directory / "tick.json",
        )
        if all(path.is_file() for path in paths):
            layouts.append(paths)
    if len(layouts) != 1:
        raise ValueError(
            "the E2E datapack must contain exactly one complete singular or plural "
            "function layout"
        )
    return layouts[0]


def world_gamerules(load_function: str) -> frozenset[str]:
    """Resolve the one complete deterministic gamerule vocabulary in the function."""

    lines = frozenset(line.strip() for line in load_function.splitlines())
    variants = [rules for rules in WORLD_GAMERULE_VARIANTS if rules <= lines]
    if len(variants) != 1:
        raise ValueError(
            "the E2E load function must contain exactly one complete legacy or "
            "namespaced gamerule set"
        )
    return variants[0]


class E2EDeterministicRenderingTest(unittest.TestCase):
    def test_star_scroll_is_frozen_only_for_e2e(self) -> None:
        source = BACKGROUND.read_text(encoding="utf-8")

        self.assertIn('Boolean.getBoolean("quickskin.e2e.enabled")', source)
        self.assertIn("? E2E_FIXED_GUI_TICK / 20.0", source)
        self.assertIn(": (tickCount + partialTick) / 20.0", source)
        self.assertIn("DETERMINISTIC_E2E_RENDER ? 0.0F : partialTick", source)

    def test_panorama_angle_is_shared_and_frozen_only_for_e2e(self) -> None:
        source = PANORAMA.read_text(encoding="utf-8")
        code = re.sub(r"//[^\n]*|/\*.*?\*/", "", source, flags=re.DOTALL)

        self.assertIn('Boolean.getBoolean("quickskin.e2e.enabled")', code)
        self.assertIn("E2E_FIXED_PANORAMA_SPIN = 0.0F;", code)
        self.assertEqual(
            1,
            len(
                re.findall(
                    r"if \(DETERMINISTIC_E2E_RENDER\) \{\s*return E2E_FIXED_PANORAMA_SPIN;\s*\}\s*"
                    r"return spinAt\(Util\.getMillis\(\)\);",
                    code,
                )
            ),
        )
        # The live clock is reduced to one whole turn in long arithmetic before it becomes a float:
        # a boot-relative clock (Very Many Players installs System::nanoTime) otherwise rounds to
        # 64 ms after six days of uptime, and the panorama moves at 15 Hz (issue #2050).
        self.assertIn("MILLIS_PER_TURN = 360L * MILLIS_PER_DEGREE;", code)
        self.assertIn(
            "return Math.floorMod(millis, MILLIS_PER_TURN) / (float) MILLIS_PER_DEGREE;", code
        )
        self.assertNotRegex(code, r"getMillis\(\)\s*/")
        # No reflective field guess: the renderer mixin names the angle through the mappings.
        self.assertNotIn("getDeclaredFields", code)
        self.assertNotIn("setFloat", code)

        mixin = re.sub(
            r"//[^\n]*|/\*.*?\*/",
            "",
            (ROOT / "common/src/main/java/com/quickskin/mod/mixin/PanoramaRendererMixin.java")
            .read_text(encoding="utf-8"),
            flags=re.DOTALL,
        )
        # Both API families (render before 26.1, extractRenderState after) take every read of the
        # renderer's spin from the shared clock, which carries the E2E freeze.
        for method, owner in (("render", "PanoramaRenderer"), ("extractRenderState", "Panorama")):
            with self.subTest(method=method):
                self.assertEqual(
                    1,
                    len(
                        re.findall(
                            r"@Redirect\(\s*method = \"" + method + r"\",\s*at = @At\(\s*"
                            r"value = \"FIELD\",\s*target = \"Lnet/minecraft/client/renderer/"
                            + owner
                            + r";spin:F\",\s*opcode = Opcodes\.GETFIELD\s*\),",
                            mixin,
                        )
                    ),
                )
        self.assertEqual(2, mixin.count("private float quickskin$sharedPanoramaSpin("))
        self.assertEqual(1, mixin.count("return PanoramaTimeSync.panoramaSpin();"))
        # Quick Skin's own panorama goes through the same redirected render call.
        self.assertNotIn("syncPanoramaRenderer", BACKGROUND.read_text(encoding="utf-8"))
        options = OPTIONS.read_text(encoding="utf-8")
        self.assertIn("panoramaScrollSpeed:0.0", options)
        self.assertNotIn("panoramaSpeed:0.0", options)

    def test_preview_pose_is_fixed_without_replacing_live_clocks(self) -> None:
        source = PLAYER.read_text(encoding="utf-8")

        self.assertIn('Boolean.getBoolean("quickskin.e2e.enabled")', source)
        self.assertIn("playerToRender.tickCount = E2E_FIXED_PREVIEW_TICK", source)
        self.assertEqual(
            2,
            len(
                re.findall(
                    r"if \(DETERMINISTIC_E2E_RENDER\) \{\s*"
                    r"playerToRender\.tickCount = originalTickCount;",
                    source,
                )
            ),
        )
        self.assertIn("? E2E_FIXED_ANIMATION_TIME_MS", source)
        self.assertIn(": System.currentTimeMillis()", source)
        self.assertIn("DETERMINISTIC_E2E_RENDER ? 1.0f : 0.15f", source)

    def test_preview_pins_the_previous_tick_rotation_for_the_draw(self) -> None:
        source = PLAYER.read_text(encoding="utf-8")

        for field, current in (
            ("yRotO", "getYRot()"),
            ("xRotO", "getXRot()"),
            ("yHeadRotO", "yHeadRot"),
            ("yBodyRotO", "yBodyRot"),
        ):
            saved = "original" + field[0].upper() + field[1:]
            with self.subTest(field=field):
                self.assertEqual(1, source.count(f"float {saved} = playerToRender.{field};"))
                self.assertEqual(
                    1, source.count(f"playerToRender.{field} = playerToRender.{current};")
                )
                self.assertEqual(2, source.count(f"playerToRender.{field} = {saved};"))

        # The pin copies the preview rotation, so it must follow it and precede the first draw.
        pin = source.index("playerToRender.yBodyRotO = playerToRender.yBodyRot;")
        self.assertLess(source.index("playerToRender.yBodyRot = targetRotation;"), pin)
        self.assertLess(pin, source.index("InventoryScreen.renderEntityInInventory("))

    def test_only_the_hud_preview_keeps_a_held_tacz_gun_visible(self) -> None:
        source = PLAYER.read_text(encoding="utf-8")

        # Both Stonecutter variants of the inline draw (before 1.21 and 1.21 to 1.21.5) decide what
        # the draw leaves visible and open the equipment scope with it; changing one alone compiles
        # on every target. The decision reads the held item, so it comes before the scope answers
        # the hand as empty, and before the cape is bound: nothing that reads the player may sit
        # between a binding and the try that releases it.
        self.assertEqual(
            2,
            len(
                re.findall(
                    r"Set<PreviewEquipmentPolicy\.Slot> visibleSlots =\s*"
                    r"previewVisibleSlots\(playerToRender, playerData\);\s*"
                    r"bindPreviewCape\(playerToRender, playerData\);\s*"
                    r"beginPreviewEquipment\(playerToRender, visibleSlots\);\s*"
                    r"try \{\s*"
                    r"InventoryScreen\.renderEntityInInventory\(",
                    source,
                )
            ),
        )
        self.assertEqual(2, source.count("previewVisibleSlots(playerToRender, playerData);"))
        self.assertEqual(2, source.count("beginPreviewEquipment(playerToRender, "))
        self.assertEqual(1, source.count("PREVIEW_EQUIPMENT_SCOPE.begin("))
        self.assertEqual(1, source.count("PREVIEW_EQUIPMENT_SCOPE.begin(player, visible);"))
        # The equipment-read hook asks the scope of this draw, which knows the visible slots. The
        # bare rule would hide the gun again, and no lane with TaCZ exists to notice.
        self.assertEqual(
            1,
            len(
                re.findall(
                    r"return PREVIEW_EQUIPMENT_SCOPE\.openScopes\(\) != 0\s*"
                    r"&& PREVIEW_EQUIPMENT_SCOPE\.suppresses\(player, previewSlotOf\(slot\)\);",
                    source,
                )
            ),
        )
        self.assertNotIn("PreviewEquipmentPolicy.suppresses(previewSlotOf(", source)
        decision = source[
            source.index("previewVisibleSlots(\n") : source.index(
                "public static boolean suppressesPreviewEquipment("
            )
        ]
        conditions = (
            "!playerData.isHeldGunVisible()",
            "player != Minecraft.getInstance().player",
            "PreviewHeldGun.TACZ.matches(player.getMainHandItem().getItem())",
        )
        for condition in conditions:
            self.assertEqual(1, decision.count(condition), condition)
        # A preview that did not ask pays no item read, and only the live local player qualifies.
        self.assertEqual(
            sorted(conditions, key=decision.index), list(conditions), "conditions out of order"
        )
        self.assertEqual(1, decision.count("return HELD_GUN_SLOTS;"))
        self.assertIn("EnumSet.of(PreviewEquipmentPolicy.Slot.MAIN_HAND)", source)
        # The render-state path (1.21.6 and newer, where no TaCZ exists) keeps blanking the hands.
        self.assertEqual(1, source.count("isHeldGunVisible()"))
        self.assertEqual(1, source.count("HELD_GUN_SLOTS;"))
        self.assertIn(
            "armed.rightArmPose = net.minecraft.client.model.HumanoidModel.ArmPose.EMPTY;", source
        )

        detector = PLAYER.with_name("PreviewHeldGun.java").read_text(encoding="utf-8")
        self.assertIn('PlatformHelper.isModLoaded("tacz")', detector)
        self.assertIn('"com.tacz.guns.api.item.IGun"', detector)
        self.assertNotIn("Class.forName(name, true", detector)
        self.assertNotIn("Class.forName(name)", detector)

        callers = []
        third_party_imports = []
        for owner in ("modules", "common", "fabric", "forge", "neoforge"):
            for path in sorted((ROOT / owner).glob("**/src/*/java/**/*.java")):
                relative = path.relative_to(ROOT).as_posix()
                if "/build/" in relative or "/src/test/" in relative:
                    continue
                text = path.read_text(encoding="utf-8")
                if ".markHeldGunVisible()" in text:
                    callers.append(path.name)
                if "import com.tacz." in text:
                    third_party_imports.append(relative)
        # The skin menu, the cape menu, the cape editor and the title and pause previews are
        # cosmetic: they keep showing the skin and the cape and nothing the player holds.
        self.assertEqual(["SkinPreviewOverlay.java"], callers)
        self.assertEqual([], third_party_imports)

    def test_tacz_animates_its_actions_only_while_the_hud_preview_shows_the_gun(self) -> None:
        source = PLAYER.read_text(encoding="utf-8")
        decision = source[
            source.index("previewVisibleSlots(\n") : source.index(
                "public static boolean suppressesPreviewEquipment("
            )
        ]
        # Only the draw that leaves the gun visible says that the preview shows it, after every
        # condition of that decision, and nothing else answers the hook. A mark made earlier would
        # let TaCZ animate for a menu preview, a hidden hand or another player.
        self.assertEqual(1, source.count("HELD_GUN_PRESENCE.drawn("))
        self.assertEqual(
            1,
            len(
                re.findall(
                    r"HELD_GUN_PRESENCE\.drawn\(player\);\s*return HELD_GUN_SLOTS;", decision
                )
            ),
        )
        self.assertEqual(1, source.count("HELD_GUN_PRESENCE.showing("))
        self.assertEqual(
            1,
            len(
                re.findall(
                    r"public static boolean previewShowsHeldGun\(Object player\) \{\s*"
                    r"return HELD_GUN_PRESENCE\.showing\(player\);\s*\}",
                    source,
                )
            ),
        )

        hook_path = (
            ROOT
            / "common/src/main/java/com/quickskin/mod/mixin/compat"
            / "TaczPreviewAnimationMixin.java"
        )
        code = re.sub(
            r"//[^\n]*|/\*.*?\*/", "", hook_path.read_text(encoding="utf-8"), flags=re.DOTALL
        )
        # TaCZ and Player Animator are named by string only: nothing to compile against, and a
        # target that is absent or never loaded leaves the mixin unapplied.
        self.assertEqual(1, code.count("@Pseudo"))
        self.assertIn(
            '@Mixin(targets = "com.tacz.guns.compat.playeranimator.animation.AnimationManager")',
            code,
        )
        for package in ("com.tacz", "dev.kosmx"):
            self.assertNotIn("import " + package, code)
        # One hook into TaCZ, and it is the redirect of the vanilla camera question in the four
        # handlers that start TaCZ's action animations. It starts, stops and chooses nothing.
        self.assertEqual(
            ["Redirect"], re.findall(r"@(?:\w+\.)*(\w+)\s*\(\s*method\b", code)
        )
        self.assertEqual(1, code.count("@At("))
        methods = re.search(r"method = \{(.*?)\}", code, flags=re.DOTALL)
        self.assertIsNotNone(methods)
        assert methods is not None
        self.assertEqual(
            [
                "onFire(Lcom/tacz/guns/api/event/common/GunShootEvent;)V",
                "onReload(Lcom/tacz/guns/api/event/common/GunReloadEvent;)V",
                "onMelee(Lcom/tacz/guns/api/event/common/GunMeleeEvent;)V",
                "onDraw(Lcom/tacz/guns/api/event/common/GunDrawEvent;)V",
            ],
            re.findall(r'"([^"]*)"', methods.group(1)),
        )
        # The first camera question of each handler and no other: the count cannot pass four.
        self.assertEqual(
            1,
            len(
                re.findall(
                    r'at = @At\(\s*value = "INVOKE",\s*'
                    r'target = "Lnet/minecraft/client/CameraType;isFirstPerson\(\)Z",\s*'
                    r"ordinal = 0,\s*remap = true\s*\)",
                    code,
                )
            ),
        )
        # TaCZ's own answer in every case but one: first person stays first person unless the HUD
        # preview is showing the local player with the gun. Third person is never changed. The
        # handler is an instance method because TaCZ's handlers are: Mixin rejects a redirect whose
        # static modifier differs from its target, and the whole mixin with it (seen at runtime).
        self.assertEqual(
            1,
            len(
                re.findall(
                    r"private boolean quickskin\$firstPersonUnlessPreviewed"
                    r"\(CameraType camera\) \{\s*"
                    r"return camera\.isFirstPerson\(\)\s*"
                    r"&& !PlayerModelRenderer\.previewShowsHeldGun\("
                    r"Minecraft\.getInstance\(\)\.player\);\s*\}",
                    code,
                )
            ),
        )

        askers = []
        for owner in ("modules", "common", "fabric", "forge", "neoforge"):
            for path in sorted((ROOT / owner).glob("**/src/*/java/**/*.java")):
                relative = path.relative_to(ROOT).as_posix()
                if "/build/" in relative or "/src/test/" in relative:
                    continue
                if ".previewShowsHeldGun(" in path.read_text(encoding="utf-8"):
                    askers.append(path.name)
        self.assertEqual(["TaczPreviewAnimationMixin.java"], askers)

        # An overlay copy of the optional mixin config replaces the canonical one. The 1.20.1
        # overlay is the lane official TaCZ exists for, and no lane with TaCZ would notice.
        configs = sorted((ROOT / "common/src").glob("*/resources/quickskin-ears.mixins.json"))
        self.assertIn(ROOT / "common/src/main/resources/quickskin-ears.mixins.json", configs)
        self.assertIn(
            ROOT / "common/src/legacy1_20_1/resources/quickskin-ears.mixins.json", configs
        )
        for config in configs:
            with self.subTest(config=config.relative_to(ROOT).as_posix()):
                self.assertIn('"TaczPreviewAnimationMixin"', config.read_text(encoding="utf-8"))
        # Gated on TaCZ's class file like Ears, so a client without TaCZ never looks for the
        # target: an absent @Pseudo target costs a Mixin warning at every start (seen at runtime).
        plugin = hook_path.with_name("EarsMixinPlugin.java").read_text(encoding="utf-8")
        self.assertIn('"TaczPreviewAnimationMixin"', plugin)
        self.assertIn(
            '"com/tacz/guns/compat/playeranimator/animation/AnimationManager.class"', plugin
        )
        self.assertEqual(
            1,
            len(
                re.findall(
                    r"if \(mixinNamed\(mixinClassName, TACZ_PREVIEW_ANIMATION_MIXIN\)\) \{\s*"
                    r"return classFileExists\(TACZ_ANIMATION_MANAGER\);\s*\}",
                    plugin,
                )
            ),
        )

    def test_disposable_world_uses_a_fixed_spawn(self) -> None:
        properties = SERVER_PROPERTIES.read_text(encoding="utf-8")
        world_load, world_tick, _world_load_tag, world_tick_tag = world_function_paths(
            WORLD_DATA
        )
        load_function = world_load.read_text(encoding="utf-8")

        self.assertIn("level-seed=quickskin-e2e", properties)
        for setting in (
            "difficulty=peaceful",
            "spawn-monsters=false",
            "spawn-animals=false",
            "spawn-npcs=false",
        ):
            self.assertIn(setting, properties)
        self.assertIn(world_gamerules(load_function), WORLD_GAMERULE_VARIANTS)
        self.assertIn("team modify qs_e2e collisionRule never", load_function)
        self.assertIn(
            "team join qs_e2e @a[team=!qs_e2e]",
            world_tick.read_text(encoding="utf-8"),
        )
        self.assertIn(
            "execute as @e[type=!minecraft:player,tag=!qs_e2e_keep] at @s "
            "run tp @s ~ -1024 ~",
            world_tick.read_text(encoding="utf-8"),
        )
        self.assertIn('"qs_e2e:tick"', world_tick_tag.read_text(encoding="utf-8"))

    def test_world_function_layout_accepts_both_pack_format_spellings(self) -> None:
        for directory in ("functions", "function"):
            with self.subTest(directory=directory), tempfile.TemporaryDirectory() as tmp:
                data_root = Path(tmp)
                paths = (
                    data_root / "qs_e2e" / directory / "load.mcfunction",
                    data_root / "qs_e2e" / directory / "tick.mcfunction",
                    data_root / "minecraft" / "tags" / directory / "load.json",
                    data_root / "minecraft" / "tags" / directory / "tick.json",
                )
                for path in paths:
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_text("test\n", encoding="utf-8")
                self.assertEqual(paths, world_function_paths(data_root))

    def test_world_gamerules_accept_both_version_vocabularies(self) -> None:
        for rules in WORLD_GAMERULE_VARIANTS:
            with self.subTest(rules=rules):
                self.assertEqual(rules, world_gamerules("\n".join(sorted(rules))))

    def test_world_player_interpolation_is_pinned_by_the_e2e_harness(self) -> None:
        source = DEFAULT_SKIN_VIEW.read_text(encoding="utf-8")
        shim = VANILLA_SHIM.read_text(encoding="utf-8")

        self.assertIn("player.tickCount = FIXED_RENDER_TICK", source)
        self.assertIn("player.walkAnimation.setSpeed(0.0F)", source)
        self.assertIn("VanillaShim.resetWalkDistance(player)", source)
        self.assertNotIn("player.walkDist =", source)
        self.assertNotIn("player.walkDistO =", source)
        for mapping_name in (
            "field_5973",
            "field_6039",
            "field_53039",
            "field_53038",
            "field_62569",
            "field_62570",
            "f_19787_",
            "f_19867_",
        ):
            self.assertIn(f'"{mapping_name}"', shim)
        self.assertIn('"avatarState", "method_74192"', shim)
        self.assertIn("player.xo = player.xOld = player.getX()", source)
        self.assertIn(
            "DefaultSkinEvidenceView.pinStandingMotion(mc.player)",
            E2E_HARNESS.read_text(encoding="utf-8"),
        )

    def test_capture_is_dispatched_after_the_counted_render_pass_completes(self) -> None:
        source = E2E_HARNESS.read_text(encoding="utf-8")
        rendered_callback = source.split("private void onRenderedFrame()", 1)[1].split(
            "private void dispatchReadyCapture", 1
        )[0]

        self.assertIn("renderedFrame++;", rendered_callback)
        self.assertNotIn("VanillaShim.screenshot", rendered_callback)
        self.assertIn("private void dispatchReadyCapture(Minecraft mc)", source)
        self.assertIn("dispatchReadyCapture(mc);", source)
        self.assertIn("captured completed rendered frame", source)

    def test_hud_preview_targets_the_authored_lower_right_region(self) -> None:
        source = FULL_SCENARIO.with_name("HudPreviewSteps.java").read_text(encoding="utf-8")

        self.assertIn("positionHudOverlayForEvidence(mc, ClientConfig.getInstance())", source)
        self.assertIn("Math.round(screenWidth * 0.89f)", source)
        self.assertIn("Math.round(screenHeight * 0.96f)", source)
        self.assertIn('overlayCachedInt("cachedModelCenterX")', source)
        self.assertIn("overlayGeometryFailure(mc)", source)


if __name__ == "__main__":
    unittest.main()
