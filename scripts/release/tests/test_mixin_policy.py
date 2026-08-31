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
ACTIVE_LOADERS = tuple(sorted({artifact["loader"] for artifact in MATRIX["artifacts"]}))
LOADER_JAVA = {
    loader: ROOT / loader / "src" / "main" / "java"
    for loader in ACTIVE_LOADERS
    if (ROOT / loader / "src" / "main" / "java").is_dir()
}
LOADER_RESOURCES = {
    loader: ROOT / loader / "src" / "main" / "resources"
    for loader in ACTIVE_LOADERS
    if (ROOT / loader / "src" / "main" / "resources").is_dir()
}
MIXIN_MARKER = re.compile(r"@Mixin\s*\(")
INJECTOR_MARKER = re.compile(r"@(Inject|ModifyArg|Redirect)\s*\(")
HANDLER_MARKER = re.compile(r"\b(quickskin\$[A-Za-z0-9_$]+)\s*\(")
JAVA_COMMENT = re.compile(r"//[^\n]*|/\*.*?\*/", re.DOTALL)


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
        for loader, source_root in LOADER_JAVA.items():
            try:
                return f"{loader}:" + path.relative_to(source_root).as_posix()
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
    "neoforge:com/quickskin/mod/neoforge/mixin/CapeLayerMixin.java",
    "neoforge:com/quickskin/mod/neoforge/mixin/MixinAbstractClientPlayer.java",
    "neoforge:com/quickskin/mod/neoforge/mixin/PlayerInfoMixin.java",
    "neoforge:com/quickskin/mod/neoforge/mixin/SkinManagerMixin.java",
}

# These are real Quick Skin features, but losing one hook should degrade only that rendering or UI
# enhancement in production. Packaged E2E enables Mixin's debug count and turns expect=1 into a
# deterministic compatibility gate.
DEGRADABLE_MIXINS = {
    "main:com/quickskin/mod/mixin/GuiSkinRendererMixin.java",
    "main:com/quickskin/mod/mixin/PanoramaRendererMixin.java",
    "main:com/quickskin/mod/mixin/PreviewEquipmentMixin.java",
    "neoforge:com/quickskin/mod/neoforge/mixin/GuiSkinRendererMixin.java",
    "neoforge:com/quickskin/mod/neoforge/mixin/PlayerRendererMixin.java",
    "neoforge:com/quickskin/mod/neoforge/mixin/PreviewEquipmentMixin.java",
}

# Third-party integrations remain fail-local in production. Their targets are either selected by
# the optional-config plugin or are harmless vanilla interception points whose handlers no-op when
# the integration is absent.
OPTIONAL_MIXINS = {
    "main:com/quickskin/mod/mixin/compat/CpmModelDefinitionLoaderMixin.java",
    "main:com/quickskin/mod/mixin/compat/CpmRenderDepthMixin.java",
    "main:com/quickskin/mod/mixin/compat/CpmSubmitCollectorMixin.java",
    "main:com/quickskin/mod/mixin/compat/EarsLayerRendererMixin.java",
    "main:com/quickskin/mod/mixin/compat/EarsModMixin.java",
    "overlay:com/quickskin/mod/mixin/MixinSkinManager.java",
    "overlay:com/quickskin/mod/mixin/compat/ReplayModCompatMixin.java",
}

ACCESSOR_ONLY_MIXINS: set[str] = set()

# CPM changed the call made by playerRenderPre. Both optional injection points are kept so one
# source supports both eras, which means neither alternative can truthfully declare expect=1.
ALTERNATIVE_HOOKS = {
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
}

