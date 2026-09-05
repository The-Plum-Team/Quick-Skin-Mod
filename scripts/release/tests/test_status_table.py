from __future__ import annotations

import sys
import copy
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts" / "release"))

import status_table  # noqa: E402
import matrix as release_matrix  # noqa: E402


def matrix_for(version: str, loaders: tuple[str, ...], java: int) -> dict[str, object]:
    return {
        "artifacts": [
            {
                "artifact_version": version,
                "loader": loader,
                "java": java,
            }
            for loader in loaders
        ]
    }


class ReleaseStatusTableTest(unittest.TestCase):
    def test_shared_source_lists_exact_matrix_targets_with_shared_badges_and_independent_tags(self):
        path = ROOT / "release/release-matrix.json"
        data = release_matrix.load_matrix(path)
        version = release_matrix.read_mod_version(path, data)
        section = status_table.render_shared_status_section(data,
            repository="The-Plum-Team/Quick-Skin-Mod", mod_version=version)
        targets = {row["artifact_version"] for row in data["artifacts"]}
        rows = [line.split(" | ")[0][2:] for line in section.splitlines() if line.startswith("| ")][1:]
        self.assertEqual(sorted(targets, key=lambda value: tuple(map(int, value.split("."))), reverse=True), rows)
        self.assertEqual(2, section.count("/badge.svg?branch=master"))
        self.assertNotIn("fabric-and-neoforge-", section)
        self.assertNotIn("forge-and-fabric-", section)
        for target in targets:
            self.assertIn(f"/releases/tag/mc{target}-v{version}", section)
        invalid = copy.deepcopy(data)
        invalid["artifacts"][-1]["java"] = 0
        with self.assertRaises(release_matrix.MatrixError):
            status_table.render_shared_status_section(invalid,
                repository="The-Plum-Team/Quick-Skin-Mod", mod_version=version)

    def test_shared_cli_does_not_discover_or_read_historical_refs(self):
        with tempfile.TemporaryDirectory() as temporary:
            readme = Path(temporary) / "README.md"
            readme.write_text("before\n" + status_table.START_MARKER + "\nold\n"
                              + status_table.END_MARKER + "\nafter\n")
            arguments = ["--matrix", str(ROOT / "release/release-matrix.json"),
                         "--repository", "The-Plum-Team/Quick-Skin-Mod", "--readme", str(readme)]
            with patch.object(status_table, "load_discovered_matrices", side_effect=AssertionError("read historical refs")):
                self.assertEqual(0, status_table.main([*arguments, "--write"]))
                self.assertEqual(0, status_table.main([*arguments, "--check"]))
            self.assertTrue(readme.read_text().startswith("before\n"))
            self.assertTrue(readme.read_text().endswith("\nafter\n"))

    def test_renders_newest_first_with_branch_specific_badges(self) -> None:
        section = status_table.render_status_section(
            {
                "forge-and-fabric-1.20.1": matrix_for(
                    "1.20.1", ("fabric", "forge"), 17
                ),
                "fabric-and-neoforge-1.21.1": matrix_for(
                    "1.21.1", ("fabric", "neoforge"), 21
                ),
            },
            repository="AkaNebur/Quick-Skin-Mod",
        )

        self.assertLess(section.index("| 1.21.1 |"), section.index("| 1.20.1 |"))
        self.assertIn("Fabric + NeoForge", section)
        self.assertIn("build-gate.yml/badge.svg?branch=fabric-and-neoforge-1.21.1", section)
        self.assertIn("on-demand-e2e.yml/badge.svg?branch=forge-and-fabric-1.20.1", section)
        self.assertIn("?query=branch%3Afabric-and-neoforge-1.21.1", section)

    def test_rejects_branch_and_matrix_disagreement(self) -> None:
        with self.assertRaisesRegex(status_table.StatusTableError, "loaders"):
            status_table.release_metadata(
                "forge-and-fabric-1.20.1",
                matrix_for("1.20.1", ("fabric", "neoforge"), 17),
            )

    def test_rejects_two_branches_for_one_minecraft_version(self) -> None:
        with self.assertRaisesRegex(status_table.StatusTableError, "multiple"):
            status_table.render_status_section(
                {
                    "fabric-1.20.1": matrix_for("1.20.1", ("fabric",), 17),
                    "forge-1.20.1": matrix_for("1.20.1", ("forge",), 17),
                },
                repository="AkaNebur/Quick-Skin-Mod",
            )

    def test_replaces_only_the_marked_readme_section(self) -> None:
        original = (
            "before\n"
            f"{status_table.START_MARKER}\nold\n{status_table.END_MARKER}\n"
            "after\n"
        )
        section = (
            f"{status_table.START_MARKER}\nnew\n{status_table.END_MARKER}"
        )
        self.assertEqual(
            status_table.replace_status_section(original, section),
            f"before\n{section}\nafter\n",
        )

    def test_requires_one_marker_pair(self) -> None:
        with self.assertRaisesRegex(status_table.StatusTableError, "exactly one"):
            status_table.replace_status_section("no markers\n", "unused")

    def test_rejects_reversed_markers(self) -> None:
        malformed = f"{status_table.END_MARKER}\n{status_table.START_MARKER}\n"
        with self.assertRaisesRegex(status_table.StatusTableError, "out of order"):
            status_table.replace_status_section(malformed, "unused")


if __name__ == "__main__":
    unittest.main()
