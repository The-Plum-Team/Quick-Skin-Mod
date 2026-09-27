"""The repository matches the pinned mod-base template (mod-base SPEC 8.2 and ADR 0010).

``template check`` compares the managed files byte for byte with the pinned kit and checks the
fragment files, the ``AGENTS.md`` grammar, the managed-docs link rule and the caller's extension
region. The kit is reached only through ``scripts/ci/tests/mod_base_path.py``; an unavailable kit
fails these tests instead of skipping them.
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts" / "ci" / "tests"))

import mod_base_path  # noqa: E402


LOCAL_AGENT_IMPORTS = [
    "docs/ai/PROJECT.md",
    "docs/ai/SOURCE-ARCHITECTURE.md",
    "docs/ai/RUNTIME-INVARIANTS.md",
    "docs/ai/WORKFLOW.md",
]
BOOTSTRAP = ROOT / "scripts" / "ci" / "mod_base_kit.py"
CLI_TIMEOUT_SECONDS = 600


def describe(drifts: list) -> list[str]:
    return [f"{drift.kind}: {drift.path}\n{drift.detail}" for drift in drifts]


class ModBaseTemplateTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.kit_root = mod_base_path.kit_root()
        from mod_base.template import tool

        cls.tool = tool

    def test_template_check_reports_no_drift(self) -> None:
        drifts, pending = self.tool.evaluate(ROOT, kit_root=self.kit_root)
        self.assertEqual(describe(drifts), [])
        # Quick Skin adopts the template in one pull request, so nothing may stay pending.
        self.assertEqual(describe(pending), [])

    def test_adoption_defers_nothing_and_imports_the_local_guides_in_order(self) -> None:
        config = json.loads((ROOT / "site" / "mod-base.json").read_text(encoding="utf-8"))
        self.assertEqual(config["template"]["deferred"], [])
        self.assertEqual(config["template"]["agents_local"], LOCAL_AGENT_IMPORTS)

    def test_managed_shared_guides_are_the_kit_bytes(self) -> None:
        manifest = self.tool.load_manifest(self.kit_root)
        shared = [
            entry
            for entry in manifest["files"]
            if entry["class"] == "managed" and entry["path"].startswith("docs/ai/shared/")
        ]
        self.assertEqual(
            sorted(entry["path"] for entry in shared),
            ["docs/ai/shared/PUBLIC-EVIDENCE.md", "docs/ai/shared/REPOSITORY.md"],
        )
        for entry in shared:
            with self.subTest(path=entry["path"]):
                expected = (self.kit_root / "template" / entry["source"]).read_bytes()
                self.assertEqual((ROOT / entry["path"]).read_bytes(), expected)

    def test_bootstrap_command_reports_a_clean_template(self) -> None:
        # The exact command the Build policy job and the contributor guide run.
        environment = dict(os.environ, PYTHONDONTWRITEBYTECODE="1")
        result = subprocess.run(
            [sys.executable, str(BOOTSTRAP), "run", "template", "check", "--repo", str(ROOT)],
            cwd=ROOT,
            env=environment,
            stdin=subprocess.DEVNULL,
            capture_output=True,
            text=True,
            timeout=CLI_TIMEOUT_SECONDS,
            check=False,
        )
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
