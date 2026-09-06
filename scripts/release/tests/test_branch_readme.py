from __future__ import annotations

import sys
import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts" / "release"))

import branch_readme  # noqa: E402


def matrix_for_branch() -> dict[str, object]:
    return {
        "project": {
            "release_branch": "fabric-and-neoforge-1.21.1",
            "sources": "https://github.com/AkaNebur/Quick-Skin-Mod",
            "modrinth_id": "zAIE84Ch",
            "curseforge_id": 1323980,
        },
        "artifacts": [
            {
                "artifact_node": "fabric-1.21.1",
                "artifact_version": "1.21.1",
                "loader": "fabric",
                "java": 21,
            },
            {
                "artifact_node": "neoforge-1.21.1",
                "artifact_version": "1.21.1",
                "loader": "neoforge",
                "java": 21,
            },
        ],
        "runtimes": [
            {
                "artifact_node": "fabric-1.21.1",
                "loader_version": "0.16.9",
                "fabric_api": "0.110.0+1.21.1",
                "architectury": {"version": "13.0.6"},
            },
            {
                "artifact_node": "neoforge-1.21.1",
                "loader_version": "21.1.77",
                "architectury": {"version": "13.0.6"},
                "compatibility_patch": "neoforge-26.1-break-event-v1",
            },
        ],
        "source_overlays": {
            "common": {"1.21.1": "legacy1_21_1"},
            "fabric": {},
            "neoforge": {"1.21.1": "legacy1_21_1"},
        },
    }


class BranchReadmeTest(unittest.TestCase):
    def test_unified_profile_derives_every_target_and_rejects_version_branch_identity(self) -> None:
        data = json.loads((ROOT / "release/release-matrix.json").read_bytes())
        section = branch_readme.render_branch_profile(data, profile_branch="master")
        self.assertIn("same source revision", section)
        self.assertIn("mc<version>-v<mod_version>", section)
        for version in {row["artifact_version"] for row in data["artifacts"]}:
            rows = [row for row in data["artifacts"] if row["artifact_version"] == version]
            names = " + ".join(branch_readme.release_matrix.LOADER_DISPLAY_NAMES[row["loader"]] for row in rows)
            self.assertIn(f"| `{version}` | {names} | `{rows[0]['java']}` |", section)
        with self.assertRaisesRegex(branch_readme.BranchReadmeError, "does not match"):
            branch_readme.render_branch_profile(data, profile_branch="fabric-and-neoforge-1.21.1")

    def test_renders_release_specific_compatibility_delta(self) -> None:
        section = branch_readme.render_branch_profile(
            matrix_for_branch(),
            profile_branch="fabric-and-neoforge-1.21.1",
        )

        for expected in (
            "Build 1.21.1",
            "branch=fabric-and-neoforge-1.21.1",
            "Minecraft `1.21.1`",
            "Fabric + NeoForge",
            "Fabric Loader `0.16.9`",
            "Fabric API `0.110.0+1.21.1`",
            "NeoForge `21.1.77`",
            "Architectury API `13.0.6`",
            "compatibility patch `neoforge-26.1-break-event-v1`",
            "Packaged E2E runtime pins",
            "not minimum dependency claims",
            "`common/src/legacy1_21_1`",
            "`neoforge/src/legacy1_21_1`",
            "`fabric/src/main`",
        ):
            with self.subTest(expected=expected):
                self.assertIn(expected, section)
        self.assertNotIn("shared integration branch", section)

    def test_renders_master_as_integration_not_release(self) -> None:
        section = branch_readme.render_branch_profile(
            matrix_for_branch(), profile_branch="master"
        )

        self.assertIn("branch=master", section)
        self.assertIn("shared integration branch", section)
        self.assertIn("not a publishable Minecraft release branch", section)
        self.assertIn("Current integration baseline", section)
        self.assertIn("fabric-and-neoforge-1.21.1", section)

    def test_rejects_profile_branch_that_disagrees_with_matrix(self) -> None:
        with self.assertRaisesRegex(
            branch_readme.BranchReadmeError, "does not match"
        ):
            branch_readme.render_branch_profile(
                matrix_for_branch(),
                profile_branch="fabric-and-neoforge-1.21.11",
            )

    def test_replaces_only_profile_and_preserves_status_block(self) -> None:
        original = (
            "# Quick Skin\n\n"
            f"{branch_readme.START_MARKER}\nold\n{branch_readme.END_MARKER}\n\n"
            "## Verified releases\n\n"
            "<!-- release-status:start -->\nstatus\n<!-- release-status:end -->\n"
        )
        section = (
            f"{branch_readme.START_MARKER}\nnew\n{branch_readme.END_MARKER}"
        )
        updated = branch_readme.replace_profile_section(original, section)

        self.assertIn(f"# Quick Skin\n\n{section}", updated)
        self.assertIn(
            "<!-- release-status:start -->\nstatus\n<!-- release-status:end -->",
            updated,
        )
        self.assertEqual(
            branch_readme.replace_profile_section(updated, section), updated
        )

    def test_bootstraps_only_the_known_legacy_header(self) -> None:
        original = (
            "# Quick Skin\n\nlegacy badge and introduction\n\n"
            "## Verified releases\n\nremaining content\n"
        )
        section = (
            f"{branch_readme.START_MARKER}\nnew\n{branch_readme.END_MARKER}"
        )

        self.assertEqual(
            branch_readme.replace_profile_section(
                original, section, bootstrap=True
            ),
            f"# Quick Skin\n\n{section}\n\n## Verified releases\n\nremaining content\n",
        )
        with self.assertRaisesRegex(
            branch_readme.BranchReadmeError, "cannot be migrated"
        ):
            branch_readme.replace_profile_section(
                "# Different project\n", section, bootstrap=True
            )

    def test_requires_one_ordered_marker_pair(self) -> None:
        section = (
            f"{branch_readme.START_MARKER}\nnew\n{branch_readme.END_MARKER}"
        )
        with self.assertRaisesRegex(
            branch_readme.BranchReadmeError, "exactly one"
        ):
            branch_readme.replace_profile_section("no markers\n", section)
        malformed = (
            f"{branch_readme.END_MARKER}\n{branch_readme.START_MARKER}\n"
        )
        with self.assertRaisesRegex(
            branch_readme.BranchReadmeError, "out of order"
        ):
            branch_readme.replace_profile_section(malformed, section)

    def test_profile_must_own_the_entire_generated_header(self) -> None:
        section = (
            f"{branch_readme.START_MARKER}\nnew\n{branch_readme.END_MARKER}"
        )
        duplicated_header = (
            f"# Quick Skin\n\n{section}\n"
            "legacy badge and introduction\n\n"
            "## Verified releases\n\nremaining content\n"
        )

        with self.assertRaisesRegex(
            branch_readme.BranchReadmeError, "entire generated header"
        ):
            branch_readme.replace_profile_section(duplicated_header, section)
        self.assertEqual(
            branch_readme.replace_profile_section(
                duplicated_header, section, bootstrap=True
            ),
            f"# Quick Skin\n\n{section}\n\n"
            "## Verified releases\n\nremaining content\n",
        )


if __name__ == "__main__":
    unittest.main()
