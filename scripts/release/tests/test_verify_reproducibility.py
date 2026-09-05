from __future__ import annotations

import hashlib
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts" / "release"))

import verify_reproducibility  # noqa: E402


class ReproducibilityTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.repository = Path(self.temporary.name)
        (self.repository / "gradle.properties").write_text(
            "mod_version=9.1.0\n", encoding="utf-8"
        )
        release = self.repository / "release"
        release.mkdir()
        self.matrix_path = release / "release-matrix.json"
        self.matrix_path.write_text("{}\n", encoding="utf-8")
        self.data = {
            "lane_count": 1,
            "project": {"mod_version_property": "mod_version"},
            "artifacts": [
                {
                    "artifact_node": "fabric-test",
                    "jar": "out/production-{mod_version}.jar",
                    "harness_jar": "out/harness.jar",
                }
            ],
        }
        output = self.repository / "out"
        output.mkdir()
        self.production = output / "production-9.1.0.jar"
        self.harness = output / "harness.jar"
        self.production.write_bytes(b"production")
        self.harness.write_bytes(b"harness")
        self.manifest = {
            "schema_version": 2,
            "artifacts": [
                {
                    "artifact_node": "fabric-test",
                    "sha256": self.digest(self.production),
                    "harness": {"sha256": self.digest(self.harness)},
                }
            ],
        }

    def tearDown(self) -> None:
        self.temporary.cleanup()

    @staticmethod
    def digest(path: Path) -> str:
        return hashlib.sha256(path.read_bytes()).hexdigest()

    def test_accepts_exact_second_build_bytes_for_production_and_harness(self) -> None:
        result = verify_reproducibility.compare_rebuild(
            self.repository, self.matrix_path, self.manifest, self.data
        )
        self.assertEqual(
            {(item["artifact_node"], item["kind"]) for item in result},
            {("fabric-test", "production"), ("fabric-test", "harness")},
        )

    def test_rejects_a_changed_second_build(self) -> None:
        self.harness.write_bytes(b"changed")
        with self.assertRaisesRegex(
            verify_reproducibility.ReproducibilityError,
            "non-reproducible fabric-test harness",
        ):
            verify_reproducibility.compare_rebuild(
                self.repository, self.matrix_path, self.manifest, self.data
            )

    def test_rejects_duplicate_nodes_even_when_the_unique_inventory_matches(self) -> None:
        self.manifest["artifacts"].append(dict(self.manifest["artifacts"][0]))
        with self.assertRaisesRegex(verify_reproducibility.ReproducibilityError, "node inventory"):
            verify_reproducibility.compare_rebuild(
                self.repository, self.matrix_path, self.manifest, self.data)


if __name__ == "__main__":
    unittest.main()
