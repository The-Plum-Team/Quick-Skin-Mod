"""Mutations that must never turn an unproven dependency into selective coverage."""

from __future__ import annotations

import copy
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from module_graph import (  # noqa: E402
    GRAPH_PATH,
    MAX_GRAPH_BYTES,
    ModuleGraphError,
    load_graph,
    parse_graph,
)


def module(module_id, *, api=(), implementation=(), runtime_only=(), environment="common"):
    return {
        "id": module_id,
        "path": "modules/" + module_id,
        "kind": "java-library",
        "environment": environment,
        "api": list(api),
        "implementation": list(implementation),
        "runtime_only": list(runtime_only),
        "libraries": {},
    }


class ModuleGraphTest(unittest.TestCase):
    def setUp(self):
        self.data = {
            "schema_version": 1,
            "libraries": {},
            "bindings": [],
            "modules": [
                module("editor", implementation=["textures"]),
                module("textures", api=["identity"]),
                module("identity"),
                module("settings"),
                module("integration", runtime_only=["editor"]),
            ],
        }

    def graph(self, data=None):
        return parse_graph(json.dumps(self.data if data is None else data).encode())

    def test_leaf_change_reaches_transitive_and_runtime_consumers(self):
        graph = self.graph()
        self.assertEqual(
            ("editor", "identity", "integration", "textures"),
            graph.affected_modules(["modules/identity/src/main/java/Id.java"]),
        )
        self.assertEqual(("editor", "integration"), graph.affected_modules([
            "modules/editor/src/main/resources/preview.png",
        ]))
        self.assertEqual(("settings",), graph.affected_modules([
            "modules/settings/src/main/java/Settings.java",
        ]))

    def test_feature_cannot_import_the_final_assembly(self):
        self.data["modules"][1]["kind"] = "minecraft-assembly"
        self.data["modules"][0]["kind"] = "minecraft"
        with self.assertRaisesRegex(ModuleGraphError, "assembled mod"):
            self.graph()

    def provider_graph(self, impact="propagate"):
        data = copy.deepcopy(self.data)
        data["modules"] += [module("provider", api=["identity"]),
                            module("bootstrap", implementation=["editor", "provider", "identity"])]
        data["bindings"] = [dict(id="editor-adapter", api="identity", providers=["provider"],
                                 consumers=["editor"], composition="bootstrap", impact=impact)]
        return data

    def test_provider_change_reaches_bound_consumer_without_affecting_every_api_user(self):
        graph = self.graph(self.provider_graph())
        affected = graph.affected_modules(["modules/provider/src/main/java/Adapter.java"])
        self.assertEqual(("bootstrap", "editor", "integration", "provider"), affected)
        self.assertNotIn("textures", affected)
        self.assertEqual(("editor-adapter",), graph.affected_bindings(affected))

    def test_runtime_feedback_reaches_a_fixed_point_without_a_compile_cycle(self):
        data = self.provider_graph()
        data["bindings"].append(dict(id="feedback", api="identity", providers=["editor"],
                                     consumers=["provider"], composition="bootstrap", impact="propagate"))
        graph = self.graph(data)
        self.assertEqual(("bootstrap", "editor", "integration", "provider"), graph.affected_modules([
            "modules/editor/src/main/java/Editor.java"]))

    def test_coverage_binding_requires_an_interaction_without_rewriting_consumer_exports(self):
        graph = self.graph(self.provider_graph("coverage"))
        affected = graph.affected_modules(["modules/provider/src/main/java/Adapter.java"])
        self.assertEqual(("bootstrap", "provider"), affected)
        self.assertEqual(("editor-adapter",), graph.affected_bindings(affected))

    def test_unchanged_composition_does_not_couple_every_feature(self):
        graph = self.graph(self.provider_graph())
        affected = graph.affected_modules(["modules/editor/src/main/java/Editor.java"])
        self.assertNotIn("provider", affected)
        # Direct wiring edits do require the bound consumer.
        self.assertIn("editor", graph.affected_modules(["modules/bootstrap/Bindings.java"]))

    def test_invalid_or_inaccessible_binding_is_rejected(self):
        for mutation in ("unknown-provider", "duplicate", "hidden-api", "missing-wiring", "unknown-impact"):
            data = self.provider_graph()
            if mutation == "unknown-provider":
                data["bindings"][0]["providers"] = ["absent"]
            elif mutation == "duplicate":
                data["bindings"].append(copy.deepcopy(data["bindings"][0]))
            elif mutation == "hidden-api":
                next(m for m in data["modules"] if m["id"] == "textures")["api"] = []
                next(m for m in data["modules"] if m["id"] == "textures")["implementation"] = ["identity"]
            elif mutation == "missing-wiring":
                data["bindings"][0]["composition"] = "integration"
            else:
                data["bindings"][0]["impact"] = "ignore"
            with self.subTest(mutation=mutation), self.assertRaises(ModuleGraphError):
                self.graph(data)

    def test_unknown_empty_and_sibling_prefix_require_full_coverage(self):
        graph = self.graph()
        for paths in (
            [],
            ["modules/editor-extra/src/main/java/Screen.java"],
            ["modules/editor/src/main/java/Screen.java", "unknown/Bridge.java"],
            [GRAPH_PATH],
        ):
            with self.subTest(paths=paths):
                self.assertIsNone(graph.affected_modules(paths))

    def test_deterministic_order_and_dependency_closure(self):
        forward = self.graph()
        self.data["modules"].reverse()
        backward = self.graph()
        self.assertEqual(forward.order, backward.order)
        self.assertEqual(("identity", "textures", "editor"), forward.dependencies_of("integration"))
        self.assertEqual((), forward.dependencies_of("settings"))
        with self.assertRaises(ModuleGraphError):
            forward.dependencies_of("missing")

    def test_graph_hash_binds_exact_input_bytes(self):
        raw = json.dumps(self.data).encode()
        self.assertNotEqual(parse_graph(raw).sha256, parse_graph(raw + b"\n").sha256)

    def test_cycle_is_rejected_even_when_one_edge_is_runtime_only(self):
        self.data["modules"][2]["runtime_only"] = ["integration"]
        with self.assertRaisesRegex(ModuleGraphError, "cycle"):
            self.graph()

    def test_missing_self_and_duplicate_dependencies_are_rejected(self):
        for api, implementation in ((["missing"], []), (["editor"], []),
                                     (["identity", "identity"], []),
                                     (["identity"], ["identity"])):
            with self.subTest(api=api, implementation=implementation):
                data = copy.deepcopy(self.data)
                data["modules"][0]["api"] = api
                data["modules"][0]["implementation"] = implementation
                with self.assertRaises(ModuleGraphError):
                    self.graph(data)

    def test_ambiguous_ownership_and_invalid_paths_are_rejected(self):
        for path in ("modules/editor", "modules/editor/nested", "../editor", "/tmp/editor",
                     "modules//editor", "modules/./editor", "modules\\editor", ".", "C:/editor"):
            with self.subTest(path=path):
                data = copy.deepcopy(self.data)
                data["modules"][1]["path"] = path
                with self.assertRaises(ModuleGraphError):
                    self.graph(data)

    def test_environment_leaks_cannot_hide_behind_transitive_dependencies(self):
        for environment in ("client", "server", "mixed"):
            with self.subTest(environment=environment):
                data = copy.deepcopy(self.data)
                data["modules"][2]["environment"] = environment
                with self.assertRaisesRegex(ModuleGraphError, "environment leak"):
                    self.graph(data)

    def test_pure_library_cannot_depend_on_a_minecraft_module(self):
        self.data["modules"][2]["kind"] = "minecraft"
        with self.assertRaisesRegex(ModuleGraphError, "Minecraft dependency"):
            self.graph()

    def test_libraries_pin_one_coordinate_and_known_scopes(self):
        self.data["libraries"] = {"json": "com.google.code.gson:gson:2.10"}
        self.data["modules"][0]["libraries"] = {
            "compile_only": ["json"], "test_runtime_only": ["json"],
        }
        graph = self.graph()
        self.assertEqual(self.data["libraries"], graph.to_dict()["libraries"])
        self.assertEqual(self.data["modules"][0]["libraries"],
                         graph.by_id["editor"].to_dict()["libraries"])
        for coordinate in ("gson", "group:gson:2.+", "group:gson:[2,3)",
                           "group:gson:2-SNAPSHOT", "group:gson:latest", None):
            with self.subTest(coordinate=coordinate):
                data = copy.deepcopy(self.data)
                data["libraries"]["json"] = coordinate
                with self.assertRaises(ModuleGraphError):
                    self.graph(data)
        for scopes in ({"implementation": ["missing"]}, {"anything": ["json"]},
                       {"api": ["json", "json"]}, {"api": ["json"], "compile_only": ["json"]},
                       {"api": "json"}):
            with self.subTest(scopes=scopes):
                data = copy.deepcopy(self.data)
                data["modules"][0]["libraries"] = scopes
                with self.assertRaises(ModuleGraphError):
                    self.graph(data)
        self.data["libraries"]["other-json"] = "com.google.code.gson:gson:2.14.0"
        with self.assertRaisesRegex(ModuleGraphError, "duplicate library"):
            self.graph()

    def test_schema_duplicates_and_oversized_inputs_are_rejected(self):
        raw = json.dumps(self.data).encode()
        for invalid in (b"", raw.replace(b'"schema_version": 1',
                                        b'"schema_version": 1, "schema_version": 1'),
                        b" " * (MAX_GRAPH_BYTES + 1), b"[]", b"{", b"\xff"):
            with self.subTest(length=len(invalid)):
                with self.assertRaises(ModuleGraphError):
                    parse_graph(invalid)
        for field, value in (("schema_version", True), ("schema_version", 2), ("modules", [])):
            data = copy.deepcopy(self.data)
            data[field] = value
            with self.assertRaises(ModuleGraphError):
                self.graph(data)
        for key in ("unrecognized", "features", "versions"):
            data = copy.deepcopy(self.data)
            data["modules"][0][key] = []
            with self.assertRaises(ModuleGraphError):
                self.graph(data)

    def test_disk_loading_rejects_missing_and_linked_module_directories(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            path = root / GRAPH_PATH
            path.parent.mkdir()
            path.write_text(json.dumps(self.data))
            with self.assertRaisesRegex(ModuleGraphError, "missing or linked"):
                load_graph(root)
            for row in self.data["modules"]:
                (root / row["path"]).mkdir(parents=True)
            self.assertEqual(self.graph().order, load_graph(root).order)
            directory = root / "modules/editor"
            directory.rmdir()
            directory.symlink_to(root / "modules/settings", target_is_directory=True)
            with self.assertRaisesRegex(ModuleGraphError, "missing or linked"):
                load_graph(root)


if __name__ == "__main__":
    unittest.main()