# Audited vanilla bytecode multiplicities. Before 1.21.2 renderHand requests two buffers (arm and
# sleeve); 1.21.2 through 1.21.8 make one immediate arm draw. The collector used from 1.21.9 onward
# is deliberately not intercepted. SkinManager 1.20.1 has two RETURN opcodes in its one target method; in 1.21.11,
# NeoForge's patched getInsecureSkin changes from two returns to one at 1.21.6. In 1.21.11,
# createLookup and get have three and two returns respectively on both loaders.
INJECTION_COUNT_OVERRIDES = {
    (
        "main:com/quickskin/mod/mixin/ItemInHandRendererMixin.java",
        "quickskin$redirectRenderHandBuffer",
    ): {1, 2},
    (
        "overlay:com/quickskin/mod/mixin/MixinSkinManager.java",
        "quickskin$overrideSkinInfo",
    ): {2},
    (
        "neoforge:com/quickskin/mod/neoforge/mixin/PlayerRendererMixin.java",
        "quickskin$redirectRenderHandBuffer",
    ): {1, 2},
    (
        "main:com/quickskin/mod/mixin/SkinManagerMixin.java",
        "quickskin$modifyInsecureSkin",
    ): {1, 2},
    (
        "neoforge:com/quickskin/mod/neoforge/mixin/SkinManagerMixin.java",
        "quickskin$modifyInsecureSkin",
    ): {1, 2},
    (
        "main:com/quickskin/mod/mixin/SkinManagerMixin.java",
        "quickskin$modifyCreateLookup",
    ): {3},
    (
        "main:com/quickskin/mod/mixin/SkinManagerMixin.java",
        "quickskin$modifyGet",
    ): {2},
    (
        "neoforge:com/quickskin/mod/neoforge/mixin/SkinManagerMixin.java",
        "quickskin$modifyCreateLookup",
    ): {3},
    (
        "neoforge:com/quickskin/mod/neoforge/mixin/SkinManagerMixin.java",
        "quickskin$modifyGet",
    ): {2},
}

ALLOW_COUNT_OVERRIDES = {
    (
        "main:com/quickskin/mod/mixin/compat/EarsLayerRendererMixin.java",
        "quickskin$getEarsFeatures",
    ): {2},
    (
        "main:com/quickskin/mod/mixin/compat/EarsModMixin.java",
        "quickskin$getEarsFeatures",
    ): {2},
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
        for source_root in (CANONICAL_JAVA, *OVERLAY_JAVA, *LOADER_JAVA.values()):
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
                    self.assertEqual(
                        assignment_values(context, "allow"),
                        ALLOW_COUNT_OVERRIDES.get((source_name, handler_name), expected_counts),
                    )
                    if (source_name, handler_name) in ALTERNATIVE_HOOKS:
                        self.assertEqual(assignment_values(context, "expect"), {0})
                    else:
                        self.assertEqual(assignment_values(context, "expect"), expected_counts)

    def test_neoforge_insecure_skin_count_tracks_the_patched_bytecode_boundary(self) -> None:
        source = (
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
            / "SkinManagerMixin.java"
        ).read_text(encoding="utf-8")

        self.assertRegex(
            source,
            r'//\? if <1\.21\.6 \{\s+'
            r'expect = 2,\s+allow = 2\s+'
            r'//\?\} else \{\s+'
            r'expect = 1,\s+allow = 1\s+'
            r'//\?\}',
        )

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

    def test_loader_specific_core_configs_fail_closed(self) -> None:
        for loader, resource_root in LOADER_RESOURCES.items():
            path = resource_root / f"quickskin-{loader}.mixins.json"
            if not path.is_file():
                continue
            with self.subTest(config=relative(path)):
                config = json.loads(path.read_text(encoding="utf-8"))
                self.assertIs(config["required"], True)
                self.assertEqual(config["injectors"]["defaultRequire"], 1)

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

    def test_packaged_clients_enable_expect_counting(self) -> None:
        runtime = (ROOT / "e2e" / "packaged_runtime.py").read_text(encoding="utf-8")
        self.assertIn('"-Dmixin.debug.countInjections=true"', runtime)


if __name__ == "__main__":
    unittest.main()
