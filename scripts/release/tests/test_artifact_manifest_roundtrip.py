from __future__ import annotations

import hashlib
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock


ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts" / "release"))
sys.path.insert(0, str(ROOT / "e2e"))

import generate_sbom  # noqa: E402
import matrix as release_matrix  # noqa: E402
import verify_release  # noqa: E402
import verify_reproducibility  # noqa: E402
from artifact_manifest import (  # noqa: E402
    ArtifactManifestError,
    file_digest,
    load_artifact_manifest,
)
from orchestrator import read_manifest  # noqa: E402
from release_identity import derive as derive_release_identity  # noqa: E402


class ArtifactManifestRoundTripTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.repository = Path(self.temporary.name)
        self.matrix_path = self.repository / "release" / "release-matrix.json"
        self.matrix_path.parent.mkdir()
        self.mod_version = "3.0.0"
        self.commit = "a" * 40
        self.data = {
            "schema_version": 2,
            "lane_count": 1,
            "project": {
                "name": "Quick Skin",
                "description": "Round-trip fixture",
                "release_branch": "fabric-and-forge-1.20.1",
                "mod_version_property": "mod_version",
            },
            "artifacts": [
                {
                    "artifact_node": "fabric-1.20.1",
                    "artifact_version": "1.20.1",
                    "loader": "fabric",
                    "jar": "out/Quick Skin - Fabric - 1.20.1-{mod_version}.jar",
                    "harness_jar": "out/Quick Skin E2E - Fabric - 1.20.1-0.0.0.jar",
                    "game_versions": ["1.20.1"],
                }
            ],
        }
        self.matrix_path.write_text(json.dumps(self.data, indent=2) + "\n", encoding="utf-8")
        (self.repository / "gradle.properties").write_text(
            f"mod_version={self.mod_version}\n", encoding="utf-8"
        )
        output = self.repository / "out"
        output.mkdir()
        self.production = output / "Quick Skin - Fabric - 1.20.1-3.0.0.jar"
        self.harness = output / "Quick Skin E2E - Fabric - 1.20.1-0.0.0.jar"
        self.production.write_bytes(b"round-trip-production")
        self.harness.write_bytes(b"round-trip-harness")
        self.stage = self.repository / "build" / "release"
        self.manifest_path = self.stage / "artifacts.json"

        locks = self.repository / "gradle" / "dependency-locks"
        locks.mkdir(parents=True)
        (locks / "fabric-1.20.1.lockfile").write_text(
            "org.sejda.imageio:webp-imageio:0.1.6=shadowBundle\nempty=\n",
            encoding="utf-8",
        )
        (self.repository / "gradle" / "verification-metadata.xml").write_text(
            """<?xml version="1.0" encoding="UTF-8"?>
<verification-metadata xmlns="https://schema.gradle.org/dependency-verification">
  <components>
    <component group="org.sejda.imageio" name="webp-imageio" version="0.1.6">
      <artifact name="webp-imageio-0.1.6.jar">
        <sha256 value="3d30473ef5cadf126a25b2613cbe36218ba7d4184be873edc8f28a183a9fb29d"/>
      </artifact>
    </component>
  </components>
</verification-metadata>
""",
            encoding="utf-8",
        )

    def tearDown(self) -> None:
        self.temporary.cleanup()

    @staticmethod
    def verified_production(path: Path, *_args: object) -> dict[str, object]:
        return {
            "filename": path.name,
            "bytes": path.stat().st_size,
            "sha1": file_digest(path, "sha1"),
            "sha256": file_digest(path, "sha256"),
            "sha512": file_digest(path, "sha512"),
        }

    @staticmethod
    def verified_harness(path: Path, *_args: object) -> dict[str, object]:
        return {
            "filename": path.name,
            "bytes": path.stat().st_size,
            "sha256": file_digest(path, "sha256"),
        }

    def build_manifest(self, *, target: str | None = None) -> dict[str, object]:
        with (
            mock.patch.object(verify_release, "git_commit", return_value=self.commit),
            mock.patch.object(verify_release, "verify_jar", side_effect=self.verified_production),
            mock.patch.object(verify_release, "verify_harness", side_effect=self.verified_harness),
        ):
            manifest = verify_release.build_manifest(
                self.repository,
                self.matrix_path,
                self.stage,
                self.manifest_path,
                self.mod_version,
                self.data,
                target=target,
            )
        self.manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
        return manifest

    def test_producer_validator_sbom_and_e2e_reader_share_one_contract(self) -> None:
        produced = self.build_manifest()
        identity = derive_release_identity(self.matrix_path, self.data)
        loaded = load_artifact_manifest(
            self.manifest_path,
            repository=self.repository,
            matrix_path=self.matrix_path,
            matrix=self.data,
            stage=self.stage,
            expected_mod_version=self.mod_version,
            expected_commit=self.commit,
            expected_release=identity.manifest(),
        )
        regenerated = generate_sbom.build_cyclonedx_bytes(
            self.repository,
            self.matrix_path,
            self.data,
            loaded,
            self.stage,
            expected_mod_version=self.mod_version,
            expected_commit=self.commit,
            expected_release=identity.manifest(),
        )
        consumed = read_manifest(
            self.manifest_path,
            self.data,
            self.matrix_path,
            self.repository,
            self.mod_version,
            self.commit,
            identity.manifest(),
        )

        self.assertEqual(produced, loaded)
        self.assertEqual(produced, consumed)
        self.assertEqual(
            hashlib.sha256(regenerated).hexdigest(),
            produced["sbom"]["sha256"],
        )

    def test_producer_rejects_manifest_outside_its_stage(self) -> None:
        with self.assertRaisesRegex(
            ArtifactManifestError, "stored directly in the release stage"
        ):
            verify_release.build_manifest(
                self.repository,
                self.matrix_path,
                self.stage,
                self.repository / "metadata" / "artifacts.json",
                self.mod_version,
                self.data,
            )

    def test_target_stage_keeps_full_matrix_provenance_and_cannot_impersonate_the_bundle(self) -> None:
        self.data = release_matrix.load_matrix(ROOT / "release/release-matrix.json")
        self.matrix_path.write_text(json.dumps(self.data) + "\n", encoding="utf-8")
        (self.repository / "gradle.properties").write_bytes((ROOT / "gradle.properties").read_bytes())
        for artifact in self.data["artifacts"]:
            for key in ("jar", "harness_jar"):
                path = self.repository / artifact[key].format(mod_version=self.mod_version)
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes((artifact["artifact_node"] + key).encode())
            (self.repository / "gradle/dependency-locks" / f"{artifact['artifact_node']}.lockfile").write_text(
                "org.sejda.imageio:webp-imageio:0.1.6=shadowBundle\nempty=\n", encoding="utf-8")
        before = self.matrix_path.read_bytes()
        target = self.data["unit_test_version"]
        selected = release_matrix.select_release_target(self.data, target)
        produced = self.build_manifest(target=target)
        identity = derive_release_identity(self.matrix_path, self.data, target=target)
        loaded = load_artifact_manifest(self.manifest_path, repository=self.repository,
            matrix_path=self.matrix_path, matrix=selected, stage=self.stage,
            expected_mod_version=self.mod_version, expected_commit=self.commit,
            expected_release=identity.manifest())
        self.assertEqual(produced, loaded)
        self.assertEqual(before, self.matrix_path.read_bytes())
        self.assertEqual(hashlib.sha256(before).hexdigest(), produced["matrix_sha256"])
        self.assertEqual({target}, {row["artifact_version"] for row in produced["artifacts"]})
        self.assertEqual({row["filename"] for row in produced["artifacts"]},
                         {path.name for path in (self.stage / "files").iterdir()})
        self.assertEqual(produced["sbom"]["sha256"], hashlib.sha256(
            generate_sbom.build_cyclonedx_bytes(self.repository, self.matrix_path, selected,
                produced, self.stage, expected_release=identity.manifest())).hexdigest())
        comparisons = verify_reproducibility.compare_rebuild(
            self.repository, self.matrix_path, produced, self.data, target=target)
        self.assertEqual({row["artifact_node"] for row in selected["artifacts"]},
                         {row["artifact_node"] for row in comparisons})
        with self.assertRaisesRegex(verify_reproducibility.ReproducibilityError, "release scope"):
            verify_reproducibility.compare_rebuild(self.repository, self.matrix_path, produced, self.data)
        for wrong_scope in (self.data, release_matrix.select_release_target(self.data,
                next(row["artifact_version"] for row in self.data["artifacts"]
                     if row["artifact_version"] != target))):
            with self.assertRaises(ArtifactManifestError):
                load_artifact_manifest(self.manifest_path, repository=self.repository,
                    matrix_path=self.matrix_path, matrix=wrong_scope, stage=self.stage)
        self.data["artifacts"][-1]["java"] = 0
        with self.assertRaises(release_matrix.MatrixError):
            self.build_manifest(target=target)
        self.assertEqual(produced, json.loads(self.manifest_path.read_bytes()))


if __name__ == "__main__":
    unittest.main()
