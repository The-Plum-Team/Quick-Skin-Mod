from __future__ import annotations

import sys
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts" / "release"))

from workflow_guidance import WorkflowGuidanceError, render_guidance  # noqa: E402
import matrix as release_matrix  # noqa: E402


class WorkflowGuidanceTest(unittest.TestCase):
    def matrix(self, version: str = "1.21.11") -> dict[str, object]:
        return {
            "project": {"release_branch": "fabric-and-neoforge-1.21.11"},
            "artifacts": [
                {"artifact_version": version, "loader": "fabric"},
                {"artifact_version": version, "loader": "neoforge"},
            ],
        }

    def test_renders_both_common_test_tasks_from_the_matrix(self) -> None:
        source = (
            "first :common:1.20.1:test\n"
            "second :common:1.20.1:test `\n"
        )

        rendered = render_guidance(
            source,
            self.matrix(),
            profile_branch="fabric-and-neoforge-1.21.11",
        )

        self.assertEqual(2, rendered.count(":common:1.21.11:test"))
        self.assertNotIn(":common:1.20.1:test", rendered)

    def test_master_uses_the_checked_in_baseline_version(self) -> None:
        source = ":common:1.20.1:test\n:common:1.20.1:test\n"
        self.assertEqual(
            source,
            render_guidance(source, self.matrix("1.20.1"), profile_branch="master"),
        )

    def test_shared_matrix_uses_its_unit_lane_independently_of_artifact_order(self) -> None:
        data = release_matrix.load_matrix(ROOT / "release/release-matrix.json")
        data["artifacts"].reverse()
        source = ":common:1.21.11:test\n:common:1.21.11:test\n"
        rendered = render_guidance(source, data, profile_branch="master")
        self.assertEqual(2, rendered.count(f":common:{data['unit_test_version']}:test"))
        data["unit_test_version"] = "0.0.0"
        with self.assertRaises(release_matrix.MatrixError):
            render_guidance(source, data, profile_branch="master")

    def test_rejects_wrong_branch_and_mixed_artifact_versions(self) -> None:
        source = ":common:1.20.1:test\n:common:1.20.1:test\n"
        with self.assertRaisesRegex(WorkflowGuidanceError, "does not match"):
            render_guidance(source, self.matrix(), profile_branch="wrong")

        mixed = self.matrix()
        mixed["artifacts"][1]["artifact_version"] = "1.21.10"  # type: ignore[index]
        with self.assertRaisesRegex(WorkflowGuidanceError, "one Minecraft version"):
            render_guidance(
                source,
                mixed,
                profile_branch="fabric-and-neoforge-1.21.11",
            )

    def test_rejects_missing_or_divergent_task_anchors(self) -> None:
        cases = (
            ":common:1.20.1:test\n",
            ":common:1.20.1:test\n:common:1.21.1:test\n",
        )
        for source in cases:
            with self.subTest(source=source), self.assertRaisesRegex(
                WorkflowGuidanceError, "exactly two identical"
            ):
                render_guidance(source, self.matrix(), profile_branch="master")


if __name__ == "__main__":
    unittest.main()
