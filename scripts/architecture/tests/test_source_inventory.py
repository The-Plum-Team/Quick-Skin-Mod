from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from scripts.architecture.module_graph import ModuleGraphError
from scripts.architecture.source_inventory import java_source, java_sources


class SourceInventoryTest(unittest.TestCase):
    def setUp(self) -> None:
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        modules = []
        for name in ("api", "feature"):
            (self.root / "modules" / name).mkdir(parents=True)
            modules.append(dict(id=name, path=f"modules/{name}", kind="java-library",
                                environment="common", api=[], implementation=[], runtime_only=[], libraries={}))
        (self.root / "architecture").mkdir()
        (self.root / "architecture/modules.json").write_text(
            json.dumps(dict(schema_version=1, libraries={}, modules=modules)))

    def source(self, module: str, source_set: str = "main") -> Path:
        path = self.root / "modules" / module / "src" / source_set / "java/com/quickskin/mod/Example.java"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("package com.quickskin.mod; class Example {}\n")
        return path

    def test_source_move_changes_owner_without_changing_policy_lookup(self) -> None:
        old = self.source("api")
        self.assertEqual(old, java_source("Example.java", repository=self.root))
        new = self.source("feature")
        old.unlink()
        self.assertEqual(new, java_source("Example.java", repository=self.root))
        self.assertEqual((new,), java_sources(repository=self.root))

    def test_ambiguous_or_missing_source_cannot_silently_skip_policy(self) -> None:
        with self.assertRaises(ModuleGraphError):
            java_source("Example.java", repository=self.root)
        self.source("api")
        self.source("feature")
        with self.assertRaises(ModuleGraphError):
            java_source("Example.java", repository=self.root)

    def test_overlay_selection_is_explicit_and_paths_cannot_escape(self) -> None:
        canonical = self.source("feature")
        legacy = self.source("feature", "legacy1_20_1")
        self.assertEqual(canonical, java_source("Example.java", repository=self.root))
        self.assertEqual(legacy, java_source("Example.java", source_set="legacy1_20_1", repository=self.root))
        for path in ("../Example.java", "/Example.java", "a/../Example.java"):
            with self.assertRaises(ModuleGraphError):
                java_source(path, repository=self.root)
        canonical.unlink()
        canonical.symlink_to(legacy)
        with self.assertRaises(ModuleGraphError):
            java_sources(repository=self.root)


if __name__ == "__main__":
    unittest.main()
