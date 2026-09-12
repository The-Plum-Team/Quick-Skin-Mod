from __future__ import annotations

import hashlib
import json
import sys
import tempfile
import unittest
import uuid
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts" / "release"))

import generate_sbom  # noqa: E402


class CycloneDxSbomTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.repository = Path(self.temporary.name)
        release = self.repository / "release"
        release.mkdir()
        self.matrix_path = release / "release-matrix.json"
        self.matrix = {
            "schema_version": 2,
            "lane_count": 2,
            "project": {
                "name": "Quick Skin",
                "description": "Test release",
                "release_branch": "fabric-and-forge-1.20.1",
            },
            "artifacts": [
                {
                    "artifact_node": "fabric-1.20.1",
                    "artifact_version": "1.20.1",
                    "loader": "fabric",
                    "jar": "fabric/Quick Skin - Fabric - 1.20.1-{mod_version}.jar",
                    "harness_jar": "fabric/Quick Skin E2E - Fabric - 1.20.1-0.0.0.jar",
                    "game_versions": ["1.20.1"],
                },
                {
                    "artifact_node": "forge-1.20.1",
                    "artifact_version": "1.20.1",
                    "loader": "forge",
                    "jar": "forge/Quick Skin - Forge - 1.20.1-{mod_version}.jar",
                    "harness_jar": "forge/Quick Skin E2E - Forge - 1.20.1-0.0.0.jar",
                    "game_versions": ["1.20.1"],
                },
            ],
        }
        self.matrix_path.write_text(
            json.dumps(self.matrix, indent=2) + "\n",
            encoding="utf-8",
        )
        matrix_hash = hashlib.sha256(self.matrix_path.read_bytes()).hexdigest()
        self.stage = self.repository / "build" / "release"
        self.manifest = {
            "schema_version": 2,
            "matrix": "release/release-matrix.json",
            "matrix_sha256": matrix_hash,
            "lane_count": 2,
            "mod_version": "3.0.0",
            "git_commit": "a" * 40,
            "release": {
                "release_id": "mc1.20.1-v3.0.0",
                "tag": "mc1.20.1-v3.0.0",
                "branch": "fabric-and-forge-1.20.1",
                "mod_version": "3.0.0",
                "minecraft_versions": ["1.20.1"],
            },
            "artifacts": [
                self.artifact_record(
                    "fabric-1.20.1",
                    "fabric",
                    "Quick Skin - Fabric - 1.20.1-3.0.0.jar",
                    "Quick Skin E2E - Fabric - 1.20.1-0.0.0.jar",
                    b"fabric-production",
                ),
                self.artifact_record(
                    "forge-1.20.1",
                    "forge",
                    "Quick Skin - Forge - 1.20.1-3.0.0.jar",
                    "Quick Skin E2E - Forge - 1.20.1-0.0.0.jar",
                    b"forge-production",
                ),
            ],
        }

        locks = self.repository / "gradle" / "dependency-locks"
        locks.mkdir(parents=True)
        lock = (
            "# Gradle dependency lock\n"
            "org.sejda.imageio:webp-imageio:0.1.6=shadowBundle\n"
            "empty=\n"
        )
        for loader in ("fabric", "forge"):
            (locks / f"{loader}-1.20.1.lockfile").write_text(lock, encoding="utf-8")
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

    def artifact_record(
        self,
        node: str,
        loader: str,
        filename: str,
        harness_filename: str,
        payload: bytes,
    ) -> dict[str, object]:
        production = self.stage / "files" / filename
        production.parent.mkdir(parents=True, exist_ok=True)
        production.write_bytes(payload)
        harness = self.stage / "harness" / harness_filename
        harness.parent.mkdir(parents=True, exist_ok=True)
        harness.write_bytes(b"harness-" + payload)
        return {
            "artifact_node": node,
            "artifact_version": "1.20.1",
            "loader": loader,
            "game_versions": ["1.20.1"],
            "filename": filename,
            "path": f"files/{filename}",
            "bytes": len(payload),
            "sha1": hashlib.sha1(payload).hexdigest(),
            "sha256": hashlib.sha256(payload).hexdigest(),
            "sha512": hashlib.sha512(payload).hexdigest(),
            "harness": {
                "filename": harness_filename,
                "path": f"harness/{harness_filename}",
                "bytes": harness.stat().st_size,
                "sha256": hashlib.sha256(harness.read_bytes()).hexdigest(),
            },
        }

    def build(self) -> bytes:
        return generate_sbom.build_cyclonedx_bytes(
            self.repository,
            self.matrix_path,
            self.matrix,
            self.manifest,
            self.stage,
        )

    def test_is_deterministic_and_describes_each_jar_and_embedded_dependency(self) -> None:
        first = self.build()
        self.assertEqual(first, self.build())
        self.assertTrue(first.endswith(b"\n"))
        sbom = json.loads(first)
        self.assertEqual(sbom["bomFormat"], "CycloneDX")
        self.assertEqual(sbom["specVersion"], "1.6")
        self.assertNotIn("timestamp", sbom["metadata"])
        serial = uuid.UUID(sbom["serialNumber"])
        self.assertEqual(serial.urn, sbom["serialNumber"])
        self.assertEqual(serial.version, 5)
        self.assertEqual(serial.variant, uuid.RFC_4122)

        files = [component for component in sbom["components"] if component["type"] == "file"]
        libraries = [component for component in sbom["components"] if component["type"] == "library"]
        self.assertEqual({component["name"] for component in files}, {
            "Quick Skin - Fabric - 1.20.1-3.0.0.jar",
            "Quick Skin - Forge - 1.20.1-3.0.0.jar",
        })
        self.assertEqual(len(libraries), 1)
        self.assertEqual(libraries[0]["purl"], "pkg:maven/org.sejda.imageio/webp-imageio@0.1.6")
        self.assertEqual(
            libraries[0]["hashes"],
            [{
                "alg": "SHA-256",
                "content": "3d30473ef5cadf126a25b2613cbe36218ba7d4184be873edc8f28a183a9fb29d",
            }],
        )
        graph = {entry["ref"]: entry["dependsOn"] for entry in sbom["dependencies"]}
        for component in files:
            self.assertEqual(
                graph[component["bom-ref"]],
                ["pkg:maven/org.sejda.imageio/webp-imageio@0.1.6"],
            )

    def test_serial_number_changes_with_dependencies_and_source_identity(self) -> None:
        original = json.loads(self.build())["serialNumber"]
        verification = self.repository / "gradle" / "verification-metadata.xml"
        verification.write_text(
            verification.read_text(encoding="utf-8").replace(
                "3d30473ef5cadf126a25b2613cbe36218ba7d4184be873edc8f28a183a9fb29d",
                "4" * 64,
            ),
            encoding="utf-8",
        )
        changed_dependencies = json.loads(self.build())["serialNumber"]
        self.assertNotEqual(original, changed_dependencies)
        self.assertEqual(changed_dependencies, json.loads(self.build())["serialNumber"])

        self.manifest["git_commit"] = "b" * 40
        changed_source = json.loads(self.build())["serialNumber"]
        self.assertNotEqual(changed_dependencies, changed_source)

    def test_rejects_missing_or_stale_serial_number(self) -> None:
        sbom = json.loads(self.build())
        serial = sbom.pop("serialNumber")
        with self.assertRaisesRegex(generate_sbom.SbomError, "serialNumber"):
            generate_sbom.validate_cyclonedx(sbom)

        sbom["serialNumber"] = serial
        sbom["metadata"]["component"]["description"] = "Changed release description"
        with self.assertRaisesRegex(generate_sbom.SbomError, "serialNumber"):
            generate_sbom.validate_cyclonedx(sbom)

    def test_staged_record_binds_exact_canonical_bytes_and_all_inputs(self) -> None:
        self.manifest["sbom"] = generate_sbom.stage_sbom(
            self.repository,
            self.matrix_path,
            self.stage,
            self.matrix,
            self.manifest,
        )
        generate_sbom.verify_staged_sbom(
            self.repository,
            self.matrix_path,
            self.stage,
            self.matrix,
            self.manifest,
        )

        lock = self.repository / "gradle" / "dependency-locks" / "fabric-1.20.1.lockfile"
        lock.write_text(lock.read_text(encoding="utf-8") + "# reviewed change\n", encoding="utf-8")
        with self.assertRaisesRegex(generate_sbom.SbomError, "does not match matrix"):
            generate_sbom.verify_staged_sbom(
                self.repository,
                self.matrix_path,
                self.stage,
                self.matrix,
                self.manifest,
            )

    def test_fails_closed_for_missing_lock_checksum_or_artifact_node(self) -> None:
        missing_lock = self.repository / "gradle" / "dependency-locks" / "forge-1.20.1.lockfile"
        missing_lock.unlink()
        with self.assertRaisesRegex(generate_sbom.SbomError, "missing dependency lock"):
            self.build()

        missing_lock.write_text(
            "org.example:unchecked:1.0=shadowBundle\nempty=\n",
            encoding="utf-8",
        )
        with self.assertRaisesRegex(generate_sbom.SbomError, "no unique node"):
            self.build()

        self.manifest["artifacts"] = self.manifest["artifacts"][:1]
        with self.assertRaisesRegex(generate_sbom.SbomError, "lane_count=2 records"):
            self.build()

    def test_refuses_to_generate_from_changed_staged_jar_bytes(self) -> None:
        production = self.stage / self.manifest["artifacts"][0]["path"]
        production.write_bytes(b"tampered")

        with self.assertRaisesRegex(generate_sbom.SbomError, "(?:byte count|SHA256) mismatch"):
            self.build()


if __name__ == "__main__":
    unittest.main()
